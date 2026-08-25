/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.coverage.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Java agent that captures Rego coverage from opa-java-sdk evaluation runs.
 *
 * <p>Usage: {@code -javaagent:opa-ir-coverage-agent.jar=/path/to/output-dir}
 *
 * <p>The argument is a directory: one init script serves every {@code Test} task and every parallel
 * fork, so the per-JVM discriminator has to be derived inside the JVM ({@code
 * report-<pid>-<uid>.json}). Merging is the IDE side's job.
 *
 * <p>Report generation is delegated to the SDK's {@code OpaCoverageReport.from}, so
 * {@code not_covered} means "rule the planner never planned", not "statement never executed".
 *
 * <p>Single bundle per JVM; a second bundle with a different file table makes the agent throw.
 *
 * <p>Every SDK access is reflective so the agent survives relocation of the SDK's packages.
 */
public final class OpaIrCoverageAgent {

    private static volatile List<String> filenames;
    private static volatile List<?> unplannedRules;
    private static volatile Object profiler;

    /**
     * SDK package root, derived once from the first builder class name we see. The SDK may be shaded
     * under an unknown root, so it is never hardcoded.
     */
    private static volatile String pkgRoot;

    private static volatile Path outputDir;
    private static volatile String reportFileName;

    // Keyed by the builder's exact Class so a second SDK copy from another classloader recomputes
    // instead of invoking a Method belonging to the wrong Class.
    private static volatile Class<?> cachedBuilderClass;
    private static volatile Method cachedWithProfiler;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (agentArgs == null || agentArgs.isBlank()) {
            System.err.println(
                "[opa-ir-cov] usage: -javaagent:opa-ir-coverage-agent.jar=<output-dir>");
            return;
        }
        outputDir = Path.of(agentArgs.trim());

        // The uid guards against PID reuse within one long build.
        reportFileName = "report-" + ProcessHandle.current().pid() + "-"
            + String.format("%08x", (int) UUID.randomUUID().getMostSignificantBits()) + ".json";

        // Not inst.appendToBootstrapClassLoaderSearch(): SDK classes in child loaders would bind to a
        // second, bootstrap copy of this class while premain populated the system copy's statics,
        // silently splitting the captured state.
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(ElementMatchers.nameEndsWith(".opa.rego.Engine"))
            .transform((b, td, cl, m, pd) ->
                b.visit(Advice.to(EngineInterceptor.class)
                    .on(ElementMatchers.named("prepareForEvaluation")
                        .and(ElementMatchers.takesArguments(0)))))
            .type(ElementMatchers.nameEndsWith(".opa.bundle.Bundle$Builder"))
            .transform((b, td, cl, m, pd) ->
                b.visit(Advice.to(BundleInterceptor.class)
                    .on(ElementMatchers.named("withIrPolicy"))))
            .installOn(inst);

        Runtime.getRuntime().addShutdownHook(
            new Thread(OpaIrCoverageAgent::writeReport, "opa-ir-cov-shutdown"));

        System.err.println("[opa-ir-cov] agent installed; will write report to "
            + outputDir.resolve(reportFileName));
    }

    /**
     * Extracts the file index → filename table and the planner's unplanned-rules list from a
     * {@code Policy} — the two arguments {@code OpaCoverageReport.from} needs beyond the profiler.
     */
    public static synchronized void capturePolicy(Object policy) {
        List<String> captured;
        List<?> rulesList;
        try {
            if (policy == null) return;

            captured = readFilenames(invoke(policy, "getStatic"));
            if (captured == null) return;

            // Absent on SDKs that predate unplanned-rule tracking.
            Object rules = invokeIfPresent(policy, "getUnplannedRules");
            rulesList = rules instanceof List ? (List<?>) rules : List.of();
        } catch (Throwable t) {
            System.err.println("[opa-ir-cov] failed to capture policy: " + t);
            return;
        }

        mergeFilenames(captured);
        unplannedRules = rulesList;
    }

    /** Policy.getStatic().getFiles(), mapped to StringConst::getValue, index-preserving. */
    private static List<String> readFilenames(Object staticField) throws Exception {
        if (staticField == null) return null;
        Object filesObj = invoke(staticField, "getFiles");
        if (!(filesObj instanceof List)) return null;

        List<?> files = (List<?>) filesObj;
        List<String> filenames = new ArrayList<>(files.size());
        for (Object stringConst : files) {
            Object value = stringConst == null ? null : invoke(stringConst, "getValue");
            filenames.add(value instanceof String ? (String) value : null);
        }
        return filenames;
    }

    private static void mergeFilenames(List<String> captured) {
        List<String> existing = filenames;
        if (existing == null) {
            filenames = List.copyOf(captured);
            return;
        }
        if (!existing.equals(captured)) {
            // Printed as well as thrown: the throw escapes through Bundle.Builder.withIrPolicy as a
            // bundle-load failure with no obvious link to coverage, and SDK wrapping may swallow the
            // message.
            String message =
                "[opa-ir-cov] multiple bundles loaded with different file tables; agent supports "
                    + "one bundle per JVM. first=" + existing + " second=" + captured;
            System.err.println(message);
            throw new IllegalStateException(message);
        }
    }

    public static synchronized void registerProfiler(Object builder) {
        try {
            if (builder == null) return;
            Object p = getOrCreateProfiler(builder);

            Method withProfiler = cachedWithProfiler;
            if (withProfiler == null || cachedBuilderClass != builder.getClass()) {
                Class<?> profilerInterface =
                    Class.forName(pkgRoot + ".tracing.Profiler", true,
                        builder.getClass().getClassLoader());
                withProfiler = builder.getClass().getMethod("withProfiler", profilerInterface);
                cachedBuilderClass = builder.getClass();
                cachedWithProfiler = withProfiler;
            }
            withProfiler.invoke(builder, p);
        } catch (Throwable t) {
            System.err.println("[opa-ir-cov] failed to register profiler: " + t);
        }
    }

    /**
     * Lazily creates the single shared {@code CoverageProfiler}, and is the one place
     * {@link #pkgRoot} is derived.
     */
    private static Object getOrCreateProfiler(Object preparedQueryBuilder) throws Exception {
        Object existing = profiler;
        if (existing != null) return existing;

        // builder class name looks like "<pkgRoot>.rego.Engine$PreparedQuery$Builder"
        String name = preparedQueryBuilder.getClass().getName();
        int idx = name.indexOf(".rego.");
        if (idx < 0) {
            throw new IllegalStateException(
                "[opa-ir-cov] unexpected builder class: " + name
                    + "; cannot derive the SDK package root");
        }
        pkgRoot = name.substring(0, idx);

        ClassLoader cl = preparedQueryBuilder.getClass().getClassLoader();
        Class<?> profilerImpl =
            Class.forName(pkgRoot + ".tracing.CoverageProfiler", true, cl);
        Object created = profilerImpl.getDeclaredConstructor().newInstance();
        profiler = created;
        return created;
    }

    private static void writeReport() {
        try {
            Object p = profiler;
            List<String> names = filenames;
            if (p == null || names == null) {
                System.err.println(
                    "[opa-ir-cov] no coverage data captured (profiler=" + (p != null)
                        + ", filenames=" + (names != null) + "); skipping report");
                return;
            }
            List<?> rules = unplannedRules;
            if (rules == null) rules = List.of();

            String root = pkgRoot;
            if (root == null) {
                // Unreachable in practice: pkgRoot is set before the profiler exists.
                System.err.println("[opa-ir-cov] SDK package root unknown; skipping report");
                return;
            }

            Class<?> reportClass;
            try {
                reportClass = Class.forName(
                    root + ".jackson.OpaCoverageReport", true, p.getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                System.err.println(
                    "[opa-ir-cov] " + root + ".jackson.OpaCoverageReport not found on the "
                        + "target app's runtime classpath; add opa-jackson as a runtime "
                        + "dependency to generate coverage reports. skipping report");
                return;
            }

            // Duck-typed: getMethod("from", p.getClass(), ...) would demand the SDK declare the
            // parameter as the exact CoverageProfiler class, so widening it to the Profiler
            // interface would break us inside a shutdown hook — a green run with missing coverage.
            Method from = findFromMethod(reportClass, p);
            if (from == null) {
                System.err.println(
                    "[opa-ir-cov] no usable " + root + ".jackson.OpaCoverageReport.from(<profiler>,"
                        + " List, List) accepting " + p.getClass().getName() + "; the opa-jackson on"
                        + " the target app's runtime classpath is incompatible with this agent."
                        + " skipping report");
                return;
            }
            Object reportNode = from.invoke(null, p, names, rules);

            // The IDE may glob report-*.json while a slower fork is still writing, so write to a
            // sibling .tmp (same filesystem, so ATOMIC_MOVE works) and move it into place.
            Path target = outputDir.resolve(reportFileName);
            Files.createDirectories(outputDir);
            Path tmp = outputDir.resolve(reportFileName + ".tmp");
            try {
                Files.writeString(tmp, reportNode.toString());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Better a non-atomic move than a lost report.
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // The .tmp is invisible to the report-*.json glob, so it would linger until the
                // IDE's stale-run sweep: one leaked file per fork per run.
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Nothing useful left to do in a shutdown hook.
                }
                throw e;
            }
            System.err.println("[opa-ir-cov] wrote coverage report to " + target);
        } catch (Throwable t) {
            System.err.println("[opa-ir-cov] failed to write report: " + t);
            t.printStackTrace();
        }
    }

    /** First static {@code from(<something profiler is an instance of>, List, List)}. */
    private static Method findFromMethod(Class<?> reportClass, Object profilerInstance) {
        for (Method m : reportClass.getMethods()) {
            if (!m.getName().equals("from")) continue;
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 3) continue;
            if (!params[0].isInstance(profilerInstance)) continue;
            if (!params[1].isAssignableFrom(List.class) || !params[2].isAssignableFrom(List.class)) {
                continue;
            }
            return m;
        }
        return null;
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    /** Reflective call tolerating a method the target may not declare; null when absent. */
    private static Object invokeIfPresent(Object target, String method) throws Exception {
        Method m;
        try {
            m = target.getClass().getMethod(method);
        } catch (NoSuchMethodException e) {
            return null;
        }
        return m.invoke(target);
    }

    private OpaIrCoverageAgent() {}
}

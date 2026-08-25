/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageEditorAnnotatorImpl
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.execution.ExecutionListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.openpolicyagent.ideaplugin.lang.psi.RegoFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/*
 * One coverage run, start to finish. The platform splits this across four extension points, so each
 * step below has to be its own class, and they are ordered here the way they fire:
 *
 *   1. OpaIrCoverageExecutionListener   arms a run when Gradle is launched with the Coverage executor
 *   2. OpaIrCoverageGradleTaskManager   claims that run and injects -javaagent into the test JVMs
 *   3. OpaIrCoverageTaskNotificationListener  consumes it when the build ends and loads the reports
 *
 * OpaIrCoverageGradleSession holds the state the three share, and owns the correlation that keeps an
 * unrelated Gradle invocation from stealing the run.
 */

/**
 * A run that has been armed for IR coverage collection.
 *
 * [outputDir] is a directory, not a file: one init script string is shared by every Test task and
 * fork, so each JVM derives its own `report-<pid>-<uid>.json` name inside it.
 *
 * [taskId] is bound later, once Gradle configures the build; see
 * [OpaIrCoverageGradleSession.bindTaskId].
 */
data class OpaIrPendingRun(
    val outputDir: Path,
    val runConfigName: String,
    val startedAt: Long,
    val taskId: ExternalSystemTaskId? = null,
)

@Service(Service.Level.PROJECT)
class OpaIrCoverageGradleSession {

    @Volatile
    private var pendingRun: OpaIrPendingRun? = null

    fun arm(runConfigName: String): OpaIrPendingRun {
        val parent = Path.of(System.getProperty("java.io.tmpdir"), REPORT_ROOT_DIR_NAME)
        Files.createDirectories(parent)

        // Run directories must outlive their run: the platform re-invokes loadCoverageData lazily
        // whenever the suite is re-selected or restored, so deleting on load breaks every later
        // load. Hence a time-based sweep at the start of a run; no cross-process registry exists to
        // reference-count persisted suites.
        sweepStaleRuns(parent)

        val run = OpaIrPendingRun(
            outputDir = Files.createTempDirectory(parent, "run-"),
            runConfigName = runConfigName,
            startedAt = System.currentTimeMillis(),
        )
        pendingRun = run
        return run
    }

    /**
     * Associates the armed run with the Gradle task that is going to carry it, and reports whether
     * [id] is the invocation that owns the run.
     *
     * First-writer-wins, never rebinds. A run stays pending for the whole build, so unrelated
     * Gradle invocations also reach configureTasks; rebinding would let the wrong invocation's onEnd
     * consume the run, leaving the real coverage build with no suite.
     */
    fun bindTaskId(id: ExternalSystemTaskId): Boolean = synchronized(this) {
        val run = pendingRun ?: return false
        val bound = run.taskId
        if (bound != null) return bound == id
        pendingRun = run.copy(taskId = id)
        return true
    }

    fun lookup(): OpaIrPendingRun? = pendingRun

    /**
     * Consumes the pending run only if it belongs to [id].
     *
     * Residual race: a run armed but never configured (build fails early, or is cancelled) stays
     * pending until the next arm() replaces it. Consuming unbound runs on an arbitrary task end is
     * deliberately not done, since a concurrent sync would then eat the coverage.
     */
    fun consumeFor(id: ExternalSystemTaskId): OpaIrPendingRun? = synchronized(this) {
        val run = pendingRun ?: return null
        if (run.taskId != id) return null
        pendingRun = null
        return run
    }

    private fun sweepStaleRuns(parent: Path) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        val children = parent.toFile().listFiles() ?: return
        for (child in children) {
            // Per entry: another IDE process may own one, and a failed sweep must not block the run.
            try {
                if (!child.isDirectory || child.lastModified() >= cutoff) continue
                child.deleteRecursively()
            } catch (e: Exception) {
                thisLogger().debug("OPA IR coverage: could not sweep stale run dir $child", e)
            }
        }
    }

    companion object {
        private const val REPORT_ROOT_DIR_NAME = "opa-ir-coverage"
        private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * The agent writes `report-*.json.tmp` and moves it into place, so a partially written
         * report is never matched.
         *
         * Zero-length files are excluded here, the single place both the "did this run produce
         * anything?" gate and the loader consult; were they to disagree, one truncated report would
         * pass the gate and then fail parsing, discarding every good report in the directory.
         */
        fun isReportFile(f: File): Boolean =
            f.isFile && f.name.startsWith("report-") && f.name.endsWith(".json") && f.length() > 0

        /** Reports for a run, sorted by name so that loading (and its logging) is deterministic. */
        fun reportsIn(dir: File): List<File> =
            dir.listFiles { f: File -> isReportFile(f) }?.sortedBy { it.name }.orEmpty()
    }
}

class OpaIrCoverageExecutionListener(private val project: Project) : ExecutionListener {

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        if (executorId != CoverageExecutor.EXECUTOR_ID) return
        val runProfile = env.runProfile as? ExternalSystemRunConfiguration ?: return
        if (runProfile.settings.externalSystemId != GradleConstants.SYSTEM_ID) return

        if (!looksLikeTestRun(runProfile.settings.taskNames)) {
            thisLogger().debug(
                "OPA IR coverage: not arming, no task looks like a test run: ${runProfile.settings.taskNames}")
            return
        }

        val session = project.getService(OpaIrCoverageGradleSession::class.java)
        // arm() touches the filesystem, and this runs inside a message-bus callback: an escaping
        // IOException would surface as an internal error on every "Run with coverage".
        try {
            session.arm(runProfile.name)
        } catch (e: Exception) {
            thisLogger().warn("OPA IR coverage: could not arm run; coverage will not be collected", e)
        }
    }

    /**
     * True unless the invocation is clearly not running tests.
     *
     * Deliberately one-sided: arming a non-test run is cheap, missing a real test run loses coverage
     * entirely. An empty task list means Gradle's default tasks, unresolvable here, so it counts as
     * "maybe".
     */
    private fun looksLikeTestRun(taskNames: List<String>): Boolean {
        if (taskNames.isEmpty()) return true
        return taskNames.any { task ->
            // "check" and "build" both depend on test tasks in the java plugin.
            val name = task.substringAfterLast(':').lowercase()
            name.contains("test") || name == "check" || name == "build"
        }
    }
}

class OpaIrCoverageGradleTaskManager : GradleTaskManagerExtension {

    override fun configureTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: GradleExecutionSettings,
        gradleVersion: GradleVersion?,
    ) {
        val project = id.findProject() ?: return
        val session = project.getService(OpaIrCoverageGradleSession::class.java)
        session.lookup() ?: return

        // Syncs (RESOLVE_PROJECT) also reach configureTasks; they run no Test tasks and must not
        // take ownership of the armed run.
        if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return

        // First writer wins. If the run belongs to another invocation, inject nothing: its Test JVMs
        // would write into our directory and its onEnd would consume the run.
        if (!session.bindTaskId(id)) {
            thisLogger().debug(
                "OPA IR coverage: run already bound to another Gradle invocation; not instrumenting $projectPath")
            return
        }

        val pending = session.lookup() ?: return

        val agentJar = try {
            OpaIrAgentExtractor.getInstance().extract()
        } catch (e: Exception) {
            thisLogger().warn("Failed to extract OPA IR coverage agent", e)
            return
        }

        val agentPath = agentJar.toString().replace("\\", "\\\\").replace("'", "\\'")
        // The agent argument is the run directory, not a file: this one string is shared by every
        // Test task and fork, so the per-JVM discriminator must be derived inside each JVM.
        val outputPath = pending.outputDir.toString().replace("\\", "\\\\").replace("'", "\\'")

        // Known limitation: `allprojects` does not reach included (composite) builds, so their Test
        // tasks are not instrumented and the run reports "no reports", blaming opa-jackson.
        val initScript = """
            allprojects {
                tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                    jvmArgs '-javaagent:$agentPath=$outputPath'
                }
            }
        """.trimIndent()

        settings.addInitScript("opa-ir-coverage", initScript)
        thisLogger().info("Injected OPA IR coverage agent init script for $projectPath")
    }
}

class OpaIrCoverageTaskNotificationListener : ExternalSystemTaskNotificationListener {

    override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
        if (id.projectSystemId != GradleConstants.SYSTEM_ID) return

        val project = id.findProject() ?: return
        val session = project.getService(OpaIrCoverageGradleSession::class.java)
        // Only the bound invocation may consume the run; another Gradle task ending in between would
        // otherwise read the reports before the test JVMs have written them.
        val pending = session.consumeFor(id) ?: return

        // A JVM that captured nothing writes no file at all, so an empty directory means the agent
        // produced nothing rather than "coverage is zero".
        val reports = OpaIrCoverageGradleSession.reportsIn(pending.outputDir.toFile())
        if (reports.isEmpty()) {
            thisLogger().info(
                "OPA IR coverage: no reports in ${pending.outputDir}; the most likely cause is that " +
                    "opa-jackson is not on the tested application's runtime classpath, so the agent " +
                    "found no Rego bundle or profiler to instrument"
            )
            return
        }

        ApplicationManager.getApplication().invokeLater({
            val runner = CoverageRunner.getInstance(OpaIrCoverageRunner::class.java) ?: run {
                thisLogger().warn("OPA IR coverage: OpaIrCoverageRunner not found")
                return@invokeLater
            }
            val engine = CoverageEngine.EP_NAME.findExtension(OpaIrCoverageEngine::class.java) ?: run {
                thisLogger().warn("OPA IR coverage: OpaIrCoverageEngine not found")
                return@invokeLater
            }
            // The provider is given the run directory, not a file: loadCoverageData only ever
            // receives a single File, so passing the directory is what lets the runner merge every
            // JVM's report.
            val provider = DefaultCoverageFileProvider(pending.outputDir.toFile())
            val suite = engine.createCoverageSuite(
                "Rego IR — ${pending.runConfigName}",
                project,
                runner,
                provider,
                pending.startedAt,
            )
            val manager = CoverageDataManager.getInstance(project)
            manager.coverageGathered(suite)
            thisLogger().info(
                "OPA IR coverage: loaded suite from ${pending.outputDir} (${reports.size} report(s))"
            )

            // CoverageDataAnnotationsManager.show() iterates active bundles in ConcurrentHashMap
            // order and early-returns as soon as one bundle's engine reports it does not handle the
            // file type, so with a Java suite active the rego files never get gutter markers. Drive
            // CoverageEditorAnnotatorImpl ourselves instead.
            val bundle = manager.activeSuites().find { it.coverageEngine is OpaIrCoverageEngine }
                ?: return@invokeLater
            val psiManager = PsiManager.getInstance(project)
            val toAnnotate = mutableListOf<Pair<RegoFile, Editor>>()
            for (vFile in FileEditorManager.getInstance(project).openFiles) {
                if (!vFile.name.endsWith(".rego")) continue
                val psiFile = psiManager.findFile(vFile) as? RegoFile ?: continue
                for (fileEditor in FileEditorManager.getInstance(project).getAllEditors(vFile)) {
                    if (fileEditor !is TextEditor) continue
                    toAnnotate.add(psiFile to fileEditor.editor)
                }
            }
            // showCoverage builds markup from the PsiFile, so it needs read access; expireWith drops
            // it if the project closes. The explicit Runnable disambiguates nonBlocking's overloads.
            ReadAction.nonBlocking(Runnable {
                for ((psiFile, editor) in toAnnotate) {
                    if (!psiFile.isValid || editor.isDisposed) continue
                    CoverageEditorAnnotatorImpl(psiFile, editor).showCoverage(bundle)
                }
            })
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
        }, ModalityState.nonModal())
    }
}

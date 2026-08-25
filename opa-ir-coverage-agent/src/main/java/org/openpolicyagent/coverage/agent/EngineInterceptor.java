/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.coverage.agent;

import net.bytebuddy.asm.Advice;

/**
 * Registers the shared {@code CoverageProfiler} on the {@code PreparedQuery.Builder} returned by
 * {@code Engine.prepareForEvaluation()}, so every built {@code PreparedQuery} sees coverage
 * callbacks.
 *
 * <p>ByteBuddy compiles this body into the SDK class, so it must stay a trivial one-liner with no
 * dependencies of its own; the real work lives in {@link OpaIrCoverageAgent#registerProfiler}.
 */
public final class EngineInterceptor {

    @Advice.OnMethodExit
    public static void registerProfiler(@Advice.Return Object builder) {
        OpaIrCoverageAgent.registerProfiler(builder);
    }

    private EngineInterceptor() {}
}

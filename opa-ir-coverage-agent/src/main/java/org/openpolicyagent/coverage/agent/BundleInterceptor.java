/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.coverage.agent;

import net.bytebuddy.asm.Advice;

/**
 * Hooks {@code Bundle.Builder.withIrPolicy} rather than {@code BundleAssembler.loadPlan} because the
 * {@code Policy} is already parsed and available as a method argument, so nothing has to re-read the
 * input stream.
 *
 * <p>ByteBuddy compiles this body into the SDK class, so it must stay a trivial one-liner with no
 * dependencies of its own; the real work lives in {@link OpaIrCoverageAgent}.
 */
public final class BundleInterceptor {

    @Advice.OnMethodEnter
    public static void capturePolicy(@Advice.Argument(0) Object policy) {
        OpaIrCoverageAgent.capturePolicy(policy);
    }

    private BundleInterceptor() {}
}

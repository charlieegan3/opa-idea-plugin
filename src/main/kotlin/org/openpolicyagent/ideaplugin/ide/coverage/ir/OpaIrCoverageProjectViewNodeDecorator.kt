/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.intellij.coverage.AbstractCoverageProjectViewNodeDecorator
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageEngine
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleTextAttributes

class OpaIrCoverageProjectViewNodeDecorator : AbstractCoverageProjectViewNodeDecorator() {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val vFile = extractVirtualFile(node) ?: return
        if (!vFile.name.endsWith(".rego")) return
        val project = node.project ?: return

        val manager = CoverageDataManager.getInstance(project)
        val engine = CoverageEngine.EP_NAME.findExtension(OpaIrCoverageEngine::class.java)
        // decorate() runs on every repaint, so tracing stays at debug level.
        if (engine == null) { thisLogger().debug("OPA IR decorator: engine not found"); return }

        val bundle = manager.activeSuites().find { it.coverageEngine is OpaIrCoverageEngine }
        if (bundle == null) {
            thisLogger().debug("OPA IR decorator: no active Rego IR bundle")
            return
        }

        val annotator = engine.getCoverageAnnotator(project) as? OpaIrCoverageAnnotator
        if (annotator == null) { thisLogger().debug("OPA IR decorator: annotator not OpaIrCoverageAnnotator"); return }

        val info = annotator.getDirectCoverageInfoString(vFile, bundle) ?: return

        data.addText(" $info", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun extractVirtualFile(node: ProjectViewNode<*>): VirtualFile? = when (val v = node.value) {
        is PsiFile -> v.virtualFile
        is VirtualFile -> v
        else -> (node as? PsiFileNode)?.virtualFile
    }
}

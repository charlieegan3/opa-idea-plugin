/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.ProjectData

@Service(Service.Level.PROJECT)
class OpaIrCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project) {

    /**
     * Reads coverage for [vFile] straight from ProjectData, bypassing the async-populated
     * myFileCoverageInfos cache. Probes the lowercased path first for case-insensitive
     * filesystems.
     */
    fun getDirectCoverageInfoString(
        vFile: VirtualFile,
        bundle: CoverageSuitesBundle,
    ): String? {
        val projectData = bundle.getCoverageData() ?: return null
        val classData = projectData.getClassData(vFile.path.lowercase())
            ?: projectData.getClassData(vFile.path)
            ?: return null
        val info = fileInfoForCoveredFile(classData) ?: return null
        return getLinesCoverageInformationString(info)
    }

    override fun getFileCoverageInformationString(
        project: Project,
        vFile: VirtualFile,
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        if (vFile.name.endsWith(".rego")) {
            val direct = getDirectCoverageInfoString(vFile, bundle)
            if (direct != null) return direct
        }
        return super.getFileCoverageInformationString(project, vFile, bundle, manager)
    }

    // Same for directories: without this, rego/ only appears once myDirCoverageInfos is filled.
    override fun getDirCoverageInformationString(
        project: Project,
        vFile: VirtualFile,
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        val projectData = bundle.getCoverageData()
        if (projectData != null) {
            val dirPrefix = vFile.path.lowercase() + "/"
            val regoEntries = projectData.classes.keys
                .filter { it.endsWith(".rego") && it.startsWith(dirPrefix) }
            if (regoEntries.isNotEmpty()) {
                val info = BaseCoverageAnnotator.DirCoverageInfo()
                info.totalFilesCount = regoEntries.size
                for (key in regoEntries) {
                    val classData = projectData.getClassData(key) ?: continue
                    val fileInfo = fileInfoForCoveredFile(classData)
                    if (fileInfo != null && fileInfo.coveredLineCount > 0) info.coveredFilesCount++
                    info.totalLineCount += fileInfo?.totalLineCount ?: 0
                    info.coveredLineCount += fileInfo?.coveredLineCount ?: 0
                }
                val result = getFilesCoverageInformationString(info)
                if (result != null) return result
            }
        }
        return super.getDirCoverageInformationString(project, vFile, bundle, manager)
    }

    override fun annotate(
        dir: VirtualFile,
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager,
        projectData: ProjectData,
        project: Project,
        runner: CoverageAnnotatorRunner,
    ) {
        super.annotate(dir, bundle, manager, projectData, project, runner)

        val regoKeys = projectData.classes.keys.filter { it.endsWith(".rego") }
        if (regoKeys.isEmpty()) return

        val regoDirs = regoKeys.mapNotNull { key ->
            LocalFileSystem.getInstance().findFileByPath(key)?.parent
        }.toSet()

        val projectFileIndex = ProjectFileIndex.getInstance(project)
        for (regoDir in regoDirs) {
            // The base annotate() only walks known roots, so rego source folders are missing
            // from the report.
            collectFolderCoverage(
                regoDir,
                manager,
                runner,
                projectData,
                // includeTestFolders: rego coverage comes from policy sources only.
                false,
                projectFileIndex,
                bundle.coverageEngine,
                // visitedDirs: each rego dir is walked once from a distinct parent, so a fresh
                // set per call loses nothing.
                mutableSetOf(),
                // normalizedFiles2Files: case aliases, handled by explicit probing above.
                emptyMap(),
            )
        }
    }
}

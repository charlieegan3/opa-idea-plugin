/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import org.openpolicyagent.ideaplugin.opa.project.settings.OpaProjectSettings
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parses the JSON written by `opa-ir-coverage-agent` into IntelliJ's [ProjectData].
 *
 * Wire format is the SDK's `OpaCoverageReport` (see `opa-jackson`'s `OpaCoverageReport.from`):
 * ```
 * { "files": { "<build-time-path>": {
 *     "covered": [ { "start": {"row": r, "col": c}, "end": {...} }, ... ],
 *     "not_covered": [ { "start": {...}, "end": {...} }, ... ]
 * } } }
 * ```
 *
 * Paths are frozen at `opa build` time, often on another machine, so they are reanchored under the
 * local Rego source root.
 *
 * `not_covered` is the plan's `unplanned_rules` — rules the planner never compiled — not dead
 * statements inside compiled rules; the SDK has no statement-level walk.
 *
 * Each forked test JVM writes its own report; merging is a union of rows with covered-wins applied
 * only once every report is read, which keeps it order-independent.
 */
class OpaIrCoverageReportParser {

    fun parse(jsonFile: File, project: Project): ProjectData = parse(listOf(jsonFile), project)

    fun parse(jsonFiles: List<File>, project: Project): ProjectData {
        val sourceRoot = OpaProjectSettings.getInstance(project).regoBundleSourceRoot
        val projectBase = project.basePath ?: return ProjectData()
        val localSourceRoot = Paths.get(projectBase, sourceRoot)
        return parseAll(jsonFiles.map { it.readText() }, sourceRoot, localSourceRoot)
    }

    /** Pure overload for unit tests — no [Project] required. */
    internal fun parse(jsonText: String, sourceRootName: String, localSourceRoot: Path): ProjectData =
        parseAll(listOf(jsonText), sourceRootName, localSourceRoot)

    internal fun parseAll(
        jsonTexts: List<String>,
        sourceRootName: String,
        localSourceRoot: Path,
    ): ProjectData {
        val data = ProjectData()
        val coveredByFile = linkedMapOf<String, MutableSet<Int>>()
        val notCoveredByFile = linkedMapOf<String, MutableSet<Int>>()

        for (jsonText in jsonTexts) {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val files = if (root.has("files")) root.getAsJsonObject("files") else continue
            for ((buildPath, entry) in files.entrySet()) {
                val localPath = remapPath(buildPath, sourceRootName, localSourceRoot) ?: continue
                val fileObj = entry.asJsonObject
                coveredByFile.getOrPut(localPath) { mutableSetOf() }.addAll(fileObj.rows("covered"))
                notCoveredByFile.getOrPut(localPath) { mutableSetOf() }
                    .addAll(fileObj.rows("not_covered"))
            }
        }

        for (localPath in coveredByFile.keys) {
            val covered = coveredByFile.getValue(localPath)
            // A row that executed in any one JVM is covered, even if unplanned in another.
            val notCovered = notCoveredByFile.getValue(localPath) - covered
            if (covered.isEmpty() && notCovered.isEmpty()) continue

            val maxRow = maxOf(covered.maxOrNull() ?: 0, notCovered.maxOrNull() ?: 0)

            // SimpleCoverageAnnotator.normalizeFilePath lowercases on case-insensitive filesystems
            // (macOS), so key by original case (gutter lookup via getQualifiedNames) and by
            // lowercase (coverage tree via collectBaseFileCoverage).
            val keys = linkedSetOf(localPath)
            val lowercasedPath = localPath.lowercase()
            if (lowercasedPath != localPath) keys.add(lowercasedPath)
            for (key in keys) {
                // Fresh LineData per key: the platform mutates hit counts in place, so sharing
                // instances would leak mutations between the two keys.
                val lineArr = arrayOfNulls<LineData>(maxRow + 1)
                for (row in notCovered) {
                    lineArr[row] = LineData(row, "").apply { setStatus(LineCoverage.NONE) }
                }
                for (row in covered) {
                    lineArr[row] = LineData(row, "").apply { setStatus(LineCoverage.FULL) }
                }
                data.getOrCreateClassData(key).setLines(lineArr)
            }
        }
        return data
    }

    /**
     * Strips everything up to and including the last segment matching the configured Rego source
     * root, then resolves the remainder under [localSourceRoot]. Null when no such segment exists.
     */
    internal fun remapPath(
        buildPath: String,
        sourceRootName: String,
        localSourceRoot: Path,
    ): String? {
        val segments = buildPath.split('/', '\\').filter { it.isNotEmpty() }
        val marker = sourceRootName.split('/', '\\').last { it.isNotEmpty() }
        val lastIdx = segments.indexOfLast { it == marker }
        if (lastIdx < 0 || lastIdx == segments.lastIndex) return null
        val relative = segments.drop(lastIdx + 1).joinToString("/")
        return localSourceRoot.resolve(relative).toString()
    }

    /**
     * Expands `{start:{row},end:{row}}` ranges to the rows they span. `col` is discarded because
     * IntelliJ's [LineData] is line-granular. Non-positive rows are dropped: synthetic statements
     * have no source location in some SDK versions.
     */
    private fun JsonObject.rows(name: String): Set<Int> {
        if (!has(name)) return emptySet()
        val rows = mutableSetOf<Int>()
        for (element in getAsJsonArray(name)) {
            val range = element.asJsonObject
            val startRow = range.getAsJsonObject("start").get("row").asInt
            val endRow = range.getAsJsonObject("end").get("row").asInt
            for (row in startRow..endRow) {
                if (row > 0) rows.add(row)
            }
        }
        return rows
    }
}

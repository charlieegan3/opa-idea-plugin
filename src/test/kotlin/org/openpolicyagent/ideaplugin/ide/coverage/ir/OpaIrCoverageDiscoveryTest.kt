/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.intellij.rt.coverage.data.LineCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [OpaIrCoverageRunner.loadCoverageData] needs a platform `Project` to resolve settings, so report
 * discovery and the file-backed merge are exercised directly here.
 */
class OpaIrCoverageDiscoveryTest {

    private lateinit var runDir: Path
    private val localRoot = Paths.get("/proj/rego")

    @Before
    fun setUp() {
        runDir = Files.createTempDirectory("opa-ir-cov-test-")
    }

    @After
    fun tearDown() {
        runDir.toFile().deleteRecursively()
    }

    private fun write(name: String, content: String): File {
        val f = runDir.resolve(name).toFile()
        f.writeText(content)
        return f
    }

    private fun report(vararg coveredRows: Int): String {
        val ranges = coveredRows.joinToString(",") { """{"start":{"row":$it},"end":{"row":$it}}""" }
        return """{"files":{"/build/rego/policy.rego":{"covered":[$ranges],"not_covered":[]}}}"""
    }

    @Test
    fun `reportsIn returns every report file, sorted by name`() {
        write("report-51202-7d10f5e3.json", report(2))
        write("report-51201-9c4e21ab.json", report(1))

        val found = OpaIrCoverageGradleSession.reportsIn(runDir.toFile())

        assertThat(found.map { it.name })
            .containsExactly("report-51201-9c4e21ab.json", "report-51202-7d10f5e3.json")
    }

    @Test
    fun `reportsIn ignores the tmp file a fork is still writing`() {
        write("report-51201-9c4e21ab.json", report(1))
        // The agent writes report-<pid>-<uid>.json.tmp and atomically moves it into place.
        write("report-51202-7d10f5e3.json.tmp", """{"files":{"/build/rego/p.rego":{"cove""")

        val found = OpaIrCoverageGradleSession.reportsIn(runDir.toFile())

        assertThat(found.map { it.name }).containsExactly("report-51201-9c4e21ab.json")
    }

    @Test
    fun `reportsIn ignores zero-length reports so one bad file cannot fail the whole run`() {
        write("report-51201-9c4e21ab.json", report(1))
        write("report-51202-7d10f5e3.json", "")

        val found = OpaIrCoverageGradleSession.reportsIn(runDir.toFile())

        assertThat(found.map { it.name }).containsExactly("report-51201-9c4e21ab.json")
        assertThat(OpaIrCoverageGradleSession.isReportFile(runDir.resolve("report-51202-7d10f5e3.json").toFile()))
            .isFalse()
    }

    @Test
    fun `reportsIn ignores unrelated files and directories`() {
        write("report-51201-9c4e21ab.json", report(1))
        write("coverage.json", report(9))
        write("report-51202.txt", report(9))
        Files.createDirectory(runDir.resolve("report-51203-aaaaaaaa.json"))

        val found = OpaIrCoverageGradleSession.reportsIn(runDir.toFile())

        assertThat(found.map { it.name }).containsExactly("report-51201-9c4e21ab.json")
    }

    @Test
    fun `reportsIn returns empty for a run where no JVM wrote anything`() {
        assertThat(OpaIrCoverageGradleSession.reportsIn(runDir.toFile())).isEmpty()
    }

    @Test
    fun `discovered reports from separate forks merge into one ProjectData`() {
        // Each fork covered a different row of the same file.
        write("report-51201-9c4e21ab.json", report(3))
        write("report-51202-7d10f5e3.json", report(7))

        val reports = OpaIrCoverageGradleSession.reportsIn(runDir.toFile())
        val data = OpaIrCoverageReportParser()
            .parseAll(reports.map { it.readText() }, "rego", localRoot)

        val cd = data.getClassData(Paths.get("/proj/rego/policy.rego").toString())
        assertThat(cd).isNotNull
        assertThat(cd.getLineData(3).status.toInt()).isEqualTo(LineCoverage.FULL.toInt())
        assertThat(cd.getLineData(7).status.toInt()).isEqualTo(LineCoverage.FULL.toInt())
    }
}

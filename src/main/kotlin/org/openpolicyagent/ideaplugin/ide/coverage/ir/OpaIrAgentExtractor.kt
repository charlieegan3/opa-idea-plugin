/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */
package org.openpolicyagent.ideaplugin.ide.coverage.ir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

@Service(Service.Level.APP)
class OpaIrAgentExtractor {

    private val lock = Any()
    private var cachedPath: Path? = null

    fun extract(): Path = synchronized(lock) {
        cachedPath?.let { if (Files.exists(it)) return it }

        val target = File(FileUtil.getTempDirectory())
            .resolve("opa-ir-coverage")
            .resolve("opa-ir-coverage-agent.jar")
            .toPath()

        val resource = javaClass.classLoader.getResourceAsStream("agent/opa-ir-coverage-agent.jar")
            ?: throw IllegalStateException("agent/opa-ir-coverage-agent.jar not found in plugin resources")

        val bytes = resource.use { it.readBytes() }
        val newHash = sha256(bytes)

        // Compare content, not just existence: skips rewriting on every run while still
        // replacing a jar left over from an older plugin build.
        if (target.toFile().exists()) {
            val existingHash = sha256(Files.readAllBytes(target))
            if (existingHash == newHash) {
                cachedPath = target
                return target
            }
        }

        Files.createDirectories(target.parent)
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        thisLogger().info("Extracted opa-ir-coverage-agent.jar to $target")

        cachedPath = target
        target
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun getInstance(): OpaIrAgentExtractor =
            ApplicationManager.getApplication().getService(OpaIrAgentExtractor::class.java)
    }
}

/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.openpolicyagent.ideaplugin.lsp

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import org.openpolicyagent.ideaplugin.OpaTestBase

class RegalSemanticTokensColorsProviderTest : OpaTestBase() {

    private val provider = RegalSemanticTokensColorsProvider()

    private fun psiFile() = myFixture.configureByText("test.rego", "package test")

    fun `test variable declaration token maps to CONSTANT`() {
        val key = provider.getTextAttributesKey("variable", listOf("declaration"), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.CONSTANT, key)
    }

    fun `test variable definition token maps to CONSTANT`() {
        val key = provider.getTextAttributesKey("variable", listOf("definition"), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.CONSTANT, key)
    }

    fun `test variable reference token maps to CONSTANT`() {
        val key = provider.getTextAttributesKey("variable", listOf("reference"), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.CONSTANT, key)
    }

    fun `test variable with no recognized modifier returns null`() {
        val key = provider.getTextAttributesKey("variable", emptyList(), psiFile())
        assertNull(key)
    }

    fun `test namespace token maps to STRING`() {
        val key = provider.getTextAttributesKey("namespace", emptyList(), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.STRING, key)
    }

    fun `test import token maps to INSTANCE_METHOD`() {
        val key = provider.getTextAttributesKey("import", emptyList(), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.INSTANCE_METHOD, key)
    }

    fun `test keyword token maps to KEYWORD`() {
        val key = provider.getTextAttributesKey("keyword", emptyList(), psiFile())
        assertEquals(DefaultLanguageHighlighterColors.KEYWORD, key)
    }

    fun `test unknown token type returns null`() {
        val key = provider.getTextAttributesKey("unknown", emptyList(), psiFile())
        assertNull(key)
    }
}

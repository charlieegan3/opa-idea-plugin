/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.openpolicyagent.ideaplugin.lsp

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.SemanticTokensColorsProvider

class RegalSemanticTokensColorsProvider : SemanticTokensColorsProvider {
    /**
     * Returns the [TextAttributesKey] to use for colorization for the given token type and given token modifiers and null otherwise.
     *
     * @param tokenType      the token type.
     * @param tokenModifiers the token modifiers.
     * @param file           the Psi file.
     * @return the [TextAttributesKey] to use for colorization for the given token type and given token modifiers and null otherwise.
     */
    override fun getTextAttributesKey(
        tokenType: String,
        tokenModifiers: List<String>,
        file: PsiFile
    ): TextAttributesKey? = when (tokenType) {
        // These are the only token types we support currently, may need to update these if more are supported in the future!
        "namespace" -> DefaultLanguageHighlighterColors.STRING
        "variable" -> when {
            tokenModifiers.contains("declaration") -> DefaultLanguageHighlighterColors.CONSTANT
            tokenModifiers.contains("definition")  -> DefaultLanguageHighlighterColors.CONSTANT
            tokenModifiers.contains("reference")   -> DefaultLanguageHighlighterColors.CONSTANT
            else -> null
        }
        "import"  -> DefaultLanguageHighlighterColors.INSTANCE_METHOD
        "keyword" -> DefaultLanguageHighlighterColors.KEYWORD
        else -> null
    }
}

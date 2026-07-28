/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.idea.annotator

import org.azora.lang.idea.AzoraFile
import org.azora.lang.idea.highlighting.AzoraSemanticModel
import org.azora.lang.idea.highlighting.AzoraToken
import org.azora.lang.idea.symbol.AzoraMacroIndex
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Applies Azora's semantic colors on top of the lexer's: macros, function
 * calls, type references, declaration names and smart casts.
 *
 * The lexer cannot make these calls — it does not know which names a `meta`
 * declaration turned into a macro, nor which names are types — so the work
 * happens here, against the project's real macro and symbol indexes. The whole
 * file is classified once and cached; annotating a token is then a lookup.
 */
class AzoraSemanticAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return // leaves only
        val file = element.containingFile as? AzoraFile ?: return

        val key = classification(file)[element.textRange.startOffset] ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
            .create()
    }

    /** The file's token classification, recomputed only when the file changes. */
    private fun classification(file: PsiFile): Map<Int, TextAttributesKey> =
        CachedValuesManager.getCachedValue(file) {
            val macros = runCatching {
                AzoraMacroIndex.getInstance(file.project).macrosFor(file.text)
            }.getOrNull()

            val result = if (macros == null) {
                emptyMap()
            } else {
                AzoraSemanticModel.classify(tokensOf(file), macros)
            }
            CachedValueProvider.Result.create(result, file)
        }

    /** Flattens the file's leaf tokens into the form the model works on. */
    private fun tokensOf(file: PsiFile): List<AzoraToken> {
        val node = file.node ?: return emptyList()
        return node.getChildren(null).map { child ->
            AzoraToken(child.elementType, child.startOffset, child.startOffset + child.textLength, child.text)
        }
    }
}

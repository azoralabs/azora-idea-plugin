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

package org.azora.lang.idea.findusages

import org.azora.lang.idea.AzoraTokenTypes
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet
import org.azora.lang.idea.AzoraLexerAdapter

/**
 * Find usages provider for Azora.
 *
 * Enables "Find Usages" (Ctrl+Alt+F7 / Cmd+Alt+F7) for identifiers in
 * Azora source files. Uses a [DefaultWordsScanner] backed by the Azora
 * lexer adapter so that IntelliJ can index identifiers, keywords, strings,
 * and comments for fast lookup.
 *
 * Since the plugin uses a flat token-based PSI (no full AST with named
 * declaration nodes), the provider primarily supports text-based identifier
 * search. This still allows users to find all occurrences of a symbol name
 * across Azora files in the project.
 */
class AzoraFindUsagesProvider : FindUsagesProvider {

    /**
     * Returns a [DefaultWordsScanner] that tokenizes Azora source for usage indexing.
     *
     * Configures the scanner with identifier, comment, and literal token sets
     * so IntelliJ can build a word index for fast cross-file usage lookups.
     *
     * @return a words scanner backed by [AzoraLexerAdapter].
     */
    override fun getWordsScanner(): WordsScanner {
        return DefaultWordsScanner(
            AzoraLexerAdapter(),
            /* identifierTokens */ TokenSet.create(AzoraTokenTypes.IDENTIFIER),
            /* commentTokens */ AzoraTokenTypes.COMMENTS,
            /* literalTokens */ TokenSet.orSet(AzoraTokenTypes.STRINGS, AzoraTokenTypes.NUMBERS)
        )
    }

    /**
     * Returns `true` if "Find Usages" can be invoked on [psiElement].
     *
     * Supports both [PsiNamedElement] instances and any element whose
     * token type is [AzoraTokenTypes.IDENTIFIER].
     *
     * @param psiElement the element to check.
     * @return `true` if usages can be searched for this element.
     */
    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is PsiNamedElement ||
                psiElement.node?.elementType == AzoraTokenTypes.IDENTIFIER
    }

    /**
     * Returns `null` because no custom help topic is associated with Azora find usages.
     *
     * @param psiElement the element being queried.
     */
    override fun getHelpId(psiElement: PsiElement): String? = null

    /**
     * Returns a generic type description for the given [element].
     *
     * Always returns `"identifier"` because the flat PSI tree does not
     * distinguish between declaration kinds at the element level.
     *
     * @param element the element whose type is requested.
     * @return `"identifier"`.
     */
    override fun getType(element: PsiElement): String {
        // Since we use flat PSI, return a generic type
        return "identifier"
    }

    /**
     * Returns a human-readable name for [element], shown in the "Find Usages" results.
     *
     * Uses [PsiNamedElement.getName] if available, falling back to the element's text.
     *
     * @param element the element to describe.
     * @return the element's name or text.
     */
    override fun getDescriptiveName(element: PsiElement): String {
        if (element is PsiNamedElement) {
            return element.name ?: element.text
        }
        return element.text
    }

    /**
     * Returns the display text for [element] in usage result nodes.
     *
     * Delegates to [getDescriptiveName].
     *
     * @param element the element to describe.
     * @param useFullName whether to use a fully qualified name (unused).
     * @return the element's descriptive name.
     */
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return getDescriptiveName(element)
    }
}

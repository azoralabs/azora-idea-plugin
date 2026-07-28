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

package org.azora.lang.idea

import com.intellij.lang.*
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/**
 * [PairedBraceMatcher] for the Azora language.
 *
 * Handles matching and auto-insertion of `()`, `[]`, and `{}` pairs.
 * Curly braces are marked as structural (used for code folding),
 * while parentheses and brackets are not.
 */
class AzoraBraceMatcher : PairedBraceMatcher {

    /** Returns the array of brace pairs recognized by Azora. */
    override fun getPairs(): Array<BracePair> = PAIRS

    /**
     * Returns `true` unconditionally, allowing auto-insertion of a closing
     * brace regardless of the token type that follows the caret.
     *
     * @param lbraceType the type of the opening brace being inserted.
     * @param contextType the type of the token immediately after the caret, or `null`.
     */
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    /**
     * Returns the offset where the code construct containing the brace begins.
     *
     * Returns [openingBraceOffset] as-is because the flat PSI tree does not
     * provide structural information to locate the containing statement or block.
     *
     * @param file the PSI file containing the brace.
     * @param openingBraceOffset the offset of the opening brace in the file.
     */
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {

        /** The set of matched brace pairs: `()`, `[]`, and `{}` (structural). */
        private val PAIRS = arrayOf(
            BracePair(AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN, false),
            BracePair(AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET, false),
            BracePair(AzoraTokenTypes.L_BRACE, AzoraTokenTypes.R_BRACE, true),
        )
    }
}

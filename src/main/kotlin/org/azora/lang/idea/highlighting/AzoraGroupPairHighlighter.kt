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

package org.azora.lang.idea.highlighting

import org.azora.lang.idea.AzoraFile
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer

/**
 * Highlights the two halves of a group together.
 *
 * `[x, y, z] = [a, b, c]` pairs by position, and checking that the pairing is
 * the one you meant is counting on two sides at once. With the caret on `y`,
 * `b` lights up with it - and from `c`, `z` does.
 */
class AzoraGroupPairHighlighterFactory : HighlightUsagesHandlerFactoryBase() {

    override fun createHighlightUsagesHandler(
        editor: Editor,
        file: PsiFile,
        target: PsiElement,
    ): HighlightUsagesHandlerBase<PsiElement>? {
        if (file !is AzoraFile) return null
        val ranges = AzoraGroups.counterpartsAt(file.text, target.textRange.startOffset)
        if (ranges.isEmpty()) return null
        return Handler(editor, file, target, ranges.map { TextRange(it.first, it.last + 1) })
    }

    private class Handler(
        editor: Editor,
        file: PsiFile,
        private val target: PsiElement,
        private val ranges: List<TextRange>,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

        override fun getTargets(): MutableList<PsiElement> = mutableListOf(target)

        override fun selectTargets(targets: MutableList<out PsiElement>, selectionConsumer: Consumer<in MutableList<out PsiElement>>) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: MutableList<out PsiElement>) {
            ranges.forEach { myReadUsages.add(it) }
        }
    }
}

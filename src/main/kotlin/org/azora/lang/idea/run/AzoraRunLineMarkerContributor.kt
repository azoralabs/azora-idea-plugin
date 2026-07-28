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

package org.azora.lang.idea.run

import org.azora.lang.idea.AzoraTokenTypes
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

/**
 * Puts a run gutter icon on every Azora entry point and test.
 *
 * Three things are runnable and all three get an icon:
 *
 * * `func main(…)` — the synchronous entry point.
 * * `task main(…)` — the asynchronous entry point.
 * * `test "…" { … }` — every test block, anchored on the `test` keyword so the
 *   icon always lands on the declaration line.
 */
class AzoraRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null) return null // leaves only
        val file = element.containingFile ?: return null
        if (file.virtualFile?.extension != "az") return null

        val type = element.node?.elementType ?: return null

        if (type == AzoraTokenTypes.DECLARATION_KEYWORD && element.text == "test") {
            val name = testName(element)
            val label = if (name != null) "Run test '$name'" else "Run tests in ${file.name}"
            return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { label }
        }

        if (type == AzoraTokenTypes.IDENTIFIER && element.text == "main") {
            val keyword = previousMeaningful(element) ?: return null
            if (keyword.node?.elementType != AzoraTokenTypes.DECLARATION_KEYWORD) return null
            val form = keyword.text
            if (form != "func" && form != "task") return null
            return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) {
                "Run $form main()"
            }
        }

        return null
    }

    /** The quoted name that follows a `test` keyword, if there is one. */
    private fun testName(testKeyword: PsiElement): String? {
        val next = nextMeaningful(testKeyword) ?: return null
        if (next.node?.elementType != AzoraTokenTypes.STRING_LITERAL) return null
        return next.text.trim('"').takeIf { it.isNotEmpty() }
    }

    private fun previousMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.prevSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.prevSibling
        }
        return sibling
    }

    private fun nextMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.nextSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.nextSibling
        }
        return sibling
    }
}

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

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * An intention that replaces the text a diagnostic covers.
 *
 * Every Azora quick fix is expressible this way — correcting a module path,
 * closing a string, deleting a stray bracket — so one action type covers them
 * all, and the fix's effect is exactly what its title says.
 *
 * @param title what the fix does, shown in the intention popup.
 * @param range the text to replace.
 * @param replacement the text to put there; a `null` replacement is a no-op
 *   marker for advice that has no mechanical fix.
 */
class AzoraReplacementFix(
    private val title: String,
    private val range: TextRange,
    private val replacement: String?,
) : IntentionAction {

    override fun getText(): String = title

    override fun getFamilyName(): String = "Azora"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        replacement != null && file != null && range.endOffset <= file.textLength

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val text = replacement ?: return
        val document = editor?.document ?: file?.viewProvider?.document ?: return
        if (range.endOffset > document.textLength) return
        document.replaceString(range.startOffset, range.endOffset, text)
    }

    override fun startInWriteAction(): Boolean = true
}

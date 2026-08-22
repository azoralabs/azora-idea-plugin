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
import org.azora.lang.idea.symbol.AzoraAutoImport
import org.azora.lang.idea.symbol.AzoraImports
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Alt-Enter on a name that is written but not imported: *Import 'Display' from
 * 'std.format'*.
 *
 * Offered only where it would actually help - the name under the caret resolves
 * to nothing today, and some module declares it. Both halves matter: without
 * the first the intention would appear on every name in the file, and without
 * the second it would appear and then do nothing.
 *
 * The check runs when the intention popup opens rather than while typing, so a
 * name is never underlined on the strength of an index that has not finished
 * scanning. What a wrong answer costs here is one line in a menu.
 */
class AzoraImportIntention : IntentionAction {

    /** Recomputed by [isAvailable]; [invoke] never asks a second time. */
    private var offered: List<AzoraAutoImport.Candidate> = emptyList()

    override fun getText(): String = offered.firstOrNull()
        ?.let { "Import '${it.symbol.name}' from '${it.module}'" }
        ?: "Import the symbol under the caret"

    override fun getFamilyName(): String = "Azora"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        offered = emptyList()
        if (file !is AzoraFile || editor == null) return false
        val content = file.text
        val offset = editor.caretModel.offset.coerceIn(0, content.length)
        // On an import line the name is the thing being imported; offering to
        // import it again is nonsense.
        if (AzoraImports.isInsideClause(content, offset)) return false

        val path = file.virtualFile?.path ?: file.name
        val service = AzoraSymbolService.getInstance(project)
        val reference = AzoraResolver(project, service).referenceAt(content, offset) ?: return false
        // A qualified name is reached through its receiver, so importing the
        // last segment would not be what made it resolve.
        if (reference.isQualified) return false
        if (AzoraResolver(project, service).resolve(content = content, filePath = path, offset = offset).isNotEmpty()) {
            return false
        }
        offered = runCatching {
            AzoraAutoImport.candidatesFor(project, path, content, reference.name)
        }.getOrDefault(emptyList()).take(MAX_CANDIDATES)
        return offered.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val candidate = offered.firstOrNull() ?: return
        if (file == null) return
        val documents = PsiDocumentManager.getInstance(project)
        val document = editor?.document ?: documents.getDocument(file) ?: return
        val edit = AzoraAutoImport.importEdit(document.text, candidate.module) ?: return
        AzoraAutoImport.apply(document, edit)
        documents.commitDocument(document)
    }

    override fun startInWriteAction(): Boolean = true

    private companion object {
        /** Beyond a handful, the list stops being a choice and becomes a search. */
        const val MAX_CANDIDATES = 8
    }
}

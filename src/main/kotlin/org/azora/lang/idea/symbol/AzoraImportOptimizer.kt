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

package org.azora.lang.idea.symbol

import org.azora.lang.idea.AzoraFile
import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * **Optimize Imports** for Azora: every `import path.*` becomes the selection it
 * stands for.
 *
 * ```
 * import std.container::*   →   import std.container.[list, map]
 * import std.format::*      →   import std.format::[Display, Debug]
 * ```
 *
 * The rule itself is [AzoraImportRewriter]; this is only the part that asks the
 * index which names a module offers and writes the answer to the document.
 *
 * Explicit imports are left exactly as written. Deciding that one is unused
 * means being sure a name is reachable no other way - through a macro, a
 * re-export, an operator the module declares - and the index does not know
 * that. An import that is written is a statement of intent, and this action
 * only replaces a `*` that stated none.
 */
class AzoraImportOptimizer : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean = file is AzoraFile

    override fun processFile(file: PsiFile): Runnable {
        val project = file.project
        val original = file.text
        val optimized = runCatching { rewrite(project, file, original) }.getOrDefault(original)
        if (optimized == original) return Runnable { }

        return Runnable {
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(file) ?: return@Runnable
            document.setText(optimized)
            documents.commitDocument(document)
        }
    }

    private fun rewrite(project: Project, file: PsiFile, source: String): String {
        val service = AzoraSymbolService.getInstance(project)
        val symbols = HashMap<String, Collection<String>>()
        val modules = HashMap<String, Collection<String>>()
        return AzoraImportRewriter.narrowWildcards(
            source,
            namesFor = { module ->
                symbols.getOrPut(module) { service.symbolsOfModule(project, module).map { it.name }.distinct() }
            },
            // A wildcard covers the child modules too, and a file that uses one
            // depends on the module rather than on a name inside it - which is
            // the difference the two separators exist to state.
            modulesFor = { module -> modules.getOrPut(module) { service.childModulesOf(module) } },
        )
    }
}

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

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

/**
 * Finding the import a name needs, and writing it.
 *
 * Two features want the same two things: completion, which offers a name that
 * is not imported yet and imports it when the name is accepted, and the quick
 * fix on a name already written. Both ask [candidatesFor] what would make the
 * name resolve, and both write it with [importEdit].
 *
 * Nothing here decides *whether* to import - that is the caller's, because the
 * two features learn that a name is unresolved in different ways.
 */
object AzoraAutoImport {

    /** A module that declares the wanted name, and the symbol it declares. */
    data class Candidate(val module: String, val symbol: SymbolInfo) {
        /** What the import line would say. */
        val importPath: String get() = module
    }

    /** An insertion to make: [text] at [offset], already newline-terminated. */
    data class Edit(val offset: Int, val text: String)

    /**
     * Modules that would make [name] resolve, best first.
     *
     * A module this file already imports is never offered - the name either
     * resolves already or the import would change nothing. `std` sorts ahead of
     * the project's own modules only by being shorter, which is the same
     * tie-break a reader would use.
     */
    fun candidatesFor(
        project: Project?,
        filePath: String,
        content: String,
        name: String,
    ): List<Candidate> {
        if (name.isEmpty()) return emptyList()
        val already = importedPathsWithParents(content)
        val service = AzoraSymbolService.getInstance(project ?: return emptyList())
        return service.getImportableSymbols(project, filePath).asSequence()
            .filter { it.name == name }
            .mapNotNull { symbol -> symbol.modulePath?.let { Candidate(it, symbol) } }
            .filter { it.module !in already }
            .distinctBy { it.module }
            .sortedWith(compareBy({ it.module.count { c -> c == '.' } }, { it.module }))
            .toList()
    }

    /**
     * Where and what to write to import [path] into [content], or null when the
     * file already imports it.
     *
     * The line goes with the other imports, in the order they are already in if
     * they are sorted and at the end of the block if they are not - a file that
     * keeps its imports tidy stays tidy, and one that does not is not
     * rearranged behind the author's back.
     */
    fun importEdit(content: String, path: String): Edit? {
        val clauses = AzoraImports.clauses(content)
        if (clauses.any { clause -> clause.leaves.any { it.path == path } }) return null

        val line = "import $path"
        if (clauses.isEmpty()) return Edit(afterHeader(content), "$line\n")

        val sorted = clauses.map { it.base }.let { it == it.sorted() }
        if (sorted) {
            val successor = clauses.firstOrNull { it.base > path }
            if (successor != null) {
                return Edit(lineStartOf(content, successor.start), "${successor.indent}$line\n")
            }
        }
        val last = clauses.last()
        val end = content.indexOf('\n', last.end).let { if (it < 0) content.length else it }
        return Edit(end + 1, "${last.indent}$line\n")
    }

    /**
     * The paths a file imports, plus every parent of each.
     *
     * `import std.container.list` makes `std.container` imported for this
     * purpose: adding `import std.container` beside it would be noise, and a
     * name found in either is already reachable.
     */
    private fun importedPathsWithParents(content: String): Set<String> {
        val result = linkedSetOf<String>()
        for (path in AzoraImports.importedPaths(content)) {
            var current = path
            while (current.isNotEmpty()) {
                result.add(current)
                current = current.substringBeforeLast('.', "")
            }
        }
        return result
    }

    /** The offset an import block should start at: after a `module` header, else at the top. */
    private fun afterHeader(content: String): Int {
        val header = Regex("""(?m)^\s*(?:export\s+|exposed\s+)?module\s+[^\n]*$""").find(content) ?: return 0
        val lineEnd = content.indexOf('\n', header.range.last).let { if (it < 0) content.length else it }
        // A blank line after the header is conventional; keep it above the imports.
        var at = lineEnd + 1
        while (at < content.length && content[at] == '\n') at++
        return at.coerceAtMost(content.length)
    }

    private fun lineStartOf(content: String, offset: Int): Int =
        content.lastIndexOf('\n', offset - 1) + 1

    /** Applies [edit] to [document]. Must be called inside a write action. */
    fun apply(document: Document, edit: Edit) {
        document.insertString(edit.offset.coerceIn(0, document.textLength), edit.text)
    }
}

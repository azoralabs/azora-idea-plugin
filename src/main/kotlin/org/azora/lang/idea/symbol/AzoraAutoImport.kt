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
        val importPath: String get() = "$module::${symbol.name}"
    }

    /**
     * The change to make: [text] replaces `[offset, end)`.
     *
     * [end] equals [offset] for the common case of writing a new clause, and
     * spans an existing one when a name joins it - `import std.traits::Equal`
     * beside `import std.traits::PartialEqual` would be two clauses about one
     * module, so the first becomes `import std.traits::{PartialEqual, Equal}`.
     */
    data class Edit(val offset: Int, val text: String, val end: Int = offset)

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
        val already = modulesAlreadyProviding(content, name)
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
     * Where and what to write so [content] reaches [symbol] in [module], or
     * null when it already does.
     *
     * A clause names what it brings in: `import std.traits::PartialEqual`, not
     * `import std.traits`. The bare module path is a different clause - it
     * walks the module tree - so importing one symbol writes the `::` form, and
     * a second symbol out of the same module joins the clause that is already
     * there rather than opening another one about the same module.
     *
     * A new line goes with the other imports, in the order they are already in
     * if they are sorted and at the end of the block if they are not - a file
     * that keeps its imports tidy stays tidy, and one that does not is not
     * rearranged behind the author's back.
     *
     * [symbol] is null only for a caller that really does mean the module
     * itself, which is the one case the bare path is right for.
     */
    fun importEdit(content: String, module: String, symbol: String? = null): Edit? {
        val clauses = AzoraImports.clauses(content)
        if (clauses.any { clause -> clause.leaves.any { it.reaches(module, symbol) } }) return null

        val path = if (symbol == null) module else "$module::$symbol"
        if (symbol != null) {
            joinExistingClause(content, clauses, module, symbol)?.let { return it }
        }
        val line = "import $path"
        if (clauses.isEmpty()) return Edit(headerEnd(content), openingBlock(content, line))

        val sorted = clauses.map { it.base }.let { it == it.sorted() }
        if (sorted) {
            val successor = clauses.firstOrNull { it.base > module }
            if (successor != null) {
                return Edit(lineStartOf(content, successor.start), "${successor.indent}$line\n")
            }
        }
        val last = clauses.last()
        val end = content.indexOf('\n', last.end).let { if (it < 0) content.length else it }
        return Edit(end + 1, "${last.indent}$line\n")
    }

    /** Whether this leaf already brings [symbol] (or [module] itself) into reach. */
    private fun AzoraImports.Leaf.reaches(module: String, symbol: String?): Boolean = when {
        isWildcard -> path == module
        isSelection -> container == module && name == symbol
        // A bare path reaches the module, and everything in it with it.
        else -> path == module || (symbol != null && path == "$module.$symbol")
    }

    /**
     * [symbol] added to the clause that already selects out of [module], or
     * null when there is no such clause.
     *
     * `import std.traits::PartialEqual` + `Equal` becomes
     * `import std.traits::{PartialEqual, Equal}`, in the order the names were
     * asked for. A clause that takes the module whole is never touched: it
     * already reaches the name, and narrowing it here would drop the rest.
     */
    private fun joinExistingClause(
        content: String,
        clauses: List<AzoraImports.Clause>,
        module: String,
        symbol: String,
    ): Edit? {
        val clause = clauses.firstOrNull { candidate ->
            candidate.leaves.isNotEmpty() &&
                candidate.leaves.all { it.isSelection && it.container == module } &&
                restOfLineIsBlank(content, candidate.end)
        } ?: return null
        val names = clause.leaves.map { it.name } + symbol
        val selection = if (names.size == 1) names.first() else "{${names.joinToString(", ")}}"
        return Edit(clause.start, "import $module::$selection", clause.end)
    }

    /**
     * Whether nothing but blanks and a trailing comment follow [end] on its line.
     *
     * A clause the reader wrote more on - `as Alias`, `without [X]` - is left
     * alone. Rewriting one of those from the parsed part alone would drop what
     * the parser did not read, and a second clause is always safe.
     */
    private fun restOfLineIsBlank(content: String, end: Int): Boolean {
        val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it }
        val rest = content.substring(end.coerceAtMost(lineEnd), lineEnd).trim()
        return rest.isEmpty() || rest.startsWith("//")
    }

    /**
     * The first import of a file, with the blank lines that set it apart.
     *
     * A module header, a blank line, the imports, a blank line, the code: the
     * shape every Azora file has. Whichever of the two blanks is already there
     * is not written twice.
     */
    private fun openingBlock(content: String, line: String): String {
        val at = headerEnd(content)
        val leading = if (at == 0 || content.getOrNull(at - 2) == '\n') "" else "\n"
        val trailing = if (at >= content.length) "" else "\n"
        return "$leading$line\n$trailing"
    }

    /**
     * The offset an import block starts at: after a `module` header and the
     * blank lines below it, else the top of the file.
     */
    private fun headerEnd(content: String): Int {
        val header = Regex("""(?m)^\s*(?:export\s+|exposed\s+)?module\s+[^\n]*$""").find(content) ?: return 0
        val lineEnd = content.indexOf('\n', header.range.last).let { if (it < 0) content.length else it }
        var at = (lineEnd + 1).coerceAtMost(content.length)
        while (at < content.length && content[at] == '\n') at++
        return at
    }

    /** Modules whose import already puts [name] within reach of [content]. */
    private fun modulesAlreadyProviding(content: String, name: String): Set<String> {
        val result = linkedSetOf<String>()
        for (clause in AzoraImports.clauses(content)) {
            for (leaf in clause.leaves) {
                when {
                    leaf.isSelection -> if (leaf.name == name) result.add(leaf.container)
                    leaf.isWildcard -> result.add(leaf.path)
                    else -> {
                        // A bare path takes a module whole, so every parent of it
                        // is a module this file already reaches through.
                        var current = leaf.path
                        while (current.isNotEmpty()) {
                            result.add(current)
                            current = current.substringBeforeLast('.', "")
                        }
                    }
                }
            }
        }
        return result
    }

    private fun lineStartOf(content: String, offset: Int): Int =
        content.lastIndexOf('\n', offset - 1) + 1

    /** Applies [edit] to [document]. Must be called inside a write action. */
    fun apply(document: Document, edit: Edit) {
        val start = edit.offset.coerceIn(0, document.textLength)
        val end = edit.end.coerceIn(start, document.textLength)
        document.replaceString(start, end, edit.text)
    }
}

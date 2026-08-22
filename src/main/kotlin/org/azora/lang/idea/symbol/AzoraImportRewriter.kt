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

/**
 * Narrowing `import path.*` to the names a file actually uses.
 *
 * A wildcard says "everything below here", which is convenient to write and
 * tells a reader nothing. Optimizing imports replaces it with the selection it
 * stands for, so the clause states what the file depends on:
 *
 * ```
 * import std.container::*   →   import std.container.[list, map]
 * import std.format::*      →   import std.format::[Display, Debug]
 * ```
 *
 * Which separator the narrowed clause uses is not a style choice: a `.` walks
 * down the module tree and a `::` reaches inside a module, so a wildcard that
 * covered child modules narrows to a dotted group and one that covered
 * declarations narrows to a `::` group. A wildcard covering both becomes two
 * clauses, because one clause cannot say both.
 *
 * Pure text in, pure text out - no PSI, no project - so the rule is testable on
 * its own and the IDE action is only the part that writes the result.
 */
object AzoraImportRewriter {

    /**
     * [source] with each wildcard clause narrowed to what [namesFor] says the
     * module offers and the file uses, or [source] unchanged when nothing
     * narrows.
     *
     * A wildcard whose module offers nothing the file mentions is left alone
     * rather than deleted. The names come from an index that only sees what it
     * has scanned, and silently dropping a dependency on the strength of an
     * empty answer is the one failure a reader would not notice.
     *
     * @param namesFor the names a module declares, as the index knows them.
     */
    fun narrowWildcards(
        source: String,
        namesFor: (String) -> Collection<String>,
        modulesFor: (String) -> Collection<String> = { emptyList() },
    ): String {
        val clauses = AzoraImports.clauses(source)
        val wildcards = clauses.filter { clause -> clause.leaves.any { it.isWildcard } }
        if (wildcards.isEmpty()) return source

        val used = usedNames(source, clauses)
        val builder = StringBuilder(source)
        // Back to front, so an earlier clause's offsets survive a later rewrite.
        for (clause in wildcards.sortedByDescending { it.start }) {
            val replacement = narrow(clause, used, namesFor, modulesFor) ?: continue
            builder.replace(clause.start, clause.end, replacement)
        }
        return builder.toString()
    }

    /** The clause text a narrowed [clause] should become, or null to leave it. */
    private fun narrow(
        clause: AzoraImports.Clause,
        used: Set<String>,
        namesFor: (String) -> Collection<String>,
        modulesFor: (String) -> Collection<String>,
    ): String? {
        val parts = mutableListOf<String>()
        var narrowed = false
        for (leaf in clause.leaves) {
            if (!leaf.isWildcard) {
                parts.add(leaf.path)
                continue
            }
            val modules = modulesFor(leaf.path).toSet()
            val offered = (namesFor(leaf.path).toSet() + modules)
            val taken = offered.filterTo(sortedSetOf()) { it in used }
            if (taken.isEmpty()) return null
            if (taken.size == offered.size) {
                // Everything the module has is used; the wildcard already says
                // that, and spelling out the same list is longer, not clearer.
                parts.add("${leaf.path}::*")
                continue
            }
            narrowed = true
            val (childModules, symbols) = taken.partition { it in modules }
            selection(leaf.path, childModules, ".")?.let(parts::add)
            selection(leaf.path, symbols, "::")?.let(parts::add)
        }
        if (!narrowed) return null
        return parts.joinToString("\n${clause.indent}") { "import $it" }
    }

    /** `path.[a, b]` / `path::[a, b]`, or the one-name form, or nothing. */
    private fun selection(path: String, names: List<String>, separator: String): String? = when {
        names.isEmpty() -> null
        names.size == 1 -> "$path$separator${names.first()}"
        else -> "$path$separator[${names.joinToString(", ")}]"
    }

    /**
     * Every name [source] mentions outside its import clauses.
     *
     * Deliberately blunt: any identifier anywhere in the body counts, including
     * ones in comments and strings. An import is removed only when the name is
     * nowhere at all, and the cost of the two errors is not symmetric - keeping
     * an import that is only mentioned in a comment costs a line, dropping one
     * that is used breaks the build.
     */
    private fun usedNames(source: String, clauses: List<AzoraImports.Clause>): Set<String> {
        val body = StringBuilder(source)
        for (clause in clauses.sortedByDescending { it.start }) {
            for (i in clause.start until clause.end.coerceAtMost(body.length)) {
                if (body[i] != '\n') body.setCharAt(i, ' ')
            }
        }
        return NAME.findAll(body).mapTo(linkedSetOf()) { it.value }
    }

    private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
}

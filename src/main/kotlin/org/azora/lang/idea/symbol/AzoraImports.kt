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
 * The one reader of Azora's `import` grammar.
 *
 * Four editor features need to know the same three things about an import - how
 * far the clause reaches, which paths it brings in, and which offset names
 * which path - and each of them used to answer with its own regex. A group that
 * spans lines, or the bracket spelling next to the brace one, then worked in
 * some of them and not others. Everything reads it here instead.
 *
 * The grammar, as the compiler's `parseImportSpec` states it:
 *
 * ```
 * clause := 'import' spec
 * spec   := path ('.' group | '::' ('*' | name | group))?
 * path   := name ('.' name)*
 * group  := '[' spec (','|newline) … ']' | '{' … '}'
 * ```
 *
 * Two separators, two jobs. A `.` walks down the module tree, so every segment
 * it joins is a module. A `::` reaches inside the module the path lands on, so
 * a name, a `*` or a group after one is a *selection*:
 *
 * ```
 * import std.format::Display                one symbol out of a module
 * import std.io::*                          every symbol a module declares
 * import std.format::[Display, Debug]       several symbols out of one module
 * import std.container.[array::*, list::*]  several modules under one path
 * ```
 *
 * A group member is a clause in its own right, so it nests as far as it likes.
 */
object AzoraImports {

    /**
     * An identifier written in a clause, and where it sits in the file.
     *
     * @property isSelection whether a `::` led to it - a name *inside* a module
     *   rather than another step down the module tree. This is what tells a
     *   module path from the thing selected out of it, and the two are colored
     *   and navigated differently.
     */
    data class Segment(
        val text: String,
        val start: Int,
        val end: Int,
        val isSelection: Boolean = false,
    ) {
        val range: IntRange get() = start until end
    }

    /**
     * One leaf of a clause: a complete dotted path, and whether it was starred.
     *
     * `import std.container.[list, map]` has two leaves, `std.container.list`
     * and `std.container.map`, neither starred. `import std.io.*` has one,
     * `std.io`, starred.
     */
    data class Leaf(
        val path: String,
        val isWildcard: Boolean,
        /** The segments this leaf is spelled with, last one first in the path. */
        val segments: List<Segment>,
    ) {
        /** The last segment - the thing selected, as opposed to where it lives. */
        val name: String get() = path.substringAfterLast('.')

        /** The path without its last segment; empty for a one-segment path. */
        val container: String get() = path.substringBeforeLast('.', "")

        /**
         * Whether a `::` reached the last segment - a name *inside* a module.
         *
         * `import std.traits::Equal` selects `Equal` out of `std.traits`;
         * `import std.container.list` walks to the module `list`. Both spell a
         * dotted [path], and only this tells them apart.
         */
        val isSelection: Boolean get() = segments.lastOrNull()?.isSelection == true

        /** The module this leaf reaches into: [path] itself unless it selects. */
        val module: String get() = if (isSelection) container else path
    }

    /**
     * A whole `import …` statement.
     *
     * [start] is the offset of the keyword and [end] one past the last character
     * of the clause - past the closing bracket of a group that spans lines, not
     * at the first line break inside it.
     */
    data class Clause(
        val start: Int,
        val end: Int,
        val text: String,
        val leaves: List<Leaf>,
        val segments: List<Segment>,
        /** The whitespace the line was indented with, so a rewrite keeps it. */
        val indent: String,
    ) {
        /** The path common to every leaf - what the clause is "about". */
        val base: String
            get() = leaves.firstOrNull()?.let { first ->
                var common = first.path
                for (leaf in leaves.drop(1)) {
                    while (common.isNotEmpty() && !leaf.path.startsWith("$common.") && leaf.path != common) {
                        common = common.substringBeforeLast('.', "")
                    }
                }
                common
            }.orEmpty()
    }

    /** Every import clause in [content], in source order. */
    fun clauses(content: String): List<Clause> {
        val result = mutableListOf<Clause>()
        var cursor = 0
        while (cursor < content.length) {
            val lineEnd = content.indexOf('\n', cursor).let { if (it < 0) content.length else it }
            val keyword = keywordAt(content, cursor, lineEnd)
            if (keyword == null) {
                cursor = lineEnd + 1
                continue
            }
            val clause = readClause(content, keyword.first, keyword.second)
            result.add(clause)
            cursor = (clause.end).coerceAtLeast(cursor + 1)
            // Step past the rest of the line the clause ended on.
            val after = content.indexOf('\n', clause.end).let { if (it < 0) content.length else it }
            cursor = after + 1
        }
        return result
    }

    /**
     * The full dotted path the identifier at [offset] names, or null if the
     * offset is not on an import clause's segment.
     *
     * On `import std.container.[list, map]`, the caret on `container` names
     * `std.container` and the caret on `map` names `std.container.map` - a
     * segment names everything up to and including itself, which is what
     * navigation has to open.
     */
    fun pathAt(content: String, offset: Int): String? {
        for (clause in clauses(content)) {
            if (offset < clause.start || offset > clause.end) continue
            for (leaf in clause.leaves) {
                for ((index, segment) in leaf.segments.withIndex()) {
                    if (offset < segment.start || offset > segment.end) continue
                    return leaf.path.split('.').take(index + 1).joinToString(".")
                }
            }
        }
        return null
    }

    /** True when [offset] sits inside any import clause of [content]. */
    fun isInsideClause(content: String, offset: Int): Boolean =
        clauses(content).any { offset >= it.start && offset <= it.end }

    /** Every path any clause brings in, wildcards included as their bare path. */
    fun importedPaths(content: String): Set<String> =
        clauses(content).flatMapTo(linkedSetOf()) { clause -> clause.leaves.map { it.path } }

    /**
     * The modules a file takes *whole* - every name in them is reachable.
     *
     * `import std.io::*` and `import std.container.list` are both this; the
     * second because a dotted path with no `::` walks the module tree, and a
     * module named without a selection brings what it declares.
     *
     * A `path::Name` clause is deliberately absent: it reaches one name, which
     * is [selections]' answer and not this one. Keeping the two apart is what
     * lets an import be narrowed one symbol at a time.
     */
    fun wholeModules(content: String): Set<String> =
        clauses(content).flatMapTo(linkedSetOf()) { clause ->
            clause.leaves.filterNot { it.isSelection }.map { it.path }
        }

    /**
     * The single names each module is reached into for: `std.traits` →
     * `[PartialEqual, Equal]` for `import std.traits::[PartialEqual, Equal]`.
     *
     * A dotted path contributes here too, under the reading that its last
     * segment is a symbol rather than a module - `import std.math.abs` names
     * either, and only the module graph knows which, so both are offered and
     * the caller keeps whichever it can match.
     */
    fun selections(content: String): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        for (clause in clauses(content)) {
            for (leaf in clause.leaves) {
                if (leaf.isWildcard) continue
                if (leaf.container.isEmpty()) continue
                result.getOrPut(leaf.container) { linkedSetOf() }.add(leaf.name)
            }
        }
        return result
    }

    // ── Scanning ────────────────────────────────────────────────────────

    /**
     * The keyword opening an import on the line starting at [lineStart], as
     * (keyword offset, offset just past it), or null if the line opens none.
     */
    private fun keywordAt(content: String, lineStart: Int, lineEnd: Int): Pair<Int, Int>? {
        var i = lineStart
        while (i < lineEnd && (content[i] == ' ' || content[i] == '\t')) i++
        for (modifier in MODIFIERS) {
            if (content.startsWith(modifier, i)) {
                i += modifier.length
                while (i < lineEnd && (content[i] == ' ' || content[i] == '\t')) i++
                break
            }
        }
        for (keyword in KEYWORDS) {
            if (!content.startsWith(keyword, i)) continue
            val after = i + keyword.length
            if (after < content.length && !content[after].isWhitespace()) continue
            return i to after
        }
        return null
    }

    /** Reads one clause, starting just past its keyword at [bodyStart]. */
    private fun readClause(content: String, keywordStart: Int, bodyStart: Int): Clause {
        val segments = mutableListOf<Segment>()
        val leaves = mutableListOf<Leaf>()
        val cursor = Cursor(content, bodyStart)
        cursor.skipBlanks(acrossLines = false)
        val bodyFrom = cursor.at
        parseSpec(cursor, emptyList(), segments, leaves)
        val end = cursor.at
        val lineStart = content.lastIndexOf('\n', keywordStart - 1) + 1
        return Clause(
            start = keywordStart,
            end = end,
            text = content.substring(keywordStart, end),
            leaves = leaves,
            segments = segments,
            indent = content.substring(lineStart, keywordStart),
        ).also { if (bodyFrom > end) Unit }
    }

    /**
     * `name ('.' (name | '*' | group))*`, appending whatever leaves it finds.
     *
     * [prefix] is the path inherited from an enclosing group, so a member of
     * `std.[math.abs]` knows it is `std.math.abs`.
     */
    private fun parseSpec(
        cursor: Cursor,
        prefix: List<Segment>,
        segments: MutableList<Segment>,
        leaves: MutableList<Leaf>,
    ) {
        val head = cursor.readName() ?: return
        segments.add(head)
        var path = prefix + head

        fun finish(wildcard: Boolean) {
            leaves.add(Leaf(path.joinToString(".") { it.text }, isWildcard = wildcard, segments = path))
        }

        while (true) {
            val save = cursor.at
            cursor.skipBlanks(acrossLines = false)
            // `::` first: it is the longer token, and `.` would otherwise take
            // its first character and leave a stray colon behind.
            if (cursor.takeColons()) {
                cursor.skipBlanks(acrossLines = false)
                when {
                    cursor.take('*') -> { finish(wildcard = true); return }
                    cursor.peek() == '[' || cursor.peek() == '{' -> {
                        parseGroup(cursor, path, segments, leaves, selection = true)
                        return
                    }
                    else -> {
                        val selected = cursor.readName()?.copy(isSelection = true)
                        if (selected == null) { cursor.at = save; break }
                        segments.add(selected)
                        path = path + selected
                        finish(wildcard = false)
                        return
                    }
                }
            }
            if (!cursor.take('.')) { cursor.at = save; break }
            cursor.skipBlanks(acrossLines = false)
            when {
                // A `*` always selects symbols, so it is never reached by a `.`.
                cursor.take('*') -> { finish(wildcard = true); return }
                cursor.peek() == '[' || cursor.peek() == '{' -> {
                    parseGroup(cursor, path, segments, leaves, selection = false)
                    return
                }
                else -> {
                    val next = cursor.readName()
                    if (next == null) { cursor.at = save; break }
                    segments.add(next)
                    path = path + next
                }
            }
        }
        finish(wildcard = false)
    }

    /**
     * `[ spec, spec ]` or `{ spec, spec }`, members separated by commas or lines.
     *
     * @param selection whether a `::` opened the group, which makes its members
     *   names inside the module rather than modules under the path.
     */
    private fun parseGroup(
        cursor: Cursor,
        prefix: List<Segment>,
        segments: MutableList<Segment>,
        leaves: MutableList<Leaf>,
        selection: Boolean,
    ) {
        val closer = if (cursor.take('[')) ']' else { cursor.take('{'); '}' }
        while (true) {
            cursor.skipBlanks(acrossLines = true)
            if (cursor.atEnd() || cursor.take(closer)) return
            if (cursor.take(',')) continue
            val before = cursor.at
            val mark = segments.size
            val leafMark = leaves.size
            parseSpec(cursor, prefix, segments, leaves)
            // A group opened by `::` holds names inside the module, so its own
            // head is a selection even though no `::` precedes it directly.
            // The leaves are marked as well as the flat list: a leaf that does
            // not know its last segment was selected reads as a module path,
            // and `std.traits::[Equal]` would name a module `std.traits.Equal`.
            if (selection && segments.size > mark) {
                segments[mark] = segments[mark].copy(isSelection = true)
                for (index in leafMark until leaves.size) {
                    val leaf = leaves[index]
                    if (prefix.size >= leaf.segments.size) continue
                    val patched = leaf.segments.toMutableList()
                    patched[prefix.size] = patched[prefix.size].copy(isSelection = true)
                    leaves[index] = leaf.copy(segments = patched)
                }
            }
            // Nothing consumed means the text is not a member after all; give up
            // rather than spin, and let the clause end where it is.
            if (cursor.at == before) { cursor.at = cursor.content.length; return }
        }
    }

    /** A tiny mutable scanner - the clause is short and the grammar is small. */
    private class Cursor(val content: String, var at: Int) {
        fun atEnd() = at >= content.length
        fun peek(): Char = if (at < content.length) content[at] else ' '

        fun take(c: Char): Boolean {
            if (peek() != c) return false
            at++
            return true
        }

        fun takeColons(): Boolean {
            if (at + 1 >= content.length || content[at] != ':' || content[at + 1] != ':') return false
            at += 2
            return true
        }

        fun skipBlanks(acrossLines: Boolean) {
            while (at < content.length) {
                val c = content[at]
                if (c == ' ' || c == '\t' || c == '\r') { at++; continue }
                if (c == '\n' && acrossLines) { at++; continue }
                if (c == '/' && skipComment(acrossLines)) continue
                break
            }
        }

        /**
         * A comment inside a clause is blank space.
         *
         * A group that spans lines is the one place a reader has room to say
         * why a name is there, and `reflection::reflect // why` used to end the
         * clause at the `/`: nothing after it was an import any more, which is
         * what left the rest of the group uncolored and unnavigable.
         *
         * A `//` comment stops *at* its newline rather than past it, so the
         * caller still decides whether crossing a line is allowed. A block
         * comment that spans lines is skipped only when it is.
         */
        private fun skipComment(acrossLines: Boolean): Boolean {
            if (at + 1 >= content.length) return false
            return when (content[at + 1]) {
                '/' -> {
                    val newline = content.indexOf('\n', at)
                    at = if (newline < 0) content.length else newline
                    true
                }
                '*' -> {
                    val close = content.indexOf("*/", at + 2)
                    val end = if (close < 0) content.length else close + 2
                    if (!acrossLines && content.substring(at, end).contains('\n')) return false
                    at = end
                    true
                }
                else -> false
            }
        }

        fun readName(): Segment? {
            skipBlanks(acrossLines = false)
            if (at >= content.length) return null
            if (!content[at].isLetter() && content[at] != '_') return null
            val start = at
            while (at < content.length && (content[at].isLetterOrDigit() || content[at] == '_')) at++
            return Segment(content.substring(start, at), start, at)
        }
    }

    private val KEYWORDS = listOf("import")
    private val MODIFIERS = listOf("export ", "exposed ", "pub ")
}

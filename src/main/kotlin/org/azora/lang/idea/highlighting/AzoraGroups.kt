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

/**
 * The reader of a *grouped* statement's two sides.
 *
 * `[x, y, z] = [a, b, c]` pairs its entries by position, and so does
 * `fin [a, b] = with self { [x, y] }`. A reader checking that the pairing is
 * the one they meant has to count on both sides at once; this is what lets the
 * editor answer instead, by highlighting `y` and `b` together.
 *
 * Nothing here parses the language. A group is `[…]`, an `=`, and optionally
 * another `[…]` - a shape that is decidable from the text, which is what keeps
 * this usable while a line is still being typed.
 */
object AzoraGroups {

    /**
     * The entry ranges that answer to each other at [offset], or an empty list.
     *
     * Two ranges when the offset sits in a group whose counterpart has as many
     * entries: the one under the caret and the one it pairs with.
     */
    fun counterpartsAt(text: String, offset: Int): List<IntRange> {
        for ((left, right) in groupPairs(text)) {
            val leftEntries = entries(text, left)
            val rightEntries = entries(text, right)
            if (leftEntries.size != rightEntries.size) continue
            leftEntries.indexOfFirst { offset in it.first..it.last + 1 }
                .takeIf { it >= 0 }
                ?.let { return listOf(leftEntries[it], rightEntries[it]) }
            rightEntries.indexOfFirst { offset in it.first..it.last + 1 }
                .takeIf { it >= 0 }
                ?.let { return listOf(leftEntries[it], rightEntries[it]) }
        }
        return emptyList()
    }

    /**
     * Every `[…] = […]` in [text], as the ranges *inside* each bracket.
     *
     * The right-hand group may sit inside a `with … { … }`, which is how a
     * group reads members off another value; what is between the `=` and the
     * bracket does not change the pairing.
     */
    private fun groupPairs(text: String): List<Pair<IntRange, IntRange>> {
        val pairs = mutableListOf<Pair<IntRange, IntRange>>()
        var index = text.indexOf('[')
        while (index >= 0) {
            val close = matching(text, index)
            if (close < 0) break
            val equals = equalsAfter(text, close + 1)
            if (equals >= 0) {
                val open = nextOpenBracket(text, equals + 1)
                if (open >= 0) {
                    val rightClose = matching(text, open)
                    if (rightClose >= 0) {
                        pairs.add((index + 1 until close) to (open + 1 until rightClose))
                    }
                }
            }
            index = text.indexOf('[', index + 1)
        }
        return pairs
    }

    /**
     * The offset of the `=` that a group at [from] is assigned by, or -1.
     *
     * A type may be stated between the two - `[a, b]: Int = …` - and is passed
     * over: it says what the names are, not what they take.
     */
    private fun equalsAfter(text: String, from: Int): Int {
        var cursor = from
        while (cursor < text.length) {
            val c = text[cursor]
            when {
                c == '=' -> return if (text.getOrNull(cursor + 1) == '=') -1 else cursor
                c == ':' || c.isWhitespace() || c.isLetterOrDigit() || c == '_' || c == '<' || c == '>' ||
                    c == '.' || c == '*' || c == '?' || c == '&' || c == '!' || c == ',' -> cursor++
                else -> return -1
            }
            if (c == '\n') return -1
        }
        return -1
    }

    /** The `[` opening the value group, if that is what follows the `=`. */
    private fun nextOpenBracket(text: String, from: Int): Int {
        var cursor = from
        var sawBrace = false
        while (cursor < text.length) {
            val c = text[cursor]
            when {
                c == '[' -> return cursor
                // `= with self { [ … ] }` - the receiver and its brace sit in
                // between, and the group is still the group.
                c == '{' -> { sawBrace = true; cursor++ }
                c == '}' || c == ';' -> return -1
                c == '\n' && !sawBrace -> return -1
                else -> cursor++
            }
        }
        return -1
    }

    /** The entries of one group, as ranges into [text]. */
    private fun entries(text: String, inside: IntRange): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var depth = 0
        var start = -1
        var end = -1
        fun flush() {
            if (start >= 0 && end >= start) result.add(start..end)
            start = -1
            end = -1
        }
        for (index in inside) {
            val c = text[index]
            when {
                c == '[' || c == '(' || c == '<' -> depth++
                c == ']' || c == ')' || c == '>' -> depth--
                depth == 0 && (c == ',' || c == '\n') -> { flush(); continue }
            }
            if (c.isWhitespace()) continue
            if (start < 0) start = index
            end = index
        }
        flush()
        return result
    }

    /** The index of the `]` closing the `[` at [open], or -1. */
    private fun matching(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }
}

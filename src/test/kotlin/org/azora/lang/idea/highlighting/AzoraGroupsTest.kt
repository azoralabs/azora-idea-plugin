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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two sides of a group answer to each other by position, and the editor is
 * what should be counting.
 */
class AzoraGroupsTest {

    /** The text of each range the caret on [word] pairs. */
    private fun paired(source: String, word: String, occurrence: Int = 0): List<String> {
        var at = -1
        repeat(occurrence + 1) { at = source.indexOf(word, at + 1) }
        return AzoraGroups.counterpartsAt(source, at).map { source.substring(it.first, it.last + 1) }
    }

    @Test
    fun `a name pairs with the value at its position`() {
        val source = "[x, y, z] = [a, b, c]"

        assertEquals(listOf("y", "b"), paired(source, "y"))
        assertEquals(listOf("x", "a"), paired(source, "x"))
        assertEquals(listOf("z", "c"), paired(source, "z"))
    }

    @Test
    fun `a value pairs back with its name`() {
        val source = "[x, y, z] = [a, b, c]"

        assertEquals(listOf("z", "c"), paired(source, "c"))
    }

    @Test
    fun `a binding pairs with what it reads`() {
        val source = "fin [oldKeys, oldValues] = with self { [keys, values] }"

        assertEquals(listOf("oldValues", "values"), paired(source, "oldValues"))
    }

    @Test
    fun `a stated type does not break the pairing`() {
        val source = "fin [a, b]: Int = [1, 2]"

        assertEquals(listOf("b", "2"), paired(source, "b"))
    }

    @Test
    fun `a receiver group pairs with its values`() {
        val source = "self.[keys[i], values[i]] = [k, v]"

        assertEquals(listOf("values[i]", "v"), paired(source, "values"))
    }

    @Test
    fun `entries may be separated by lines alone`() {
        val source = "self.[\n    a\n    b\n] = [\n    1\n    2\n]"

        assertEquals(listOf("b", "2"), paired(source, "b"))
    }

    @Test
    fun `a group with one value on the right pairs with nothing`() {
        // One expression is written to every name; there is no counterpart to
        // point at.
        assertTrue(AzoraGroups.counterpartsAt("self.[a, b] = 0", "self.[a".length).isEmpty())
    }

    @Test
    fun `sides of different lengths pair with nothing`() {
        assertTrue(AzoraGroups.counterpartsAt("[a, b, c] = [1, 2]", 1).isEmpty())
    }

    @Test
    fun `an ordinary index is not a group`() {
        assertTrue(AzoraGroups.counterpartsAt("values[i] = 0", 7).isEmpty())
    }
}

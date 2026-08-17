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

package org.azora.lang.idea.azon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AzonIndentationTest {

    @Test
    fun `indents object and array contents by delimiter depth`() {
        assertIndent("package: {\n", 4)
        assertIndent("package: {\n    targets: [\n", 8)
    }

    @Test
    fun `dedents closing delimiters to their containing level`() {
        assertIndentAtMarker(
            """
            package: {
                targets: [
                    "native"
                <caret>]
            }
            """.trimIndent(),
            4,
        )
        assertIndentAtMarker("package: {\n    name: \"app\"\n<caret>}", 0)
    }

    @Test
    fun `ignores delimiters in strings and comments`() {
        assertIndent(
            """
            package: {
                text: "{ [ } ]"
                // } ] {
            """.trimIndent() + "\n",
            4,
        )
        assertIndent(
            """
            package: {
                /* nested comment { [
                   still ignored } ] */
            """.trimIndent() + "\n",
            4,
        )
    }

    @Test
    fun `supports a value continued after a colon`() {
        assertIndent("package:\n", 4)
        assertIndent("package: {\n", 4)
    }

    @Test
    fun `keeps top-level members at column zero`() {
        assertIndent("name: \"azora\"\n", 0)
    }

    private fun assertIndent(text: String, spaces: Int) {
        assertEquals(" ".repeat(spaces), AzonIndentation.lineIndent(text, text.length, 4))
    }

    private fun assertIndentAtMarker(markedText: String, spaces: Int) {
        val offset = markedText.indexOf(MARKER)
        val text = markedText.replace(MARKER, "")
        assertEquals(" ".repeat(spaces), AzonIndentation.lineIndent(text, offset, 4))
    }

    private companion object {
        const val MARKER = "<caret>"
    }
}

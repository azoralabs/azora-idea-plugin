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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where an added import goes.
 *
 * Completion and the Alt-Enter fix both write imports, and a tool that writes
 * into someone's file has to leave it looking like they wrote it: an
 * alphabetised block stays alphabetised, an unsorted one is appended to rather
 * than silently reordered.
 */
class AzoraAutoImportTest {

    private fun applied(source: String, path: String): String? {
        val edit = AzoraAutoImport.importEdit(source, path) ?: return null
        return source.substring(0, edit.offset) + edit.text + source.substring(edit.offset)
    }

    // -- placement -----------------------------------------------------------

    @Test
    fun `a sorted block keeps its order`() {
        assertEquals(
            "import app.model\nimport std.io\nimport std.math\n\nfunc main() {}",
            applied("import app.model\nimport std.math\n\nfunc main() {}", "std.io"),
        )
    }

    @Test
    fun `a name after every import goes last`() {
        assertEquals(
            "import app.model\nimport std.io\nimport zz.late\n\nfunc main() {}",
            applied("import app.model\nimport std.io\n\nfunc main() {}", "zz.late"),
        )
    }

    @Test
    fun `an unsorted block is appended to, not reordered`() {
        assertEquals(
            "import std.math\nimport app.model\nimport std.io\n\nfunc main() {}",
            applied("import std.math\nimport app.model\n\nfunc main() {}", "std.io"),
        )
    }

    @Test
    fun `a file with no imports gets one at the top`() {
        assertEquals("import std.io\nfunc main() {}", applied("func main() {}", "std.io"))
    }

    @Test
    fun `a module header keeps its place`() {
        assertEquals(
            "module app.main\n\nimport std.io\nfunc main() {}",
            applied("module app.main\n\nfunc main() {}", "std.io"),
        )
    }

    @Test
    fun `indentation is copied from the block`() {
        val out = applied("    import std.math\n\nfunc main() {}", "std.io")
        assertEquals("    import std.io\n    import std.math\n\nfunc main() {}", out)
    }

    @Test
    fun `a multi-line group is appended after, not inside`() {
        val source = "import std.container.[\n    list\n]\n\nfunc main() {}"
        assertEquals(
            "import std.container.[\n    list\n]\nimport std.io\n\nfunc main() {}",
            applied(source, "std.io"),
        )
    }

    // -- and when to write nothing -------------------------------------------

    @Test
    fun `an import that is already there is not repeated`() {
        assertNull(AzoraAutoImport.importEdit("import std.io\n\nfunc main() {}", "std.io"))
    }

    @Test
    fun `a path selected out of a group counts as imported`() {
        assertNull(
            AzoraAutoImport.importEdit("import std.container.[list, map]\n", "std.container.list"),
        )
    }
}

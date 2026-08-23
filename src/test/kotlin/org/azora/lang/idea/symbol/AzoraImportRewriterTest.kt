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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Optimize Imports, as a pure text rule.
 *
 * A wildcard is convenient to write and tells a reader nothing; narrowing it
 * replaces it with the selection it stood for. What the rule must never do is
 * lose a dependency - so the cases here are as much about what it leaves alone
 * as about what it rewrites.
 */
class AzoraImportRewriterTest {

    /** Declarations a module holds - reached with `::`. */
    private val symbols = mapOf(
        "std.io" to listOf("println", "print", "readLine"),
        "app.model" to listOf("User", "Account"),
    )

    /** Modules nested under a path - reached with `.`. */
    private val children = mapOf(
        "std.container" to listOf("list", "map", "set", "deque"),
    )

    private fun narrow(source: String): String =
        AzoraImportRewriter.narrowWildcards(
            source,
            namesFor = { symbols[it].orEmpty() },
            modulesFor = { children[it].orEmpty() },
        )

    // -- what it rewrites ----------------------------------------------------

    @Test
    fun `a wildcard becomes the names that are used`() {
        val source = "import std.container::*\n\nfunc main() {\n    fin a = list()\n    fin b = map()\n}"
        assertTrue(
            "import std.container.[list, map]" in narrow(source),
            "narrowed to:\n${narrow(source)}",
        )
    }

    @Test
    fun `one used name needs no group`() {
        val source = "import std.container::*\n\nfunc main() {\n    fin a = list()\n}"
        assertTrue("import std.container.list" in narrow(source), narrow(source))
        assertTrue("[" !in narrow(source), "a single name is not a group: ${narrow(source)}")
    }

    @Test
    fun `child modules narrow to a dotted group`() {
        // `.` walks down the module tree, and `list` is a module under the path.
        val source = "import std.container::*\n\nfunc main() {\n    fin a = list()\n    fin b = map()\n}"
        assertTrue("import std.container.[list, map]" in narrow(source), narrow(source))
    }

    @Test
    fun `declarations narrow to a colons group`() {
        // `::` reaches inside a module, and these are names it declares.
        val source = "import std.io::*\n\nfunc main() {\n    println(1)\n    print(2)\n}"
        assertTrue("import std.io::[print, println]" in narrow(source), narrow(source))
    }

    @Test
    fun `one declaration needs no group either`() {
        val source = "import std.io::*\n\nfunc main() {\n    println(1)\n}"
        assertTrue("import std.io::println" in narrow(source), narrow(source))
    }

    @Test
    fun `the names are written in a stable order`() {
        val source = "import std.container::*\n\nfunc main() {\n    fin a = set()\n    fin b = list()\n}"
        assertTrue("import std.container.[list, set]" in narrow(source), narrow(source))
    }

    @Test
    fun `the rest of the file is untouched`() {
        val source = "import std.container::*\n\nfunc main() {\n    fin a = list()\n}\n"
        assertTrue(narrow(source).endsWith("func main() {\n    fin a = list()\n}\n"))
    }

    @Test
    fun `several wildcards are narrowed in one pass`() {
        val source = "import std.container::*\nimport std.io::*\n\nfunc main() {\n    println(list())\n}"
        val out = narrow(source)
        assertTrue("import std.container.list" in out, out)
        assertTrue("import std.io::println" in out, out)
    }

    @Test
    fun `indentation is kept`() {
        val source = "    import std.container::*\n\nfunc main() {\n    fin a = list()\n    fin b = map()\n}"
        assertTrue("    import std.container.[list, map]" in narrow(source), narrow(source))
    }

    // -- what it leaves alone ------------------------------------------------

    @Test
    fun `a wildcard whose module is fully used stays a wildcard`() {
        // Listing every name is longer than the `*` that already says it.
        val source = "import std.io::*\n\nfunc main() {\n    println(1)\n    print(2)\n    readLine()\n}"
        assertTrue("import std.io::*" in narrow(source), narrow(source))
    }

    @Test
    fun `a wildcard with nothing used is left alone`() {
        // The index only sees what it has scanned. Dropping a dependency on the
        // strength of an empty answer is the one failure nobody would notice.
        val source = "import std.container::*\n\nfunc main() {\n    println(1)\n}"
        assertTrue("import std.container::*" in narrow(source), narrow(source))
    }

    @Test
    fun `an unknown module is left alone`() {
        val source = "import vendor.thing::*\n\nfunc main() {}"
        assertEquals(source, narrow(source))
    }

    @Test
    fun `explicit imports are never touched`() {
        val source = "import std.container.list\nimport std.io\n\nfunc main() {}"
        assertEquals(source, narrow(source))
    }

    @Test
    fun `a file with no wildcard is returned unchanged`() {
        val source = "func main() {}\n"
        assertEquals(source, narrow(source))
    }

    @Test
    fun `a name that only appears in the import does not count as used`() {
        // Otherwise every wildcard would narrow to the module's own last
        // segment, which names nothing in it.
        val source = "import std.container::*\n\nfunc main() {}"
        assertTrue("import std.container::*" in narrow(source), narrow(source))
    }

    // -- groups of one -------------------------------------------------------

    private fun collapse(source: String): String = AzoraImportRewriter.collapseRedundantGroups(source)

    @Test
    fun `a selection group of one loses its brackets`() {
        assertEquals("import std.io::println\n", collapse("import std.io::[println]\n"))
    }

    @Test
    fun `a module group of one loses its brackets`() {
        assertEquals("import std.container.list\n", collapse("import std.container.[list]\n"))
    }

    @Test
    fun `the brace spelling collapses too`() {
        assertEquals("import std.io::println\n", collapse("import std.io::{println}\n"))
    }

    @Test
    fun `a group of one spanning lines collapses onto one`() {
        assertEquals("import std.io::println\n", collapse("import std.io::[\n    println\n]\n"))
    }

    @Test
    fun `a starred member keeps its star`() {
        assertEquals("import std.container.array::*\n", collapse("import std.container.[array::*]\n"))
    }

    @Test
    fun `a nested group of one flattens to the path it names`() {
        assertEquals("import std.container.list\n", collapse("import std.[container.[list]]\n"))
    }

    @Test
    fun `a clause's modifier is kept`() {
        assertEquals("export import std.io::println\n", collapse("export import std.io::[println]\n"))
    }

    @Test
    fun `use is not an import keyword`() {
        // `use` was dropped from the language, so a line opening with it is not
        // a clause and nothing about it is rewritten.
        val source = "use std.io::[println]\n"
        assertEquals(source, collapse(source))
    }

    @Test
    fun `a group of two is left alone`() {
        val source = "import std.io::[println, print]\n"
        assertEquals(source, collapse(source))
    }

    @Test
    fun `a group carrying a comment is left alone`() {
        // The comment says why the name is in the list; collapsing the clause
        // onto one line would take it with the brackets.
        val source = "import std.io::[\n    println // the only one used\n]\n"
        assertEquals(source, collapse(source))
    }

    @Test
    fun `an import with no group is left alone`() {
        val source = "import std.io::println\nimport std.io::*\nimport std.container.list\n"
        assertEquals(source, collapse(source))
    }

    @Test
    fun `several groups of one all collapse`() {
        assertEquals(
            "import std.io::println\nimport std.container.list\n",
            collapse("import std.io::[println]\nimport std.container.[list]\n"),
        )
    }

    @Test
    fun `the reported brackets cover the group and nothing else`() {
        val source = "import std.io::[println]\n"
        val collapse = AzoraImportRewriter.redundantGroups(source).single()
        assertEquals("[println]", source.substring(collapse.brackets.first, collapse.brackets.last + 1))
        assertEquals("import std.io::[println]", source.substring(collapse.clause.first, collapse.clause.last + 1))
    }
}

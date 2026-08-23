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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The import grammar, read once for every feature that needs it.
 *
 * The cases that matter are the ones the old per-feature regexes disagreed
 * about: a group written with brackets rather than braces, a group that spans
 * lines, and a nested member that carries its own dotted path.
 */
class AzoraImportsTest {

    private fun paths(source: String): List<String> =
        AzoraImports.clauses(source).flatMap { clause -> clause.leaves.map { it.path } }

    // -- the shapes ----------------------------------------------------------

    @Test
    fun `a plain path is one leaf`() {
        assertEquals(listOf("std.math"), paths("import std.math"))
    }

    @Test
    fun `a wildcard keeps its path and is marked`() {
        val leaf = AzoraImports.clauses("import std.io::*").single().leaves.single()
        assertEquals("std.io", leaf.path)
        assertTrue(leaf.isWildcard)
    }

    @Test
    fun `a bracket group is one leaf per member`() {
        assertEquals(
            listOf("std.container.list", "std.container.map"),
            paths("import std.container.[list, map]"),
        )
    }

    @Test
    fun `a brace group reads the same`() {
        assertEquals(
            listOf("std.container.list", "std.container.map"),
            paths("import std.container.{list, map}"),
        )
    }

    @Test
    fun `a group may span lines`() {
        assertEquals(
            listOf("std.container.list", "std.container.map", "std.container.set"),
            paths("import std.container.[\n    list\n    map\n    set\n]"),
        )
    }

    @Test
    fun `a group member carries its own path`() {
        assertEquals(
            listOf("std.math.abs", "std.io"),
            paths("import std.[math::abs, io::*]"),
        )
    }

    @Test
    fun `a nested wildcard is still a wildcard`() {
        val leaves = AzoraImports.clauses("import std.[math::abs, io::*]").single().leaves
        assertEquals(listOf(false, true), leaves.map { it.isWildcard })
    }

    @Test
    fun `several clauses are read in order`() {
        assertEquals(
            listOf("std.io", "std.math", "app.model"),
            paths("import std.io\nimport std.math\n\nimport app.model\n"),
        )
    }

    @Test
    fun `a modifier before the keyword does not hide it`() {
        assertEquals(listOf("std.io"), paths("export import std.io"))
    }

    // -- where a clause ends -------------------------------------------------

    @Test
    fun `a clause ends at its line`() {
        val clause = AzoraImports.clauses("import std.math\nfunc main() {}").single()
        assertEquals("import std.math", clause.text)
    }

    @Test
    fun `a multi-line group ends at its bracket`() {
        val source = "import std.container.[\n    list\n]\nfunc main() {}"
        val clause = AzoraImports.clauses(source).single()
        assertTrue(clause.text.trimEnd().endsWith("]"), "clause was <${clause.text}>")
        assertTrue("func" !in clause.text, "the clause did not swallow the file: <${clause.text}>")
    }

    @Test
    fun `a comment inside a group does not end the clause`() {
        // A group spanning lines is the one place there is room to say why a
        // name is there. The `/` used to end the clause, which left every
        // member after the comment outside the import entirely.
        val source = "import std.[\n    reflection::reflect // why\n    serializer::Serializable\n]"

        val leaves = AzoraImports.clauses(source).single().leaves.map { it.path }

        assertEquals(listOf("std.reflection.reflect", "std.serializer.Serializable"), leaves)
    }

    @Test
    fun `a comment after a clause is not part of it`() {
        val clause = AzoraImports.clauses("import std.math // the numbers").single()
        assertEquals("import std.math", clause.text)
    }

    @Test
    fun `a declaration is not an import`() {
        assertEquals(emptyList<String>(), paths("func important() {}\npack Importer {}"))
    }

    // -- naming an offset ----------------------------------------------------

    @Test
    fun `a path segment names everything up to itself`() {
        val source = "import std.container.list"
        assertEquals("std", AzoraImports.pathAt(source, source.indexOf("std")))
        assertEquals("std.container", AzoraImports.pathAt(source, source.indexOf("container")))
        assertEquals("std.container.list", AzoraImports.pathAt(source, source.indexOf("list")))
    }

    @Test
    fun `a group member names its full path`() {
        val source = "import std.container.[list, map]"
        assertEquals("std.container.map", AzoraImports.pathAt(source, source.indexOf("map")))
    }

    @Test
    fun `an offset outside a clause names nothing`() {
        val source = "import std.math\nfunc main() {}"
        assertNull(AzoraImports.pathAt(source, source.indexOf("main")))
    }

    // -- the base a clause is about ------------------------------------------

    @Test
    fun `the base of a group is the path they share`() {
        assertEquals(
            "std.container",
            AzoraImports.clauses("import std.container.[list, map]").single().base,
        )
    }

    @Test
    fun `the base of a plain import is the path itself`() {
        assertEquals("std.math", AzoraImports.clauses("import std.math").single().base)
    }

    // -- path versus selection -----------------------------------------------

    @Test
    fun `a symbol is selected with colons`() {
        assertEquals(listOf("std.format.Display"), paths("import std.format::Display"))
    }

    @Test
    fun `a group of symbols is opened with colons`() {
        assertEquals(
            listOf("std.format.Display", "std.format.Debug"),
            paths("import std.format::[Display, Debug]"),
        )
    }

    @Test
    fun `a dotted segment is path and a selected one is not`() {
        val segments = AzoraImports.clauses("import std.format::Display").single().segments
        assertEquals(
            listOf("std" to false, "format" to false, "Display" to true),
            segments.map { it.text to it.isSelection },
        )
    }

    @Test
    fun `a group opened with colons holds selections`() {
        val segments = AzoraImports.clauses("import std.format::[Display, Debug]").single().segments
        assertEquals(
            listOf("std" to false, "format" to false, "Display" to true, "Debug" to true),
            segments.map { it.text to it.isSelection },
        )
    }

    @Test
    fun `a group opened with a dot holds modules`() {
        val segments = AzoraImports.clauses("import std.container.[list, map]").single().segments
        assertTrue(
            segments.none { it.isSelection },
            "all modules: ${segments.map { it.text to it.isSelection }}",
        )
    }

    @Test
    fun `a member may select inside its own module`() {
        val segments = AzoraImports.clauses("import std.[math::abs]").single().segments
        assertEquals(
            listOf("std" to false, "math" to false, "abs" to true),
            segments.map { it.text to it.isSelection },
        )
    }

    @Test
    fun `a wildcard leaves nothing selected`() {
        val segments = AzoraImports.clauses("import std.io::*").single().segments
        assertTrue(segments.none { it.isSelection }, "the `*` is not a segment")
    }

    // -- what a clause reaches -----------------------------------------------

    @Test
    fun `a leaf knows whether it selects`() {
        val selected = AzoraImports.clauses("import std.traits::Equal").single().leaves.single()
        assertTrue(selected.isSelection)
        assertEquals("std.traits", selected.module)

        val walked = AzoraImports.clauses("import std.container.list").single().leaves.single()
        assertTrue(!walked.isSelection)
        assertEquals("std.container.list", walked.module)
    }

    @Test
    fun `every member of a selection group selects`() {
        val leaves = AzoraImports.clauses("import std.traits::[Equal, Order]").single().leaves
        assertTrue(leaves.all { it.isSelection }, "leaves: ${leaves.map { it.path to it.isSelection }}")
        assertEquals(listOf("std.traits", "std.traits"), leaves.map { it.module })
    }

    @Test
    fun `only a module taken whole is a whole module`() {
        assertEquals(setOf("std.io"), AzoraImports.wholeModules("import std.io::*"))
        assertEquals(setOf("std.container.list"), AzoraImports.wholeModules("import std.container.list"))
        assertEquals(emptySet<String>(), AzoraImports.wholeModules("import std.traits::[Equal, Order]"))
    }

    @Test
    fun `selections are grouped by the module they come from`() {
        assertEquals(
            mapOf("std.traits" to setOf("Equal", "Order"), "std.format" to setOf("Display")),
            AzoraImports.selections("import std.traits::[Equal, Order]\nimport std.format::Display"),
        )
    }
}

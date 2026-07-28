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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the name resolution that go-to-declaration, hover, and member
 * completion all share.
 */
class AzoraResolverTest {

    private val service = AzoraSymbolService()
    private val resolver = AzoraResolver(null, service)

    /** The offset of the [occurrence]-th whole-word match of [word] in [source]. */
    private fun offsetOf(source: String, word: String, occurrence: Int = 0): Int {
        val matches = Regex("""\b${Regex.escape(word)}\b""").findAll(source).toList()
        return matches[occurrence].range.first
    }

    // ── Reading the reference under the caret ──────────────────────────

    @Test
    fun `reads an unqualified name`() {
        val source = "fin total = compute()"
        val reference = resolver.referenceAt(source, offsetOf(source, "compute"))!!

        assertEquals("compute", reference.name)
        assertTrue(reference.qualifier.isEmpty())
    }

    @Test
    fun `reads a dotted receiver chain`() {
        val source = "fin port = config.server.port"
        val reference = resolver.referenceAt(source, offsetOf(source, "port", occurrence = 1))!!

        assertEquals("port", reference.name)
        assertEquals(listOf("config", "server"), reference.qualifier)
        assertEquals(".", reference.separator)
    }

    @Test
    fun `reads a zone path written with double colons`() {
        val source = "fin r = std::math::sqrt(2.0)"
        val reference = resolver.referenceAt(source, offsetOf(source, "sqrt"))!!

        assertEquals("sqrt", reference.name)
        assertEquals(listOf("std", "math"), reference.qualifier)
        assertEquals("::", reference.separator)
    }

    @Test
    fun `a range operator is not read as a receiver`() {
        val source = "for i in 0..count {\n}"
        val reference = resolver.referenceAt(source, offsetOf(source, "count"))!!

        assertEquals("count", reference.name)
        assertTrue(reference.qualifier.isEmpty(), "`..` is a range, not member access")
    }

    // ── Locals and parameters ──────────────────────────────────────────

    @Test
    fun `a local binding shadows a top-level declaration of the same name`() {
        val source = """
            func total(): Int {
                return 0
            }

            func main() {
                fin total = 42
                println(total)
            }
        """.trimIndent()

        val resolved = resolver.resolve("/test.az", source, offsetOf(source, "total", occurrence = 2))
        assertEquals(1, resolved.size)
        // The local wins, so Ctrl-click lands on the binding, not the function.
        assertEquals(SymbolKind.FIN, resolved.single().kind)
    }

    @Test
    fun `parameters of the enclosing function are in scope`() {
        val source = """
            func greet(name: String, times: Int): String {
                return name
            }
        """.trimIndent()

        val locals = resolver.localsInScope(source, offsetOf(source, "name", occurrence = 1))
        val parameter = locals.first { it.name == "name" }

        assertEquals(SymbolKind.PARAM, parameter.kind)
        assertEquals("String", parameter.type)
    }

    @Test
    fun `a for-loop binding is in scope inside the loop`() {
        val source = """
            func main() {
                for item in items {
                    println(item)
                }
            }
        """.trimIndent()

        val locals = resolver.localsInScope(source, offsetOf(source, "item", occurrence = 1))
        assertTrue(locals.any { it.name == "item" })
    }

    @Test
    fun `a binding declared after the caret is not in scope`() {
        val source = """
            func main() {
                println(later)
                fin later = 1
            }
        """.trimIndent()

        val locals = resolver.localsInScope(source, offsetOf(source, "later"))
        assertTrue(locals.none { it.name == "later" })
    }

    @Test
    fun `infers a binding type from its initializer`() {
        val source = """
            func main() {
                fin origin = Point(x: 0.0, y: 0.0)
                fin label = "hello"
                fin count = 7
            }
        """.trimIndent()

        val locals = resolver.localsInScope(source, source.length)
        assertEquals("Point", locals.first { it.name == "origin" }.type)
        assertEquals("String", locals.first { it.name == "label" }.type)
        assertEquals("Int", locals.first { it.name == "count" }.type)
    }

    // ── self ───────────────────────────────────────────────────────────

    @Test
    fun `self resolves to the enclosing impl type`() {
        val source = """
            pack Point {
                var x: Real
            }

            impl Point {
                func magnitude(): Real {
                    return self.x
                }
            }
        """.trimIndent()

        assertEquals("Point", resolver.selfType(source, offsetOf(source, "magnitude")))
    }

    @Test
    fun `self resolves through an impl-for-spec header`() {
        val source = """
            impl Printable for Report {
                func display(): String {
                    return self.title
                }
            }
        """.trimIndent()

        assertEquals("Report", resolver.selfType(source, offsetOf(source, "display")))
    }

    @Test
    fun `self is unknown at the top level`() {
        assertNull(resolver.selfType("func main() {\n    println(1)\n}", 20))
    }

    // ── Members ────────────────────────────────────────────────────────

    @Test
    fun `member completion resolves a local's fields through its type`() {
        val source = """
            pack Point {
                var x: Real
                var y: Real
            }

            func main() {
                fin origin = Point(x: 0.0, y: 0.0)
                println(origin.x)
            }
        """.trimIndent()

        val members = resolver.membersOfQualifier(listOf("origin"), "/test.az", source, source.length)
        assertEquals(setOf("x", "y"), members.map { it.name }.toSet())
    }

    @Test
    fun `member completion resolves methods contributed by an impl block`() {
        val source = """
            pack Point {
                var x: Real
            }

            impl Point {
                func distanceTo(other: Point): Real {
                    return 0.0
                }
            }

            func main() {
                fin p = Point(x: 1.0)
                println(p.distanceTo(p))
            }
        """.trimIndent()

        val members = resolver.membersOfQualifier(listOf("p"), "/test.az", source, source.length)
        assertTrue(members.any { it.name == "distanceTo" }, "got ${members.map { it.name }}")
    }

    @Test
    fun `member completion follows a chained receiver`() {
        val source = """
            pack Server {
                var port: Int
            }

            pack Config {
                var server: Server
            }

            func main() {
                fin config = Config(server: Server(port: 80))
                println(config.server.port)
            }
        """.trimIndent()

        val members = resolver.membersOfQualifier(listOf("config", "server"), "/test.az", source, source.length)
        assertTrue(members.any { it.name == "port" }, "got ${members.map { it.name }}")
    }

    @Test
    fun `self members are offered inside an impl`() {
        val source = """
            pack Point {
                var x: Real
            }

            impl Point {
                func show(): Real {
                    return self.x
                }
            }
        """.trimIndent()

        val members = resolver.membersOfQualifier(listOf("self"), "/test.az", source, offsetOf(source, "show"))
        assertTrue(members.any { it.name == "x" })
    }

    @Test
    fun `enum variants are offered on the enum name`() {
        val source = """
            enum Direction {
                North, South, East, West
            }
        """.trimIndent()

        val members = resolver.membersOfQualifier(listOf("Direction"), "/test.az", source, source.length)
        assertEquals(setOf("North", "South", "East", "West"), members.map { it.name }.toSet())
    }

    // ── Imports ────────────────────────────────────────────────────────

    @Test
    fun `collects imported modules and their short aliases`() {
        val modules = resolver.importedModules(
            """
            import std.io
            import std.container.map
            """.trimIndent()
        )

        assertTrue("std.io" in modules)
        assertTrue("io" in modules)
        assertTrue("std.container.map" in modules)
        assertTrue("map" in modules)
    }

    @Test
    fun `expands a grouped import`() {
        val modules = resolver.importedModules("import std.{math, container}")

        assertTrue("std.math" in modules, "got $modules")
        assertTrue("std.container" in modules)
        assertTrue("math" in modules)
    }

    // ── Whole-reference resolution ─────────────────────────────────────

    @Test
    fun `resolves a top-level function declared in the same file`() {
        val source = """
            func helper(): Int {
                return 1
            }

            func main() {
                println(helper())
            }
        """.trimIndent()

        val resolved = resolver.resolve("/test.az", source, offsetOf(source, "helper", occurrence = 1))
        assertEquals(SymbolKind.FUNC, resolved.first().kind)
        assertEquals(1, resolved.first().line)
    }

    @Test
    fun `resolves a field through its receiver rather than by name alone`() {
        val source = """
            pack Point {
                var x: Real
            }

            func main() {
                fin p = Point(x: 1.0)
                println(p.x)
            }
        """.trimIndent()

        val resolved = resolver.resolve("/test.az", source, offsetOf(source, "x", occurrence = 2))
        assertNotNull(resolved.firstOrNull())
        assertEquals(SymbolKind.FIELD, resolved.first().kind)
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        val source = "func main() {\n    println(nothingHere)\n}"
        assertTrue(resolver.resolve("/test.az", source, offsetOf(source, "nothingHere")).isEmpty())
    }
}

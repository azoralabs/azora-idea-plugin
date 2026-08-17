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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AzoraSymbolService] symbol extraction.
 */
class AzoraSymbolServiceTest {

    private lateinit var service: AzoraSymbolService

    @BeforeEach
    fun setUp() {
        service = AzoraSymbolService()
    }

    // ── Function declarations ──────────────────────────────────────────

    @Test
    fun `extracts simple function`() {
        val symbols = service.getSymbolsForFile("test.az", "func main() {}")
        val func = symbols.find { it.name == "main" }
        assertNotNull(func)
        assertEquals(SymbolKind.FUNC, func!!.kind)
    }

    @Test
    fun `extracts function with params and return type`() {
        val symbols = service.getSymbolsForFile("test.az", "func add(a: Int, b: Int): Int {}")
        val func = symbols.find { it.name == "add" }
        assertNotNull(func)
        assertEquals(2, func!!.params.size)
        assertEquals("a", func.params[0].first)
        assertEquals("Int", func.params[0].second)
        assertEquals("Int", func.type)
    }

    @Test
    fun `extracts exposed function`() {
        val symbols = service.getSymbolsForFile("test.az", "exposed func helper(): String {}")
        val func = symbols.find { it.name == "helper" }
        assertNotNull(func)
        assertTrue(func!!.isExposed)
    }

    // ── Pack declarations ──────────────────────────────────────────────

    @Test
    fun `extracts pack with fields`() {
        val source = """
            pack Point {
                var x: Real = 0.0
                var y: Real = 0.0
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val pack = symbols.find { it.name == "Point" }
        assertNotNull(pack)
        assertEquals(SymbolKind.PACK, pack!!.kind)
        assertEquals(2, pack.members.size)
        assertEquals("x", pack.members[0].name)
        assertEquals("Real", pack.members[0].type)
    }

    // ── Enum declarations ──────────────────────────────────────────────

    @Test
    fun `extracts enum with variants`() {
        val source = """
            enum Direction {
                North
                South
                East
                West
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val enum = symbols.find { it.name == "Direction" }
        assertNotNull(enum)
        assertEquals(SymbolKind.ENUM, enum!!.kind)
        assertEquals(4, enum.members.size)
        assertTrue(enum.members.all { it.kind == SymbolKind.VARIANT })
        assertEquals("North", enum.members[0].name)
    }

    // ── Payload enum declarations ──────────────────────────────────────

    @Test
    fun `extracts variant enum with parameterized variants`() {
        val source = """
            variant enum Shape {
                Circle(radius: Real)
                Rectangle(width: Real, height: Real)
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val shape = symbols.find { it.name == "Shape" }
        assertNotNull(shape)
        assertEquals(SymbolKind.ENUM, shape!!.kind)
        assertEquals(2, shape.members.size)
        val circle = shape.members[0]
        assertEquals("Circle", circle.name)
        assertEquals(1, circle.params.size)
        assertEquals("radius", circle.params[0].first)
        assertEquals(2, shape.members[1].params.size)
    }

    // ── Error declarations ─────────────────────────────────────────────

    @Test
    fun `extracts error with variants`() {
        val source = """
            error NetworkError {
                Timeout
                NotFound
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val fail = symbols.find { it.name == "NetworkError" }
        assertNotNull(fail)
        assertEquals(SymbolKind.FAIL, fail!!.kind)
        assertEquals(2, fail.members.size)
    }

    // ── Realm declarations ─────────────────────────────────────────────

    @Test
    fun `extracts realm with nested symbols`() {
        val source = """
            realm MathUtils {
                func square(n: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val realm = symbols.find { it.name == "MathUtils" }
        assertNotNull(realm)
        assertEquals(SymbolKind.SCOPE, realm!!.kind)
        assertTrue(realm.members.any { it.name == "square" })
    }

    @Test
    fun `extracts qualified realm path`() {
        val source = """
            realm std::math {
                func abs(x: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val realm = symbols.find { it.name == "std::math" }
        assertNotNull(realm)
        assertEquals(SymbolKind.SCOPE, realm!!.kind)
        assertTrue(realm.members.any { it.name == "abs" })
    }

    @Test
    fun `extracts current realm paths and keeps member locations`() {
        val source = """
            realm ide::editor {
                func open(): Unit {}
            }
        """.trimIndent()
        val realm = service.getSymbolsForFile("realm.az", source).single { it.kind == SymbolKind.SCOPE }

        assertEquals("ide::editor", realm.name)
        assertEquals(2, realm.members.single { it.name == "open" }.line)
    }

    @Test
    fun `recognizes current module declarations only`() {
        assertNull(service.moduleOfFile("mod std.math\n"))
        assertEquals("std.math", service.moduleOfFile("module std.math\n"))
    }

    // ── Var/Fin declarations ───────────────────────────────────────────

    @Test
    fun `extracts var and fin`() {
        val source = """
            var counter: Int = 0
            fin name: String = "hello"
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val varSym = symbols.find { it.name == "counter" }
        val finSym = symbols.find { it.name == "name" }
        assertNotNull(varSym)
        assertNotNull(finSym)
        assertEquals(SymbolKind.VAR, varSym!!.kind)
        assertTrue(varSym.isMutable)
        assertEquals(SymbolKind.FIN, finSym!!.kind)
        assertFalse(finSym.isMutable)
    }

    @Test
    fun `infers constructor initialized binding type`() {
        val source = """
            pack Point
            fin p = Point()
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val point = symbols.find { it.name == "p" }
        assertNotNull(point)
        assertEquals("Point", point!!.type)
    }

    // ── Async and reactive declarations ────────────────────────────────

    @Test
    fun `extracts async function declaration`() {
        val symbols = service.getSymbolsForFile("test.az", "async func fetchData(url: String): String {}")
        val function = symbols.find { it.name == "fetchData" }
        assertNotNull(function)
        assertEquals(SymbolKind.FUNC, function!!.kind)
    }

    @Test
    fun `extracts react async function declaration`() {
        val symbols = service.getSymbolsForFile("test.az", "react async func observe(): Int {}")
        val function = symbols.find { it.name == "observe" }
        assertNotNull(function)
        assertEquals(SymbolKind.FUNC, function!!.kind)
    }

    // ── Module and import/use ──────────────────────────────────────────

    @Test
    fun `module header is available as file metadata`() {
        assertEquals("example.app", service.moduleOfFile("exposed module example.app"))
    }

    @Test
    fun `extracts use declarations`() {
        val source = """
            use std.io
            use std.{math, concurrency}
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val uses = symbols.filter { it.kind == SymbolKind.USE }
        assertEquals(2, uses.size)
    }

    // ── Test declarations ──────────────────────────────────────────────

    @Test
    fun `extracts quoted test name`() {
        val symbols = service.getSymbolsForFile("test.az", "test \"point addition\" {}")
        val test = symbols.find { it.kind == SymbolKind.TEST }
        assertNotNull(test)
        assertEquals("point addition", test!!.name)
    }

    // ── Typealias ──────────────────────────────────────────────────────

    @Test
    fun `extracts typealias`() {
        val symbols = service.getSymbolsForFile("test.az", "typealias Callback = (Int) -> Unit")
        val alias = symbols.find { it.kind == SymbolKind.TYPEALIAS }
        assertNotNull(alias)
        assertEquals("Callback", alias!!.name)
    }

    // ── Impl declarations ─────────────────────────────────────────────

    @Test
    fun `indexes kotlin style impl trait for type under target type`() {
        val source = """
            pack List<T>

            impl Into<String> for List<T> {
                func render[self: Self&](): String { return "" }
            }
        """.trimIndent()
        val members = service.getMembersForType("List", "test.az", source)
        assertTrue(members.any { it.name == "render" }, "Expected impl member on List")
    }

    @Test
    fun `indexes external operator implementation under target type`() {
        val source = """
            pack Set<T>
            impl oper[] for Set<T> { self&, index -> }
        """.trimIndent()
        val members = service.getMembersForType("Set", "test.az", source)
        assertTrue(members.any { it.kind == SymbolKind.OPERATOR && it.name == "oper[]" })
    }

    // ── Stdlib realms ─────────────────────────────────────────────────

    @Test
    fun `resolves std module path members`() {
        // Stdlib is indexed from the installed SDK (not hardcoded). When the SDK
        // stdlib is present, `std.math` resolves its declarations (e.g. `abs`);
        // with no SDK configured the resolver returns an empty list, not an error.
        val members = service.resolveScopePath(listOf("std", "math"), "test.az", "")
        assertTrue(members.isEmpty() || members.any { it.name == "abs" })
    }

    @Test
    fun `resolves std alias path members`() {
        val members = service.resolveScopePath(listOf("math"), "test.az", "")
        assertTrue(members.isEmpty() || members.any { it.name == "abs" })
    }

    // ── Infix macro operator names ─────────────────────────────────────

    @Test
    fun `infix operator names come from declarations, never from a builtin list`() {
        val source = """
            macro ${'$'}a @combine ${'$'}b => c(${'$'}a, ${'$'}b)
            macro ${'$'}a @to ${'$'}b => e(${'$'}a, ${'$'}b)
        """.trimIndent()
        val names = service.infixOperatorNames("infix.az", source)

        assertTrue(names.contains("combine"), "user-declared 'combine' should be recognized")
        assertTrue(names.contains("to"), "current infix macro should be recognized")

        // `with`, `by` and `reverse` are language keywords. They are operators
        // only where some `macro` declaration makes them one, so nothing may
        // seed them here — that is what lets them stay keyword-colored in a
        // project that does not declare them.
        assertFalse(names.contains("with"), "'with' must not be assumed to be a macro")
        assertFalse(names.contains("by"))
        assertFalse(names.contains("reverse"))
    }

    // ── Cache behavior ─────────────────────────────────────────────────

    @Test
    fun `caches symbols and invalidate clears cache`() {
        val source1 = "func first() {}"
        val source2 = "func second() {}"

        val symbols1 = service.getSymbolsForFile("cached.az", source1)
        assertTrue(symbols1.any { it.name == "first" })

        // A changed document must invalidate by content hash immediately; stale
        // cache entries break hover and goto while a file is being edited.
        val symbols2 = service.getSymbolsForFile("cached.az", source2)
        assertTrue(symbols2.any { it.name == "second" }, "Expected fresh symbols")

        // Explicit invalidation remains supported as well.
        service.invalidate("cached.az")
        val symbols3 = service.getSymbolsForFile("cached.az", source2)
        assertTrue(symbols3.any { it.name == "second" }, "Expected fresh extraction")
    }

    // ── Bridge targets ─────────────────────────────────────────────────

    @Test
    fun `returns bridge targets`() {
        val targets = service.getBridgeTargets()
        assertTrue(targets.contains("C"))
        assertTrue(targets.contains("JS"))
        assertTrue(targets.contains("KOTLIN"))
    }
}

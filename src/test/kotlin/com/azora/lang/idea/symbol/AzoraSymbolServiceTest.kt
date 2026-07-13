/*
 * Copyright 2026 AzoraTech
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

package com.azora.lang.idea.symbol

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
        val symbols = service.getSymbolsForFile("test.az", "expose func helper(): String {}")
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
                North, South, East, West
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

    // ── Slot declarations ──────────────────────────────────────────────

    @Test
    fun `extracts slot with parameterized variants`() {
        val source = """
            slot Shape {
                Circle(radius: Real),
                Rectangle(width: Real, height: Real)
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val slot = symbols.find { it.name == "Shape" }
        assertNotNull(slot)
        assertEquals(SymbolKind.SLOT, slot!!.kind)
        assertEquals(2, slot.members.size)
        val circle = slot.members[0]
        assertEquals("Circle", circle.name)
        assertEquals(1, circle.params.size)
        assertEquals("radius", circle.params[0].first)
    }

    // ── Fail declarations ──────────────────────────────────────────────

    @Test
    fun `extracts fail with variants`() {
        val source = """
            fail NetworkError {
                Timeout,
                NotFound
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val fail = symbols.find { it.name == "NetworkError" }
        assertNotNull(fail)
        assertEquals(SymbolKind.FAIL, fail!!.kind)
        assertEquals(2, fail.members.size)
    }

    // ── Zone declarations ──────────────────────────────────────────────

    @Test
    fun `extracts zone with nested symbols`() {
        val source = """
            zone MathUtils {
                func square(n: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val zone = symbols.find { it.name == "MathUtils" }
        assertNotNull(zone)
        assertEquals(SymbolKind.SCOPE, zone!!.kind)
        assertTrue(zone.members.any { it.name == "square" })
    }

    @Test
    fun `extracts friend zone path`() {
        val source = """
            friend zone std::math {
                func abs(x: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val zone = symbols.find { it.name == "std::math" }
        assertNotNull(zone)
        assertEquals(SymbolKind.SCOPE, zone!!.kind)
        assertTrue(zone.members.any { it.name == "abs" })
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

    // ── Task/Flow declarations ─────────────────────────────────────────

    @Test
    fun `extracts task declaration`() {
        val symbols = service.getSymbolsForFile("test.az", "task fetchData(url: String): String {}")
        val task = symbols.find { it.name == "fetchData" }
        assertNotNull(task)
        assertEquals(SymbolKind.TASK, task!!.kind)
    }

    @Test
    fun `extracts flow declaration`() {
        val symbols = service.getSymbolsForFile("test.az", "flow fibonacci(): Int {}")
        val flow = symbols.find { it.name == "fibonacci" }
        assertNotNull(flow)
        assertEquals(SymbolKind.FLOW, flow!!.kind)
    }

    // ── Package and use ────────────────────────────────────────────────

    @Test
    fun `extracts package declaration`() {
        val symbols = service.getSymbolsForFile("test.az", "package example.app")
        val pkg = symbols.find { it.kind == SymbolKind.PACKAGE }
        assertNotNull(pkg)
        assertEquals("example.app", pkg!!.name)
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
                func render(): String { ref self -> return "" }
            }
        """.trimIndent()
        val members = service.getMembersForType("List", "test.az", source)
        assertTrue(members.any { it.name == "render" }, "Expected impl member on List")
    }

    @Test
    fun `indexes external operator implementation under target type`() {
        val source = """
            pack Set<T>
            impl oper[] for Set<T> { ref self, index -> }
        """.trimIndent()
        val members = service.getMembersForType("Set", "test.az", source)
        assertTrue(members.any { it.kind == SymbolKind.OPERATOR && it.name == "oper[]" })
    }

    // ── Stdlib zones ──────────────────────────────────────────────────

    @Test
    fun `resolves std module path members`() {
        val members = service.resolveScopePath(listOf("std", "math"), "test.az", "")
        assertTrue(members.any { it.name == "abs" })
    }

    @Test
    fun `resolves std alias path members`() {
        val members = service.resolveScopePath(listOf("math"), "test.az", "")
        assertTrue(members.any { it.name == "abs" })
    }

    // ── Cache behavior ─────────────────────────────────────────────────

    @Test
    fun `caches symbols and invalidate clears cache`() {
        val source1 = "func first() {}"
        val source2 = "func second() {}"

        val symbols1 = service.getSymbolsForFile("cached.az", source1)
        assertTrue(symbols1.any { it.name == "first" })

        // Second call with different content returns cached result
        val symbols2 = service.getSymbolsForFile("cached.az", source2)
        assertTrue(symbols2.any { it.name == "first" }, "Expected cached result")

        // After invalidate, new content is used
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

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

    // ── Module visibility ──────────────────────────────────────────────

    @Test
    fun `a module walked in place is as visible as one imported`() {
        // Writing the whole path is the reference; an import is the shorthand
        // for not writing it. `::` also survives the import parser, which reads
        // dotted paths only - `import std.serializer::Serializable` brought in
        // nothing at all, so the name it selects had no color and nowhere to
        // navigate to.
        val source = """
            import std.serializer::Serializable

            func build() {
                fin queue = std.container.deque::Deque()
                std::println("built")
            }
        """.trimIndent()

        val paths = service.qualifiedModulePaths(source)

        assertTrue(paths.contains("std.serializer"), "$paths")
        assertTrue(paths.contains("std.container.deque"), "$paths")
        assertTrue(paths.contains("std"), "$paths")
    }

    @Test
    fun `a group spanning lines imports every module it names`() {
        // The import reader used to be a line scanner that knew one spelling -
        // `path.{a, b}` on a single line. A group written across lines, or with
        // `::` in it, imported nothing at all, so everything it selected was
        // uncolored and unnavigable.
        val source = """
            test "queue serialization metadata is declared" {
                import std.[
                    reflection::reflect // the compile-time handle
                    serializer::Serializable
                ]
            }
        """.trimIndent()

        val paths = service.importedModulePaths(source)

        assertTrue(paths.contains("std.reflection"), "$paths")
        assertTrue(paths.contains("std.serializer"), "$paths")
    }

    @Test
    fun `a bodyless bridge spec is still a spec`() {
        // `std/traits/traits.az` declares the four specs every `derives` list
        // in `primitive.az` names, and two of them have no body at all.
        val source = """
            module std.traits

            /** Partial equality. */
            @Since("0.1")
            bridge spec PartialEqual<Rhs = Self> {
                oper== [self: Self&](rhs: Rhs&): Bool
            }

            /** Full equality. */
            @Since("0.1")
            bridge spec Equal requires PartialEqual

            /** Total order. */
            @Since("0.1")
            bridge spec Order requires Equal {
                oper<=> [self: Self&](rhs: Self&): Compare
            }

            /** Hashing. */
            @Since("0.1")
            bridge spec Hash requires Equal {
                prop hash[self: Self&]: ULong
            }
        """.trimIndent()

        val specs = service.getSymbolsForFile("/std/traits/traits.az", source)
            .filter { it.kind == SymbolKind.SPEC }
            .map { it.name }

        assertEquals(listOf("PartialEqual", "Equal", "Order", "Hash"), specs, "got: $specs")
    }

    @Test
    fun `a wildcard reaching into a module still names it`() {
        // `std/primitive.az` opens with this, and every spec its `derives`
        // lists name comes from it.
        val paths = service.importedModulePaths("import std.traits::*")

        assertEquals(setOf("std.traits"), paths)
    }

    @Test
    fun `a selected name is not mistaken for a module`() {
        val paths = service.importedModulePaths("import std.format::Display")

        assertEquals(setOf("std.format"), paths)
    }

    @Test
    fun `a type reaching inside itself names no module`() {
        // Module segments are lowercase; `Compare::Less` is a type and a case.
        assertTrue(service.qualifiedModulePaths("fin x = Compare::Less").isEmpty())
    }

    // ── Names that resolve to nothing ──────────────────────────────────

    @Test
    fun `an empty index reports nothing unknown`() {
        // Not scanned yet is not the same as does not exist, and a wall of red
        // while a project loads would be worse than a name reported late.
        assertTrue(service.unknownNames(null, "test.az", "func f() {}", setOf("Whatever")).isEmpty())
    }

    // ── Deprecation ────────────────────────────────────────────────────

    @Test
    fun `a declaration under Deprecated is marked`() {
        val source = """
            @Deprecated(since: "0.2", replacement: "next")
            func old(): Int {}

            func current(): Int {}
        """.trimIndent()

        val symbols = service.getSymbolsForFile("test.az", source)

        assertTrue(symbols.single { it.name == "old" }.isDeprecated)
        assertFalse(symbols.single { it.name == "current" }.isDeprecated)
    }

    @Test
    fun `a doc comment between the decorator and the declaration is still its own`() {
        val source = """
            @Deprecated
            /** Was the way to do this. */
            pack Old {}
        """.trimIndent()

        assertTrue(service.getSymbolsForFile("test.az", source).single { it.name == "Old" }.isDeprecated)
    }

    @Test
    fun `stacked decorators preserve deprecation`() {
        val source = "@Stable\n@Deprecated\nfunc old() {}"

        assertTrue(service.getSymbolsForFile("test.az", source).single { it.name == "old" }.isDeprecated)
    }

    @Test
    fun `parenthesized decorators preserve deprecation`() {
        val source = "@(Stable, Deprecated)\nfunc old() {}"
        assertTrue(service.getSymbolsForFile("test.az", source).single { it.name == "old" }.isDeprecated)
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

    // ── Scope declarations ─────────────────────────────────────────────

    @Test
    fun `extracts scope with nested symbols`() {
        val source = """
            scope MathUtils {
                func square(n: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val scope = symbols.find { it.name == "MathUtils" }
        assertNotNull(scope)
        assertEquals(SymbolKind.SCOPE, scope!!.kind)
        assertTrue(scope.members.any { it.name == "square" })
    }

    @Test
    fun `extracts qualified scope path`() {
        val source = """
            scope std::math {
                func abs(x: Int): Int {}
            }
        """.trimIndent()
        val symbols = service.getSymbolsForFile("test.az", source)
        val scope = symbols.find { it.name == "std::math" }
        assertNotNull(scope)
        assertEquals(SymbolKind.SCOPE, scope!!.kind)
        assertTrue(scope.members.any { it.name == "abs" })
    }

    @Test
    fun `extracts current scope paths and keeps member locations`() {
        val source = """
            scope ide::editor {
                func open(): Unit {}
            }
        """.trimIndent()
        val scope = service.getSymbolsForFile("scope.az", source).single { it.kind == SymbolKind.SCOPE }

        assertEquals("ide::editor", scope.name)
        assertEquals(2, scope.members.single { it.name == "open" }.line)
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
    fun `extracts import declarations`() {
        val source = """
            import std.io
            import std.{math, concurrency}
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

    // ── Stdlib scopes ─────────────────────────────────────────────────

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

        // `with` and `by` are keywords; `reverse` is an ordinary name. They are operators
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

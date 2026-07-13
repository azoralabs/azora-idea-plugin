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

package com.azora.lang.idea.highlighting

import com.azora.lang.idea.AzoraFileType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.*
import javax.swing.Icon

/**
 * [ColorSettingsPage] for customizing Azora syntax highlighting colors.
 *
 * Appears under "Editor > Color Scheme > Azora" in the IDE settings.
 * Provides a live preview of all Azora token categories (keywords, identifiers,
 * literals, comments, operators, punctuation) using a comprehensive demo file.
 */
class AzoraColorSettingsPage : ColorSettingsPage {

    /** Returns the Azora file icon shown next to the page name in settings. */
    override fun getIcon(): Icon = AzoraFileType.INSTANCE.icon

    /** Returns a new [AzoraSyntaxHighlighter] used to colorize the demo text. */
    override fun getHighlighter(): SyntaxHighlighter = AzoraSyntaxHighlighter()

    /** Returns the sample Azora source code displayed in the color settings preview. */
    override fun getDemoText(): String = DEMO_TEXT

    /** Returns `null` because no additional tag-to-descriptor mappings are needed. */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    /** Returns the array of [AttributesDescriptor]s defining the configurable color entries. */
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    /** Returns an empty array because no custom color descriptors are needed. */
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /** Returns `"Azora"` as the page name shown in the color scheme settings tree. */
    override fun getDisplayName(): String = "Azora"

    companion object {

        /**
         * The attribute descriptors defining all configurable color entries.
         *
         * Organized into groups: Keywords, Identifiers, Literals, Comments,
         * Operators and Punctuation, and Special.
         */
        private val DESCRIPTORS = arrayOf(
            // Keywords
            AttributesDescriptor("Keywords//General keyword", AzoraSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Keywords//Declaration keyword", AzoraSyntaxHighlighter.DECLARATION_KEYWORD),
            AttributesDescriptor("Keywords//Control flow keyword", AzoraSyntaxHighlighter.CONTROL_KEYWORD),
            AttributesDescriptor("Keywords//Modifier keyword", AzoraSyntaxHighlighter.MODIFIER_KEYWORD),
            AttributesDescriptor("Keywords//Memory keyword", AzoraSyntaxHighlighter.MEMORY_KEYWORD),
            AttributesDescriptor("Keywords//Reactive keyword", AzoraSyntaxHighlighter.REACTIVE_KEYWORD),

            // Identifiers
            AttributesDescriptor("Identifiers//Identifier", AzoraSyntaxHighlighter.IDENTIFIER),
            AttributesDescriptor("Identifiers//Type parameter", AzoraSyntaxHighlighter.TYPE_PARAMETER),

            // Literals
            AttributesDescriptor("Literals//Number", AzoraSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Literals//String", AzoraSyntaxHighlighter.STRING),

            // Comments
            AttributesDescriptor("Comments//Line comment", AzoraSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", AzoraSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Doc comment", AzoraSyntaxHighlighter.DOC_COMMENT),

            // Operators and punctuation
            AttributesDescriptor("Operators and Punctuation//Operator", AzoraSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Operators and Punctuation//Decorator", AzoraSyntaxHighlighter.DECORATOR),
            AttributesDescriptor("Operators and Punctuation//Parentheses", AzoraSyntaxHighlighter.PAREN),
            AttributesDescriptor("Operators and Punctuation//Braces", AzoraSyntaxHighlighter.BRACE),
            AttributesDescriptor("Operators and Punctuation//Brackets", AzoraSyntaxHighlighter.BRACKET),
            AttributesDescriptor("Operators and Punctuation//Comma", AzoraSyntaxHighlighter.COMMA_ATTR),
            AttributesDescriptor("Operators and Punctuation//Semicolon", AzoraSyntaxHighlighter.SEMICOLON_ATTR),
            AttributesDescriptor("Operators and Punctuation//Arrow", AzoraSyntaxHighlighter.ARROW_ATTR),
            AttributesDescriptor("Operators and Punctuation//Dot", AzoraSyntaxHighlighter.DOT_ATTR),
            AttributesDescriptor("Operators and Punctuation//Colon", AzoraSyntaxHighlighter.COLON_ATTR),

            // Special
            AttributesDescriptor("Bad character", AzoraSyntaxHighlighter.BAD_CHAR),
        )

        /**
         * The demo Azora source code shown in the color settings preview pane.
         *
         * Covers all language features and token categories to give users
         * a comprehensive preview when customizing colors.
         */
        private val DEMO_TEXT = """
            package example.app

            use std.{math, container, concurrency}
            use std.io
            use pages.*

            // -------------------------------------------------------
            // Doc comments
            // -------------------------------------------------------

            /**
             * A 2D point in Cartesian space.
             * @param x The X coordinate.
             * @param y The Y coordinate.
             */
            pack Point {
                var x: Real = 0.0
                var y: Real = 0.0
            }

            impl Point {
                ctor(x: Real, y: Real) {
                    self.x = x
                    self.y = y
                }

                dtor {
                    trace("Point destroyed")
                }

                func distanceTo(other: Point): Real { ref self ->
                    fin dx = other.x - x
                    fin dy = other.y - y
                    return toReal(dx * dx + dy * dy)
                }

                prop magnitude: Real = toReal(x * x + y * y)

                oper+(other: Point): Point { ref self ->
                    return Point(x: x + other.x, y: y + other.y)
                }

                oper==(other: Point): Bool { ref self ->
                    return x == other.x && y == other.y
                }
            }

            // -------------------------------------------------------
            // Enums, Slots, and Fail Sets
            // -------------------------------------------------------

            enum Direction {
                North, South, East, West
            }

            slot Shape {
                Circle(radius: Real),
                Rectangle(width: Real, height: Real),
                Triangle(base: Real, height: Real)
            }

            fail NetworkError {
                Timeout,
                ConnectionRefused,
                NotFound
            }

            // -------------------------------------------------------
            // Scopes
            // -------------------------------------------------------

            friend zone std::math {
                func square(n: Int): Int {
                    return n * n
                }

                func clamp(value: Int, lo: Int, hi: Int): Int {
                    if value < lo { return lo }
                    if value > hi { return hi }
                    return value
                }
            }

            // -------------------------------------------------------
            // Generics with Constraints
            // -------------------------------------------------------

            spec Printable {
                func display(): String
            }

            func<T> printAll(items: List<T>): Unit where each T: Printable {
                for item in items {
                    println(item.display())
                }
            }

            // -------------------------------------------------------
            // Bridge / FFI
            // -------------------------------------------------------

            bridge .C {
                func puts(s: String): Int
            }

            bridge .JVM {
                func currentTimeMillis(): Long
            }

            // -------------------------------------------------------
            // Contracts (in / out)
            // -------------------------------------------------------

            func safeDivide(a: Real, b: Real): Real {
                in {
                    assert b != 0.0
                }
                out { r ->
                    assert r >= 0.0
                }
                return a / b
            }

            // -------------------------------------------------------
            // Views, mem/rem/ret, and effect (Reactive UI)
            // -------------------------------------------------------

            @Entry
            view CounterApp() {
                rem count = 0
                mem session = CounterSession()
                ret renderer = CounterRenderer()
                rem label = "Clicks"

                effect {
                    println("Count changed: " + toString(count))
                }

                Column(modifier: Modifier.padding(16)) {
                    Text(text: label + ": " + toString(count))
                    Row(modifier: Modifier.fillMaxWidth()) {
                        Button(text: "+", onClick: { count += 1 })
                        Button(text: "-", onClick: { count -= 1 })
                    }
                }
            }

            // -------------------------------------------------------
            // Async / Await / Flow / Yield
            // -------------------------------------------------------

            task fetchData(url: String): String {
                fin response = await httpGet(url)
                return response
            }

            flow fibonacci(): Int {
                var a = 0
                var b = 1
                loop {
                    yield a
                    fin temp = a + b
                    a = b
                    b = temp
                }
            }

            // -------------------------------------------------------
            // DI: Solo, Wrap, Inject
            // -------------------------------------------------------

            solo AppConfig {
                fin apiUrl: String = "https://api.example.com"
                fin timeout: Int = 30
            }

            wrap ServiceModule {
                bind ApiService = ApiServiceImpl()
                lazy bind Database = DatabaseImpl()
            }

            func startApp() {
                inject config: AppConfig
                inject api: ApiService
                println(config.apiUrl)
            }

            // -------------------------------------------------------
            // Memory: alloc, drop, unsafe, zone
            // -------------------------------------------------------

            func lowLevel() {
                zone scratch {
                    fin buf = alloc Byte(1024)
                    unsafe {
                        buf[0] = 0xFF as Byte
                    }
                    drop buf
                }
            }

            // -------------------------------------------------------
            // CTCE (inline for / inline if)
            // -------------------------------------------------------

            inline for i in 0..4 {
                func generated(): Int {
                    return 42
                }
            }

            inline if platform == "js" {
                func platformName(): String { return "JavaScript" }
            } else {
                func platformName(): String { return "Native" }
            }

            // -------------------------------------------------------
            // Tests
            // -------------------------------------------------------

            test "point addition" {
                fin a = Point(x: 1.0, y: 2.0)
                fin b = Point(x: 3.0, y: 4.0)
                fin c = a + b
                assert c.x == 4.0
                assert c.y == 6.0
            }

            test "safe divide contract" {
                fin result = safeDivide(10.0, 2.0)
                assert result == 5.0
            }

            // -------------------------------------------------------
            // Pattern Matching with when
            // -------------------------------------------------------

            func describeShape(s: Shape): String {
                return when s {
                    is Shape.Circle -> "Circle r=" + toString(s.radius)
                    is Shape.Rectangle -> "Rect " + toString(s.width) + "x" + toString(s.height)
                    is Shape.Triangle -> "Triangle"
                    else -> "Unknown"
                }
            }

            // -------------------------------------------------------
            // For loops with 'by', 'with', 'reverse'
            // -------------------------------------------------------

            func loopExamples() {
                var items = [10, 20, 30, 40, 50]

                for i in 0..4 by 2 {
                    println(toString(items[i]))
                }

                for item in items with index {
                    println(toString(index) + ": " + toString(item))
                }

                for item in items reverse {
                    println(toString(item))
                }
            }

            // -------------------------------------------------------
            // Error handling with fail / try / catch
            // -------------------------------------------------------

            func loadConfig(path: String): String fail NetworkError {
                if path == "" {
                    fail return .NotFound
                }
                return try readFile(path) catch "default"
            }

            // -------------------------------------------------------
            // Typealias and nullable operators
            // -------------------------------------------------------

            typealias Callback = (Int) -> Unit

            func nullableExample() {
                var name: String? = null
                name ?= "default"
                fin len = name ?? "fallback"
                println(len)
            }

            // -------------------------------------------------------
            // Entry point
            // -------------------------------------------------------

            func main() {
                fin origin = Point(x: 0.0, y: 0.0)
                fin target = Point(x: 3.0, y: 4.0)
                fin dist = origin.distanceTo(target)
                println("Distance: " + toString(dist))

                fin shape = Shape.Circle(radius: 5.0)
                println(describeShape(shape))

                loopExamples()
                lowLevel()
            }
        """.trimIndent()
    }
}

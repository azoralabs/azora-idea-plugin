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

import org.azora.lang.idea.AzoraFileType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * The **Editor | Color Scheme | Azora** page.
 *
 * Every category the plugin can color is listed here and every one ships an
 * Azora-palette default, so the scheme is a starting point rather than a
 * requirement: change any entry and the plugin honours it.
 *
 * The preview covers the categories the lexer assigns directly and, through
 * the `<tag>` map, the ones the semantic annotator adds — macros, calls, type
 * references and smart casts — so their colors can be judged in context.
 */
class AzoraColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon = AzoraFileType.INSTANCE.icon

    override fun getHighlighter(): SyntaxHighlighter = AzoraSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Azora"

    companion object {

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
            AttributesDescriptor("Identifiers//Type reference", AzoraSyntaxHighlighter.TYPE_NAME),
            AttributesDescriptor("Identifiers//Type declaration", AzoraSyntaxHighlighter.TYPE_DECLARATION),
            AttributesDescriptor("Identifiers//Spec type", AzoraSyntaxHighlighter.SPEC_TYPE),
            AttributesDescriptor("Identifiers//Type parameter", AzoraSyntaxHighlighter.TYPE_PARAMETER),
            AttributesDescriptor("Identifiers//Zone usage", AzoraSyntaxHighlighter.ZONE_USAGE),
            AttributesDescriptor("Identifiers//Import path", AzoraSyntaxHighlighter.MODULE_PATH),
            AttributesDescriptor("Identifiers//Function call", AzoraSyntaxHighlighter.FUNCTION_CALL),
            AttributesDescriptor("Identifiers//Function declaration", AzoraSyntaxHighlighter.FUNCTION_DECLARATION),
            AttributesDescriptor("Identifiers//Spec function", AzoraSyntaxHighlighter.SPEC_FUNCTION),
            AttributesDescriptor("Identifiers//Override function", AzoraSyntaxHighlighter.OVERRIDE_FUNCTION),
            AttributesDescriptor("Identifiers//Parameter", AzoraSyntaxHighlighter.PARAMETER),
            AttributesDescriptor("Identifiers//Field", AzoraSyntaxHighlighter.FIELD),
            AttributesDescriptor("Identifiers//Property", AzoraSyntaxHighlighter.PROPERTY),
            AttributesDescriptor("Identifiers//Spec property", AzoraSyntaxHighlighter.SPEC_PROPERTY),
            AttributesDescriptor("Identifiers//Override property", AzoraSyntaxHighlighter.OVERRIDE_PROPERTY),
            AttributesDescriptor("Identifiers//Unused declaration", AzoraSyntaxHighlighter.UNUSED),
            AttributesDescriptor("Identifiers//Unused parameter", AzoraSyntaxHighlighter.UNUSED_PARAMETER),
            AttributesDescriptor("Identifiers//Unused property", AzoraSyntaxHighlighter.UNUSED_PROPERTY),
            AttributesDescriptor("Identifiers//Unused spec member", AzoraSyntaxHighlighter.UNUSED_SPEC_MEMBER),
            AttributesDescriptor("Identifiers//Unused override member", AzoraSyntaxHighlighter.UNUSED_OVERRIDE_MEMBER),
            AttributesDescriptor("Identifiers//Smart cast", AzoraSyntaxHighlighter.SMART_CAST),

            // Macros
            AttributesDescriptor("Macros//Macro", AzoraSyntaxHighlighter.MACRO),

            // Literals
            AttributesDescriptor("Literals//Number", AzoraSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Literals//String", AzoraSyntaxHighlighter.STRING),
            AttributesDescriptor("Literals//Escape sequence", AzoraSyntaxHighlighter.STRING_ESCAPE),
            AttributesDescriptor("Literals//String interpolation", AzoraSyntaxHighlighter.INTERPOLATION),

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

            // Diagnostics
            AttributesDescriptor("Diagnostics//Error", AzoraSyntaxHighlighter.ERROR),
            AttributesDescriptor("Diagnostics//Warning", AzoraSyntaxHighlighter.WARNING),
            AttributesDescriptor("Diagnostics//Bad character", AzoraSyntaxHighlighter.BAD_CHAR),
        )

        /** Tags used in [DEMO_TEXT] for the categories the annotator assigns. */
        private val TAGS = mapOf(
            "macro" to AzoraSyntaxHighlighter.MACRO,
            "type" to AzoraSyntaxHighlighter.TYPE_NAME,
            "typeDecl" to AzoraSyntaxHighlighter.TYPE_DECLARATION,
            "specType" to AzoraSyntaxHighlighter.SPEC_TYPE,
            "zoneUsage" to AzoraSyntaxHighlighter.ZONE_USAGE,
            "modulePath" to AzoraSyntaxHighlighter.MODULE_PATH,
            "call" to AzoraSyntaxHighlighter.FUNCTION_CALL,
            "funcDecl" to AzoraSyntaxHighlighter.FUNCTION_DECLARATION,
            "specFunc" to AzoraSyntaxHighlighter.SPEC_FUNCTION,
            "overrideFunc" to AzoraSyntaxHighlighter.OVERRIDE_FUNCTION,
            "param" to AzoraSyntaxHighlighter.PARAMETER,
            "field" to AzoraSyntaxHighlighter.FIELD,
            "property" to AzoraSyntaxHighlighter.PROPERTY,
            "specProperty" to AzoraSyntaxHighlighter.SPEC_PROPERTY,
            "overrideProperty" to AzoraSyntaxHighlighter.OVERRIDE_PROPERTY,
            "unused" to AzoraSyntaxHighlighter.UNUSED,
            "unusedParam" to AzoraSyntaxHighlighter.UNUSED_PARAMETER,
            "smartCast" to AzoraSyntaxHighlighter.SMART_CAST,
            "error" to AzoraSyntaxHighlighter.ERROR,
            "warning" to AzoraSyntaxHighlighter.WARNING,
        )

        /** The `"""` that opens an Azora raw string, spelled out for the demo. */
        private const val RAW = "\"\"\""

        private val DEMO_TEXT = """
            module <typeDecl>example</typeDecl>.app

            import <modulePath>std</modulePath>.{<modulePath>math</modulePath>, <modulePath>container</modulePath>}
            import <modulePath>std</modulePath>.<modulePath>io</modulePath>

            /**
             * A point in two dimensions.
             * @param x the horizontal coordinate.
             */
            @Stable(sinceAzora: "0.0.5")
            pack <typeDecl>Point</typeDecl> {
                var <field>x</field>: <type>Real</type> = 0.0
                var <field>y</field>: <type>Real</type> = 0.0
            }

            impl <type>Point</type> {
                ctor(<param>x</param>: <type>Real</type>, <param>y</param>: <type>Real</type>) {
                    <param>self</param>.<field>x</field> = <param>x</param>
                    <param>self</param>.<field>y</field> = <param>y</param>
                }

                /// The squared distance to another point.
                func <funcDecl>distanceTo</funcDecl>(<param>other</param>: <type>Point</type>): <type>Real</type> {
                    fin dx = <param>other</param>.<field>x</field> - <param>self</param>.<field>x</field>
                    return dx * dx
                }

                oper+(<param>other</param>: <type>Point</type>): <type>Point</type> {
                    return <type>Point</type>(x: <param>self</param>.<field>x</field> + <param>other</param>.<field>x</field>, y: 0.0)
                }
            }

            enum <typeDecl>Direction</typeDecl> { North, South, East, West }

            slot <typeDecl>Shape</typeDecl> {
                Circle(radius: <type>Real</type>),
                Rectangle(width: <type>Real</type>, height: <type>Real</type>)
            }

            fail <typeDecl>NetworkError</typeDecl> { Timeout, NotFound }

            // Macros are found in real `meta` declarations, never hardcoded.
            meta .Prefix("vec") { [...${'$'}items] => }
            meta .Infix("to") { ${'$'}a ${'$'}b => }
            meta type {
                res ${'$'}T => ref ${'$'}T
                ${'$'}Base with ${'$'}Filter => ${'$'}Base
            }

            func <funcDecl>macros</funcDecl>() {
                // `with` is a keyword in statement position…
                with (context) {
                    fin numbers = <macro>vec</macro>@[1, 2, 3]
                    fin pairs = "a" <macro>to</macro> 1
                }
                // …and the macro the engine declared when it joins two types.
                fin query: Query<<type>Position</type> <macro>with</macro> <type>Velocity</type>> = <call>makeQuery</call>()
            }

            func <funcDecl>strings</funcDecl>(<param>name</param>: <type>String</type>): <type>String</type> {
                fin greeting = "Hello, ${'$'}<param>name</param>!\n"
                fin detail = "total: ${'$'}{<call>count</call>() + 1}"
                fin raw = ${RAW}no ${'$'}escapes here${RAW}
                return greeting + detail + raw
            }

            func <funcDecl>describe</funcDecl>(<param>shape</param>: <type>Shape</type>): <type>String</type> {
                if <param>shape</param> is <type>Circle</type> {
                    // Inside the branch the binding is narrowed.
                    return "circle " + <call>toString</call>(<smartCast>shape</smartCast>.<field>radius</field>)
                }
                guard <param>shape</param> is <type>Rectangle</type> else { return "unknown" }
                return <call>toString</call>(<smartCast>shape</smartCast>.<field>width</field>)
            }

            expose task <funcDecl>fetch</funcDecl>(<param>url</param>: <type>String</type>): <type>String</type> {
                fin response = await <call>httpGet</call>(<param>url</param>)
                defer { <call>close</call>(response) }
                return response
            }

            flow <funcDecl>fibonacci</funcDecl>(): <type>Int</type> {
                var a = 0
                loop {
                    yield a
                    a = a + 1
                }
            }

            solo <typeDecl>AppConfig</typeDecl> {
                fin <field>apiUrl</field>: <type>String</type> = "https://api.example.com"
            }

            wrap <typeDecl>ServiceModule</typeDecl> {
                bind <type>ApiService</type> = <type>ApiServiceImpl</type>()
            }

            func <funcDecl>lowLevel</funcDecl>() {
                zone scratch {
                    fin buffer = alloc <type>Byte</type>(1024)
                    unsafe { buffer[0] = 0xFF as <type>Byte</type> }
                    drop buffer
                }
            }

            func <funcDecl>diagnostics</funcDecl>() {
                fin broken = <error>"unterminated</error>
                import <warning>std.doesNotExist</warning>
            }

            test "points add componentwise" {
                fin a = <type>Point</type>(x: 1.0, y: 2.0)
                assert (a + a).<field>x</field> == 2.0 { "x doubles" }
            }

            func <funcDecl>main</funcDecl>() {
                <call>println</call>(<call>describe</call>(<type>Shape</type>.Circle(radius: 5.0)))
            }
        """.trimIndent()
    }
}

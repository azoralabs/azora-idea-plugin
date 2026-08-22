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
            AttributesDescriptor("Identifiers//Scope path", AzoraSyntaxHighlighter.ZONE_USAGE),
            AttributesDescriptor("Identifiers//Import path", AzoraSyntaxHighlighter.MODULE_PATH),
            AttributesDescriptor("Identifiers//Wildcard", AzoraSyntaxHighlighter.WILDCARD),
            AttributesDescriptor("Identifiers//Loop label", AzoraSyntaxHighlighter.LOOP_LABEL),
            AttributesDescriptor("Identifiers//Function call", AzoraSyntaxHighlighter.FUNCTION_CALL),
            AttributesDescriptor("Identifiers//Function declaration", AzoraSyntaxHighlighter.FUNCTION_DECLARATION),
            AttributesDescriptor("Identifiers//Spec function", AzoraSyntaxHighlighter.SPEC_FUNCTION),
            AttributesDescriptor("Identifiers//Override function", AzoraSyntaxHighlighter.OVERRIDE_FUNCTION),
            AttributesDescriptor("Identifiers//Parameter", AzoraSyntaxHighlighter.PARAMETER),
            AttributesDescriptor("Identifiers//Context receiver parameter", AzoraSyntaxHighlighter.CONTEXT_PARAMETER),
            AttributesDescriptor("Identifiers//Field", AzoraSyntaxHighlighter.FIELD),
            AttributesDescriptor("Identifiers//Enum case", AzoraSyntaxHighlighter.ENUM_CASE),
            AttributesDescriptor("Identifiers//Error case", AzoraSyntaxHighlighter.ERROR_CASE),
            AttributesDescriptor("Identifiers//Property", AzoraSyntaxHighlighter.PROPERTY),
            AttributesDescriptor("Identifiers//Property read", AzoraSyntaxHighlighter.PROPERTY_CALL),
            AttributesDescriptor("Identifiers//Spec property", AzoraSyntaxHighlighter.SPEC_PROPERTY),
            AttributesDescriptor("Identifiers//Override property", AzoraSyntaxHighlighter.OVERRIDE_PROPERTY),
            AttributesDescriptor("Identifiers//Smart cast", AzoraSyntaxHighlighter.SMART_CAST),

            // Macros
            AttributesDescriptor("Macros//Macro", AzoraSyntaxHighlighter.MACRO),
            AttributesDescriptor("Macros//Macro hole", AzoraSyntaxHighlighter.MACRO_HOLE),

            // Literals
            AttributesDescriptor("Literals//Number", AzoraSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Literals//String", AzoraSyntaxHighlighter.STRING),
            AttributesDescriptor("Literals//Escape sequence", AzoraSyntaxHighlighter.STRING_ESCAPE),
            AttributesDescriptor("Literals//String interpolation", AzoraSyntaxHighlighter.INTERPOLATION),

            // Comments
            AttributesDescriptor("Comments//Line comment", AzoraSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", AzoraSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Doc comment", AzoraSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Comments//Doc tag", AzoraSyntaxHighlighter.DOC_TAG),
            AttributesDescriptor("Comments//Doc tag name", AzoraSyntaxHighlighter.DOC_TAG_VALUE),

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
            "typeParam" to AzoraSyntaxHighlighter.TYPE_PARAMETER,
            "ctxParam" to AzoraSyntaxHighlighter.CONTEXT_PARAMETER,
            "propRead" to AzoraSyntaxHighlighter.PROPERTY_CALL,
            "typeDecl" to AzoraSyntaxHighlighter.TYPE_DECLARATION,
            "specType" to AzoraSyntaxHighlighter.SPEC_TYPE,
            "zoneUsage" to AzoraSyntaxHighlighter.ZONE_USAGE,
            "modulePath" to AzoraSyntaxHighlighter.MODULE_PATH,
            "wildcard" to AzoraSyntaxHighlighter.WILDCARD,
            "label" to AzoraSyntaxHighlighter.LOOP_LABEL,
            "call" to AzoraSyntaxHighlighter.FUNCTION_CALL,
            "funcDecl" to AzoraSyntaxHighlighter.FUNCTION_DECLARATION,
            "specFunc" to AzoraSyntaxHighlighter.SPEC_FUNCTION,
            "overrideFunc" to AzoraSyntaxHighlighter.OVERRIDE_FUNCTION,
            "param" to AzoraSyntaxHighlighter.PARAMETER,
            "field" to AzoraSyntaxHighlighter.FIELD,
            "enumCase" to AzoraSyntaxHighlighter.ENUM_CASE,
            "errorCase" to AzoraSyntaxHighlighter.ERROR_CASE,
            "property" to AzoraSyntaxHighlighter.PROPERTY,
            "specProperty" to AzoraSyntaxHighlighter.SPEC_PROPERTY,
            "overrideProperty" to AzoraSyntaxHighlighter.OVERRIDE_PROPERTY,
            "smartCast" to AzoraSyntaxHighlighter.SMART_CAST,
            "error" to AzoraSyntaxHighlighter.ERROR,
            "warning" to AzoraSyntaxHighlighter.WARNING,
        )

        /** The `"""` that opens an Azora raw string, spelled out for the demo. */
        private const val RAW = "\"\"\""

        private val DEMO_TEXT = """
            module <modulePath>example</modulePath>.<modulePath>app</modulePath>

            import <modulePath>std</modulePath>.<modulePath>math</modulePath>
            import <modulePath>std</modulePath>.<modulePath>io</modulePath>::<wildcard>*</wildcard>
            import <modulePath>std</modulePath>.<modulePath>container</modulePath>.[<modulePath>list</modulePath>, <modulePath>map</modulePath>]
            import <modulePath>std</modulePath>.<modulePath>format</modulePath>::[<type>Display</type>]

            /**
             * A point in two dimensions.
             *
             * @param x The horizontal offset.
             * @param y The vertical offset.
             * @return The point that was built.
             */
            @Stable(since: "0.1")
            pack <typeDecl>Point</typeDecl> {
                var <field>x</field>: <type>std::Double</type> = 0.0
                var <field>y</field>: <type>std::Double</type> = 0.0
            }

            annot <typeDecl>Serializable</typeDecl>

            variant enum <typeDecl>Shape</typeDecl> {
                <enumCase>Circle</enumCase>(radius: <type>std::Double</type>)
                <enumCase>Rectangle</enumCase>(width: <type>std::Double</type> height: <type>std::Double</type>)
            }

            error <typeDecl>NetworkError</typeDecl> {
                <errorCase>Timeout</errorCase>
                <errorCase>NotFound</errorCase>
            }

            macro @buildPoint {
                [${'$'}x ${'$'}y] => Point(${ '$' }x ${ '$' }y)
            }
            macro ${'$'}left @to ${'$'}right => pair(${ '$' }left ${ '$' }right)

            impl <type>Point</type> {
                react ctor[<ctxParam>self</ctxParam>: Self!](
                    <param>x</param>: <type>std::Double</type>
                    <param>y</param>: <type>std::Double</type>
                ) {
                    <ctxParam>self</ctxParam>.<field>x</field> = <param>x</param>
                    <ctxParam>self</ctxParam>.<field>y</field> = <param>y</param>
                }

                prop <property>magnitude</property>[<ctxParam>self</ctxParam>: Self&]: <type>Double</type> = <ctxParam>self</ctxParam>.<field>x</field>

                func <funcDecl>distanceTo</funcDecl>[<ctxParam>self</ctxParam>: Self&](
                    <param>other</param>: <type>Point</type>
                ): <type>Double</type> {
                    fin dx = <param>other</param>.<field>x</field> - <ctxParam>self</ctxParam>.<field>x</field>
                    return dx * dx + <ctxParam>self</ctxParam>.<propRead>magnitude</propRead>
                }
            }

            scope <zoneUsage>ide</zoneUsage>::<zoneUsage>editor</zoneUsage> {
                react func <funcDecl>render</funcDecl><<typeParam>T</typeParam>>(
                    <param>value</param>: <typeParam>T</typeParam>
                ) {
                    fin built = <macro>@buildPoint</macro>[1.0 2.0]
                    fin pair = built <macro>@to</macro> <param>value</param>
                    <call>std::println</call>(pair)
                }
            }

            func <funcDecl>search</funcDecl>(<param>items</param>: <type>Array&lt;std::Int&gt;</type>) {
                <label>outer</label>: for item in <param>items</param> {
                    if item == 0 { break:<label>outer</label> }
                }
            }

            func <funcDecl>main</funcDecl>() {
                fin unusedValue = 1
                <call>ide::editor::render</call>(<type>Shape</type>.<enumCase>Circle</enumCase>(5.0))
            }

            // Exact diagnostics use wavy underlines.
            fin bad: std::Double = <error>5</error>
            import <warning>std.doesNotExist</warning>
        """.trimIndent()
    }
}

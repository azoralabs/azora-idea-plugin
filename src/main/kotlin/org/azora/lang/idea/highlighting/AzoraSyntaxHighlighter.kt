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

import org.azora.lang.idea.*
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * [SyntaxHighlighterBase] for the Azora language.
 *
 * Maps each [AzoraTokenTypes] element type to one or more [TextAttributesKey]s.
 * Every key ships an explicit default drawn from [AzoraPalette] — the same
 * palette the Azora code playground uses — so a fresh install already looks
 * like Azora, while remaining fully overridable in the color settings page.
 *
 * Purely lexical categories are resolved here. Categories that need to know
 * what a name *means* (macros, calls, types, smart casts) are applied on top by
 * `AzoraSemanticAnnotator`.
 */
class AzoraSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = AzoraLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when (tokenType) {
            // Keyword categories
            AzoraTokenTypes.DECLARATION_KEYWORD -> DECLARATION_KEYWORD_KEYS
            AzoraTokenTypes.CONTROL_KEYWORD -> CONTROL_KEYWORD_KEYS
            AzoraTokenTypes.MODIFIER_KEYWORD -> MODIFIER_KEYWORD_KEYS
            AzoraTokenTypes.MEMORY_KEYWORD -> MEMORY_KEYWORD_KEYS
            AzoraTokenTypes.REACTIVE_KEYWORD -> REACTIVE_KEYWORD_KEYS
            AzoraTokenTypes.KEYWORD -> KEYWORD_KEYS
            AzoraTokenTypes.BOOL_LITERAL -> KEYWORD_KEYS

            // Identifiers
            AzoraTokenTypes.TYPE_PARAMETER -> TYPE_PARAMETER_KEYS
            AzoraTokenTypes.IDENTIFIER -> IDENTIFIER_KEYS

            // Literals
            AzoraTokenTypes.INT_LITERAL -> NUMBER_KEYS
            AzoraTokenTypes.REAL_LITERAL -> REAL_NUMBER_KEYS
            AzoraTokenTypes.STRING_LITERAL, AzoraTokenTypes.RAW_STRING_LITERAL -> STRING_KEYS
            AzoraTokenTypes.CHAR_LITERAL -> STRING_KEYS
            AzoraTokenTypes.STRING_ESCAPE -> STRING_ESCAPE_KEYS
            AzoraTokenTypes.INTERPOLATION_START,
            AzoraTokenTypes.INTERPOLATION_END -> INTERPOLATION_KEYS

            // Comments
            AzoraTokenTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            AzoraTokenTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            AzoraTokenTypes.DOC_COMMENT -> DOC_COMMENT_KEYS
            AzoraTokenTypes.DOC_TAG -> DOC_TAG_KEYS
            AzoraTokenTypes.DOC_TAG_VALUE -> DOC_TAG_VALUE_KEYS

            // Operators and punctuation
            AzoraTokenTypes.OPERATOR -> OPERATOR_KEYS
            AzoraTokenTypes.DECORATOR -> DECORATOR_KEYS
            AzoraTokenTypes.MACRO -> MACRO_KEYS
            AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN -> PAREN_KEYS
            AzoraTokenTypes.L_BRACE, AzoraTokenTypes.R_BRACE -> BRACE_KEYS
            AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET -> BRACKET_KEYS
            AzoraTokenTypes.COMMA -> COMMA_KEYS
            AzoraTokenTypes.COLON -> COLON_KEYS
            AzoraTokenTypes.ARROW -> ARROW_KEYS
            AzoraTokenTypes.DOT -> DOT_KEYS
            AzoraTokenTypes.SEMICOLON -> SEMICOLON_KEYS

            AzoraTokenTypes.BAD_CHARACTER -> BAD_CHAR_KEYS
            else -> EMPTY_KEYS
        }
    }

    companion object {

        // ── Keyword categories ─────────────────────────────────────────

        /** General-purpose keywords (`true`, `false`, `null`). */
        val KEYWORD = key("AZORA_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        /** Declaration keywords (`func`, `pack`, `enum`, `variant`, `annot`, …). */
        val DECLARATION_KEYWORD = key("AZORA_DECLARATION_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        /** Control-flow keywords (`if`, `else`, `for`, `return`, `with`, …). */
        val CONTROL_KEYWORD = key("AZORA_CONTROL_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        /** Modifier keywords (`exposed`, `confined`, `inline`, `react`, …). */
        val MODIFIER_KEYWORD = key("AZORA_MODIFIER_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        /** Memory keywords (`alloc`, `purge`, `take`, `unsafe`, `preserve`). */
        val MEMORY_KEYWORD = key("AZORA_MEMORY_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        /** Reactive keywords (`remember`, `retain`, `effect`). */
        val REACTIVE_KEYWORD = key("AZORA_REACTIVE_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))

        // ── Identifiers ────────────────────────────────────────────────

        /** Ordinary identifiers. */
        val IDENTIFIER = key("AZORA_IDENTIFIER", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Generic type parameters (`T`, `U`, …). */
        val TYPE_PARAMETER = key("AZORA_TYPE_PARAMETER", AzoraPalette.bold(AzoraPalette.TYPE_PARAMETER))

        /** A loop label and the same label after `break:` / `continue:`. */
        val LOOP_LABEL = key("AZORA_LOOP_LABEL", AzoraPalette.fg(AzoraPalette.LABEL))

        /** Named types: packs, enums, errors, specs, scopes, and aliases. */
        val TYPE_NAME = key("AZORA_TYPE_NAME", AzoraPalette.fg(AzoraPalette.TYPE))

        /** The name in a type declaration (`pack ‹Point›`). */
        val TYPE_DECLARATION = key("AZORA_TYPE_DECLARATION", AzoraPalette.fg(AzoraPalette.TYPE))

        /** A spec declaration or reference. */
        val SPEC_TYPE = key("AZORA_SPEC_TYPE", AzoraPalette.italic(AzoraPalette.TYPE))

        /** A scope path segment, including every segment around `::`. */
        val ZONE_USAGE = key("AZORA_ZONE_USAGE", AzoraPalette.italic(AzoraPalette.FOREGROUND))

        /**
         * The `*` that stands for "everything here" - `std.io::*`,
         * `derive Ignore for Fixture::*`.
         *
         * The [AzoraPalette.SECONDARY] blue, like a loop label and a `${…}`
         * hole: all three are marks *about* the code rather than code, and this
         * one is the only `*` that is not multiplication.
         */
        val WILDCARD = key("AZORA_WILDCARD", AzoraPalette.bold(AzoraPalette.SECONDARY))

        /** A macro hole - `$items` in `[...$items] => List(...$items)`. */
        val MACRO_HOLE = key("AZORA_MACRO_HOLE", AzoraPalette.bold(AzoraPalette.MACRO_HOLE))

        /** A segment of an `import` module path. */
        val MODULE_PATH = key("AZORA_MODULE_PATH", AzoraPalette.italic(AzoraPalette.FOREGROUND))

        /** A called function. */
        val FUNCTION_CALL = key("AZORA_FUNCTION_CALL", AzoraPalette.fg(AzoraPalette.FUNCTION))

        /** The name in a function declaration (`func ‹main›`). */
        val FUNCTION_DECLARATION = key("AZORA_FUNCTION_DECLARATION", AzoraPalette.fg(AzoraPalette.FUNCTION))

        /** A callable declared by a spec. */
        val SPEC_FUNCTION = key("AZORA_SPEC_FUNCTION", AzoraPalette.italic(AzoraPalette.FUNCTION))

        /** A spec callable implemented by an `impl ... for ...` block. */
        val OVERRIDE_FUNCTION = key(
            "AZORA_OVERRIDE_FUNCTION",
            AzoraPalette.italicUnderlined(AzoraPalette.FUNCTION),
        )

        /** Function/constructor parameters - the light gray-blue of a handed-over value. */
        val PARAMETER = key("AZORA_PARAMETER", AzoraPalette.fg(AzoraPalette.PARAMETER))

        /**
         * A context receiver parameter - the `[self&, scope: Scope&]` a `func`
         * or `prop` declares before its own parameter list.
         */
        val CONTEXT_PARAMETER = key(
            "AZORA_CONTEXT_PARAMETER",
            AzoraPalette.fg(AzoraPalette.CONTEXT_PARAMETER),
        )

        /** Pack fields and computed properties accessed through a receiver. */
        val FIELD = key("AZORA_FIELD", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /**
         * A case of an `enum`, both where it is declared and where it is named.
         *
         * A case is a value the type spells out rather than one computed at run
         * time, and the italic is what says so — the same mark [SPEC_TYPE]
         * wears for the same reason.
         */
        val ENUM_CASE = key("AZORA_ENUM_CASE", AzoraPalette.italic(AzoraPalette.FOREGROUND))

        /** A case of an `error` declaration - [ENUM_CASE] in the failure red. */
        val ERROR_CASE = key("AZORA_ERROR_CASE", AzoraPalette.italic(AzoraPalette.ERROR_CASE))

        /**
         * A computed property declaration - italic, underlined.
         *
         * The italic is what a `prop` name wears everywhere it appears: the
         * declaration, the read, the spec that asks for it and the `impl` that
         * answers. It stands for the same thing an [ENUM_CASE]'s italic does -
         * a name that is not a plain slot in memory. The underline says the
         * name is a member a reader may go and read, where a parameter is a
         * value already in front of them.
         */
        val PROPERTY = key("AZORA_PROPERTY", AzoraPalette.italicUnderlined(AzoraPalette.PROPERTY))

        /** Reading a computed property through a receiver - the call side of [PROPERTY]. */
        val PROPERTY_CALL = key("AZORA_PROPERTY_CALL", AzoraPalette.italicUnderlined(AzoraPalette.PROPERTY))

        /**
         * A property declared by a spec.
         *
         * Once the italic belongs to every `prop` name, it can no longer say
         * "spec member" here the way [SPEC_FUNCTION]'s does: a property reads
         * the same wherever it is declared, which is what a reader asked for.
         * The key stays its own so a scheme can still tell the two apart.
         */
        val SPEC_PROPERTY = key("AZORA_SPEC_PROPERTY", AzoraPalette.italicUnderlined(AzoraPalette.PROPERTY))

        /** A spec property implemented by an `impl ... for ...` block. */
        val OVERRIDE_PROPERTY = key(
            "AZORA_OVERRIDE_PROPERTY",
            AzoraPalette.italicUnderlined(AzoraPalette.PROPERTY),
        )

        // A declaration nothing names has no color of its own. A color says
        // what a name *is* - a `func` is the function blue whether or not this
        // file happens to hold its caller - and the annotator's warning is where
        // "nothing uses this" belongs, because that is a claim about the project
        // rather than about the name.

        /** An identifier narrowed by a smart cast (`is` / `guard is`). */
        val SMART_CAST = key("AZORA_SMART_CAST", AzoraPalette.background(AzoraPalette.SMART_CAST_BACKGROUND))

        /**
         * A name whose declaration carries `@Deprecated`, struck through.
         *
         * Layered over whatever the name already reads as: a deprecated `func`
         * is still function-blue, and the strike is the whole message. It
         * applies wherever the name is written, because the declaration is
         * what deprecates it, not the place that mentions it.
         */
        val DEPRECATED = key("AZORA_DEPRECATED", AzoraPalette.struckThrough())

        // ── Literals ───────────────────────────────────────────────────

        /** Integer literals. */
        val NUMBER = key("AZORA_NUMBER", AzoraPalette.fg(AzoraPalette.NUMBER))

        /** Floating-point literals use the same pastel cyan numeric palette. */
        val REAL_NUMBER = key("AZORA_REAL_NUMBER", AzoraPalette.fg(AzoraPalette.NUMBER))

        /** String and character literals. */
        val STRING = key("AZORA_STRING", AzoraPalette.fg(AzoraPalette.STRING))

        /** Escape sequences inside literals (`\n`, `\"`, `\$`). */
        val STRING_ESCAPE = key("AZORA_STRING_ESCAPE", AzoraPalette.fg(AzoraPalette.STRING_ESCAPE))

        /** The `${` / `$` / `}` that delimit a string interpolation hole. */
        val INTERPOLATION = key("AZORA_INTERPOLATION", AzoraPalette.bold(AzoraPalette.STRING_ESCAPE))

        // ── Comments ───────────────────────────────────────────────────

        /** Single-line comments (`//`). */
        val LINE_COMMENT = key("AZORA_LINE_COMMENT", AzoraPalette.italic(AzoraPalette.COMMENT))

        /** Block comments. */
        val BLOCK_COMMENT = key("AZORA_BLOCK_COMMENT", AzoraPalette.italic(AzoraPalette.COMMENT))

        /**
         * Documentation comments (`/** … */`) - the string green, taken down a
         * step, because a doc comment describes code rather than being code.
         */
        val DOC_COMMENT = key("AZORA_DOC_COMMENT", AzoraPalette.italic(AzoraPalette.DOC))

        /** A doc tag (`@param`, `@return`) - the same green as a string literal. */
        val DOC_TAG = key("AZORA_DOC_TAG", AzoraPalette.italic(AzoraPalette.STRING))

        /** The name a doc tag documents (`capacity` in `@param capacity`). */
        val DOC_TAG_VALUE = key("AZORA_DOC_TAG_VALUE", AzoraPalette.italic(AzoraPalette.DOC_TAG_VALUE))

        // ── Operators and punctuation ──────────────────────────────────

        /** Operators (`+`, `-`, `==`, …). */
        val OPERATOR = key("AZORA_OPERATOR", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Decorators (`@Stable`, `@Derive`). */
        val DECORATOR = key("AZORA_DECORATOR", AzoraPalette.fg(AzoraPalette.DECORATOR))

        /**
         * Macros — prefix (`vec@[…]`), infix (`a with b`) and type (`res T`).
         * Applied lexically for `name@…` forms and semantically for everything
         * else, from macros actually declared in the project or SDK.
         */
        val MACRO = key("AZORA_MACRO", AzoraPalette.bold(AzoraPalette.MACRO))

        /** Parentheses `()`. */
        val PAREN = key("AZORA_PAREN", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Curly braces `{}`. */
        val BRACE = key("AZORA_BRACE", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Square brackets `[]`. */
        val BRACKET = key("AZORA_BRACKET", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Commas. */
        val COMMA_ATTR = key("AZORA_COMMA", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Colons. */
        val COLON_ATTR = key("AZORA_COLON", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Arrows (`->`, `=>`). */
        val ARROW_ATTR = key("AZORA_ARROW", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Dots (`.`). */
        val DOT_ATTR = key("AZORA_DOT", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Semicolons. */
        val SEMICOLON_ATTR = key("AZORA_SEMICOLON", AzoraPalette.fg(AzoraPalette.FOREGROUND))

        /** Unrecognized characters. */
        val BAD_CHAR = key("AZORA_BAD_CHARACTER", AzoraPalette.underlined(AzoraPalette.INVALID))

        // ── Diagnostics ────────────────────────────────────────────────

        /** Wavy red underline used for errors reported by the annotator. */
        val ERROR = key("AZORA_ERROR", AzoraPalette.wavy(AzoraPalette.ERROR))

        /** Wavy yellow underline used for warnings reported by the annotator. */
        val WARNING = key("AZORA_WARNING", AzoraPalette.wavy(AzoraPalette.WARNING))

        // ── Key arrays (returned by getTokenHighlights) ────────────────

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val DECLARATION_KEYWORD_KEYS = arrayOf(DECLARATION_KEYWORD)
        private val CONTROL_KEYWORD_KEYS = arrayOf(CONTROL_KEYWORD)
        private val MODIFIER_KEYWORD_KEYS = arrayOf(MODIFIER_KEYWORD)
        private val MEMORY_KEYWORD_KEYS = arrayOf(MEMORY_KEYWORD)
        private val REACTIVE_KEYWORD_KEYS = arrayOf(REACTIVE_KEYWORD)
        private val TYPE_PARAMETER_KEYS = arrayOf(TYPE_PARAMETER)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val REAL_NUMBER_KEYS = arrayOf(REAL_NUMBER)
        private val STRING_KEYS = arrayOf(STRING)
        private val STRING_ESCAPE_KEYS = arrayOf(STRING_ESCAPE)
        private val INTERPOLATION_KEYS = arrayOf(INTERPOLATION)
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        private val DOC_COMMENT_KEYS = arrayOf(DOC_COMMENT)
        private val DOC_TAG_KEYS = arrayOf(DOC_TAG)
        private val DOC_TAG_VALUE_KEYS = arrayOf(DOC_TAG_VALUE)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val DECORATOR_KEYS = arrayOf(DECORATOR)
        private val MACRO_KEYS = arrayOf(MACRO)
        private val PAREN_KEYS = arrayOf(PAREN)
        private val BRACE_KEYS = arrayOf(BRACE)
        private val BRACKET_KEYS = arrayOf(BRACKET)
        private val COMMA_KEYS = arrayOf(COMMA_ATTR)
        private val COLON_KEYS = arrayOf(COLON_ATTR)
        private val ARROW_KEYS = arrayOf(ARROW_ATTR)
        private val DOT_KEYS = arrayOf(DOT_ATTR)
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON_ATTR)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHAR)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()

        /**
         * Registers a key with an Azora-palette default. The default only
         * applies where the active color scheme says nothing about the key, so
         * user customizations and third-party schemes still win.
         */
        private fun key(name: String, defaults: com.intellij.openapi.editor.markup.TextAttributes) =
            createTextAttributesKey(name, defaults)

        /** Kept so third-party code referencing platform fallbacks still links. */
        @Suppress("unused")
        internal val PLATFORM_KEYWORD_FALLBACK = DefaultLanguageHighlighterColors.KEYWORD
    }
}

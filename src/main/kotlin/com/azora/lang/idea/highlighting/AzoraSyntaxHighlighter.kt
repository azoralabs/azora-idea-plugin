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

import com.azora.lang.idea.*
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * [SyntaxHighlighterBase] for the Azora language.
 *
 * Maps each [AzoraTokenTypes] element type to one or more [TextAttributesKey]s
 * that control syntax coloring in the editor. Keywords are split into semantic
 * categories so the color scheme can differentiate declaration, control-flow,
 * modifier, memory, and reactive keywords with distinct visual treatments.
 */
class AzoraSyntaxHighlighter : SyntaxHighlighterBase() {

    /**
     * Returns a new [AzoraLexerAdapter] used to tokenize the file for highlighting.
     *
     * @return a fresh lexer instance.
     */
    override fun getHighlightingLexer(): Lexer = AzoraLexerAdapter()

    /**
     * Returns the [TextAttributesKey] array for the given [tokenType].
     *
     * Maps each Azora token type to the appropriate highlighting attributes.
     * Returns an empty array for tokens that need no special highlighting.
     *
     * @param tokenType the token element type produced by the lexer.
     * @return the array of text attribute keys to apply, or an empty array.
     */
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
            AzoraTokenTypes.INT_LITERAL, AzoraTokenTypes.REAL_LITERAL -> NUMBER_KEYS
            AzoraTokenTypes.STRING_LITERAL -> STRING_KEYS
            AzoraTokenTypes.CHAR_LITERAL -> STRING_KEYS

            // Comments
            AzoraTokenTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            AzoraTokenTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            AzoraTokenTypes.DOC_COMMENT -> DOC_COMMENT_KEYS

            // Operators and punctuation
            AzoraTokenTypes.OPERATOR -> OPERATOR_KEYS
            AzoraTokenTypes.DECORATOR -> DECORATOR_KEYS
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

        /** Text attributes for general-purpose keywords (`true`, `false`, `null`, `self`, etc.). */
        val KEYWORD = createTextAttributesKey("AZORA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /** Text attributes for declaration keywords (`func`, `pack`, `enum`, `slot`, etc.). */
        val DECLARATION_KEYWORD = createTextAttributesKey("AZORA_DECLARATION_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /** Text attributes for control-flow keywords (`if`, `else`, `for`, `return`, etc.). */
        val CONTROL_KEYWORD = createTextAttributesKey("AZORA_CONTROL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /** Text attributes for modifier keywords (`expose`, `confine`, `mut`, `ref`, etc.). */
        val MODIFIER_KEYWORD = createTextAttributesKey("AZORA_MODIFIER_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /** Text attributes for memory keywords (`alloc`, `drop`, `unsafe`, `deref`). */
        val MEMORY_KEYWORD = createTextAttributesKey("AZORA_MEMORY_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        /** Text attributes for reactive/UI keywords (`rem`, `effect`, `view`). */
        val REACTIVE_KEYWORD = createTextAttributesKey("AZORA_REACTIVE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        // ── Identifiers ────────────────────────────────────────────────

        /** Text attributes for regular identifiers. */
        val IDENTIFIER = createTextAttributesKey("AZORA_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)

        /** Text attributes for generic type parameters (`T`, `U`, etc.), falling back to Kotlin's type parameter style. */
        val TYPE_PARAMETER = createTextAttributesKey(
            "AZORA_TYPE_PARAMETER",
            TextAttributesKey.find("KOTLIN_TYPE_PARAMETER")
        )

        // ── Literals ───────────────────────────────────────────────────

        /** Text attributes for numeric literals (integers and reals). */
        val NUMBER = createTextAttributesKey("AZORA_NUMBER", DefaultLanguageHighlighterColors.NUMBER)

        /** Text attributes for string and character literals. */
        val STRING = createTextAttributesKey("AZORA_STRING", DefaultLanguageHighlighterColors.STRING)

        // ── Comments ───────────────────────────────────────────────────

        /** Text attributes for single-line comments (`//`). */
        val LINE_COMMENT = createTextAttributesKey("AZORA_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)

        /** Text attributes for block comments. */
        val BLOCK_COMMENT = createTextAttributesKey("AZORA_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)

        /** Text attributes for documentation comments (`///`). */
        val DOC_COMMENT = createTextAttributesKey("AZORA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)

        // ── Operators and punctuation ──────────────────────────────────

        /** Text attributes for operators (`+`, `-`, `==`, etc.). */
        val OPERATOR = createTextAttributesKey("AZORA_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)

        /** Text attributes for decorators/annotations (`@entry`, `@test`). */
        val DECORATOR = createTextAttributesKey("AZORA_DECORATOR", DefaultLanguageHighlighterColors.METADATA)

        /** Text attributes for parentheses `()`. */
        val PAREN = createTextAttributesKey("AZORA_PAREN", DefaultLanguageHighlighterColors.PARENTHESES)

        /** Text attributes for curly braces `{}`. */
        val BRACE = createTextAttributesKey("AZORA_BRACE", DefaultLanguageHighlighterColors.BRACES)

        /** Text attributes for square brackets `[]`. */
        val BRACKET = createTextAttributesKey("AZORA_BRACKET", DefaultLanguageHighlighterColors.BRACKETS)

        /** Text attributes for commas. */
        val COMMA_ATTR = createTextAttributesKey("AZORA_COMMA", DefaultLanguageHighlighterColors.COMMA)

        /** Text attributes for colons. */
        val COLON_ATTR = createTextAttributesKey("AZORA_COLON", DefaultLanguageHighlighterColors.OPERATION_SIGN)

        /** Text attributes for arrows (`->`). */
        val ARROW_ATTR = createTextAttributesKey("AZORA_ARROW", DefaultLanguageHighlighterColors.OPERATION_SIGN)

        /** Text attributes for dots (`.`). */
        val DOT_ATTR = createTextAttributesKey("AZORA_DOT", DefaultLanguageHighlighterColors.DOT)

        /** Text attributes for semicolons. */
        val SEMICOLON_ATTR = createTextAttributesKey("AZORA_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)

        /** Text attributes for unrecognized/bad characters. */
        val BAD_CHAR = createTextAttributesKey("AZORA_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        // ── Key arrays (returned by getTokenHighlights) ────────────────

        /** Highlight keys for general keywords. */
        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        /** Highlight keys for declaration keywords. */
        private val DECLARATION_KEYWORD_KEYS = arrayOf(DECLARATION_KEYWORD)
        /** Highlight keys for control-flow keywords. */
        private val CONTROL_KEYWORD_KEYS = arrayOf(CONTROL_KEYWORD)
        /** Highlight keys for modifier keywords. */
        private val MODIFIER_KEYWORD_KEYS = arrayOf(MODIFIER_KEYWORD)
        /** Highlight keys for memory keywords. */
        private val MEMORY_KEYWORD_KEYS = arrayOf(MEMORY_KEYWORD)
        /** Highlight keys for reactive keywords. */
        private val REACTIVE_KEYWORD_KEYS = arrayOf(REACTIVE_KEYWORD)
        /** Highlight keys for type parameter identifiers. */
        private val TYPE_PARAMETER_KEYS = arrayOf(TYPE_PARAMETER)
        /** Highlight keys for regular identifiers. */
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        /** Highlight keys for numeric literals. */
        private val NUMBER_KEYS = arrayOf(NUMBER)
        /** Highlight keys for string/character literals. */
        private val STRING_KEYS = arrayOf(STRING)
        /** Highlight keys for line comments. */
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        /** Highlight keys for block comments. */
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        /** Highlight keys for doc comments. */
        private val DOC_COMMENT_KEYS = arrayOf(DOC_COMMENT)
        /** Highlight keys for operators. */
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        /** Highlight keys for decorators. */
        private val DECORATOR_KEYS = arrayOf(DECORATOR)
        /** Highlight keys for parentheses. */
        private val PAREN_KEYS = arrayOf(PAREN)
        /** Highlight keys for curly braces. */
        private val BRACE_KEYS = arrayOf(BRACE)
        /** Highlight keys for square brackets. */
        private val BRACKET_KEYS = arrayOf(BRACKET)
        /** Highlight keys for commas. */
        private val COMMA_KEYS = arrayOf(COMMA_ATTR)
        /** Highlight keys for colons. */
        private val COLON_KEYS = arrayOf(COLON_ATTR)
        /** Highlight keys for arrows. */
        private val ARROW_KEYS = arrayOf(ARROW_ATTR)
        /** Highlight keys for dots. */
        private val DOT_KEYS = arrayOf(DOT_ATTR)
        /** Highlight keys for semicolons. */
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON_ATTR)
        /** Highlight keys for bad characters. */
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHAR)
        /** Empty key array for tokens with no highlighting. */
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}

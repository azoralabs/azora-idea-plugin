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

package com.azora.lang.idea

import com.intellij.psi.TokenType
import com.intellij.psi.tree.*

/**
 * A custom [IElementType] scoped to the [AzoraLanguage].
 *
 * Each distinct token produced by the Azora lexer is represented by
 * an instance of this class, allowing IntelliJ to distinguish Azora
 * tokens from those of other languages in the same editor.
 *
 * @param debugName a human-readable name shown in PSI viewers and logs.
 */
class AzoraTokenType(debugName: String) : IElementType(debugName, AzoraLanguage)

/**
 * Central registry of all Azora [IElementType] instances and [TokenSet] groupings.
 *
 * Maps Azora compiler token kinds to IntelliJ IElementTypes for
 * syntax highlighting, parsing, and PSI tree construction.
 *
 * Keywords are split into semantic categories so the syntax highlighter
 * can assign distinct colors to declaration, control-flow, modifier,
 * memory, and reactive keywords.
 */
object AzoraTokenTypes {

    // ── Literals ───────────────────────────────────────────────────────

    /** Integer literal, e.g. `42`, `0xFF`, `0b1010`. */
    @JvmField val INT_LITERAL = AzoraTokenType("INT_LITERAL")

    /** Real (floating-point) literal, e.g. `3.14`, `1e-5`. */
    @JvmField val REAL_LITERAL = AzoraTokenType("REAL_LITERAL")

    /** Double-quoted string literal, e.g. `"hello"`. */
    @JvmField val STRING_LITERAL = AzoraTokenType("STRING_LITERAL")

    /** Single-quoted character literal, e.g. `'a'`. */
    @JvmField val CHAR_LITERAL = AzoraTokenType("CHAR_LITERAL")

    /** Boolean literal: `true` or `false`. */
    @JvmField val BOOL_LITERAL = AzoraTokenType("BOOL_LITERAL")

    // ── Identifier ─────────────────────────────────────────────────────

    /** A user-defined name such as a variable, function, or type name. */
    @JvmField val IDENTIFIER = AzoraTokenType("IDENTIFIER")

    // ── Type parameter ─────────────────────────────────────────────────

    /** A generic type parameter name (e.g. `T`, `U`, `V`) inside angle brackets. */
    @JvmField val TYPE_PARAMETER = AzoraTokenType("TYPE_PARAMETER")

    // ── Keyword categories ─────────────────────────────────────────────

    /** General-purpose keyword fallback (anything not in a more specific category). */
    @JvmField val KEYWORD = AzoraTokenType("KEYWORD")

    /** Declaration keywords: `func`, `pack`, `enum`, `slot`, `view`, `zone`, `impl`, `spec`, etc. */
    @JvmField val DECLARATION_KEYWORD = AzoraTokenType("DECLARATION_KEYWORD")

    /** Control-flow keywords: `if`, `else`, `for`, `while`, `loop`, `when`, `return`, `break`, `continue`, etc. */
    @JvmField val CONTROL_KEYWORD = AzoraTokenType("CONTROL_KEYWORD")

    /** Modifier keywords: `expose`, `confine`, `mut`, `ref`, `inline`, `isolated`, `threadlocal`. */
    @JvmField val MODIFIER_KEYWORD = AzoraTokenType("MODIFIER_KEYWORD")

    /** Memory keywords: `alloc`, `drop`, `unsafe`, `deref`. */
    @JvmField val MEMORY_KEYWORD = AzoraTokenType("MEMORY_KEYWORD")

    /** Reactive/UI keywords: `rem`, `effect`, `view`. */
    @JvmField val REACTIVE_KEYWORD = AzoraTokenType("REACTIVE_KEYWORD")

    // ── Operators ──────────────────────────────────────────────────────

    /** Any operator symbol, e.g. `+`, `-`, `*`, `==`, `&&`. */
    @JvmField val OPERATOR = AzoraTokenType("OPERATOR")

    // ── Delimiters ─────────────────────────────────────────────────────

    /** Left parenthesis `(`. */
    @JvmField val L_PAREN = AzoraTokenType("L_PAREN")

    /** Right parenthesis `)`. */
    @JvmField val R_PAREN = AzoraTokenType("R_PAREN")

    /** Left curly brace `{`. */
    @JvmField val L_BRACE = AzoraTokenType("L_BRACE")

    /** Right curly brace `}`. */
    @JvmField val R_BRACE = AzoraTokenType("R_BRACE")

    /** Left square bracket `[`. */
    @JvmField val L_BRACKET = AzoraTokenType("L_BRACKET")

    /** Right square bracket `]`. */
    @JvmField val R_BRACKET = AzoraTokenType("R_BRACKET")

    /** Comma `,`. */
    @JvmField val COMMA = AzoraTokenType("COMMA")

    /** Colon `:`, used in type annotations and key-value pairs. */
    @JvmField val COLON = AzoraTokenType("COLON")

    /** Dot `.`, used for member access. */
    @JvmField val DOT = AzoraTokenType("DOT")

    /** Arrow `->`, used in function return types and lambda expressions. */
    @JvmField val ARROW = AzoraTokenType("ARROW")

    /** Semicolon `;`, used as a statement terminator. */
    @JvmField val SEMICOLON = AzoraTokenType("SEMICOLON")

    // ── Comments ───────────────────────────────────────────────────────

    /** Single-line comment starting with `//`. */
    @JvmField val LINE_COMMENT = AzoraTokenType("LINE_COMMENT")

    /** Block comment delimited by `/* ... */`. */
    @JvmField val BLOCK_COMMENT = AzoraTokenType("BLOCK_COMMENT")

    /** Documentation comment starting with `///`. */
    @JvmField val DOC_COMMENT = AzoraTokenType("DOC_COMMENT")

    // ── Decorator ──────────────────────────────────────────────────────

    /** A decorator/annotation, e.g. `@entry`, `@test`. */
    @JvmField val DECORATOR = AzoraTokenType("DECORATOR")

    // ── Newline ────────────────────────────────────────────────────────

    /** A newline character, significant in Azora for statement separation. */
    @JvmField val NEWLINE = AzoraTokenType("NEWLINE")

    // ── Synthetic tokens (delegated to IntelliJ platform) ──────────────

    /** Whitespace token, delegated to the IntelliJ platform's built-in whitespace handling. */
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE

    /** Fallback token for characters that the lexer cannot recognize. */
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    // ── Token sets ─────────────────────────────────────────────────────

    /** All comment token types (line, block, and doc). */
    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)

    /** String-like literal tokens (strings and characters). */
    @JvmField val STRINGS = TokenSet.create(STRING_LITERAL, CHAR_LITERAL)

    /** Numeric literal tokens (integers and reals). */
    @JvmField val NUMBERS = TokenSet.create(INT_LITERAL, REAL_LITERAL)
}

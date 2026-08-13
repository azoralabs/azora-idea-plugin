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

package org.azora.lang.idea

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Standalone IntelliJ lexer for Azora source code.
 *
 * Tokenizes source directly without depending on the Azora compiler.
 * Produces a gap-free token stream covering every character in the buffer,
 * suitable for syntax highlighting, brace matching, and other editor features.
 */
class AzoraLexerAdapter : LexerBase() {

    /** The full character buffer being lexed. */
    private var buffer: CharSequence = ""

    /** The inclusive start offset within [buffer] for the current lexing range. */
    private var startOffset = 0

    /** The exclusive end offset within [buffer] for the current lexing range. */
    private var endOffset = 0

    /** The pre-computed list of tokens for the current lexing range. */
    private var tokens: List<LexToken> = emptyList()

    /** Index into [tokens] pointing to the current token. */
    private var tokenIndex = 0

    /**
     * A single lexed token with its [type] and absolute [start]/[end] offsets in the buffer.
     *
     * @param type the IntelliJ element type for this token.
     * @param start the inclusive start offset in the buffer.
     * @param end the exclusive end offset in the buffer.
     */
    private class LexToken(val type: IElementType, val start: Int, val end: Int)

    /**
     * Initializes the lexer with the given buffer range and tokenizes the content.
     *
     * The resulting token list is post-processed by [markTypeParameters] to
     * reclassify generic type parameter identifiers.
     *
     * @param buffer the full source text.
     * @param startOffset the inclusive start of the range to lex.
     * @param endOffset the exclusive end of the range to lex.
     * @param initialState the initial lexer state (unused, always 0).
     */
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenIndex = 0

        val source = buffer.subSequence(startOffset, endOffset).toString()
        tokens = markTypeParameters(tokenize(source))
    }

    /**
     * Returns the current lexer state.
     *
     * Always returns `0` because this lexer is stateless across tokens.
     */
    override fun getState(): Int = 0

    /**
     * Returns the [IElementType] of the current token, or `null` if the token stream is exhausted.
     */
    override fun getTokenType(): IElementType? {
        if (tokenIndex >= tokens.size) return null
        return tokens[tokenIndex].type
    }

    /**
     * Returns the inclusive start offset of the current token in the buffer.
     *
     * If the token stream is exhausted, returns [endOffset].
     */
    override fun getTokenStart(): Int {
        if (tokenIndex >= tokens.size) return endOffset
        return tokens[tokenIndex].start
    }

    /**
     * Returns the exclusive end offset of the current token in the buffer.
     *
     * If the token stream is exhausted, returns [endOffset].
     */
    override fun getTokenEnd(): Int {
        if (tokenIndex >= tokens.size) return endOffset
        return tokens[tokenIndex].end
    }

    /**
     * Advances the lexer to the next token.
     */
    override fun advance() {
        tokenIndex++
    }

    /** Returns the full character buffer being lexed. */
    override fun getBufferSequence(): CharSequence = buffer

    /** Returns the exclusive end offset of the lexing range. */
    override fun getBufferEnd(): Int = endOffset

    // -----------------------------------------------------------------------
    // Tokenizer
    // -----------------------------------------------------------------------

    /**
     * Tokenizes the given [source] string into a list of [LexToken]s.
     *
     * Handles all Azora lexical elements: comments (line, block, doc),
     * whitespace, decorators, string and character literals, numeric literals
     * (including hex, binary, exponent, and type suffixes), identifiers,
     * keywords, and operators/delimiters. Unrecognized characters produce
     * [AzoraTokenTypes.BAD_CHARACTER] tokens.
     *
     * @param source the source text to tokenize (relative to [startOffset]).
     * @return a list of tokens with absolute buffer offsets.
     */
    private fun tokenize(source: String): List<LexToken> {
        val result = mutableListOf<LexToken>()
        var pos = 0
        val len = source.length

        while (pos < len) {
            val ch = source[pos]

            when {
                // Doc comment: /** ... */
                pos + 2 < len && ch == '/' && source[pos + 1] == '*' && source[pos + 2] == '*' -> {
                    val start = pos
                    pos += 3
                    while (pos + 1 < len && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                    if (pos + 1 < len) pos += 2
                    result.add(LexToken(AzoraTokenTypes.DOC_COMMENT, start + startOffset, pos + startOffset))
                }

                // Block comment: /* ... */ (nestable)
                pos + 1 < len && ch == '/' && source[pos + 1] == '*' -> {
                    val start = pos
                    pos += 2
                    var depth = 1
                    while (pos < len && depth > 0) {
                        if (pos + 1 < len && source[pos] == '/' && source[pos + 1] == '*') {
                            depth++; pos += 2
                        } else if (pos + 1 < len && source[pos] == '*' && source[pos + 1] == '/') {
                            depth--; pos += 2
                        } else {
                            pos++
                        }
                    }
                    result.add(LexToken(AzoraTokenTypes.BLOCK_COMMENT, start + startOffset, pos + startOffset))
                }

                // Line comment: //
                pos + 1 < len && ch == '/' && source[pos + 1] == '/' -> {
                    val start = pos
                    pos += 2
                    while (pos < len && source[pos] != '\n') pos++
                    result.add(LexToken(AzoraTokenTypes.LINE_COMMENT, start + startOffset, pos + startOffset))
                }

                // Whitespace
                ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' -> {
                    val start = pos
                    while (pos < len && source[pos].let { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) pos++
                    result.add(LexToken(AzoraTokenTypes.WHITE_SPACE, start + startOffset, pos + startOffset))
                }

                // Decorator: @name
                ch == '@' && pos + 1 < len && (source[pos + 1].isLetter() || source[pos + 1] == '_') -> {
                    val start = pos
                    pos++ // skip @
                    while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
                    result.add(LexToken(AzoraTokenTypes.DECORATOR, start + startOffset, pos + startOffset))
                }

                // String literal: "...", """raw...""", with `$name` / `${…}` holes
                ch == '"' -> pos = scanString(source, pos, len, result)

                // Char literal: '...'
                ch == '\'' -> {
                    val start = pos
                    pos++ // skip opening quote
                    if (pos < len && source[pos] == '\\' && pos + 1 < len) {
                        pos += 2 // escape sequence
                        // Handle \uXXXX
                        if (pos - 1 < len && source[pos - 1] == 'u') {
                            while (pos < len && source[pos].isLetterOrDigit()) pos++
                        }
                    } else if (pos < len && source[pos] != '\'') {
                        pos++ // single char
                    }
                    if (pos < len && source[pos] == '\'') pos++ // closing quote
                    result.add(LexToken(AzoraTokenTypes.CHAR_LITERAL, start + startOffset, pos + startOffset))
                }

                // Number literal
                ch.isDigit() || (ch == '.' && pos + 1 < len && source[pos + 1].isDigit()) -> {
                    val start = pos
                    var isReal = ch == '.'
                    if (ch == '0' && pos + 1 < len && (source[pos + 1] == 'x' || source[pos + 1] == 'X')) {
                        pos += 2 // hex
                        while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
                    } else if (ch == '0' && pos + 1 < len && (source[pos + 1] == 'b' || source[pos + 1] == 'B')) {
                        pos += 2 // binary
                        while (pos < len && (source[pos] == '0' || source[pos] == '1' || source[pos] == '_')) pos++
                    } else {
                        while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                        if (pos < len && source[pos] == '.' && pos + 1 < len && source[pos + 1].isDigit()) {
                            isReal = true
                            pos++ // skip dot
                            while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                        }
                        // Exponent
                        if (pos < len && (source[pos] == 'e' || source[pos] == 'E')) {
                            isReal = true
                            pos++
                            if (pos < len && (source[pos] == '+' || source[pos] == '-')) pos++
                            while (pos < len && source[pos].isDigit()) pos++
                        }
                    }
                    // Canonical Azora suffixes (`b`, `ub`, `s`, `us`, `u`,
                    // `L`, `uL`, `c`, `uc`, `f`, `D`) plus the older i/u/f
                    // width spellings accepted by existing projects.
                    val suffixStart = pos
                    if (pos < len && source[pos] == 'u') {
                        pos++
                        if (pos < len && source[pos].isDigit()) {
                            // Legacy `u8`/`u32`/`u64` spelling.
                            while (pos < len && source[pos].isDigit()) pos++
                        } else if (pos >= len || source[pos].isWhitespace() || source[pos] in "bLc") {
                            // Canonical `ub`, `uL`, and `uc` spellings.
                            if (pos < len && source[pos] in "bLc") pos++
                        } else {
                            pos = suffixStart
                        }
                    } else if (pos < len && source[pos] in "bsLcufDi") {
                        val suffix = source[pos]
                        pos++
                        while (pos < len && source[pos].isDigit()) pos++
                        if (suffix == 'f' || suffix == 'D') isReal = true
                    }
                    val type = if (isReal) AzoraTokenTypes.REAL_LITERAL else AzoraTokenTypes.INT_LITERAL
                    result.add(LexToken(type, start + startOffset, pos + startOffset))
                }

                // Identifier or keyword (`$` is a valid identifier char, e.g. `$index`)
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    val start = pos
                    while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')) pos++
                    val word = source.substring(start, pos)
                    // A macro invocation `name@(…/[…/{…` or the mutable `name!@…`
                    // colors the name as a MACRO (purple).
                    val macroInvoke = source.getOrNull(pos) == '@' &&
                        source.getOrNull(pos + 1).let { it == '(' || it == '[' || it == '{' } ||
                        source.getOrNull(pos) == '!' && source.getOrNull(pos + 1) == '@'
                    val type = if (macroInvoke) AzoraTokenTypes.MACRO else classifyWord(source, start, word)
                    result.add(LexToken(type, start + startOffset, pos + startOffset))
                }

                // Multi-character operators (order matters, longest match first)
                else -> {
                    val start = pos
                    val type = matchOperator(source, pos, len)
                    if (type != null) {
                        pos += type.second
                        result.add(LexToken(type.first, start + startOffset, pos + startOffset))
                    } else {
                        // Single unknown character
                        pos++
                        result.add(LexToken(AzoraTokenTypes.BAD_CHARACTER, start + startOffset, pos + startOffset))
                    }
                }
            }
        }

        return result
    }

    /**
     * Scans a string literal starting at the opening quote in [pos], appending
     * every token it produces to [result], and returns the position just past
     * the literal.
     *
     * A triple-quoted raw string is a single opaque token. An ordinary string
     * is split so escape sequences and interpolation holes can be colored
     * separately: literal runs stay [AzoraTokenTypes.STRING_LITERAL], `\n`-style
     * escapes become [AzoraTokenTypes.STRING_ESCAPE], and a `$name` / `${…}`
     * hole is bracketed by [AzoraTokenTypes.INTERPOLATION_START] /
     * [AzoraTokenTypes.INTERPOLATION_END] with the expression inside tokenized
     * as ordinary code. The emitted tokens are gap-free, as IntelliJ requires.
     */
    private fun scanString(source: String, pos: Int, len: Int, result: MutableList<LexToken>): Int {
        // Raw string: """ … """ — no escapes, no interpolation, may span lines.
        if (source.startsWith("\"\"\"", pos)) {
            var i = pos + 3
            while (i < len && !source.startsWith("\"\"\"", i)) i++
            i = if (i < len) i + 3 else len
            result.add(LexToken(AzoraTokenTypes.RAW_STRING_LITERAL, pos + startOffset, i + startOffset))
            return i
        }

        var i = pos + 1 // past the opening quote
        var literalStart = pos // the opening quote belongs to the first literal run

        /** Emits the pending literal run, if any, ending just before [end]. */
        fun flushLiteral(end: Int) {
            if (end > literalStart) {
                result.add(LexToken(AzoraTokenTypes.STRING_LITERAL, literalStart + startOffset, end + startOffset))
            }
            literalStart = end
        }

        while (i < len) {
            val c = source[i]
            when {
                c == '"' -> {
                    i++
                    flushLiteral(i)
                    return i
                }
                // Azora has no multi-line non-raw strings; stop at the newline so
                // the rest of the file still lexes sanely.
                c == '\n' -> {
                    flushLiteral(i)
                    return i
                }
                c == '\\' && i + 1 < len -> {
                    flushLiteral(i)
                    i += 2
                    result.add(LexToken(AzoraTokenTypes.STRING_ESCAPE, literalStart + startOffset, i + startOffset))
                    literalStart = i
                }
                c == '$' && i + 1 < len && source[i + 1] == '{' -> {
                    flushLiteral(i)
                    result.add(LexToken(AzoraTokenTypes.INTERPOLATION_START, i + startOffset, i + 2 + startOffset))
                    val exprStart = i + 2
                    var depth = 1
                    var j = exprStart
                    while (j < len && depth > 0) {
                        when (source[j]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth == 0) break
                        j++
                    }
                    val exprEnd = j.coerceAtMost(len)
                    appendShifted(tokenize(source.substring(exprStart, exprEnd)), exprStart, result)
                    if (exprEnd < len) {
                        result.add(LexToken(AzoraTokenTypes.INTERPOLATION_END, exprEnd + startOffset, exprEnd + 1 + startOffset))
                        i = exprEnd + 1
                    } else {
                        i = exprEnd
                    }
                    literalStart = i
                }
                c == '$' && i + 1 < len && (source[i + 1].isLetter() || source[i + 1] == '_') -> {
                    flushLiteral(i)
                    result.add(LexToken(AzoraTokenTypes.INTERPOLATION_START, i + startOffset, i + 1 + startOffset))
                    var j = i + 1
                    while (j < len && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    result.add(LexToken(AzoraTokenTypes.IDENTIFIER, i + 1 + startOffset, j + startOffset))
                    i = j
                    literalStart = i
                }
                else -> i++
            }
        }

        flushLiteral(len)
        return len
    }

    /**
     * Appends [tokens] — produced by a nested [tokenize] of a substring that
     * begins at [shift] within the current source — to [result], moving each
     * token forward so its offsets are absolute again.
     */
    private fun appendShifted(tokens: List<LexToken>, shift: Int, result: MutableList<LexToken>) {
        for (t in tokens) result.add(LexToken(t.type, t.start + shift, t.end + shift))
    }

    /**
     * Attempts to match an operator or delimiter at the given position in [source].
     *
     * Tries the longest possible match first (up to 3 characters) before falling
     * back to shorter matches. Returns a pair of the matched [IElementType] and
     * the number of characters consumed, or `null` if no operator matches.
     *
     * @param source the source text being tokenized.
     * @param pos the current position in [source].
     * @param len the length of [source].
     * @return the matched element type and character count, or `null`.
     */
    private fun matchOperator(source: String, pos: Int, len: Int): Pair<IElementType, Int>? {
        val ch = source[pos]
        val next = if (pos + 1 < len) source[pos + 1] else '\u0000'
        val next2 = if (pos + 2 < len) source[pos + 2] else '\u0000'

        return when (ch) {
            '(' -> AzoraTokenTypes.L_PAREN to 1
            ')' -> AzoraTokenTypes.R_PAREN to 1
            '{' -> AzoraTokenTypes.L_BRACE to 1
            '}' -> AzoraTokenTypes.R_BRACE to 1
            '[' -> AzoraTokenTypes.L_BRACKET to 1
            ']' -> AzoraTokenTypes.R_BRACKET to 1
            ',' -> AzoraTokenTypes.COMMA to 1
            ';' -> AzoraTokenTypes.SEMICOLON to 1
            ':' -> if (next == ':') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.COLON to 1
            '.' -> when {
                next == '.' && next2 == '<' -> AzoraTokenTypes.OPERATOR to 3  // ..<
                next == '.' && next2 == '.' -> AzoraTokenTypes.OPERATOR to 3  // ...
                next == '.' -> AzoraTokenTypes.OPERATOR to 2  // ..
                else -> AzoraTokenTypes.DOT to 1
            }
            '-' -> when (next) {
                '>' -> AzoraTokenTypes.ARROW to 2
                '=' -> AzoraTokenTypes.OPERATOR to 2
                '-' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '+' -> when (next) {
                '=' -> AzoraTokenTypes.OPERATOR to 2
                '+' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '*' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '/' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '%' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '=' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '!' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '<' -> when {
                next == '=' && next2 == '>' -> AzoraTokenTypes.OPERATOR to 3
                next == '<' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '<' -> AzoraTokenTypes.OPERATOR to 2
                next == '=' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '>' -> when {
                next == '>' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '>' -> AzoraTokenTypes.OPERATOR to 2
                next == '=' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '&' -> when (next) {
                '&', '=' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '|' -> when (next) {
                '|', '=' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '^' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '~' -> AzoraTokenTypes.OPERATOR to 1
            '?' -> when {
                next == '=' -> AzoraTokenTypes.OPERATOR to 2
                next == '?' -> AzoraTokenTypes.OPERATOR to 2
                next == '+' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '-' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '*' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '/' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '%' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '+' && next2 == '+' -> AzoraTokenTypes.OPERATOR to 3
                next == '-' && next2 == '-' -> AzoraTokenTypes.OPERATOR to 3
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '#' -> {
                // Map key/set member access: #[, #[=, #{, #{=
                when {
                    next == '[' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                    next == '[' -> AzoraTokenTypes.OPERATOR to 2
                    next == '{' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                    next == '{' -> AzoraTokenTypes.OPERATOR to 2
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Post-processes the token list to reclassify identifiers inside generic
     * parameter declarations as [AzoraTokenTypes.TYPE_PARAMETER] tokens.
     *
     * Detects patterns like `func<T>`, `pack<T, U>`, `impl<T>`, etc.
     * and marks the identifiers between `<` and `>` as type parameters.
     * Also marks usages of those type parameter names within the same
     * declaration body in a second pass.
     *
     * @param tokens the raw token list from [tokenize].
     * @return a new token list with type parameter identifiers reclassified.
     */
    private fun markTypeParameters(tokens: List<LexToken>): List<LexToken> {
        val result = tokens.toMutableList()
        data class GenericScope(val names: Set<String>, val start: Int, val end: Int)
        val scopes = mutableListOf<GenericScope>()
        var i = 0
        while (i < result.size) {
            val tok = result[i]
            // Look for keyword followed by < (skipping whitespace)
            if (tok.type == AzoraTokenTypes.DECLARATION_KEYWORD) {
                var j = i + 1
                // Skip whitespace
                while (j < result.size && result[j].type == AzoraTokenTypes.WHITE_SPACE) j++
                // Check for <
                if (j < result.size && isLessThan(result[j])) {
                    val headerStart = j
                    j++ // skip <
                    var depth = 1
                    var expectsName = true
                    val names = linkedSetOf<String>()
                    while (j < result.size && depth > 0) {
                        val t = result[j]
                        when {
                            isLessThan(t) -> {
                                depth++
                                expectsName = true
                            }
                            isGreaterThan(t) -> depth--
                            t.type == AzoraTokenTypes.COMMA && depth == 1 -> expectsName = true
                            t.type == AzoraTokenTypes.IDENTIFIER && depth == 1 && expectsName -> {
                                val name = buffer.subSequence(t.start, t.end).toString()
                                result[j] = LexToken(AzoraTokenTypes.TYPE_PARAMETER, t.start, t.end)
                                names.add(name)
                                expectsName = false
                            }
                        }
                        j++
                    }
                    if (names.isNotEmpty()) {
                        var end = result.lastIndex
                        var body = j
                        while (body < result.size && result[body].type == AzoraTokenTypes.WHITE_SPACE) body++
                        if (body < result.size && result[body].type == AzoraTokenTypes.L_BRACE) {
                            end = matchingBrace(result, body) ?: result.lastIndex
                        }
                        scopes.add(GenericScope(names, headerStart, end))
                    }
                }
            }
            i++
        }

        // Mark only usages inside the declaration that introduced the generic.
        // A file may legitimately reuse `T` in unrelated declarations.
        for (scope in scopes) {
            for (k in scope.start..scope.end.coerceAtMost(result.lastIndex)) {
                val tok = result[k]
                if (tok.type == AzoraTokenTypes.IDENTIFIER) {
                    val name = buffer.subSequence(tok.start, tok.end).toString()
                    if (name in scope.names) result[k] = LexToken(AzoraTokenTypes.TYPE_PARAMETER, tok.start, tok.end)
                }
            }
        }

        return result
    }

    /** Finds the closing brace for a declaration body in the flat token list. */
    private fun matchingBrace(tokens: List<LexToken>, open: Int): Int? {
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].type) {
                AzoraTokenTypes.L_BRACE -> depth++
                AzoraTokenTypes.R_BRACE -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Checks if an operator token is a single `<` character. */
    private fun isLessThan(tok: LexToken): Boolean =
        tok.type == AzoraTokenTypes.OPERATOR && tok.end - tok.start == 1 &&
                buffer.length > tok.start && buffer[tok.start] == '<'

    /** Checks if an operator token is a single `>` character. */
    private fun isGreaterThan(tok: LexToken): Boolean =
        tok.type == AzoraTokenTypes.OPERATOR && tok.end - tok.start == 1 &&
                buffer.length > tok.start && buffer[tok.start] == '>'

    private fun classifyWord(source: String, start: Int, word: String): IElementType {
        if (word in SOFT_KEYWORDS) {
            return contextualKeywordType(source, start, word) ?: AzoraTokenTypes.IDENTIFIER
        }
        return KEYWORD_MAP[word] ?: AzoraTokenTypes.IDENTIFIER
    }

    /**
     * Classifies a soft keyword from its surroundings.
     *
     * Only words whose *keyword-ness* is positional are handled here. Whether a
     * word is a macro is never decided lexically — a name is a macro because
     * some `meta` declaration says so, which only the symbol index knows, so
     * `AzoraSemanticAnnotator` colors those on a later pass.
     */
    private fun contextualKeywordType(source: String, start: Int, word: String): IElementType? {
        val previous = previousWord(source, start)
        if (previous in DECLARATION_NAME_PREFIXES) return null
        return when (word) {
            "where" -> if (isContextualWhere(source, start)) AzoraTokenTypes.CONTROL_KEYWORD else null
            "mod" -> if (isContextualMod(source, start)) AzoraTokenTypes.DECLARATION_KEYWORD else null
            "module" -> if (isContextualModule(source, start)) AzoraTokenTypes.DECLARATION_KEYWORD else null
            "union" -> if (isContextualUnion(source, start)) AzoraTokenTypes.DECLARATION_KEYWORD else null
            "async" -> if (nextWord(source, start + word.length) == "func") AzoraTokenTypes.MODIFIER_KEYWORD else null
            "without" -> if (lineContainsBefore(source, start, "binds")) AzoraTokenTypes.CONTROL_KEYWORD else null
            "derives", "includes", "binds", "requires" ->
                if (isContextualClause(source, start)) AzoraTokenTypes.CONTROL_KEYWORD else null
            else -> null
        }
    }

    /**
     * Returns whether `where` at [start] belongs to a declaration constraint.
     *
     * Azora deliberately keeps `where` contextual: it is a keyword after a
     * callable/type header but remains a valid function, parameter, or binding
     * name everywhere else. Walking back to the declaration head mirrors AZLS
     * and also supports multiline headers without baking in a particular
     * declaration shape.
     */
    private fun isContextualWhere(source: String, start: Int): Boolean {
        var i = start - 1
        var parenDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var sawDeclarationSubject = false

        while (i >= 0) {
            when (val c = source[i]) {
                ' ', '\t', '\r', '\n' -> i--
                ')' -> {
                    parenDepth++
                    i--
                }
                ']' -> {
                    bracketDepth++
                    i--
                }
                '>' -> {
                    angleDepth++
                    i--
                }
                '(' -> {
                    if (parenDepth == 0) return false
                    parenDepth--
                    i--
                }
                '[' -> {
                    if (bracketDepth == 0) return false
                    bracketDepth--
                    i--
                }
                '<' -> {
                    if (angleDepth > 0) angleDepth-- else return false
                    i--
                }
                '{', '}', ';', '=' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) return false
                    i--
                }
                else -> {
                    if (c.isLetterOrDigit() || c == '_' || c == '$') {
                        val end = i + 1
                        while (i >= 0 && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i--
                        if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                            val word = source.substring(i + 1, end)
                            if (word in WHERE_DECLARATION_HEADS) return sawDeclarationSubject
                            sawDeclarationSubject = true
                        }
                    } else {
                        i--
                    }
                }
            }
        }
        return false
    }

    /** `module` is contextual and only starts a module declaration. */
    private fun isContextualModule(source: String, start: Int): Boolean {
        if (nextWord(source, start + "module".length) == null) return false
        val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val prefix = source.substring(lineStart, start).trim()
        return prefix.isEmpty() || prefix.split(Regex("\\s+")).all {
            it in setOf("exposed", "confined", "export")
        }
    }

    /** Legacy SDK sources spell module declarations as `mod path`. */
    private fun isContextualMod(source: String, start: Int): Boolean {
        if (nextWord(source, start + "mod".length) == null) return false
        val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val prefix = source.substring(lineStart, start).trim()
        return prefix.isEmpty() || prefix.split(Regex("\\s+")).all {
            it in setOf("expose", "confined", "export")
        }
    }

    /** `union Name` is a declaration; `value.union()` is not. */
    private fun isContextualUnion(source: String, start: Int): Boolean {
        val previous = previousWord(source, start)
        if (source.substring(0, start).trimEnd().endsWith(".")) return false
        val next = nextWord(source, start + "union".length) ?: return false
        return next.firstOrNull()?.isUpperCase() == true && previous !in setOf(".", "::")
    }

    /** Clause words are keywords only after a declaration header. */
    private fun isContextualClause(source: String, start: Int): Boolean {
        val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val prefix = source.substring(lineStart, start)
        if (prefix.trimEnd().endsWith('.') || prefix.trimEnd().endsWith("::")) return false
        var parenDepth = 0
        var bracketDepth = 0
        for (c in prefix) {
            when (c) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }
        }
        if (parenDepth > 0 || bracketDepth > 0) return false
        return prefix.split(Regex("[^A-Za-z0-9_$]+"))
            .any { it in CLAUSE_DECLARATION_HEADS }
    }

    private fun lineContainsBefore(source: String, start: Int, word: String): Boolean {
        val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        return Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(source.substring(lineStart, start))
    }

    /** Reads the next identifier after [from], or `null` when punctuation intervenes. */
    private fun nextWord(source: String, from: Int): String? {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length || !(source[i].isLetter() || source[i] == '_' || source[i] == '$')) return null
        val begin = i++
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i++
        return source.substring(begin, i)
    }

    private fun previousWord(source: String, start: Int): String? {
        var i = start - 1
        while (i >= 0 && source[i].isWhitespace()) i--
        if (i < 0 || !(source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) return null
        val end = i + 1
        while (i >= 0 && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i--
        return source.substring(i + 1, end)
    }

    companion object {

        /** Keywords that introduce declarations (functions, types, modules, etc.). */
        private val DECLARATION_KEYWORDS = AzoraLanguageFacts.declarationKeywords

        /** Keywords that control execution flow (branching, looping, error handling, concurrency). */
        private val CONTROL_KEYWORDS = AzoraLanguageFacts.controlKeywords

        /** Keywords that modify visibility, mutability, or other declaration properties. */
        private val MODIFIER_KEYWORDS = AzoraLanguageFacts.modifierKeywords

        /** Keywords related to manual memory management and unsafe operations. */
        private val MEMORY_KEYWORDS = AzoraLanguageFacts.memoryKeywords

        /** Keywords for Azora's reactive/UI programming model. */
        private val REACTIVE_KEYWORDS = AzoraLanguageFacts.reactiveKeywords

        /** Literal value keywords: `true`, `false`, `null`. */
        private val LITERAL_KEYWORDS = AzoraLanguageFacts.literalKeywords

        private val SOFT_KEYWORDS = AzoraLanguageFacts.softKeywords

        private val DECLARATION_NAME_PREFIXES = setOf(
            "func", "pack", "enum", "variant", "error", "spec", "realm", "scope",
            "module", "prop", "var", "val", "fin", "let", "typealias", "test",
            "annot", "impl", "union",
        )

        /** Declaration heads that may legally introduce a `where` clause. */
        private val WHERE_DECLARATION_HEADS = setOf(
            "func", "pack", "enum", "solo", "spec", "annot", "impl", "prop",
            "variant", "typealias", "union",
        )

        private val CLAUSE_DECLARATION_HEADS = setOf(
            "pack", "enum", "variant", "spec", "annot", "impl", "func", "typealias",
            "graph", "realm",
        )

        /**
         * Maps every Azora keyword string to its corresponding [IElementType].
         *
         * Built at class-load time from the individual keyword category sets.
         * Used by [tokenize] to classify identifier tokens as keywords.
         */
        private val KEYWORD_MAP: Map<String, IElementType> = buildMap {
            for (kw in DECLARATION_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.DECLARATION_KEYWORD)
            for (kw in CONTROL_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.CONTROL_KEYWORD)
            for (kw in MODIFIER_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.MODIFIER_KEYWORD)
            for (kw in MEMORY_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.MEMORY_KEYWORD)
            for (kw in REACTIVE_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.REACTIVE_KEYWORD)
            for (kw in LITERAL_KEYWORDS - SOFT_KEYWORDS) put(kw, AzoraTokenTypes.KEYWORD)
        }
    }
}

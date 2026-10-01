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
     * @param initialState the initial lexer state; see [getState] for why the
     *   only state a relex can begin in is the top of the file.
     */
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.tokenIndex = 0

        // Lex from the top, so every token is decided with what precedes it in
        // view. A caller that asks to start further in gets the tail of that
        // same pass; only if the two disagree about where a token begins is the
        // range lexed alone, which is what [getState] exists to prevent.
        this.startOffset = 0
        val whole = markTypeParameters(tokenize(buffer.subSequence(0, endOffset).toString()))
        val from = whole.indexOfFirst { it.start >= startOffset }
        tokens = when {
            startOffset == 0 -> whole
            from >= 0 && whole[from].start == startOffset -> whole.subList(from, whole.size)
            else -> {
                this.startOffset = startOffset
                markTypeParameters(tokenize(buffer.subSequence(startOffset, endOffset).toString()))
            }
        }
        this.startOffset = startOffset
    }

    /**
     * Returns the current lexer state - the state a restart at
     * [getTokenStart] would have to begin in.
     *
     * Only the top of the file is a restart point. What a token *is* here
     * depends on what came before it: `binds` is a keyword only after a
     * decorator header, `T` is a type parameter only after the `<T>` that
     * declared it, and the lines of a doc comment are prose only because the
     * comment opened somewhere above them.
     *
     * Reporting state `0` everywhere told the editor that every token boundary
     * was a safe place to start again. It took that at its word after an edit
     * and relexed from the middle of whatever the caret was in - which is why a
     * doc comment's lines came back colored as code, and why the odd keyword
     * mid-line lost its color, until something forced a full reparse.
     */
    override fun getState(): Int = if (getTokenStart() == 0) TOP_OF_FILE else AFTER_CONTEXT

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

        // A `${ … }` splice is a hole in code exactly as it is a hole in a
        // string, so the brace that closes one is not an ordinary brace. Each
        // open splice remembers the brace depth it was opened at; the `}` that
        // returns to that depth is the one that closes it.
        var braceDepth = 0
        val spliceDepths = ArrayDeque<Int>()

        while (pos < len) {
            val ch = source[pos]

            when {
                // Doc comment: /** ... */
                pos + 2 < len && ch == '/' && source[pos + 1] == '*' && source[pos + 2] == '*' -> {
                    val start = pos
                    pos += 3
                    while (pos + 1 < len && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                    if (pos + 1 < len) pos += 2
                    tokenizeDocComment(source, start, pos, result)
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

                // `@` is its own compiler token.  Keeping the sigil separate is
                // essential for qualified decorators (`@std::Serializable`) and
                // current prefix macros (`@std::arr[...]`): their name segments
                // remain real identifiers that semantic resolution can inspect.
                ch == '@' -> {
                    result.add(LexToken(AzoraTokenTypes.DECORATOR, pos + startOffset, pos + 1 + startOffset))
                    pos++
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

                // Number literal. This deliberately mirrors compiler Lexer.scanNumber.
                // A leading-dot spelling such as `.5` is DOT + INT in Azora.
                ch.isDigit() -> {
                    val start = pos
                    var isReal = false
                    var isHex = false
                    if (ch == '0' && pos + 1 < len && (source[pos + 1] == 'x' || source[pos + 1] == 'X')) {
                        pos += 2
                        isHex = true
                        while (pos < len && (source[pos].isDigit() || source[pos] in 'a'..'f' || source[pos] in 'A'..'F' || source[pos] == '_')) pos++
                    } else if (ch == '0' && pos + 1 < len && (source[pos + 1] == 'o' || source[pos + 1] == 'O')) {
                        pos += 2
                        while (pos < len && (source[pos] in '0'..'7' || source[pos] == '_')) pos++
                    } else if (ch == '0' && pos + 1 < len && (source[pos + 1] == 'b' || source[pos + 1] == 'B')) {
                        pos += 2
                        while (pos < len && (source[pos] == '0' || source[pos] == '1' || source[pos] == '_')) pos++
                    } else {
                        while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++

                        val previousCodeToken = result.lastOrNull {
                            it.type != AzoraTokenTypes.WHITE_SPACE && !AzoraTokenTypes.COMMENTS.contains(it.type)
                        }
                        val afterMemberAccess = previousCodeToken?.type == AzoraTokenTypes.DOT
                        if (!afterMemberAccess && pos < len && source[pos] == '.' && pos + 1 < len && source[pos + 1].isDigit()) {
                            isReal = true
                            pos++
                            while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                        } else if (!afterMemberAccess && pos < len && source[pos] == '.' &&
                            (pos + 1 >= len || (source[pos + 1] != '.' && !source[pos + 1].isLetter() && source[pos + 1] != '_'))
                        ) {
                            isReal = true
                            pos++
                        }

                        if (!afterMemberAccess && pos < len && (source[pos] == 'e' || source[pos] == 'E')) {
                            isReal = true
                            pos++
                            if (pos < len && (source[pos] == '+' || source[pos] == '-')) pos++
                            while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                        }
                    }

                    // Canonical suffixes, in the compiler's maximal-munch order.
                    if (pos < len && source[pos] == 'u') {
                        val following = source.getOrNull(pos + 1)
                        when {
                            following == 's' -> pos += 2
                            following == 'L' -> pos += 2
                            !isHex && following == 'c' -> pos += 2
                            !isHex && following == 'b' -> pos += 2
                            following == null || !following.isLetterOrDigit() -> pos++
                        }
                    } else if (pos < len && source[pos] in "sLD" ||
                        (!isHex && pos < len && source[pos] in "bcf")
                    ) {
                        if (source[pos] == 'f' || source[pos] == 'D') isReal = true
                        pos++
                    }

                    val type = if (isReal) AzoraTokenTypes.REAL_LITERAL else AzoraTokenTypes.INT_LITERAL
                    result.add(LexToken(type, start + startOffset, pos + startOffset))
                }

                // A `${ … }` splice outside a string — a name spliced into a
                // declaration (`to${T.typeName}`), a value folded at compile
                // time (`= ${_ranks[index]}`), a macro pattern hole. The `${`
                // and its `}` are the same delimiters an interpolated string
                // uses, and read the same; what is between them is code.
                ch == '$' && pos + 1 < len && source[pos + 1] == '{' -> {
                    result.add(LexToken(AzoraTokenTypes.INTERPOLATION_START, pos + startOffset, pos + 2 + startOffset))
                    spliceDepths.addLast(braceDepth)
                    braceDepth++
                    pos += 2
                }

                // Identifier or keyword (`$` is a valid identifier char, e.g. `$index`)
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    val start = pos
                    while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')) {
                        // `to${…}` is a name and a splice, not one long word.
                        if (source[pos] == '$' && pos + 1 < len && source[pos + 1] == '{' && pos > start) break
                        // `oper$op` is the keyword and the name spliced after
                        // it. A keyword does not stop being one because
                        // something is written against it.
                        if (source[pos] == '$' && pos > start &&
                            source.substring(start, pos) in AzoraLanguageFacts.allCompletionKeywords
                        ) break
                        pos++
                    }
                    val word = source.substring(start, pos)
                    result.add(LexToken(classifyWord(source, start, word), start + startOffset, pos + startOffset))
                }

                // Multi-character operators (order matters, longest match first)
                else -> {
                    val start = pos
                    val type = matchOperator(source, pos, len)
                    if (type != null) {
                        pos += type.second
                        val closesSplice = type.first == AzoraTokenTypes.R_BRACE &&
                            spliceDepths.isNotEmpty() && braceDepth - 1 == spliceDepths.last()
                        when (type.first) {
                            AzoraTokenTypes.L_BRACE -> braceDepth++
                            AzoraTokenTypes.R_BRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        }
                        if (closesSplice) {
                            spliceDepths.removeLast()
                            result.add(LexToken(AzoraTokenTypes.INTERPOLATION_END, start + startOffset, pos + startOffset))
                        } else {
                            result.add(LexToken(type.first, start + startOffset, pos + startOffset))
                        }
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
     * Splits `/** … */` between [start] and [end] into prose, tags, and the
     * names those tags document.
     *
     * A doc comment is one lexical thing but three things to read: the sentence,
     * the `@param` that introduces a clause of it, and the `capacity` that says
     * which parameter the clause is about. Only [DOC_NAMING_TAGS] take a name -
     * `@return` and `@file` are followed by prose, and coloring its first word
     * differently would only mislead.
     *
     * Everything not recognized stays [AzoraTokenTypes.DOC_COMMENT], so the runs
     * always tile the comment exactly and nothing can fall through a gap.
     */
    private fun tokenizeDocComment(source: String, start: Int, end: Int, result: MutableList<LexToken>) {
        var run = start
        var i = start

        fun flush(upTo: Int) {
            if (upTo > run) result.add(LexToken(AzoraTokenTypes.DOC_COMMENT, run + startOffset, upTo + startOffset))
            run = upTo
        }

        while (i < end) {
            // A tag opens a clause, so it only counts at the start of one: after
            // the `*` that opens a line, never inside a word or an email address.
            if (source[i] != '@' || (i > start && !source[i - 1].isWhitespace() && source[i - 1] != '*')) {
                i++
                continue
            }
            var j = i + 1
            while (j < end && source[j].isLetter()) j++
            val tag = source.substring(i + 1, j)
            if (tag.isEmpty()) { i++; continue }
            flush(i)
            result.add(LexToken(AzoraTokenTypes.DOC_TAG, i + startOffset, j + startOffset))
            run = j
            i = j
            if (tag !in DOC_NAMING_TAGS) continue
            // The documented name, if one follows on the same line.
            var k = j
            while (k < end && (source[k] == ' ' || source[k] == '\t')) k++
            var m = k
            while (m < end && (source[m].isLetterOrDigit() || source[m] == '_')) m++
            if (m > k) {
                flush(k)
                result.add(LexToken(AzoraTokenTypes.DOC_TAG_VALUE, k + startOffset, m + startOffset))
                run = m
                i = m
            }
        }
        flush(end)
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
            '=' -> when (next) {
                '=' -> AzoraTokenTypes.OPERATOR to 2
                '>' -> AzoraTokenTypes.ARROW to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '!' -> if (next == '=') AzoraTokenTypes.OPERATOR to 2 else AzoraTokenTypes.OPERATOR to 1
            '<' -> when {
                next == '=' && next2 == '>' -> AzoraTokenTypes.OPERATOR to 3
                next == '<' && next2 == '=' -> AzoraTokenTypes.OPERATOR to 3
                next == '>' -> AzoraTokenTypes.OPERATOR to 2
                next == '<' -> AzoraTokenTypes.OPERATOR to 2
                next == '=' -> AzoraTokenTypes.OPERATOR to 2
                else -> AzoraTokenTypes.OPERATOR to 1
            }
            '>' -> when {
                next == '.' && next2 == '.' -> AzoraTokenTypes.OPERATOR to 3
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
                next == '.' -> AzoraTokenTypes.OPERATOR to 2
                next == '=' -> AzoraTokenTypes.OPERATOR to 2
                next == '!' -> AzoraTokenTypes.OPERATOR to 2
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
            else -> null
        }
    }

    /**
     * Post-processes the token list to reclassify identifiers inside generic
     * parameter declarations as [AzoraTokenTypes.TYPE_PARAMETER] tokens.
     *
     * Detects the current grammar (`func name<T>`, `pack Name<T>`, etc.).
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
            val isImpl = tok.type == AzoraTokenTypes.DECLARATION_KEYWORD && tokenText(tok) == "impl"
            if (tok.type == AzoraTokenTypes.DECLARATION_KEYWORD &&
                (isImpl || tokenText(tok) in GENERIC_DECLARATION_HEADS)
            ) {
                // Current Azora puts generic parameters after the declared name.
                // An `impl` header carries them on whichever name it implements
                // for - `impl Deque<T>` and `impl Clone for Shared<T>` alike -
                // so the header's first `<` is the one that opens them.
                val genericStart = if (isImpl) implGenericStart(result, i) else {
                    val nameIndex = nextSignificant(result, i)
                    if (nameIndex == null || result[nameIndex].type != AzoraTokenTypes.IDENTIFIER) null
                    else nextSignificant(result, nameIndex)
                }
                if (genericStart == null) {
                    i++
                    continue
                }
                var j = genericStart
                if (isLessThan(result[j])) {
                    val headerStart = j
                    j++ // skip <
                    var depth = 1
                    var expectsName = true
                    val names = linkedSetOf<String>()
                    while (j < result.size && depth > 0) {
                        val t = result[j]
                        when {
                            isLessThan(t) -> depth++
                            isGreaterThan(t) -> depth--
                            isShiftRight(t) -> depth -= 2
                            t.type == AzoraTokenTypes.COMMA && depth == 1 -> expectsName = true
                            t.type == AzoraTokenTypes.IDENTIFIER && depth == 1 && expectsName -> {
                                val name = tokenText(t)
                                result[j] = LexToken(AzoraTokenTypes.TYPE_PARAMETER, t.start, t.end)
                                names.add(name)
                                expectsName = false
                            }
                        }
                        j++
                    }
                    if (names.isNotEmpty()) {
                        val end = declarationScopeEnd(result, j)
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
                    val name = tokenText(tok)
                    if (name in scope.names) result[k] = LexToken(AzoraTokenTypes.TYPE_PARAMETER, tok.start, tok.end)
                }
            }
        }

        return result
    }

    /** The `<` that opens an `impl` header's generic parameters, if it has any. */
    private fun implGenericStart(tokens: List<LexToken>, head: Int): Int? {
        var i = head + 1
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token.type == AzoraTokenTypes.WHITE_SPACE && tokenText(token).contains('\n') -> return null
                token.type == AzoraTokenTypes.L_BRACE -> return null
                tokenText(token) == "where" -> return null
                isLessThan(token) -> return i
            }
            i++
        }
        return null
    }

    /** Finds the body closing brace, or the physical-line end for a bodyless declaration. */
    private fun declarationScopeEnd(tokens: List<LexToken>, from: Int): Int {
        var parens = 0
        var brackets = 0
        var angles = 0
        var index = from
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.type == AzoraTokenTypes.L_PAREN -> parens++
                token.type == AzoraTokenTypes.R_PAREN -> parens = (parens - 1).coerceAtLeast(0)
                token.type == AzoraTokenTypes.L_BRACKET -> brackets++
                token.type == AzoraTokenTypes.R_BRACKET -> brackets = (brackets - 1).coerceAtLeast(0)
                isLessThan(token) -> angles++
                isGreaterThan(token) -> angles = (angles - 1).coerceAtLeast(0)
                isShiftRight(token) -> angles = (angles - 2).coerceAtLeast(0)
                token.type == AzoraTokenTypes.L_BRACE && parens == 0 && brackets == 0 && angles == 0 ->
                    return matchingBrace(tokens, index) ?: tokens.lastIndex
                token.type == AzoraTokenTypes.SEMICOLON && parens == 0 && brackets == 0 && angles == 0 ->
                    return index
                token.type == AzoraTokenTypes.WHITE_SPACE && tokenText(token).contains('\n') &&
                    parens == 0 && brackets == 0 && angles == 0 -> {
                    // A body or `where` may begin on the next line. Continue only
                    // when the next word is one of those declaration continuations.
                    val next = nextSignificant(tokens, index)
                    val nextText = next?.let { tokenText(tokens[it]) }
                    if (nextText !in setOf("where", "{")) return (index - 1).coerceAtLeast(from)
                }
            }
            index++
        }
        return tokens.lastIndex
    }

    private fun nextSignificant(tokens: List<LexToken>, from: Int): Int? {
        var index = from + 1
        while (index < tokens.size && AzoraTokenTypes.IGNORABLE.contains(tokens[index].type)) index++
        return index.takeIf { it < tokens.size }
    }

    private fun tokenText(token: LexToken): String =
        buffer.subSequence(token.start.coerceAtLeast(0), token.end.coerceAtMost(buffer.length)).toString()

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

    /** The lexer applies maximal munch, so nested generic closes may be one `>>` token. */
    private fun isShiftRight(tok: LexToken): Boolean =
        tok.type == AzoraTokenTypes.OPERATOR && tokenText(tok) == ">>"

    /**
     * A word is the keyword it is spelled, unless only a name can stand there.
     *
     * There is no third answer. The compiler's lexer is `keywords[text] ?:
     * IDENTIFIER` and nothing else, so an editor that asked *where* a word sits
     * before calling it a keyword was answering a question the language does
     * not ask - and got it wrong wherever its idea of the shape was narrower
     * than the grammar, which is what left `derives` uncolored on a pack whose
     * header it did not recognise.
     *
     * [isNamePosition] stays: it is the same allowance the parser makes with
     * `consumeIdentifierLike`, at the two places a keyword-spelled name is
     * legal - after a member separator, and where a declaration head can be
     * followed by nothing but a name.
     */
    private fun classifyWord(source: String, start: Int, word: String): IElementType {
        if (word == "then") {
            var i = start - 1
            while (i >= 0 && source[i].isWhitespace()) i--
            val afterDot = i >= 0 && source[i] == '.' && (i == 0 || source[i - 1] != '.')
            val afterScope = i >= 1 && source[i] == ':' && source[i - 1] == ':'
            if (afterDot || afterScope) return AzoraTokenTypes.IDENTIFIER
        }
        if (word in NAME_CAPABLE_KEYWORDS && isNamePosition(source, start, word)) {
            return AzoraTokenTypes.IDENTIFIER
        }
        return KEYWORD_MAP[word] ?: AzoraTokenTypes.IDENTIFIER
    }

    /**
     * Whether the word at [start] sits where the grammar can only accept a name,
     * which makes it a name whatever it is spelled.
     *
     * `module std.error` names a module whose last segment happens to be spelled
     * like the `error` declaration keyword, `cursor.take()` calls a method
     * spelled like the `take` memory keyword, and `func take[…]` declares one.
     * In each the word cannot be the keyword, because a keyword cannot follow a
     * member separator or a declaration head - so it is the name it looks like.
     *
     * This is the only keyword decision the lexer makes from position alone; it
     * is lexical, not semantic, because it depends on the two tokens around the
     * word and on nothing the project declares.
     */
    private fun isNamePosition(source: String, start: Int, word: String): Boolean {
        var i = start - 1
        while (i >= 0 && (source[i] == ' ' || source[i] == '\t')) i--
        // `..` and `...` are range and spread operators, not member separators.
        if (i >= 0 && source[i] == '.') return i < 1 || source[i - 1] != '.'
        if (i >= 1 && source[i] == ':' && source[i - 1] == ':') return true

        // A declaration head only reaches the name on its own line: the word
        // opening the next line is a declaration of its own, not this one's
        // name. And a head reaches *past* any word that is itself part of a
        // head, so `variant error Foo` declares `Foo` and keeps `error`.
        if (i < 0 || source[i] == '\n' || source[i] == '\r') return false
        if (word in DECLARATION_HEAD_WORDS) return false
        return previousWord(source, start) in DECLARATION_NAME_PREFIXES
    }

    /** Whether a `(`/`[` at [start] begins a callable type ending in `->`. */
    private fun isCallableTypeAt(source: String, start: Int): Boolean {
        val open = source.getOrNull(start) ?: return false
        if (open != '(' && open != '[') return false
        val close = if (open == '(') ')' else ']'
        var depth = 0
        var index = start
        while (index < source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) break
                }
                '\n' -> if (depth == 0) return false
            }
            index++
        }
        if (index >= source.length) return false
        index = skipWhitespace(source, index + 1)
        if (open == '[' && source.getOrNull(index) == '(') {
            return isCallableTypeAt(source, index)
        }
        return source.startsWith("->", index)
    }

    /**
     * The declaration head a word at [start] continues.
     *
     * Normally that is what precedes the word on its own line. A long
     * declaration puts its clause underneath itself instead, so when nothing
     * precedes the word the head is the line above it - the last one that
     * carries anything.
     */
    private fun headPrefix(source: String, start: Int): String {
        var lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        if (source.substring(lineStart, start).isNotBlank()) return source.substring(lineStart, start)
        while (lineStart > 0) {
            val aboveEnd = lineStart - 1
            val aboveStart = source.lastIndexOf('\n', aboveEnd - 1).let { if (it < 0) 0 else it + 1 }
            val above = source.substring(aboveStart, aboveEnd)
            if (above.isNotBlank()) return above.trimEnd()
            lineStart = aboveStart
        }
        return ""
    }

    private fun linePrefix(source: String, start: Int): String {
        val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        return source.substring(lineStart, start)
    }

    private fun isAfterMemberSeparator(source: String, start: Int): Boolean {
        val prefix = source.substring(0, start).trimEnd()
        return prefix.endsWith('.') || prefix.endsWith("::")
    }

    private fun skipWhitespace(source: String, from: Int): Int {
        var index = from
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }

    private fun skipIdentifier(source: String, from: Int): Int {
        var index = from
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')) index++
        return index
    }

    private fun isIdentifierStart(char: Char): Boolean = char.isLetter() || char == '_' || char == '$'

    private fun currentWordLength(source: String, start: Int): Int = skipIdentifier(source, start) - start

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

        /** The one state a relex may start in: the top of the file. */
        private const val TOP_OF_FILE = 0

        /** Anywhere else - a token whose meaning depends on what came before it. */
        private const val AFTER_CONTEXT = 1

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

        /** The compiler's own primitives: `__int`, `__uint`, `__float`. */
        private val PRIMITIVE_WORDS = AzoraLanguageFacts.primitiveWords

        /** The keywords the parser also admits as a name - see [classifyWord]. */
        private val NAME_CAPABLE_KEYWORDS = AzoraLanguageFacts.nameCapableKeywords

        /** Doc tags whose first word names a declaration - see [tokenizeDocComment]. */
        private val DOC_NAMING_TAGS = AzoraLanguageFacts.docNamingTags

        private val DECLARATION_NAME_PREFIXES = setOf(
            "func", "pack", "enum", "variant", "error", "spec", "scope",
            "module", "prop", "var", "val", "fin", "let", "typealias", "test",
            "annot", "impl", "union", "graph", "oper", "bind",
        )

        /**
         * Words a declaration head can be followed by and still not have reached
         * its name: another head (`variant error Foo`) or a modifier
         * (`exposed inline func f`). Everything else after a head is the name.
         */
        private val DECLARATION_HEAD_WORDS =
            DECLARATION_NAME_PREFIXES + AzoraLanguageFacts.modifierKeywords

        /**
         * The declaration a `derives` clause may follow.
         *
         * A pack, named, optionally generic, and optionally saying which
         * literal it is written as - `bridge pack Int<N: __uint = 32>(__int)`
         * is all three at once.
         */
        private val DERIVES_DECLARATION = Regex(
            """(?:^|\s)pack\s+[A-Za-z_$][\w$]*""" +
                """(?:\s*<[^>{}\n]*>)?(?:\s*\([^)\n]*\))?\s*$""",
        )

        /** Declaration heads that may legally introduce a `where` clause. */
        private val WHERE_DECLARATION_HEADS = setOf(
            "func", "pack", "enum", "solo", "spec", "annot", "impl", "prop",
            "variant", "typealias", "union",
        )

        /** Declarations whose own type parameters follow their declared name. */
        private val GENERIC_DECLARATION_HEADS = setOf(
            "func", "pack", "enum", "spec", "typealias", "prop", "error", "union",
        )

        /**
         * Maps every Azora keyword string to its corresponding [IElementType].
         *
         * Built at class-load time from the individual keyword category sets.
         * Used by [tokenize] to classify identifier tokens as keywords.
         */
        private val KEYWORD_MAP: Map<String, IElementType> = buildMap {
            for (kw in DECLARATION_KEYWORDS) put(kw, AzoraTokenTypes.DECLARATION_KEYWORD)
            for (kw in CONTROL_KEYWORDS) put(kw, AzoraTokenTypes.CONTROL_KEYWORD)
            for (kw in MODIFIER_KEYWORDS) put(kw, AzoraTokenTypes.MODIFIER_KEYWORD)
            for (kw in MEMORY_KEYWORDS) put(kw, AzoraTokenTypes.MEMORY_KEYWORD)
            for (kw in REACTIVE_KEYWORDS) put(kw, AzoraTokenTypes.REACTIVE_KEYWORD)
            for (kw in LITERAL_KEYWORDS) put(kw, AzoraTokenTypes.KEYWORD)
            // `__int`, `__uint`, `__float` are words of the language, reserved
            // by the `__` nothing else may be spelled with. They name what no
            // declaration can, so they read as keywords and not as the types
            // written on top of them.
            for (kw in PRIMITIVE_WORDS) put(kw, AzoraTokenTypes.KEYWORD)
        }
    }
}

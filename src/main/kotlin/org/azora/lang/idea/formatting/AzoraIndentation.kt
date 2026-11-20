/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.tree.IElementType
import org.azora.lang.idea.AzoraLexerAdapter
import org.azora.lang.idea.AzoraTokenTypes as Tokens

/** Shared indentation for Enter and Reformat Code, based on the editor lexer. */
internal class AzoraIndentation(
    source: CharSequence,
    options: CommonCodeStyleSettings.IndentOptions,
    lastLine: Int? = null,
) {
    private val starts = buildList {
        add(0)
        var index = 0
        while (index < source.length && (lastLine == null || size - 1 <= lastLine)) {
            if (source[index++] == '\n') add(index)
        }
    }
    private val widths = IntArray(starts.size)
    private val protected = BooleanArray(starts.size)
    private val indent = options.INDENT_SIZE.coerceAtLeast(1)
    private val continuation = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(1)

    init {
        val code = Array(starts.size) { mutableListOf<Token>() }
        val lexer = AzoraLexerAdapter()
        val end = lastLine?.let { starts.getOrNull(it + 1) } ?: source.length
        lexer.start(source, 0, end, 0)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            val line = lineAt(lexer.tokenStart)
            val text = source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            if (type in Tokens.COMMENTS || type in Tokens.STRINGS) {
                for (inside in line + 1..lineAt(lexer.tokenEnd - 1)) protected[inside] = true
                // Enter at EOF while a multiline literal/comment is still open.
                if (text.endsWith('\n') && type != Tokens.LINE_COMMENT) protected[lineAt(lexer.tokenEnd)] = true
            }
            if (type !in Tokens.IGNORABLE) code[line].add(Token(type, text))
            lexer.advance()
        }

        val brackets = ArrayDeque<Bracket>()
        var continued = false
        var previousExpressionWidth: Int? = null
        var previousChainWidth: Int? = null
        for ((line, tokens) in code.withIndex()) {
            var leadingCloser: Bracket? = null
            var firstCode = 0
            while (firstCode < tokens.size && tokens[firstCode].type == brackets.lastOrNull()?.close) {
                leadingCloser = brackets.removeLast()
                firstCode++
            }
            val bodyWidth = brackets.lastOrNull()?.bodyWidth ?: 0
            val chainWidth = previousExpressionWidth?.takeIf {
                leadingCloser == null && !continued && startsMemberChain(code, line)
            }?.let { previousChainWidth ?: it + continuation }
            val width = leadingCloser?.lineWidth ?: chainWidth ?: (bodyWidth + if (continued) continuation else 0)
            widths[line] = width
            if (tokens.isEmpty()) continue

            val originalDepth = brackets.size
            var lastCloser = leadingCloser
            for (token in tokens.drop(firstCode)) {
                val close = CLOSER_BY_OPENER[token.type]
                if (close != null && (token.type != Tokens.INTERPOLATION_START || token.text.length == 2)) {
                    val step = if (token.type == Tokens.L_BRACE) indent else continuation
                    brackets.addLast(Bracket(close, width, width + step, chainWidth))
                } else if (token.type == brackets.lastOrNull()?.close) {
                    lastCloser = brackets.removeLast()
                }
            }
            val close = brackets.lastOrNull()?.close
            continued = brackets.size <= originalDepth && continues(tokens, close == Tokens.R_PAREN || close == Tokens.R_BRACKET)
            val last = tokens.last()
            previousExpressionWidth = width.takeIf {
                last.type in Tokens.OPERAND_ENDERS || last.type == Tokens.R_BRACE ||
                    last.type == Tokens.INTERPOLATION_END || last.text == "null"
            }
            previousChainWidth = chainWidth ?: lastCloser?.takeIf { leadingCloser != null }?.chainWidth
        }
    }

    fun widthAtLine(line: Int): Int? = widths[line].takeUnless { protected[line] }

    fun widthAtOffset(offset: Int): Int? = widthAtLine(lineAt(offset))

    private fun lineAt(offset: Int): Int {
        val index = starts.binarySearch(offset)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }

    private fun continues(tokens: List<Token>, inSeparatedList: Boolean): Boolean {
        val last = tokens.last()
        if (last.text == "&" && tokens.getOrNull(tokens.lastIndex - 1)?.type == Tokens.DOT) return false
        return when (last.type) {
            Tokens.ARROW, Tokens.DOT, Tokens.COLON -> true
            Tokens.COMMA -> !inSeparatedList
            Tokens.CONTROL_KEYWORD -> last.text in CLAUSES
            Tokens.DECLARATION_KEYWORD -> last.text == "scope"
            Tokens.OPERATOR -> last.text in OPERATORS
            else -> false
        }
    }

    /** A leading selector continues the preceding expression. A dotted when pattern
     * has its own arrow and stays at the arm's indentation. */
    private fun startsMemberChain(lines: Array<out List<Token>>, line: Int): Boolean {
        val tokens = lines[line]
        if (tokens.firstOrNull()?.type != Tokens.DOT || tokens.getOrNull(1)?.text in BORROW_SIGILS) return false

        // A variant payload may span multiple lines, so look for the arm's
        // arrow at the pattern's own depth, beyond any payload delimiters.
        var depth = 0
        for (index in line..lines.lastIndex) {
            for (token in lines[index]) {
                when (token.type) {
                    Tokens.L_PAREN, Tokens.L_BRACKET, Tokens.L_BRACE -> depth++
                    Tokens.R_PAREN, Tokens.R_BRACKET, Tokens.R_BRACE -> depth--
                    Tokens.ARROW -> if (depth == 0) return false
                }
                if (depth < 0) return true
            }
            if (depth == 0) return true
        }
        return true
    }

    private data class Token(val type: IElementType, val text: String)
    private data class Bracket(
        val close: IElementType,
        val lineWidth: Int,
        val bodyWidth: Int,
        val chainWidth: Int?,
    )

    private companion object {
        val CLOSER_BY_OPENER = mapOf(
            Tokens.L_BRACE to Tokens.R_BRACE,
            Tokens.L_PAREN to Tokens.R_PAREN,
            Tokens.L_BRACKET to Tokens.R_BRACKET,
            Tokens.INTERPOLATION_START to Tokens.INTERPOLATION_END,
        )
        val BORROW_SIGILS = setOf("&", "!")
        val CLAUSES = setOf("then", "where", "derives", "includes", "binds", "requires")
        val OPERATORS = setOf(
            "=", "+", "-", "*", "/", "%", "&&", "||", "??", "&", "|", "^", "::", "?.",
            "==", "!=", "<=", ">=", "<=>", "<<", ">>", "+=", "-=", "*=", "/=", "%=",
            "?=", "?+=", "?-=", "?*=", "?/=", "?%=",
        )
    }
}

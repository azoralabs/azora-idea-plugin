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

package org.azora.lang.idea.hints

import org.azora.lang.idea.AzoraFile
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Shows a type the source leaves out, as an inline hint where it would have
 * been written:
 *
 * ```
 * fin origin: Point = Point(x: 0.0, y: 0.0)
 *           ^^^^^^^ shown by the IDE, not present in the source
 * ```
 *
 * Three kinds of omission get one: a binding that infers from its initializer,
 * a loop variable that takes its type from what it walks, and a `func` with no
 * declared result, which returns `Unit`.
 *
 * The hint is off by default and is toggled under
 * **Settings | Editor | Inlay Hints | Azora**, so it never gets in the way of
 * someone who prefers the source as written. A type is only shown when it can
 * be determined with confidence — a literal, a constructor call, an allocation,
 * a comparison, or a call to a function whose return type is known — so a hint
 * is never a guess.
 */
class AzoraTypeHintProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is AzoraFile) AzoraTypeHintCollector() else null
}

/** Emits one hint per omitted type. */
private class AzoraTypeHintCollector : SharedBypassCollector {

    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        if (element.node?.elementType != AzoraTokenTypes.IDENTIFIER) return
        val keyword = previousMeaningful(element) ?: return
        val keywordType = keyword.node?.elementType

        when {
            keywordType == AzoraTokenTypes.CONTROL_KEYWORD && keyword.text == LOOP_KEYWORD ->
                loopVariableHint(element, sink)
            keywordType in KEYWORD_TYPES && keyword.text == FUNCTION_KEYWORD ->
                functionResultHint(element, sink)
            keywordType in KEYWORD_TYPES && keyword.text in BINDING_KEYWORDS ->
                bindingHint(element, sink)
        }
    }

    /** `fin newCap = capacity * 2` - the type its initializer produces. */
    private fun bindingHint(name: PsiElement, sink: InlayTreeSink) {
        val next = nextMeaningful(name) ?: return
        // An explicit annotation means there is nothing to infer.
        if (next.node?.elementType == AzoraTokenTypes.COLON) return
        if (next.node?.elementType != AzoraTokenTypes.OPERATOR || next.text != "=") return
        val start = nextMeaningful(next) ?: return
        emit(name, expressionType(start) ?: return, sink)
    }

    /**
     * `for i in 0..<size` - the type of what the loop walks.
     *
     * Only a range is read: its bounds are values whose type is right there.
     * What a collection yields is its element type, which is a question for the
     * type checker rather than for a token scan, so those get no hint instead
     * of a guessed one.
     */
    private fun loopVariableHint(name: PsiElement, sink: InlayTreeSink) {
        val inKeyword = nextMeaningful(name) ?: return
        if (inKeyword.text != IN_KEYWORD) return
        val start = nextMeaningful(inKeyword) ?: return
        if (!startsARange(start)) return
        emit(name, expressionType(start) ?: return, sink)
    }

    /**
     * `func grow[self!]()` - a callable that declares no result returns `Unit`.
     *
     * The hint goes after the parameter list, where the `: Unit` would be.
     */
    private fun functionResultHint(name: PsiElement, sink: InlayTreeSink) {
        var cursor = nextMeaningful(name) ?: return
        if (cursor.node?.elementType == AzoraTokenTypes.OPERATOR && cursor.text == "<") {
            cursor = skipGenerics(cursor) ?: return
        }
        if (cursor.node?.elementType == AzoraTokenTypes.L_BRACKET) {
            cursor = skipReceiver(cursor) ?: return
        }
        if (cursor.node?.elementType != AzoraTokenTypes.L_PAREN) return
        val close = matchingParen(cursor) ?: return
        // A declared result, a failable one, or a `where` clause all mean the
        // signature goes on; only a body or the end of the line means `Unit`.
        val after = nextMeaningful(close) ?: return
        if (after.node?.elementType == AzoraTokenTypes.COLON) return
        if (after.node?.elementType == AzoraTokenTypes.OPERATOR && after.text.startsWith("?")) return
        sink.addPresentation(
            InlineInlayPosition(close.textRange.endOffset, relatedToPrevious = true),
            hasBackground = false,
        ) {
            text(": $UNIT_TYPE")
        }
    }

    private fun emit(anchor: PsiElement, type: String, sink: InlayTreeSink) {
        sink.addPresentation(
            InlineInlayPosition(anchor.textRange.endOffset, relatedToPrevious = true),
            hasBackground = false,
        ) {
            text(": $type")
        }
    }

    // ── Inference ──────────────────────────────────────────────────────

    /**
     * The type of the expression beginning at [first].
     *
     * Azora does not widen implicitly, so an arithmetic expression has the type
     * of its operands and reading the first one is enough. A comparison or a
     * logical join is a `Bool` whatever its operands are, so that is checked
     * before anything else.
     */
    private fun expressionType(first: PsiElement): String? {
        if (yieldsBool(first)) return BOOL_TYPE

        literalType(first)?.let { return it }

        val firstType = first.node?.elementType

        // `alloc T() * 8` allocates room for `T`s and hands back a pointer to
        // them: `T*`. The `* 8` is how many, not part of the type.
        if (firstType == AzoraTokenTypes.MEMORY_KEYWORD && first.text == ALLOC_KEYWORD) {
            val constructed = nextMeaningful(first) ?: return null
            if (!namesAType(constructed)) return null
            return "${constructed.text}*"
        }

        if (firstType != AzoraTokenTypes.IDENTIFIER) return null

        val after = nextMeaningful(first)
        val isCall = after?.node?.elementType == AzoraTokenTypes.L_PAREN

        // `Point(…)` — a constructor call names its own type.
        if (isCall && first.text.firstOrNull()?.isUpperCase() == true) return first.text

        val file = first.containingFile ?: return null
        val symbols = flatten(
            AzoraSymbolService.getInstance(first.project).getAllVisibleSymbols(
                first.project,
                file.virtualFile?.path ?: file.name,
                file.text,
            )
        )

        val kinds = if (isCall) CALLABLE_KINDS else VALUE_KINDS
        return symbols.firstOrNull { it.name == first.text && it.kind in kinds }?.type
    }

    /** Every symbol and every member of one, so a pack's fields are reachable. */
    private fun flatten(symbols: List<SymbolInfo>): List<SymbolInfo> = buildList {
        fun visit(symbol: SymbolInfo) {
            add(symbol)
            symbol.members.forEach(::visit)
        }
        symbols.forEach(::visit)
    }

    /** Whether a range operator follows [start] before the expression ends. */
    private fun startsARange(start: PsiElement): Boolean {
        var cursor: PsiElement? = start
        var depth = 0
        while (cursor != null) {
            if (cursor.text.contains('\n')) return false
            when (cursor.node?.elementType) {
                AzoraTokenTypes.L_BRACE -> return false
                AzoraTokenTypes.L_PAREN, AzoraTokenTypes.L_BRACKET -> depth++
                AzoraTokenTypes.R_PAREN, AzoraTokenTypes.R_BRACKET -> depth--
                AzoraTokenTypes.OPERATOR -> if (depth == 0 && cursor.text in RANGE_OPERATORS) return true
            }
            cursor = nextOnLine(cursor)
        }
        return false
    }

    /**
     * Whether the expression starting at [first] is a comparison or a logical join.
     *
     * Only the operators that can be nothing else are counted: a bare `<` or
     * `>` is as likely to open a generic argument list as to compare, and a
     * hint that guessed wrong there would be worse than no hint.
     */
    private fun yieldsBool(first: PsiElement): Boolean {
        var cursor: PsiElement? = first
        var depth = 0
        while (cursor != null) {
            if (cursor.text.contains('\n')) break
            when (cursor.node?.elementType) {
                AzoraTokenTypes.L_PAREN, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.L_BRACE -> depth++
                AzoraTokenTypes.R_PAREN, AzoraTokenTypes.R_BRACKET, AzoraTokenTypes.R_BRACE -> depth--
                AzoraTokenTypes.OPERATOR -> if (depth == 0 && cursor.text in BOOL_OPERATORS) return true
            }
            if (depth < 0) break
            cursor = nextOnLine(cursor)
        }
        return false
    }

    /** The type of a literal token, or `null` when the token is not one. */
    private fun literalType(element: PsiElement): String? = when (element.node?.elementType) {
        // A literal carries no width of its own: the suffixes are gone and the
        // target names the width. Standing alone, a literal is the default.
        AzoraTokenTypes.INT_LITERAL -> "Int"
        AzoraTokenTypes.REAL_LITERAL -> "Double"
        AzoraTokenTypes.STRING_LITERAL, AzoraTokenTypes.RAW_STRING_LITERAL -> "String"
        AzoraTokenTypes.CHAR_LITERAL -> "Char"
        AzoraTokenTypes.KEYWORD -> if (element.text == "true" || element.text == "false") BOOL_TYPE else null
        else -> null
    }

    private fun namesAType(element: PsiElement): Boolean =
        element.node?.elementType == AzoraTokenTypes.IDENTIFIER ||
            element.node?.elementType == AzoraTokenTypes.TYPE_PARAMETER

    // ── Bracket walking ────────────────────────────────────────────────

    /** The first meaningful token past the `<…>` opening at [open]. */
    private fun skipGenerics(open: PsiElement): PsiElement? {
        var depth = 0
        var cursor: PsiElement? = open
        while (cursor != null) {
            if (cursor.node?.elementType == AzoraTokenTypes.OPERATOR) {
                when (cursor.text) {
                    "<" -> depth++
                    ">" -> depth--
                    // Maximal munch makes a nested close one token.
                    ">>" -> depth -= 2
                }
                if (depth <= 0) return nextMeaningful(cursor)
            }
            cursor = nextMeaningful(cursor)
        }
        return null
    }

    /** The first meaningful token past the `[…]` receiver opening at [open]. */
    private fun skipReceiver(open: PsiElement): PsiElement? =
        matchingDelimiter(open, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET)
            ?.let { nextMeaningful(it) }

    /** The `)` closing the `(` at [open]. */
    private fun matchingParen(open: PsiElement): PsiElement? =
        matchingDelimiter(open, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN)

    private fun matchingDelimiter(
        open: PsiElement,
        opener: com.intellij.psi.tree.IElementType,
        closer: com.intellij.psi.tree.IElementType,
    ): PsiElement? {
        var depth = 0
        var cursor: PsiElement? = open
        while (cursor != null) {
            when (cursor.node?.elementType) {
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return cursor
                }
            }
            cursor = nextMeaningful(cursor)
        }
        return null
    }

    private fun previousMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.prevSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.prevSibling
        }
        return sibling
    }

    /**
     * The next meaningful token, or null when a line break comes first.
     *
     * A scan that stops "at the end of the line" cannot ask [nextMeaningful]
     * for the next token: the newline is *in* the whitespace it skips, so the
     * guard on the token it returns never saw one. Every such scan ran on into
     * the statements below - which is how `fin original = instant(…)` was told
     * it was a `Bool`, by the `==` of the assertion three lines down.
     */
    private fun nextOnLine(element: PsiElement): PsiElement? {
        var sibling = element.nextSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            if (sibling.text.contains('\n')) return null
            sibling = sibling.nextSibling
        }
        return sibling
    }

    private fun nextMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.nextSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.nextSibling
        }
        return sibling
    }

    private companion object {
        val BINDING_KEYWORDS = setOf("var", "fin", "let", "val")
        const val FUNCTION_KEYWORD = "func"
        const val LOOP_KEYWORD = "for"
        const val IN_KEYWORD = "in"
        const val ALLOC_KEYWORD = "alloc"
        const val UNIT_TYPE = "Unit"
        const val BOOL_TYPE = "Bool"
        val KEYWORD_TYPES = setOf(
            AzoraTokenTypes.DECLARATION_KEYWORD,
            AzoraTokenTypes.REACTIVE_KEYWORD,
        )

        /** Operators whose result can only be a `Bool`. */
        val BOOL_OPERATORS = setOf("==", "!=", "<=", ">=", "&&", "||")

        /** The operators that make an expression a range. */
        val RANGE_OPERATORS = setOf("..", "..<", "..=")

        val CALLABLE_KINDS = setOf(
            SymbolKind.FUNC, SymbolKind.METHOD, SymbolKind.TASK, SymbolKind.FLOW,
        )
        val VALUE_KINDS = setOf(
            SymbolKind.VAR, SymbolKind.FIN, SymbolKind.FIELD, SymbolKind.PARAM,
            SymbolKind.PROPERTY,
        )
    }
}

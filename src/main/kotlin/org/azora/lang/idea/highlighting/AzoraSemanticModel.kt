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

import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraMacros
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType

/**
 * One lexed token, flattened out of the PSI so the model can look at
 * neighbours by index instead of walking siblings repeatedly.
 */
data class AzoraToken(val type: IElementType, val start: Int, val end: Int, val text: String)

/**
 * Assigns semantic colors to a file's tokens: the categories that depend on
 * what a name *means* rather than how it is spelled — macros, calls, type
 * references, declaration names and smart casts.
 *
 * The whole file is classified in one pass and the result is cached by the
 * annotator, so per-token highlighting is a map lookup.
 */
object AzoraSemanticModel {

    /** Declaration keywords whose following name is a function-like symbol. */
    private val FUNCTION_DECL_KEYWORDS = setOf("func", "task", "flow", "deco", "oper", "infx")

    /** Declaration keywords whose following name is a type-like symbol. */
    private val TYPE_DECL_KEYWORDS = setOf(
        "pack", "enum", "slot", "fail", "spec", "typealias", "type",
        "wrap", "solo", "zone", "module", "impl",
    )

    /** Keywords that introduce a condition which may narrow a binding. */
    private val NARROWING_HEADS = setOf("if", "while", "guard")

    /**
     * Classifies every token of a file.
     *
     * @param tokens the file's tokens in source order.
     * @param macros the macro names visible from this file.
     * @return a map from token start offset to the attribute key to apply.
     *   Tokens the lexer already colors correctly are simply absent.
     */
    fun classify(tokens: List<AzoraToken>, macros: AzoraMacros): Map<Int, TextAttributesKey> {
        val result = HashMap<Int, TextAttributesKey>()
        val names = collectDeclaredNames(tokens)

        for (i in tokens.indices) {
            val token = tokens[i]
            val key = when {
                isMacroUse(tokens, i, macros) -> AzoraSyntaxHighlighter.MACRO
                token.type == AzoraTokenTypes.IDENTIFIER -> classifyIdentifier(tokens, i, names)
                else -> null
            }
            if (key != null) result[token.start] = key
        }

        for (range in smartCastRanges(tokens)) {
            for (i in range.tokenIndices) {
                val token = tokens[i]
                if (token.type == AzoraTokenTypes.IDENTIFIER && token.text == range.name) {
                    result[token.start] = AzoraSyntaxHighlighter.SMART_CAST
                }
            }
        }

        return result
    }

    // ── Macros ─────────────────────────────────────────────────────────

    /**
     * Decides whether the token at [index] is a macro invocation.
     *
     * Two things can make it one:
     *
     * * It sits between two operands. An *identifier* there is always an infix
     *   macro — Azora has no other way to write `a op b` with a word operator —
     *   so an unknown one is still colored as a macro. A *keyword* there needs
     *   evidence, because `in`, `is`, `as`, `by` and `reverse` all legitimately
     *   appear between operands as grammar; a keyword is only a macro when some
     *   `meta` declaration in [macros] claims that name. This is what makes
     *   `with` a keyword in `with (ctx) { … }` and a macro in `Base with Filter`
     *   once `azora-engine` declares it, with nothing about `with` hardcoded.
     * * It is a declared prefix macro applied to an operand, as in `res T`.
     */
    private fun isMacroUse(tokens: List<AzoraToken>, index: Int, macros: AzoraMacros): Boolean {
        val token = tokens[index]
        val isIdentifier = token.type == AzoraTokenTypes.IDENTIFIER
        val isKeyword = AzoraTokenTypes.KEYWORDS.contains(token.type)
        if (!isIdentifier && !isKeyword) return false

        val prev = prevMeaningful(tokens, index, sameLine = true)
        val next = nextMeaningful(tokens, index, sameLine = true)

        // A qualified name (`a.b`, `a::b`) is member access, never a macro.
        if (prev != null && isMemberSeparator(tokens[prev])) return false
        // The name being declared is not a use of it.
        if (prev != null && AzoraTokenTypes.KEYWORDS.contains(tokens[prev].type)) {
            if (tokens[prev].text in FUNCTION_DECL_KEYWORDS || tokens[prev].text in TYPE_DECL_KEYWORDS) return false
        }

        val leftIsOperand = prev != null && AzoraTokenTypes.OPERAND_ENDERS.contains(tokens[prev].type)
        val rightIsOperand = next != null && startsOperand(tokens, index, next)

        if (leftIsOperand && rightIsOperand) {
            return if (isIdentifier) true else token.text in macros.infix
        }
        if (rightIsOperand && token.text in macros.prefix && !leftIsOperand) return true
        return false
    }

    /**
     * True when the token at [next] begins an operand for the word at [index].
     *
     * `foo(…)` and `foo[…]` written without a space are a call and an index, so
     * a bracket that is glued to the word does not count as a fresh operand.
     */
    private fun startsOperand(tokens: List<AzoraToken>, index: Int, next: Int): Boolean {
        val candidate = tokens[next]
        if (!AzoraTokenTypes.OPERAND_STARTERS.contains(candidate.type)) return false
        val glued = candidate.start == tokens[index].end
        if (glued && (candidate.type == AzoraTokenTypes.L_PAREN || candidate.type == AzoraTokenTypes.L_BRACKET)) {
            return false
        }
        return true
    }

    private fun isMemberSeparator(token: AzoraToken): Boolean =
        token.type == AzoraTokenTypes.DOT || (token.type == AzoraTokenTypes.OPERATOR && token.text == "::")

    // ── Identifiers ────────────────────────────────────────────────────

    /**
     * Colors a plain identifier as a declaration name, a call, or a type
     * reference. Returns `null` to leave the lexer's identifier color in place.
     */
    private fun classifyIdentifier(
        tokens: List<AzoraToken>,
        index: Int,
        declaredTypes: Set<String>,
    ): TextAttributesKey? {
        val token = tokens[index]
        val prev = prevMeaningful(tokens, index, sameLine = true)?.let { tokens[it] }
        val next = nextMeaningful(tokens, index, sameLine = true)?.let { tokens[it] }

        if (prev != null && AzoraTokenTypes.KEYWORDS.contains(prev.type)) {
            if (prev.text in FUNCTION_DECL_KEYWORDS) return AzoraSyntaxHighlighter.FUNCTION_DECLARATION
            if (prev.text in TYPE_DECL_KEYWORDS) return AzoraSyntaxHighlighter.TYPE_DECLARATION
        }

        val isCall = next != null && next.type == AzoraTokenTypes.L_PAREN && next.start == token.end
        if (isCall) {
            // `Point(…)` constructs a type; `println(…)` calls a function.
            return if (declaredTypes.contains(token.text) || token.text.first().isUpperCase()) {
                AzoraSyntaxHighlighter.TYPE_NAME
            } else {
                AzoraSyntaxHighlighter.FUNCTION_CALL
            }
        }

        if (declaredTypes.contains(token.text)) return AzoraSyntaxHighlighter.TYPE_NAME
        return null
    }

    /** The names this file declares as types, used to color their references. */
    private fun collectDeclaredNames(tokens: List<AzoraToken>): Set<String> {
        val types = HashSet<String>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (!AzoraTokenTypes.KEYWORDS.contains(token.type)) continue
            if (token.text !in TYPE_DECL_KEYWORDS) continue
            val next = nextMeaningful(tokens, i, sameLine = true) ?: continue
            val name = tokens[next]
            if (name.type == AzoraTokenTypes.IDENTIFIER) types.add(name.text)
        }
        return types
    }

    // ── Smart casts ────────────────────────────────────────────────────

    /** A binding narrowed by an `is` test, and the tokens the narrowing covers. */
    private data class SmartCast(val name: String, val tokenIndices: IntRange)

    /**
     * Finds bindings narrowed by an `is` test and the region where the narrowed
     * type holds: the block of an `if`/`while`, the remainder of the enclosing
     * block after a `guard … else { … }`, and the body of a `when` arm.
     */
    private fun smartCastRanges(tokens: List<AzoraToken>): List<SmartCast> {
        val casts = mutableListOf<SmartCast>()

        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != AzoraTokenTypes.CONTROL_KEYWORD || token.text != "is") continue

            val subjectIndex = prevMeaningful(tokens, i, sameLine = true) ?: continue
            val subject = tokens[subjectIndex]
            if (subject.type != AzoraTokenTypes.IDENTIFIER) continue

            val head = enclosingConditionHead(tokens, subjectIndex) ?: continue
            val range = when (tokens[head].text) {
                "guard" -> afterGuard(tokens, head)
                else -> blockAfterCondition(tokens, i)
            } ?: continue
            casts.add(SmartCast(subject.text, range))
        }

        // `when subject { is Type -> … }` narrows the subject inside each arm.
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != AzoraTokenTypes.CONTROL_KEYWORD || token.text != "when") continue
            val subjectIndex = nextMeaningful(tokens, i, sameLine = true) ?: continue
            val subject = tokens[subjectIndex]
            if (subject.type != AzoraTokenTypes.IDENTIFIER) continue
            val body = braceBlockAfter(tokens, subjectIndex) ?: continue
            casts.add(SmartCast(subject.text, body))
        }

        return casts
    }

    /** The `if`/`while`/`guard` that opens the condition containing [index]. */
    private fun enclosingConditionHead(tokens: List<AzoraToken>, index: Int): Int? {
        var i = index
        while (i >= 0) {
            val token = tokens[i]
            if (token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n')) return null
            if (token.type == AzoraTokenTypes.L_BRACE || token.type == AzoraTokenTypes.R_BRACE) return null
            if (token.type == AzoraTokenTypes.CONTROL_KEYWORD && token.text in NARROWING_HEADS) return i
            i--
        }
        return null
    }

    /** The braced block that follows the condition starting at [from]. */
    private fun blockAfterCondition(tokens: List<AzoraToken>, from: Int): IntRange? =
        braceBlockAfter(tokens, from)

    /** The token range of the first `{ … }` block at or after [from]. */
    private fun braceBlockAfter(tokens: List<AzoraToken>, from: Int): IntRange? {
        var i = from
        while (i < tokens.size && tokens[i].type != AzoraTokenTypes.L_BRACE) {
            if (tokens[i].type == AzoraTokenTypes.WHITE_SPACE && tokens[i].text.count { it == '\n' } > 1) return null
            i++
        }
        if (i >= tokens.size) return null
        val open = i
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].type) {
                AzoraTokenTypes.L_BRACE -> depth++
                AzoraTokenTypes.R_BRACE -> {
                    depth--
                    if (depth == 0) return (open + 1) until i
                }
            }
            i++
        }
        return (open + 1) until tokens.size
    }

    /**
     * A `guard … else { … }` narrows everything after it, so the range runs
     * from the end of the `else` block to the end of the enclosing block.
     */
    private fun afterGuard(tokens: List<AzoraToken>, head: Int): IntRange? {
        val elseBlock = braceBlockAfter(tokens, head) ?: return null
        val start = elseBlock.last + 2 // past the block's closing brace
        if (start >= tokens.size) return null
        var depth = 0
        var i = start
        while (i < tokens.size) {
            when (tokens[i].type) {
                AzoraTokenTypes.L_BRACE -> depth++
                AzoraTokenTypes.R_BRACE -> {
                    if (depth == 0) return start until i
                    depth--
                }
            }
            i++
        }
        return start until tokens.size
    }

    // ── Neighbour lookup ───────────────────────────────────────────────

    /**
     * The index of the previous token that carries meaning, or `null`.
     *
     * With [sameLine] set the search stops at a newline, so two identifiers on
     * consecutive lines are never mistaken for an infix expression.
     */
    private fun prevMeaningful(tokens: List<AzoraToken>, index: Int, sameLine: Boolean): Int? {
        var i = index - 1
        while (i >= 0) {
            val token = tokens[i]
            if (sameLine && token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n')) return null
            if (sameLine && token.type == AzoraTokenTypes.NEWLINE) return null
            if (!AzoraTokenTypes.IGNORABLE.contains(token.type)) return i
            i--
        }
        return null
    }

    /** The index of the next token that carries meaning, or `null`. */
    private fun nextMeaningful(tokens: List<AzoraToken>, index: Int, sameLine: Boolean): Int? {
        var i = index + 1
        while (i < tokens.size) {
            val token = tokens[i]
            if (sameLine && token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n')) return null
            if (sameLine && token.type == AzoraTokenTypes.NEWLINE) return null
            if (!AzoraTokenTypes.IGNORABLE.contains(token.type)) return i
            i++
        }
        return null
    }
}

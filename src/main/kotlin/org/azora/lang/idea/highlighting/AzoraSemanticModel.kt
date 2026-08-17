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
import org.azora.lang.idea.AzoraLanguageFacts
import org.azora.lang.idea.symbol.AzoraMacros
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType

/**
 * One lexed token, flattened out of the PSI so the model can look at
 * neighbours by index instead of walking siblings repeatedly.
 */
data class AzoraToken(val type: IElementType, val start: Int, val end: Int, val text: String)

/**
 * Names resolved from the project, its dependencies, and the installed SDK.
 *
 * Keeping this input explicit lets the semantic model remain deterministic in
 * unit tests while the IntelliJ annotator supplies the real indexed symbols.
 */
data class AzoraSemanticSymbols(
    val types: Set<String> = emptySet(),
    val specTypes: Set<String> = emptySet(),
    val functions: Set<String> = emptySet(),
    val decorators: Set<String> = emptySet(),
    val properties: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = AzoraSemanticSymbols()
    }
}

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
    private val FUNCTION_DECL_KEYWORDS = setOf("func")

    /** Declaration keywords whose following name is a type-like symbol. */
    private val TYPE_DECL_KEYWORDS = setOf(
        "pack", "enum", "union", "error", "typealias",
    )

    /** Namespace declarations whose path is styled as one semantic unit. */
    private val REALM_DECL_KEYWORDS = setOf("realm")

    /** Binding declarations whose names may be dimmed when unused. */
    private val BINDING_KEYWORDS = setOf("var", "val", "fin", "let")

    /** Keywords that introduce a condition which may narrow a binding. */
    private val NARROWING_HEADS = setOf("if", "while")

    /**
     * Classifies every token of a file.
     *
     * @param tokens the file's tokens in source order.
     * @param macros the macro names visible from this file.
     * @return a map from token start offset to the attribute key to apply.
     *   Tokens the lexer already colors correctly are simply absent.
     */
    fun classify(
        tokens: List<AzoraToken>,
        macros: AzoraMacros,
        symbols: AzoraSemanticSymbols = AzoraSemanticSymbols.EMPTY,
    ): Map<Int, TextAttributesKey> {
        val result = HashMap<Int, TextAttributesKey>()
        val semantics = collectSemantics(tokens, symbols)
        val sigilStyles = sigilPathStyles(tokens, macros, semantics)

        for (i in tokens.indices) {
            val token = tokens[i]
            val key = when {
                i in sigilStyles -> sigilStyles.getValue(i)
                token.type == AzoraTokenTypes.IDENTIFIER && isLoopLabel(tokens, i) -> AzoraSyntaxHighlighter.LOOP_LABEL
                token.type == AzoraTokenTypes.TYPE_PARAMETER -> AzoraSyntaxHighlighter.TYPE_PARAMETER
                token.type == AzoraTokenTypes.IDENTIFIER -> classifyIdentifier(tokens, i, semantics)
                else -> null
            }
            if (key != null) result[token.start] = key
        }

        applyUnusedStyles(tokens, semantics, result)

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

    /** How a callable/property declaration participates in a spec. */
    private enum class MemberContext { NORMAL, SPEC, OVERRIDE }

    /** A declaration whose references are meaningful only inside [scope]. */
    private data class ScopedDeclaration(
        val name: String,
        val tokenIndex: Int,
        val scope: IntRange,
        val context: MemberContext = MemberContext.NORMAL,
    )

    /** Everything learned from declaration structure before individual tokens are styled. */
    private data class Semantics(
        val types: Set<String>,
        val specTypes: Set<String>,
        val functions: Set<String>,
        val decorators: Set<String>,
        val properties: Set<String>,
        val typeDeclarations: Set<Int>,
        val specDeclarations: Set<Int>,
        val decoratorDeclarations: Set<Int>,
        val functionDeclarations: List<ScopedDeclaration>,
        val propertyDeclarations: List<ScopedDeclaration>,
        val fieldDeclarations: List<ScopedDeclaration>,
        val parameters: List<ScopedDeclaration>,
        val variables: List<ScopedDeclaration>,
        val modulePathTokens: Set<Int>,
        val realmUsageTokens: Set<Int>,
    ) {
        val functionByToken = functionDeclarations.associateBy { it.tokenIndex }
        val propertyByToken = propertyDeclarations.associateBy { it.tokenIndex }
        val fieldByToken = fieldDeclarations.associateBy { it.tokenIndex }
        val parameterByToken = parameters.associateBy { it.tokenIndex }
        val variableByToken = variables.associateBy { it.tokenIndex }
    }

    /**
     * Styles a complete `@realm::Name` path from syntax: lowercase final names
     * are macros, uppercase final names are decorators. This is the same
     * distinction enforced by the parser, and keeps the `@`, qualifiers, `::`,
     * and final name visually coherent without turning the name into one lexer
     * token (which would make navigation impossible).
     */
    private fun sigilPathStyles(
        tokens: List<AzoraToken>,
        macros: AzoraMacros,
        semantics: Semantics,
    ): Map<Int, TextAttributesKey> {
        val result = mutableMapOf<Int, TextAttributesKey>()
        for (at in tokens.indices) {
            if (tokens[at].type != AzoraTokenTypes.DECORATOR || tokens[at].text != "@") continue
            var name = nextMeaningful(tokens, at, sameLine = true) ?: continue
            if (!isSigilName(tokens[name])) continue
            val path = mutableListOf(at, name)
            var final = name
            while (true) {
                val separator = nextMeaningful(tokens, final, sameLine = true) ?: break
                if (!isOperator(tokens[separator], "::")) break
                val segment = nextMeaningful(tokens, separator, sameLine = true) ?: break
                if (!isSigilName(tokens[segment])) break
                path += separator
                path += segment
                final = segment
            }
            val finalName = tokens[final].text
            val style = when {
                finalName in macros.all -> AzoraSyntaxHighlighter.MACRO
                finalName in semantics.decorators -> AzoraSyntaxHighlighter.DECORATOR
                finalName.firstOrNull()?.isUpperCase() == true -> AzoraSyntaxHighlighter.DECORATOR
                else -> AzoraSyntaxHighlighter.MACRO
            }
            path.forEach { result[it] = style }

            // Mutable macro spellings (`@vec!`) include the suffix in the
            // invocation's semantic unit even though `!` remains an operator
            // token for navigation-friendly lexing.
            nextMeaningful(tokens, final, sameLine = true)
                ?.takeIf { isOperator(tokens[it], "!") && finalName in macros.prefix }
                ?.let { result[it] = style }
        }
        return result
    }

    private fun isSigilName(token: AzoraToken): Boolean =
        token.type == AzoraTokenTypes.IDENTIFIER || AzoraTokenTypes.KEYWORDS.contains(token.type)

    private fun isMemberSeparator(token: AzoraToken): Boolean =
        token.type == AzoraTokenTypes.DOT || (token.type == AzoraTokenTypes.OPERATOR && token.text == "::")

    private fun isAfterMemberSeparator(tokens: List<AzoraToken>, index: Int): Boolean =
        prevMeaningful(tokens, index, sameLine = true)?.let { isMemberSeparator(tokens[it]) } == true

    private fun isAfterAtPath(tokens: List<AzoraToken>, index: Int): Boolean {
        var cursor = index
        while (true) {
            val previous = prevMeaningful(tokens, cursor, sameLine = true) ?: return false
            if (tokens[previous].type == AzoraTokenTypes.DECORATOR && tokens[previous].text == "@") return true
            if (!isOperator(tokens[previous], "::")) return false
            val segment = prevMeaningful(tokens, previous, sameLine = true) ?: return false
            if (tokens[segment].type != AzoraTokenTypes.IDENTIFIER) return false
            cursor = segment
        }
    }

    /** `name: for/while/reverse for/loop` and `break:name`/`continue:name`. */
    private fun isLoopLabel(tokens: List<AzoraToken>, index: Int): Boolean {
        val previous = prevMeaningful(tokens, index, sameLine = true)
        if (previous != null && tokens[previous].type == AzoraTokenTypes.COLON) {
            val head = prevMeaningful(tokens, previous, sameLine = true)
            if (head != null && tokens[head].text in setOf("break", "continue")) return true
        }

        val colon = nextMeaningful(tokens, index, sameLine = true) ?: return false
        if (tokens[colon].type != AzoraTokenTypes.COLON) return false
        val loop = nextMeaningful(tokens, colon, sameLine = true) ?: return false
        return tokens[loop].text in setOf("for", "while", "loop", "reverse")
    }

    // ── Identifiers ────────────────────────────────────────────────────

    /**
     * Colors a plain identifier as a declaration name, a call, or a type
     * reference. Returns `null` to leave the lexer's identifier color in place.
     */
    private fun classifyIdentifier(tokens: List<AzoraToken>, index: Int, semantics: Semantics): TextAttributesKey? {
        val token = tokens[index]
        val next = nextMeaningful(tokens, index, sameLine = true)?.let { tokens[it] }

        if (index in semantics.modulePathTokens) return AzoraSyntaxHighlighter.MODULE_PATH
        if (index in semantics.realmUsageTokens) return AzoraSyntaxHighlighter.ZONE_USAGE
        if (index in semantics.decoratorDeclarations) return AzoraSyntaxHighlighter.DECORATOR
        if (index in semantics.specDeclarations) return AzoraSyntaxHighlighter.SPEC_TYPE
        if (index in semantics.typeDeclarations) return AzoraSyntaxHighlighter.TYPE_DECLARATION
        semantics.functionByToken[index]?.let { return functionStyle(it.context) }
        semantics.propertyByToken[index]?.let { return propertyStyle(it.context) }
        if (semantics.fieldByToken.containsKey(index)) return AzoraSyntaxHighlighter.FIELD
        if (semantics.parameterByToken.containsKey(index) || token.text in IMPLICIT_PARAMETERS) {
            return AzoraSyntaxHighlighter.PARAMETER
        }
        if (semantics.variableByToken.containsKey(index)) return AzoraSyntaxHighlighter.IDENTIFIER

        // A local binding is a symbol in its own scope, even when its spelling
        // is also used by a top-level function or type elsewhere in the file.
        if (isLocalBindingReference(tokens, index, semantics)) {
            return if (isParameterReference(tokens, index, semantics.parameters)) {
                AzoraSyntaxHighlighter.PARAMETER
            } else {
                AzoraSyntaxHighlighter.IDENTIFIER
            }
        }

        val isCall = next != null && (
            next.type == AzoraTokenTypes.L_PAREN ||
                (isOperator(next, "<") && token.text in semantics.functions)
            )
        if (token.text in semantics.decorators && isAfterAtPath(tokens, index)) {
            return AzoraSyntaxHighlighter.DECORATOR
        }
        if (token.text in semantics.specTypes) return AzoraSyntaxHighlighter.SPEC_TYPE
        if (token.text in semantics.types) return AzoraSyntaxHighlighter.TYPE_NAME
        if (isCall && token.text in semantics.functions) return AzoraSyntaxHighlighter.FUNCTION_CALL
        if (token.text in semantics.properties && isAfterMemberSeparator(tokens, index)) {
            return AzoraSyntaxHighlighter.FIELD
        }
        if (isParameterReference(tokens, index, semantics.parameters)) return AzoraSyntaxHighlighter.PARAMETER

        return null
    }

    private fun functionStyle(context: MemberContext): TextAttributesKey = when (context) {
        MemberContext.NORMAL -> AzoraSyntaxHighlighter.FUNCTION_DECLARATION
        MemberContext.SPEC -> AzoraSyntaxHighlighter.SPEC_FUNCTION
        MemberContext.OVERRIDE -> AzoraSyntaxHighlighter.OVERRIDE_FUNCTION
    }

    private fun propertyStyle(context: MemberContext): TextAttributesKey = when (context) {
        MemberContext.NORMAL -> AzoraSyntaxHighlighter.PROPERTY
        MemberContext.SPEC -> AzoraSyntaxHighlighter.SPEC_PROPERTY
        MemberContext.OVERRIDE -> AzoraSyntaxHighlighter.OVERRIDE_PROPERTY
    }

    /** Builds the declaration and context index shared by all token classifiers. */
    private fun collectSemantics(tokens: List<AzoraToken>, external: AzoraSemanticSymbols): Semantics {
        val specBodies = declarationBodies(tokens, "spec")
        val overrideBodies = declarationBodies(tokens, "impl", requireFor = true)
        val typeDeclarations = linkedSetOf<Int>()
        val specDeclarations = linkedSetOf<Int>()
        val decoratorDeclarations = linkedSetOf<Int>()
        val callableHeads = mutableListOf<Pair<Int, Int>>()
        val anonymousCallableHeads = mutableListOf<Int>()
        val propertyHeads = mutableListOf<Pair<Int, Int>>()
        val bindingHeads = mutableListOf<Pair<Int, Int>>()

        for (i in tokens.indices) {
            val token = tokens[i]
            if (!AzoraTokenTypes.KEYWORDS.contains(token.type)) continue
            when (token.text) {
                in TYPE_DECL_KEYWORDS -> declarationNameAfter(tokens, i)?.let { typeDeclarations.add(it) }
                "spec" -> declarationNameAfter(tokens, i)?.let { specDeclarations.add(it) }
                "annot" -> declarationNameAfter(tokens, i)?.let { decoratorDeclarations.add(it) }
                in FUNCTION_DECL_KEYWORDS -> callableNameAfter(tokens, i)?.let { callableHeads.add(i to it) }
                "ctor", "dtor", "oper" -> anonymousCallableHeads.add(i)
                "prop" -> declarationNameAfter(tokens, i)?.let { propertyHeads.add(i to it) }
                in BINDING_KEYWORDS -> declarationNameAfter(tokens, i)?.let { bindingHeads.add(i to it) }
            }
        }

        val namedCallableScopes = callableHeads.map { (head, _) -> declarationScope(tokens, head) }
        val anonymousCallableScopes = anonymousCallableHeads.map { declarationScope(tokens, it) }
        val callableScopes = namedCallableScopes + anonymousCallableScopes
        val functionDeclarations = callableHeads.map { (_, nameIndex) ->
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                0..tokens.lastIndex,
                memberContext(nameIndex, specBodies, overrideBodies),
            )
        }
        val properties = propertyHeads.map { (_, nameIndex) ->
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                0..tokens.lastIndex,
                memberContext(nameIndex, specBodies, overrideBodies),
            )
        }
        val parameterOwners = buildList {
            functionDeclarations.forEachIndexed { position, declaration ->
                add(declaration.copy(scope = namedCallableScopes[position]))
            }
            anonymousCallableHeads.forEachIndexed { position, head ->
                add(ScopedDeclaration(tokens[head].text, head, anonymousCallableScopes[position]))
            }
        }
        val parameters = parameterOwners.flatMap { parameterDeclarations(tokens, it) }
        val fieldBodies = listOf("pack", "union", "error", "annot")
            .flatMap { declarationBodies(tokens, it) }
        val fieldHeads = bindingHeads.filter { (head, _) ->
            callableScopes.none { head in it } && fieldBodies.any { head in it }
        }
        val fields = fieldHeads.map { (_, nameIndex) ->
            ScopedDeclaration(tokens[nameIndex].text, nameIndex, 0..tokens.lastIndex)
        }
        val variables = bindingHeads.filterNot { it in fieldHeads }.map { (head, nameIndex) ->
            val enclosing = callableScopes.lastOrNull { head in it }
            val lexical = enclosingBlock(tokens, head)?.let { head..it.last }
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                lexical ?: enclosing ?: (head..tokens.lastIndex),
            )
        }

        return Semantics(
            types = external.types + typeDeclarations.map { tokens[it].text },
            specTypes = external.specTypes + specDeclarations.map { tokens[it].text },
            functions = external.functions + functionDeclarations.map { it.name },
            decorators = external.decorators + decoratorDeclarations.map { tokens[it].text },
            properties = external.properties + properties.map { it.name } + fields.map { it.name },
            typeDeclarations = typeDeclarations,
            specDeclarations = specDeclarations,
            decoratorDeclarations = decoratorDeclarations,
            functionDeclarations = functionDeclarations,
            propertyDeclarations = properties,
            fieldDeclarations = fields,
            parameters = parameters,
            variables = variables,
            modulePathTokens = modulePathTokens(tokens),
            realmUsageTokens = realmUsageTokens(tokens),
        )
    }

    /** Finds the declared name immediately after a current-language declaration head. */
    private fun declarationNameAfter(tokens: List<AzoraToken>, head: Int): Int? {
        val index = nextMeaningful(tokens, head, sameLine = false) ?: return null
        val token = tokens[index]
        return index.takeIf { token.type == AzoraTokenTypes.IDENTIFIER }
    }

    /** Finds a function name, whose optional `<...>` follows the name. */
    private fun callableNameAfter(tokens: List<AzoraToken>, head: Int): Int? {
        val name = declarationNameAfter(tokens, head) ?: return null
        var cursor = nextMeaningful(tokens, name, sameLine = false) ?: return name
        if (isOperator(tokens[cursor], "<")) {
            cursor = skipGenericHeader(tokens, cursor) ?: return name
        }
        // A bracketed receiver is legal between a name and its parameter list.
        if (tokens.getOrNull(cursor)?.type == AzoraTokenTypes.L_BRACKET) {
            cursor = matchingDelimiter(tokens, cursor, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET)
                ?.let { nextMeaningful(tokens, it, sameLine = false) }
                ?: return name
        }
        return name.takeIf { tokens.getOrNull(cursor)?.type == AzoraTokenTypes.L_PAREN }
    }

    /** Skips `<...>` when [index] starts a generic declaration header. */
    private fun skipGenericHeader(tokens: List<AzoraToken>, index: Int): Int? {
        if (!isOperator(tokens[index], "<")) return index
        var depth = 0
        var cursor = index
        while (cursor < tokens.size) {
            when {
                isOperator(tokens[cursor], "<") -> depth++
                isOperator(tokens[cursor], ">") -> {
                    depth--
                    if (depth == 0) return nextMeaningful(tokens, cursor, sameLine = false)
                }
                isOperator(tokens[cursor], ">>") -> {
                    depth -= 2
                    if (depth <= 0) return nextMeaningful(tokens, cursor, sameLine = false)
                }
            }
            cursor++
        }
        return null
    }

    /** Braced declaration bodies for specs or spec implementation blocks. */
    private fun declarationBodies(
        tokens: List<AzoraToken>,
        keyword: String,
        requireFor: Boolean = false,
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (i in tokens.indices) {
            if (!AzoraTokenTypes.KEYWORDS.contains(tokens[i].type) || tokens[i].text != keyword) continue
            var cursor = i + 1
            var sawFor = false
            while (cursor < tokens.size && tokens[cursor].type != AzoraTokenTypes.L_BRACE) {
                if (tokens[cursor].text == "for") sawFor = true
                if (tokens[cursor].type == AzoraTokenTypes.SEMICOLON) break
                cursor++
            }
            if (cursor >= tokens.size || tokens[cursor].type != AzoraTokenTypes.L_BRACE) continue
            if (requireFor && !sawFor) continue
            matchingBrace(tokens, cursor)?.let { close ->
                if (close > cursor) ranges.add((cursor + 1) until close)
            }
        }
        return ranges
    }

    private fun matchingBrace(tokens: List<AzoraToken>, open: Int): Int? {
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

    private fun matchingParen(tokens: List<AzoraToken>, open: Int, limit: Int): Int? {
        var depth = 0
        for (i in open..limit.coerceAtMost(tokens.lastIndex)) {
            when (tokens[i].type) {
                AzoraTokenTypes.L_PAREN -> depth++
                AzoraTokenTypes.R_PAREN -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun matchingDelimiter(
        tokens: List<AzoraToken>,
        open: Int,
        opener: IElementType,
        closer: IElementType,
    ): Int? {
        var depth = 0
        for (index in open until tokens.size) {
            when (tokens[index].type) {
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** The signature plus body of a callable declaration. */
    private fun declarationScope(tokens: List<AzoraToken>, head: Int): IntRange {
        var parens = 0
        var brackets = 0
        var angles = 0
        var index = head + 1
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.type == AzoraTokenTypes.L_PAREN -> parens++
                token.type == AzoraTokenTypes.R_PAREN -> parens = (parens - 1).coerceAtLeast(0)
                token.type == AzoraTokenTypes.L_BRACKET -> brackets++
                token.type == AzoraTokenTypes.R_BRACKET -> brackets = (brackets - 1).coerceAtLeast(0)
                isOperator(token, "<") -> angles++
                isOperator(token, ">") -> angles = (angles - 1).coerceAtLeast(0)
                isOperator(token, ">>") -> angles = (angles - 2).coerceAtLeast(0)
                token.type == AzoraTokenTypes.L_BRACE && parens == 0 && brackets == 0 && angles == 0 -> {
                    val close = matchingBrace(tokens, index) ?: tokens.lastIndex
                    return head..close
                }
                token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n') &&
                    parens == 0 && brackets == 0 && angles == 0 -> {
                    val next = nextMeaningful(tokens, index, sameLine = false)
                    val continuation = next?.let {
                        tokens[it].type == AzoraTokenTypes.L_BRACE || tokens[it].text == "where"
                    } == true
                    if (!continuation) return head..(index - 1).coerceAtLeast(head)
                }
                token.type == AzoraTokenTypes.SEMICOLON && parens == 0 && brackets == 0 && angles == 0 ->
                    return head..index
            }
            index++
        }
        return head..tokens.lastIndex
    }

    /** The innermost braced lexical block containing [index]. */
    private fun enclosingBlock(tokens: List<AzoraToken>, index: Int): IntRange? {
        val stack = ArrayDeque<Int>()
        for (cursor in 0..index.coerceAtMost(tokens.lastIndex)) {
            when (tokens[cursor].type) {
                AzoraTokenTypes.L_BRACE -> stack.addLast(cursor)
                AzoraTokenTypes.R_BRACE -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val open = stack.lastOrNull() ?: return null
        val close = matchingBrace(tokens, open) ?: tokens.lastIndex
        return open..close
    }

    private fun memberContext(
        index: Int,
        specBodies: List<IntRange>,
        overrideBodies: List<IntRange>,
    ): MemberContext = when {
        overrideBodies.any { index in it } -> MemberContext.OVERRIDE
        specBodies.any { index in it } -> MemberContext.SPEC
        else -> MemberContext.NORMAL
    }

    /** Parameters from both `[self: T&, context: C&]` and `(value: T)` lists. */
    private fun parameterDeclarations(
        tokens: List<AzoraToken>,
        function: ScopedDeclaration,
    ): List<ScopedDeclaration> {
        val declarations = mutableListOf<ScopedDeclaration>()
        val signatureEnd = function.scope.firstOrNull { tokens[it].type == AzoraTokenTypes.L_BRACE }
            ?: function.scope.last
        for (i in (function.tokenIndex + 1) until signatureEnd.coerceAtMost(tokens.size)) {
            if (tokens[i].type != AzoraTokenTypes.IDENTIFIER) continue
            val next = nextMeaningful(tokens, i, sameLine = false) ?: continue
            if (next < signatureEnd && tokens[next].type == AzoraTokenTypes.COLON) {
                declarations.add(ScopedDeclaration(tokens[i].text, i, function.scope))
            }
        }
        return declarations.distinctBy { it.tokenIndex }
    }

    /** All identifier segments on an `import` line. */
    private fun modulePathTokens(tokens: List<AzoraToken>): Set<Int> {
        val result = linkedSetOf<Int>()
        for (i in tokens.indices) {
            if (!AzoraTokenTypes.KEYWORDS.contains(tokens[i].type) ||
                tokens[i].text !in setOf("import", "use", "module")) continue
            var cursor = i + 1
            while (cursor < tokens.size) {
                val token = tokens[cursor]
                if (token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n')) break
                if (token.type == AzoraTokenTypes.SEMICOLON) break
                if (token.type == AzoraTokenTypes.IDENTIFIER) result.add(cursor)
                cursor++
            }
        }
        return result
    }

    /**
     * All segments in a realm-qualified path are one semantic span. The old
     * implementation only marked the first lowercase segment, leaving
     * `realm ide::editor` half-styled and making `std::math::sqrt` inconsistent.
     */
    private fun realmUsageTokens(tokens: List<AzoraToken>): Set<Int> {
        val result = linkedSetOf<Int>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != AzoraTokenTypes.IDENTIFIER) continue
            if (isRealmDeclarationSegment(tokens, i)) {
                addRealmPath(tokens, i, result, includeLast = true)
                continue
            }
            val next = nextMeaningful(tokens, i, sameLine = true)
            // At a use site, only owning realm segments are italic. The final
            // segment is the actual symbol and keeps its resolved function/type/
            // value style (`std::reflect`, `std::Int`, `entity::field`).
            if (next != null && isOperator(tokens[next], "::")) {
                addRealmPath(tokens, i, result, includeLast = false)
            }
        }
        return result
    }

    private fun addRealmPath(
        tokens: List<AzoraToken>,
        index: Int,
        result: MutableSet<Int>,
        includeLast: Boolean,
    ) {
        var first = index
        while (true) {
            val separator = prevMeaningful(tokens, first, sameLine = true)
            val segment = separator?.let { prevMeaningful(tokens, it, sameLine = true) }
            if (separator == null || !isOperator(tokens[separator], "::") || segment == null ||
                tokens[segment].type != AzoraTokenTypes.IDENTIFIER) break
            first = segment
        }
        val segments = mutableListOf<Int>()
        var cursor = first
        while (cursor < tokens.size) {
            if (tokens[cursor].type == AzoraTokenTypes.IDENTIFIER) segments.add(cursor)
            val separator = nextMeaningful(tokens, cursor, sameLine = true) ?: break
            if (!isOperator(tokens[separator], "::")) break
            val segment = nextMeaningful(tokens, separator, sameLine = true) ?: break
            if (tokens[segment].type != AzoraTokenTypes.IDENTIFIER) break
            cursor = segment
        }
        result.addAll(if (includeLast) segments else segments.dropLast(1))
    }

    private fun isRealmDeclarationSegment(tokens: List<AzoraToken>, index: Int): Boolean {
        var cursor = index - 1
        while (cursor >= 0) {
            val token = tokens[cursor]
            if (AzoraTokenTypes.IGNORABLE.contains(token.type)) {
                cursor--
                continue
            }
            if (token.text in REALM_DECL_KEYWORDS && AzoraTokenTypes.KEYWORDS.contains(token.type)) return true
            if (token.type == AzoraTokenTypes.IDENTIFIER || isOperator(token, "::")) {
                cursor--
                continue
            }
            break
        }
        return false
    }

    private fun isParameterReference(
        tokens: List<AzoraToken>,
        index: Int,
        parameters: List<ScopedDeclaration>,
    ): Boolean = parameters.any {
        index > it.tokenIndex && index in it.scope && tokens[index].text == it.name
    }

    private fun isLocalBindingReference(
        tokens: List<AzoraToken>,
        index: Int,
        semantics: Semantics,
    ): Boolean = semantics.variables.any {
        index > it.tokenIndex && index in it.scope && tokens[index].text == it.name
    }

    /** Applies the website's light-gray styles to declarations with no references. */
    private fun applyUnusedStyles(
        tokens: List<AzoraToken>,
        semantics: Semantics,
        result: MutableMap<Int, TextAttributesKey>,
    ) {
        val declarationTokens = buildSet {
            addAll(semantics.functionDeclarations.map { it.tokenIndex })
            addAll(semantics.propertyDeclarations.map { it.tokenIndex })
            addAll(semantics.parameters.map { it.tokenIndex })
            addAll(semantics.variables.map { it.tokenIndex })
        }

        fun isUsed(declaration: ScopedDeclaration, mustFollowDeclaration: Boolean = false): Boolean =
            declaration.scope.any { index ->
                index != declaration.tokenIndex &&
                    (!mustFollowDeclaration || index > declaration.tokenIndex) &&
                    index !in declarationTokens &&
                    tokens[index].type == AzoraTokenTypes.IDENTIFIER &&
                    tokens[index].text == declaration.name
            }

        for (declaration in semantics.functionDeclarations) {
            if (declaration.name == "main" || isUsed(declaration)) continue
            result[tokens[declaration.tokenIndex].start] = when (declaration.context) {
                MemberContext.NORMAL -> AzoraSyntaxHighlighter.UNUSED
                MemberContext.SPEC -> AzoraSyntaxHighlighter.UNUSED_SPEC_MEMBER
                MemberContext.OVERRIDE -> AzoraSyntaxHighlighter.UNUSED_OVERRIDE_MEMBER
            }
        }
        for (declaration in semantics.propertyDeclarations) {
            if (isUsed(declaration)) continue
            result[tokens[declaration.tokenIndex].start] = when (declaration.context) {
                MemberContext.NORMAL -> AzoraSyntaxHighlighter.UNUSED_PROPERTY
                MemberContext.SPEC -> AzoraSyntaxHighlighter.UNUSED_SPEC_MEMBER
                MemberContext.OVERRIDE -> AzoraSyntaxHighlighter.UNUSED_OVERRIDE_MEMBER
            }
        }
        for (declaration in semantics.parameters) {
            if (!isUsed(declaration, mustFollowDeclaration = true)) {
                result[tokens[declaration.tokenIndex].start] = AzoraSyntaxHighlighter.UNUSED_PARAMETER
            }
        }
        for (declaration in semantics.variables) {
            if (!isUsed(declaration, mustFollowDeclaration = true)) {
                result[tokens[declaration.tokenIndex].start] = AzoraSyntaxHighlighter.UNUSED
            }
        }
    }

    private fun isOperator(token: AzoraToken, value: String): Boolean =
        token.type == AzoraTokenTypes.OPERATOR && token.text == value

    private val IMPLICIT_PARAMETERS = AzoraLanguageFacts.implicitParameters

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

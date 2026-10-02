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
import org.azora.lang.idea.symbol.AzoraImports
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
    /** Everything read through a receiver - pack fields and `prop`s alike. */
    val properties: Set<String> = emptySet(),
    /**
     * The subset of [properties] declared with `prop`.
     *
     * A field and a property are read the same way and only the declaration
     * says which is which. Without that, a `prop` declared in another file read
     * as a plain field here, so `reflect<T>.hasAnnot` lost the mark that says a
     * member is computed - while the identical name declared in this file kept
     * it.
     */
    val computedProperties: Set<String> = emptySet(),
    val enumCases: Set<String> = emptySet(),
    val errorCases: Set<String> = emptySet(),
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
    private val REALM_DECL_KEYWORDS = setOf("scope")

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
        val holeTokens = macroHoleTokens(tokens)

        for (i in tokens.indices) {
            val token = tokens[i]
            val key = when {
                i in holeTokens -> AzoraSyntaxHighlighter.MACRO_HOLE
                i in sigilStyles -> sigilStyles.getValue(i)
                token.type == AzoraTokenTypes.IDENTIFIER && isLoopLabel(tokens, i) -> AzoraSyntaxHighlighter.LOOP_LABEL
                // A `*` reached through a separator stands for everything the
                // path holds; everywhere else a `*` multiplies.
                isOperator(token, "*") && isAfterMemberSeparator(tokens, i) -> AzoraSyntaxHighlighter.WILDCARD
                // `impl Deque<T>` declares a parameter; `impl Deque<Int>`
                // instantiates one. Only the symbol index can tell them apart,
                // so the lexer's guess yields to a name that is a real type.
                token.type == AzoraTokenTypes.TYPE_PARAMETER ->
                    when (token.text) {
                        in semantics.specTypes -> AzoraSyntaxHighlighter.SPEC_TYPE
                        in semantics.types -> AzoraSyntaxHighlighter.TYPE_NAME
                        else -> AzoraSyntaxHighlighter.TYPE_PARAMETER
                    }
                token.type == AzoraTokenTypes.IDENTIFIER -> classifyIdentifier(tokens, i, semantics)
                // `take(cursor)` calls a function that happens to be spelled
                // like the `take` keyword. The lexer cannot know - `take x` is
                // the keyword and looks the same up to the next character - but
                // a project that declares `func take` settles it.
                AzoraTokenTypes.KEYWORDS.contains(token.type) &&
                    token.text in semantics.functions &&
                    nextMeaningful(tokens, i, sameLine = true)
                        ?.let { tokens[it].type } == AzoraTokenTypes.L_PAREN ->
                    AzoraSyntaxHighlighter.FUNCTION_CALL
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

    /** How a callable/property declaration participates in a spec. */
    private enum class MemberContext { NORMAL, SPEC, OVERRIDE }

    /** A declaration whose references are meaningful only inside [scope]. */
    private data class ScopedDeclaration(
        val name: String,
        val tokenIndex: Int,
        val scope: IntRange,
        val context: MemberContext = MemberContext.NORMAL,
        /** The keyword that introduced it, or `-1` when it has no head of its own. */
        val head: Int = -1,
    )

    /** Everything learned from declaration structure before individual tokens are styled. */
    private data class Semantics(
        val types: Set<String>,
        val specTypes: Set<String>,
        val functions: Set<String>,
        val decorators: Set<String>,
        val properties: Set<String>,
        /** Of [properties], the ones a `prop` declares - see the same name on [AzoraSemanticSymbols]. */
        val computedProperties: Set<String>,
        val enumCases: Set<String>,
        val errorCases: Set<String>,
        val typeDeclarations: Set<Int>,
        /** Each type declaration's name token, mapped to the keyword that introduced it. */
        val typeHeads: Map<Int, Int>,
        val specDeclarations: Set<Int>,
        val decoratorDeclarations: Set<Int>,
        val enumCaseDeclarations: Set<Int>,
        val errorCaseDeclarations: Set<Int>,
        val functionDeclarations: List<ScopedDeclaration>,
        val propertyDeclarations: List<ScopedDeclaration>,
        val fieldDeclarations: List<ScopedDeclaration>,
        val parameters: List<ScopedDeclaration>,
        /** The `[self: Self&, …]` a callable declares ahead of its own parameters. */
        val contextParameters: List<ScopedDeclaration>,
        val variables: List<ScopedDeclaration>,
        val modulePathTokens: Set<Int>,
        /** The names an `import` selects out of a module, as opposed to the path. */
        val importSelectionTokens: Set<Int>,
        /** Everything written inside a `macro` or `meta` declaration's body. */
        val macroBodyTokens: Set<Int>,
        val scopeUsageTokens: Set<Int>,
    ) {
        val functionByToken = functionDeclarations.associateBy { it.tokenIndex }
        val propertyByToken = propertyDeclarations.associateBy { it.tokenIndex }
        val fieldByToken = fieldDeclarations.associateBy { it.tokenIndex }
        val parameterByToken = parameters.associateBy { it.tokenIndex }
        val contextParameterByToken = contextParameters.associateBy { it.tokenIndex }
        val variableByToken = variables.associateBy { it.tokenIndex }
    }

    /**
     * Styles a complete `@scope::Name` path from syntax: lowercase final names
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
            val first = nextMeaningful(tokens, at, sameLine = true) ?: continue
            val names = if (tokens[first].type == AzoraTokenTypes.L_PAREN) decoratorRowHeads(tokens, first)
            else listOf(first)
            for (name in names) {
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
        }
        return result
    }

    private fun decoratorRowHeads(tokens: List<AzoraToken>, open: Int): List<Int> {
        val close = matchingDelimiter(tokens, open, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN)
            ?: return emptyList()
        val result = mutableListOf<Int>()
        var depth = 0
        var entryStart = true
        for (index in open + 1 until close) {
            val token = tokens[index]
            if (depth == 0 && entryStart && isSigilName(token)) {
                result += index
                entryStart = false
            }
            when (token.type) {
                AzoraTokenTypes.L_PAREN, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.L_BRACE -> depth++
                AzoraTokenTypes.R_PAREN, AzoraTokenTypes.R_BRACKET, AzoraTokenTypes.R_BRACE -> depth--
                else -> if (depth == 0 && (token.text == "," || token.text.contains('\n'))) entryStart = true
            }
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

    /** `name: for/while/loop` and `break:name`/`continue:name`. */
    private fun isLoopLabel(tokens: List<AzoraToken>, index: Int): Boolean {
        val previous = prevMeaningful(tokens, index, sameLine = true)
        if (previous != null && tokens[previous].type == AzoraTokenTypes.COLON) {
            val head = prevMeaningful(tokens, previous, sameLine = true)
            if (head != null && tokens[head].text in setOf("break", "continue")) return true
        }

        val colon = nextMeaningful(tokens, index, sameLine = true) ?: return false
        if (tokens[colon].type != AzoraTokenTypes.COLON) return false
        val loop = nextMeaningful(tokens, colon, sameLine = true) ?: return false
        return tokens[loop].text in setOf("for", "while", "loop")
    }

    // ── Identifiers ────────────────────────────────────────────────────

    /**
     * Colors a plain identifier as a declaration name, a call, or a type
     * reference. Returns `null` to leave the lexer's identifier color in place.
     */
    private fun classifyIdentifier(tokens: List<AzoraToken>, index: Int, semantics: Semantics): TextAttributesKey? {
        val token = tokens[index]
        // A `$name` is a hole where holes live - inside a `macro` or a `meta`,
        // whose body is a pattern rather than code. Everywhere else it is
        // ordinary interpolation: `inline for name in […] { impl $name { … } }`
        // is code that names something, and reads like code that names
        // something.
        if (token.text.contains('$') && index in semantics.macroBodyTokens) {
            return AzoraSyntaxHighlighter.MACRO_HOLE
        }
        val next = nextMeaningful(tokens, index, sameLine = true)?.let { tokens[it] }

        if (index in semantics.modulePathTokens) return AzoraSyntaxHighlighter.MODULE_PATH
        // What an import *selects* is a declaration, so it reads as one: a
        // function is blue in the import line exactly as at its call site, and a
        // reader can tell what an import brought in without opening the module.
        // A name the index does not know falls through to the ordinary rules,
        // which is what leaves an unresolved import uncolored rather than
        // dressed up as something it might not be.
        if (index in semantics.importSelectionTokens) {
            declarationStyle(token.text, semantics)?.let { return it }
        }
        if (index in semantics.scopeUsageTokens) return AzoraSyntaxHighlighter.ZONE_USAGE
        if (index in semantics.decoratorDeclarations) return AzoraSyntaxHighlighter.DECORATOR
        if (index in semantics.specDeclarations) return AzoraSyntaxHighlighter.SPEC_TYPE
        if (index in semantics.typeDeclarations) return AzoraSyntaxHighlighter.TYPE_DECLARATION
        if (index in semantics.errorCaseDeclarations) return AzoraSyntaxHighlighter.ERROR_CASE
        if (index in semantics.enumCaseDeclarations) return AzoraSyntaxHighlighter.ENUM_CASE
        semantics.functionByToken[index]?.let { return functionStyle(it.context) }
        semantics.propertyByToken[index]?.let { return propertyStyle(it.context) }
        if (semantics.fieldByToken.containsKey(index)) return AzoraSyntaxHighlighter.FIELD
        // A receiver reads the same wherever it is written: naming it in the
        // body is naming the thing the call site supplied, not a local.
        if (semantics.contextParameterByToken.containsKey(index) ||
            isContextParameterReference(tokens, index, semantics.contextParameters)
        ) {
            return AzoraSyntaxHighlighter.CONTEXT_PARAMETER
        }
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
        // `Compare.Less`, `.OutOfBounds(…)` — a case is named through the type
        // that declares it, or through the leading dot that stands in for it.
        if (isAfterMemberSeparator(tokens, index)) {
            if (token.text in semantics.errorCases) return AzoraSyntaxHighlighter.ERROR_CASE
            if (token.text in semantics.enumCases) return AzoraSyntaxHighlighter.ENUM_CASE
        }
        // An `annot` is named without its `@` wherever it is talked *about*
        // rather than applied - `reflect<T>.hasAnnot<Serializable>`,
        // `derive Serializable for Point`. It is the same declaration either
        // way, so it reads the same; only the `@` is missing.
        if (token.text in semantics.decorators) return AzoraSyntaxHighlighter.DECORATOR
        if (token.text in semantics.specTypes) return AzoraSyntaxHighlighter.SPEC_TYPE
        if (token.text in semantics.types) return AzoraSyntaxHighlighter.TYPE_NAME
        // Applying a name to `(` is a call - that is the syntax, not a guess
        // about what the name resolves to. An unresolved callee is the
        // annotator's to report; it still reads as the call it is. A capitalized
        // one is left alone: `MissingType(…)` builds a type, and coloring an
        // unknown type would be a guess.
        if (isCall && (token.text in semantics.functions || token.text.firstOrNull()?.isLowerCase() == true)) {
            return AzoraSyntaxHighlighter.FUNCTION_CALL
        }
        if (token.text in semantics.properties && isAfterMemberSeparator(tokens, index)) {
            // A declared `prop` read through a receiver is the call side of the
            // declaration and wears the same italic; a plain pack field is not.
            // The index says which, so a `prop` the project declares elsewhere
            // reads here as what it is.
            return if (token.text in semantics.computedProperties) {
                AzoraSyntaxHighlighter.PROPERTY_CALL
            } else {
                AzoraSyntaxHighlighter.FIELD
            }
        }
        if (isParameterReference(tokens, index, semantics.parameters)) return AzoraSyntaxHighlighter.PARAMETER

        return null
    }

    /** How a name reads on its own, from what the project says it is. */
    private fun declarationStyle(name: String, semantics: Semantics): TextAttributesKey? = when (name) {
        in semantics.decorators -> AzoraSyntaxHighlighter.DECORATOR
        in semantics.specTypes -> AzoraSyntaxHighlighter.SPEC_TYPE
        in semantics.types -> AzoraSyntaxHighlighter.TYPE_NAME
        in semantics.errorCases -> AzoraSyntaxHighlighter.ERROR_CASE
        in semantics.enumCases -> AzoraSyntaxHighlighter.ENUM_CASE
        in semantics.functions -> AzoraSyntaxHighlighter.FUNCTION_CALL
        in semantics.properties -> AzoraSyntaxHighlighter.PROPERTY_CALL
        else -> null
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
        val typeHeads = mutableMapOf<Int, Int>()
        val callableHeads = mutableListOf<Pair<Int, Int>>()
        val anonymousCallableHeads = mutableListOf<Int>()
        val propertyHeads = mutableListOf<Pair<Int, Int>>()
        val bindingHeads = mutableListOf<Pair<Int, Int>>()

        for (i in tokens.indices) {
            val token = tokens[i]
            if (!AzoraTokenTypes.KEYWORDS.contains(token.type)) continue
            when (token.text) {
                in TYPE_DECL_KEYWORDS -> declarationNameAfter(tokens, i)?.let {
                    typeDeclarations.add(it)
                    typeHeads[it] = i
                }
                "spec" -> declarationNameAfter(tokens, i)?.let { specDeclarations.add(it) }
                // `annot @Name` - the `@` belongs to the declaration, so the
                // name it declares is one token further on.
                "annot" -> decoratorNameAfter(tokens, i)?.let { decoratorDeclarations.add(it) }
                in FUNCTION_DECL_KEYWORDS -> callableNameAfter(tokens, i)?.let { callableHeads.add(i to it) }
                "ctor", "dtor", "oper" -> anonymousCallableHeads.add(i)
                "prop" -> declarationNameAfter(tokens, i)?.let { propertyHeads.add(i to it) }
                in BINDING_KEYWORDS -> declarationNameAfter(tokens, i)?.let { bindingHeads.add(i to it) }
            }
        }

        val namedCallableScopes = callableHeads.map { (head, _) -> declarationScope(tokens, head) }
        val anonymousCallableScopes = anonymousCallableHeads.map { declarationScope(tokens, it) }
        val callableScopes = namedCallableScopes + anonymousCallableScopes
        val functionDeclarations = callableHeads.map { (head, nameIndex) ->
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                0..tokens.lastIndex,
                memberContext(nameIndex, specBodies, overrideBodies),
                head,
            )
        }
        val properties = propertyHeads.map { (head, nameIndex) ->
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                0..tokens.lastIndex,
                memberContext(nameIndex, specBodies, overrideBodies),
                head,
            )
        }
        // A `prop` declares its receiver exactly as a `func` does
        // (`prop isAtEnd[self: Self&]: Bool`), so it owns one the same way.
        val propertyScopes = propertyHeads.map { (head, _) -> declarationScope(tokens, head) }
        val parameterOwners = buildList {
            functionDeclarations.forEachIndexed { position, declaration ->
                add(declaration.copy(scope = namedCallableScopes[position]))
            }
            properties.forEachIndexed { position, declaration ->
                add(declaration.copy(scope = propertyScopes[position]))
            }
            anonymousCallableHeads.forEachIndexed { position, head ->
                add(ScopedDeclaration(tokens[head].text, head, anonymousCallableScopes[position]))
            }
        }
        val contextParameters = parameterOwners.flatMap { contextParameterDeclarations(tokens, it) }
        val receiverTokens = contextParameters.mapTo(mutableSetOf()) { it.tokenIndex }
        // A receiver is found by both passes, being an `ident:` pair inside the
        // signature. It is a receiver, so it must not also be dimmed as an
        // unused parameter when a short body never names it.
        val parameters = parameterOwners
            .flatMap { parameterDeclarations(tokens, it) }
            .filterNot { it.tokenIndex in receiverTokens }
        val fieldBodies = listOf("pack", "union", "error", "annot")
            .flatMap { declarationBodies(tokens, it) }
        val fieldHeads = bindingHeads.filter { (head, _) ->
            callableScopes.none { head in it } && fieldBodies.any { head in it }
        }
        val enumCaseDeclarations = declarationBodies(tokens, "enum").flatMap { caseDeclarations(tokens, it) }.toSet()
        val errorCaseDeclarations = declarationBodies(tokens, "error").flatMap { caseDeclarations(tokens, it) }.toSet()
        // `Expr(source: String)` - a payload's slots. They are written where a
        // parameter would be and reached where a field would be, so they read
        // as fields; what matters more is that they are read as *declarations*
        // at all, or the file appears to use a name it never binds.
        val caseSlots = (enumCaseDeclarations + errorCaseDeclarations)
            .flatMap { caseSlotDeclarations(tokens, it) }
            .map { ScopedDeclaration(tokens[it].text, it, 0..tokens.lastIndex) }
        val fields = fieldHeads.map { (_, nameIndex) ->
            ScopedDeclaration(tokens[nameIndex].text, nameIndex, 0..tokens.lastIndex)
        } + caseSlots
        val variables = bindingHeads.filterNot { it in fieldHeads }.map { (head, nameIndex) ->
            val enclosing = callableScopes.lastOrNull { head in it }
            val lexical = enclosingBlock(tokens, head)?.let { head..it.last }
            ScopedDeclaration(
                tokens[nameIndex].text,
                nameIndex,
                lexical ?: enclosing ?: (head..tokens.lastIndex),
                head = head,
            )
        }

        return Semantics(
            // The compiler's own types are always in scope: nothing declares
            // `Array` in `.az`, so no index can supply it.
            types = external.types + AzoraLanguageFacts.builtinTypes +
                typeDeclarations.map { tokens[it].text },
            specTypes = external.specTypes + specDeclarations.map { tokens[it].text },
            functions = external.functions + functionDeclarations.map { it.name },
            decorators = external.decorators + decoratorDeclarations.map { tokens[it].text },
            properties = external.properties + properties.map { it.name } + fields.map { it.name },
            computedProperties = external.computedProperties + properties.map { it.name },
            enumCases = external.enumCases + enumCaseDeclarations.map { tokens[it].text },
            errorCases = external.errorCases + errorCaseDeclarations.map { tokens[it].text },
            typeDeclarations = typeDeclarations,
            typeHeads = typeHeads,
            specDeclarations = specDeclarations,
            decoratorDeclarations = decoratorDeclarations,
            enumCaseDeclarations = enumCaseDeclarations,
            errorCaseDeclarations = errorCaseDeclarations,
            functionDeclarations = functionDeclarations,
            propertyDeclarations = properties,
            fieldDeclarations = fields,
            parameters = parameters,
            contextParameters = contextParameters,
            variables = variables,
            modulePathTokens = modulePathTokens(tokens),
            importSelectionTokens = importSelectionTokens(tokens),
            macroBodyTokens = (declarationBodies(tokens, "macro") + declarationBodies(tokens, "meta"))
                .flatMapTo(linkedSetOf()) { it },
            scopeUsageTokens = scopeUsageTokens(tokens),
        )
    }

    /** Finds the declared name immediately after a current-language declaration head. */
    private fun declarationNameAfter(tokens: List<AzoraToken>, head: Int): Int? {
        val index = nextMeaningful(tokens, head, sameLine = false) ?: return null
        val token = tokens[index]
        return index.takeIf { token.type == AzoraTokenTypes.IDENTIFIER }
    }

    /**
     * The name a decorator declaration declares - `annot @Name`.
     *
     * The `@` is part of the declaration, exactly as it is part of every use
     * site. It is written and colored as one thing, and the name after it is
     * what the rest of the file will say.
     */
    private fun decoratorNameAfter(tokens: List<AzoraToken>, head: Int): Int? {
        val at = nextMeaningful(tokens, head, sameLine = false) ?: return null
        if (tokens[at].type != AzoraTokenTypes.DECORATOR || tokens[at].text != "@") return null
        return declarationNameAfter(tokens, at)
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
    /**
     * What `@Supress(kind: .Unused)` covers in this file.
     *
     * On a declaration the row answers for that declaration and for what is
     * written inside it. On the `module` header it answers for every
     * declaration the module makes, but not for a binding inside a `func` or
     * `prop` body: a module-wide sweep is for the names the module publishes,
     * and a local nobody reads is still the author's own business.
     */
    private data class UnusedSuppression(val ranges: List<IntRange>, val moduleWide: Boolean) {
        fun covers(tokenIndex: Int, insideCallable: (Int) -> Boolean): Boolean =
            ranges.any { tokenIndex in it } || (moduleWide && !insideCallable(tokenIndex))

        companion object {
            val NONE = UnusedSuppression(emptyList(), moduleWide = false)
        }
    }

    /** Reads every `@Supress(kind: .Unused)` row and what it stands in front of. */
    private fun unusedSuppression(tokens: List<AzoraToken>): UnusedSuppression {
        val ranges = mutableListOf<IntRange>()
        var moduleWide = false
        for (i in tokens.indices) {
            if (tokens[i].type != AzoraTokenTypes.DECORATOR || tokens[i].text != "@") continue
            val first = nextMeaningful(tokens, i, sameLine = true) ?: continue
            val rowClose = if (tokens[first].type == AzoraTokenTypes.L_PAREN) {
                matchingDelimiter(tokens, first, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN) ?: continue
            } else null
            val name = if (rowClose != null) {
                decoratorRowHeads(tokens, first).firstOrNull { tokens[it].text == SUPPRESS_DECORATOR } ?: continue
            } else first
            if (tokens[name].text != SUPPRESS_DECORATOR) continue
            val open = nextMeaningful(tokens, name, sameLine = true) ?: continue
            if (tokens[open].type != AzoraTokenTypes.L_PAREN) continue
            val close = matchingDelimiter(tokens, open, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN) ?: continue
            if ((open..close).none { tokens[it].text == UNUSED_KIND }) continue

            val head = declarationHeadAfter(tokens, rowClose ?: close) ?: continue
            if (tokens[head].text == MODULE_KEYWORD) {
                moduleWide = true
                continue
            }
            val scope = declarationScope(tokens, head)
            ranges.add(minOf(head, scope.first)..maxOf(head, scope.last))
        }
        return if (ranges.isEmpty() && !moduleWide) UnusedSuppression.NONE
        else UnusedSuppression(ranges, moduleWide)
    }

    /**
     * The declaration head a decorator row stands in front of.
     *
     * Rows stack, and modifiers sit between the last of them and the keyword,
     * so the walk steps over both to reach the word that says what is being
     * declared.
     */
    private fun declarationHeadAfter(tokens: List<AzoraToken>, from: Int): Int? {
        var cursor = nextMeaningful(tokens, from, sameLine = false) ?: return null
        while (true) {
            val token = tokens[cursor]
            when {
                token.type == AzoraTokenTypes.DECORATOR && token.text == "@" -> {
                    val name = nextMeaningful(tokens, cursor, sameLine = true) ?: return null
                    if (tokens[name].type == AzoraTokenTypes.L_PAREN) {
                        val close = matchingDelimiter(tokens, name, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN)
                            ?: return null
                        cursor = nextMeaningful(tokens, close, sameLine = false) ?: return null
                        continue
                    }
                    val after = nextMeaningful(tokens, name, sameLine = true) ?: return null
                    cursor = if (tokens[after].type == AzoraTokenTypes.L_PAREN) {
                        val close = matchingDelimiter(tokens, after, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN)
                            ?: return null
                        nextMeaningful(tokens, close, sameLine = false) ?: return null
                    } else {
                        after
                    }
                }
                token.text in DECLARATION_MODIFIERS -> cursor = nextMeaningful(tokens, cursor, sameLine = false) ?: return null
                else -> return cursor
            }
        }
    }

    /**
     * The braced body of each `keyword` declaration - and nothing else.
     *
     * The search for the `{` stops where the declaration does. A signature with
     * no body is the whole point: `prop castValue[self&]: TO` inside a `bridge
     * spec`, or a `bridge func`, ends at its line, and looking past that found
     * the *next* declaration's block and called it this one's. Everything in it
     * then answered to the wrong owner - which is what let a module-wide
     * `@Supress(.Unused)` miss the enum cases and spec members of `std/core.az`,
     * each of them reading as a local inside a callable that was not there.
     */
    private fun declarationBodies(
        tokens: List<AzoraToken>,
        keyword: String,
        requireFor: Boolean = false,
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (i in tokens.indices) {
            if (!AzoraTokenTypes.KEYWORDS.contains(tokens[i].type) || tokens[i].text != keyword) continue
            val scope = declarationScope(tokens, i)
            var cursor = i + 1
            var sawFor = false
            while (cursor <= scope.last && cursor < tokens.size && tokens[cursor].type != AzoraTokenTypes.L_BRACE) {
                if (tokens[cursor].text == "for") sawFor = true
                if (tokens[cursor].type == AzoraTokenTypes.SEMICOLON) break
                cursor++
            }
            if (cursor > scope.last || cursor >= tokens.size || tokens[cursor].type != AzoraTokenTypes.L_BRACE) continue
            if (requireFor && !sawFor) continue
            matchingBrace(tokens, cursor)?.let { close ->
                if (close > cursor) ranges.add((cursor + 1) until close)
            }
        }
        return ranges
    }

    /**
     * The cases declared directly in an `enum` or `error` [body].
     *
     * A case is a name standing on its own at the top level of the body -
     * `Less`, or `OutOfBounds(index: Int, size: Int)`. Anything nested inside a
     * method, a payload list or a nested block is at a deeper delimiter depth,
     * and anything introduced by a `prop`/`func`/`var` head has that head before
     * it on its own line, so neither can be mistaken for a case.
     */
    private fun caseDeclarations(tokens: List<AzoraToken>, body: IntRange): List<Int> {
        val result = mutableListOf<Int>()
        var depth = 0
        for (index in body) {
            when (tokens[index].type) {
                AzoraTokenTypes.L_BRACE, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.L_BRACKET -> depth++
                AzoraTokenTypes.R_BRACE, AzoraTokenTypes.R_PAREN, AzoraTokenTypes.R_BRACKET -> depth--
                AzoraTokenTypes.IDENTIFIER -> {
                    if (depth != 0) continue
                    val previous = prevMeaningful(tokens, index, sameLine = true)
                    if (previous != null && tokens[previous].type != AzoraTokenTypes.COMMA) continue
                    result.add(index)
                }
            }
        }
        return result
    }

    /**
     * The slots the payload of the case at [caseName] declares.
     *
     * `Expr(source: String)` declares `source`. Read the way
     * [parameterDeclarations] reads a signature - a name followed by a `:` -
     * because a payload list is written exactly like a parameter list. A type
     * inside one is followed by a `,` or the closing paren, never a `:`, so a
     * generic argument is never mistaken for a slot.
     */
    private fun caseSlotDeclarations(tokens: List<AzoraToken>, caseName: Int): List<Int> {
        val open = nextMeaningful(tokens, caseName, sameLine = true) ?: return emptyList()
        if (tokens[open].type != AzoraTokenTypes.L_PAREN) return emptyList()
        val close = matchingParen(tokens, open, tokens.lastIndex) ?: return emptyList()
        val result = mutableListOf<Int>()
        for (i in (open + 1) until close) {
            if (tokens[i].type != AzoraTokenTypes.IDENTIFIER) continue
            val next = nextMeaningful(tokens, i, sameLine = false) ?: continue
            if (next < close && tokens[next].type == AzoraTokenTypes.COLON) result.add(i)
        }
        return result
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

    /**
     * Whether the token at [index] carries a declaration on rather than
     * starting something new.
     *
     * A brace opens a block that is still part of the declaration; the clause
     * words each introduce one - `where` its constraints, `in` and `out` its
     * contract, `scope` the body those guard.
     */
    private fun continuesDeclaration(tokens: List<AzoraToken>, index: Int): Boolean =
        tokens[index].type == AzoraTokenTypes.L_BRACE || tokens[index].text in DECLARATION_CLAUSES

    /** The words that open a clause of a callable declaration. */
    private val DECLARATION_CLAUSES = setOf("where", "in", "out", "scope")

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

    /**
     * The signature plus body of a callable declaration.
     *
     * A contracted callable has more than one braced block:
     *
     * ```
     * func dequeue[self: Self!](): T
     * in { assert self.size > 0 { "Queue is empty" } } scope {
     *     self.size--
     * }
     * ```
     *
     * Stopping at the first `{` ended the declaration at its precondition, and
     * everything the real body said - starting with its receiver - was read as
     * if it stood outside any callable at all.
     */
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
                    val next = nextMeaningful(tokens, close, sameLine = false)
                    // `} scope {` - one declaration, still going.
                    if (next == null || !continuesDeclaration(tokens, next)) return head..close
                    index = close
                }
                token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n') &&
                    parens == 0 && brackets == 0 && angles == 0 -> {
                    val next = nextMeaningful(tokens, index, sameLine = false)
                    if (next == null || !continuesDeclaration(tokens, next)) {
                        return head..(index - 1).coerceAtLeast(head)
                    }
                }
                token.type == AzoraTokenTypes.SEMICOLON && parens == 0 && brackets == 0 && angles == 0 ->
                    return head..index
            }
            index++
        }
        return head..tokens.lastIndex
    }

    /**
     * Whether the declaration at [head] has anywhere its parameters could be used.
     *
     * A signature with no body - a `spec` member, a `bridge` declaration, an
     * `annot` field list - names its parameters for the caller to read and the
     * implementer to honour. No code in it could name them, so "never used" is
     * not a fact about the declaration but about the form it is written in.
     *
     * The scan steps over the generic header, the receiver and the parameter
     * list before looking for a body or an `=`: the `=` of
     * `inclusive: Bool = true` belongs to that default value, not to an
     * implementation.
     */
    private fun hasImplementation(tokens: List<AzoraToken>, head: Int): Boolean {
        val scope = declarationScope(tokens, head)
        var cursor = head + 1
        while (cursor <= scope.last && cursor < tokens.size) {
            val token = tokens[cursor]
            when {
                token.type == AzoraTokenTypes.L_BRACE -> return true
                isOperator(token, "=") || isOperator(token, "=>") -> return true
                token.type == AzoraTokenTypes.L_PAREN ->
                    cursor = matchingDelimiter(tokens, cursor, AzoraTokenTypes.L_PAREN, AzoraTokenTypes.R_PAREN)
                        ?: return false
                token.type == AzoraTokenTypes.L_BRACKET ->
                    cursor = matchingDelimiter(tokens, cursor, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET)
                        ?: return false
                isOperator(token, "<") ->
                    cursor = (skipGenericHeader(tokens, cursor) ?: return false) - 1
            }
            cursor++
        }
        return false
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

    /**
     * The `[self: Self&, scope: Scope&]` a callable declares before its own
     * parameter list.
     *
     * A receiver is something the *call site* supplies, which is why it is
     * marked apart from the parameters the declaration invents for itself.
     *
     * Inside an `impl` the receiver's type is never in question, so `self` may
     * leave it out - `[self&]`, `[self!]`, `[self]`. It is the same receiver
     * written shorter, so it reads the same: the colon is how the *other*
     * receivers name their type, not what makes `self` one.
     */
    private fun contextParameterDeclarations(
        tokens: List<AzoraToken>,
        function: ScopedDeclaration,
    ): List<ScopedDeclaration> {
        val signatureEnd = function.scope.firstOrNull { tokens[it].type == AzoraTokenTypes.L_BRACE }
            ?: function.scope.last
        var cursor = nextMeaningful(tokens, function.tokenIndex, sameLine = false) ?: return emptyList()
        if (isOperator(tokens.getOrNull(cursor) ?: return emptyList(), "<")) {
            cursor = skipGenericHeader(tokens, cursor) ?: return emptyList()
        }
        if (tokens.getOrNull(cursor)?.type != AzoraTokenTypes.L_BRACKET) return emptyList()
        val close = matchingDelimiter(tokens, cursor, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.R_BRACKET)
            ?: return emptyList()
        val declarations = mutableListOf<ScopedDeclaration>()
        for (i in (cursor + 1) until close.coerceAtMost(signatureEnd)) {
            if (tokens[i].type != AzoraTokenTypes.IDENTIFIER) continue
            if (tokens[i].text == SELF) {
                declarations.add(ScopedDeclaration(tokens[i].text, i, function.scope))
                continue
            }
            val next = nextMeaningful(tokens, i, sameLine = false) ?: continue
            if (next < close && tokens[next].type == AzoraTokenTypes.COLON) {
                declarations.add(ScopedDeclaration(tokens[i].text, i, function.scope))
            }
        }
        return declarations.distinctBy { it.tokenIndex }
    }

    /**
     * The segments of an `import` clause that are *path* - the part that names
     * where to look rather than what to take.
     *
     * `import std.container.[List, map]` is a path (`std.container`) and a
     * selection (`List`, `map`). Only the path is a module chain, so only the
     * path wears the module italic; what is selected is a declaration and keeps
     * whatever color that declaration has, exactly as at a use site. The `*` of
     * `import std.io.*` is punctuation and is never an identifier to begin with.
     *
     * A segment followed by a `.` is always path - something is being looked up
     * inside it. A segment that ends a clause is the selection unless it is
     * lowercase, which is how a module is spelled and a type is not.
     */
    private fun modulePathTokens(tokens: List<AzoraToken>): Set<Int> {
        val byOffset = HashMap<Int, Int>(tokens.size)
        for (i in tokens.indices) byOffset[tokens[i].start] = i
        val source = buildString { for (token in tokens) append(token.text) }
        val result = linkedSetOf<Int>()
        for (clause in AzoraImports.clauses(source)) {
            for (segment in clause.segments) {
                if (segment.isSelection) continue
                byOffset[segment.start]?.let(result::add)
            }
        }
        // `module app.main` is a path too, and is not an import clause.
        for (i in tokens.indices) {
            if (!AzoraTokenTypes.KEYWORDS.contains(tokens[i].type) || tokens[i].text != "module") continue
            var cursor = i + 1
            while (cursor < tokens.size) {
                val token = tokens[cursor]
                if (token.type == AzoraTokenTypes.WHITE_SPACE && token.text.contains('\n')) break
                if (token.type == AzoraTokenTypes.IDENTIFIER) result.add(cursor)
                cursor++
            }
        }
        return result
    }

    /**
     * The *names* a `${…}` hole inside a `macro` declaration binds.
     *
     * Everywhere else `${…}` splices a value in - a name built at compile time,
     * a hole in a string - and reads as the blue that says "the literal stops
     * here". Inside a macro the same spelling is a *pattern* hole: `${key: value}`
     * names two things the expansion will bind, and neither survives expansion.
     *
     * The whole hole is gold: the `${` that opens it, the names it binds and
     * the `}` that closes it. The braces are not the string world's - nothing
     * here interpolates - and leaving them blue put the one blue thing in a
     * macro body around the one gold thing. The `:` between the names stays
     * punctuation, which is what keeps the pair readable as a pair.
     */
    private fun macroHoleTokens(tokens: List<AzoraToken>): Set<Int> =
        holeRanges(tokens).flatMapTo(linkedSetOf()) { range ->
            range.filter {
                tokens[it].type == AzoraTokenTypes.IDENTIFIER ||
                    tokens[it].type == AzoraTokenTypes.INTERPOLATION_START ||
                    tokens[it].type == AzoraTokenTypes.INTERPOLATION_END
            }
        }

    /**
     * The `$` that opens each macro hole, as a source offset.
     *
     * The lexer reads `${` as one token, so the `$` cannot be colored by
     * claiming a token: the annotator narrows the range to this offset and
     * leaves the `{` to read as the punctuation it is.
     */
    fun macroHoleSigils(tokens: List<AzoraToken>): Set<Int> =
        holeRanges(tokens).mapTo(linkedSetOf()) { tokens[it.first].start }

    /** Each `${…}` in a `macro` or `meta` body, as the token range it spans. */
    private fun holeRanges(tokens: List<AzoraToken>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (body in declarationBodies(tokens, "macro") + declarationBodies(tokens, "meta")) {
            var open = -1
            var depth = 0
            for (index in body) {
                when {
                    tokens[index].type == AzoraTokenTypes.INTERPOLATION_START -> {
                        if (depth == 0) open = index
                        depth++
                    }
                    tokens[index].type == AzoraTokenTypes.INTERPOLATION_END && depth > 0 -> {
                        depth--
                        if (depth == 0 && open >= 0) {
                            result.add(open..index)
                            open = -1
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * The names an `import` clause selects, as opposed to the path it walks.
     *
     * `import std.[reflection::reflect, serializer::Serializable]` walks to
     * `std.reflection` and `std.serializer` and selects `reflect` and
     * `Serializable` out of them. The path is a place; the selections are
     * declarations, and they read as whatever they are.
     */
    private fun importSelectionTokens(tokens: List<AzoraToken>): Set<Int> {
        val byOffset = HashMap<Int, Int>(tokens.size)
        for (i in tokens.indices) byOffset[tokens[i].start] = i
        val source = buildString { for (token in tokens) append(token.text) }
        val result = linkedSetOf<Int>()
        for (clause in AzoraImports.clauses(source)) {
            for (segment in clause.segments) {
                if (!segment.isSelection) continue
                byOffset[segment.start]?.let(result::add)
            }
        }
        return result
    }

    /**
     * All segments in a scope-qualified path are one semantic span. The old
     * implementation only marked the first lowercase segment, leaving
     * `scope ide::editor` half-styled and making `std::math::sqrt` inconsistent.
     */
    private fun scopeUsageTokens(tokens: List<AzoraToken>): Set<Int> {
        val result = linkedSetOf<Int>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != AzoraTokenTypes.IDENTIFIER) continue
            if (isScopeDeclarationSegment(tokens, i)) {
                addScopePath(tokens, i, result, includeLast = true)
                continue
            }
            val next = nextMeaningful(tokens, i, sameLine = true)
            // At a use site, only owning scope segments are italic. The final
            // segment is the actual symbol and keeps its resolved function/type/
            // value style (`std::reflect`, `std::Int`, `entity::field`).
            if (next != null && isOperator(tokens[next], "::")) {
                addScopePath(tokens, i, result, includeLast = false)
            }
        }
        return result
    }

    private fun addScopePath(
        tokens: List<AzoraToken>,
        index: Int,
        result: MutableSet<Int>,
        includeLast: Boolean,
    ) {
        // `std.container.list::ArrayList` reaches inside `std.container.list`,
        // and all three segments are that module. Both separators are walked,
        // because the caller only asks about a path that holds a `::` - a plain
        // `self.data.length` never reaches here and is never italic.
        var first = index
        while (true) {
            val separator = prevMeaningful(tokens, first, sameLine = true) ?: break
            val segment = prevMeaningful(tokens, separator, sameLine = true) ?: break
            if (!joinsAPath(tokens[separator])) break
            if (tokens[segment].type != AzoraTokenTypes.IDENTIFIER) break
            first = segment
        }
        val segments = mutableListOf<Int>()
        var cursor = first
        while (true) {
            if (tokens[cursor].type == AzoraTokenTypes.IDENTIFIER) segments.add(cursor)
            val separator = nextMeaningful(tokens, cursor, sameLine = true) ?: break
            if (!joinsAPath(tokens[separator])) break
            val segment = nextMeaningful(tokens, separator, sameLine = true) ?: break
            if (tokens[segment].type != AzoraTokenTypes.IDENTIFIER) break
            cursor = segment
        }
        result.addAll(if (includeLast) segments else segments.dropLast(1))
    }

    /** Whether [token] joins two segments of a qualified path. */
    private fun joinsAPath(token: AzoraToken): Boolean =
        token.type == AzoraTokenTypes.DOT || isOperator(token, "::")

    private fun isScopeDeclarationSegment(tokens: List<AzoraToken>, index: Int): Boolean {
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

    /** A use of a `[self: Self&]`-style receiver inside the callable that declares it. */
    private fun isContextParameterReference(
        tokens: List<AzoraToken>,
        index: Int,
        receivers: List<ScopedDeclaration>,
    ): Boolean = receivers.any {
        index > it.tokenIndex && index in it.scope && tokens[index].text == it.name
    }

    private fun isLocalBindingReference(
        tokens: List<AzoraToken>,
        index: Int,
        semantics: Semantics,
    ): Boolean = semantics.variables.any {
        index > it.tokenIndex && index in it.scope && tokens[index].text == it.name
    }

    /** Every token that is a declared name, so a declaration cannot use itself. */
    private fun declarationTokens(semantics: Semantics): Set<Int> = buildSet {
        addAll(semantics.functionDeclarations.map { it.tokenIndex })
        addAll(semantics.propertyDeclarations.map { it.tokenIndex })
        addAll(semantics.fieldDeclarations.map { it.tokenIndex })
        addAll(semantics.parameters.map { it.tokenIndex })
        addAll(semantics.contextParameters.map { it.tokenIndex })
        addAll(semantics.variables.map { it.tokenIndex })
        addAll(semantics.typeDeclarations)
        addAll(semantics.specDeclarations)
        addAll(semantics.decoratorDeclarations)
        addAll(semantics.enumCaseDeclarations)
        addAll(semantics.errorCaseDeclarations)
    }

    /**
     * Whether [token] writes the name [name].
     *
     * A keyword token counts. `func take` declares a function whose name is
     * spelled like the `take` keyword, and the lexer still calls the call site
     * a keyword - refusing to see it there is what made a called function look
     * dead.
     */
    private fun names(token: AzoraToken, name: String): Boolean =
        token.text == name &&
            (token.type == AzoraTokenTypes.IDENTIFIER || AzoraTokenTypes.KEYWORDS.contains(token.type))

    /**
     * What to call a member in the warning about it.
     *
     * A spec member says so, because what to do about it differs: nothing calls
     * `From::from` means nothing implements `From` either.
     */
    private fun kindOf(declaration: ScopedDeclaration, kind: String): String =
        if (declaration.context == MemberContext.SPEC) "Spec ${kind.lowercase()}" else kind

    /** How an unused declaration should be dealt with. */
    enum class UnusedRemedy {
        /** Nothing names it and nothing has to: it can go. */
        REMOVE,

        /**
         * It is part of a signature, so it stays and loses only its name.
         *
         * A receiver and a parameter are what the call site passes; deleting
         * either rewrites the contract. Spelling the name `_` says the value
         * arrives and is deliberately ignored, and `_` may be used as often as
         * a signature needs it.
         */
        RENAME_TO_HOLE,
    }

    /** A declaration nothing names, what kind of thing it is, and what to do about it. */
    data class UnusedDeclaration(
        val name: String,
        val kind: String,
        val remedy: UnusedRemedy,
        /** The name's own offsets, which is what the warning underlines. */
        val start: Int,
        val end: Int,
        /** For [UnusedRemedy.REMOVE], the whole declaration the fix deletes. */
        val removalStart: Int = start,
        val removalEnd: Int = end,
    )

    /**
     * The declarations in a file that nothing in the file names.
     *
     * An unused declaration is *reported*, not recolored: it is still a `func`,
     * a `prop` or a parameter, and dimming it made a file's colors depend on
     * where the caller happened to live. A reader scanning an `impl` reads the
     * color to learn what a member *is*; whether anyone calls it is a different
     * question and gets a different channel. The annotator turns each of these
     * into a warning with a fix, after checking that nothing outside the file -
     * a test, an implementor, most often - names it either.
     *
     * A spec member is reported like any other: a spec nobody implements and
     * nobody calls is as dead as an uncalled `func`, and the project-wide check
     * is what keeps the ones with implementors quiet. Spec *implementations* are
     * excluded - an `impl … for …` member is required by the spec it satisfies,
     * so it is never dead and could not be deleted if it were.
     */
    /**
     * Every name this file binds - declared, bound, or received.
     *
     * A name the file introduces itself needs nothing from anywhere else, which
     * is what makes this the guard on any question of the form "is this name
     * reachable?". A local `fin queue` is not a module that failed to be
     * imported.
     */
    fun boundNames(tokens: List<AzoraToken>): Set<String> {
        val semantics = collectSemantics(tokens, AzoraSemanticSymbols.EMPTY)
        val result = linkedSetOf<String>()
        result += semantics.types
        result += semantics.specTypes
        result += semantics.functions
        result += semantics.decorators
        result += semantics.properties
        result += semantics.enumCases
        result += semantics.errorCases
        for (declaration in semantics.variables + semantics.parameters + semantics.contextParameters) {
            result += declaration.name
        }
        result += introducedNames(tokens)
        return result
    }

    /**
     * Every name a *form* introduces, read straight off the tokens.
     *
     * [collectSemantics] knows the declarations it colors; a loop's row, a
     * pattern's capture and a lambda's parameter are bound without being
     * colored as anything of their own, so they are not in it. They still bind,
     * and a question of the form "does this name resolve?" has to know that or
     * it reports the loop variable of every `for` in the file.
     *
     * Deliberately generous: a name here is only ever *excused* from the
     * unknown-name check, so over-reading binds nothing that was not written and
     * under-reading is what produces false errors.
     */
    private fun introducedNames(tokens: List<AzoraToken>): Set<String> {
        val names = linkedSetOf<String>()
        val meaningful = tokens.filter {
            it.type != AzoraTokenTypes.WHITE_SPACE && it.type !in AzoraTokenTypes.COMMENTS
        }
        for (index in meaningful.indices) {
            val token = meaningful[index]
            when {
                token.text == "for" -> names += loopBindings(meaningful, index)
                // `scope name`, `catch e`, `rescue e` - each binds the name
                // that follows it.
                token.text == "scope" || token.text == "catch" || token.text == "rescue" -> {
                    val next = meaningful.getOrNull(index + 1)
                    if (next != null && next.type == AzoraTokenTypes.IDENTIFIER) names += next.text
                }
                token.type == AzoraTokenTypes.L_PAREN -> names += patternCaptures(meaningful, index)
                token.type == AzoraTokenTypes.L_BRACE -> names += lambdaParameters(meaningful, index)
            }
        }
        return names
    }

    /**
     * `for row in rows`, `for [head, tail] in rows`, `inline for n in … with i`.
     *
     * Everything between `for` and `in` is bound, however it is spelled, and a
     * `with` after the iterable binds the index beside it.
     */
    private fun loopBindings(tokens: List<AzoraToken>, start: Int): List<String> {
        val names = mutableListOf<String>()
        var cursor = start + 1
        while (cursor < tokens.size && tokens[cursor].text != "in" && tokens[cursor].type != AzoraTokenTypes.L_BRACE) {
            // `for i: Int in 0..mid` - what follows the colon is the type the
            // row is declared to have. It is named here, not bound here.
            if (tokens[cursor].type == AzoraTokenTypes.COLON) break
            if (tokens[cursor].type == AzoraTokenTypes.IDENTIFIER) names += tokens[cursor].text
            cursor++
        }
        while (cursor < tokens.size && tokens[cursor].text != "in" && tokens[cursor].type != AzoraTokenTypes.L_BRACE) {
            cursor++
        }
        while (cursor < tokens.size && tokens[cursor].type != AzoraTokenTypes.L_BRACE) {
            val next = tokens.getOrNull(cursor + 1)
            if (tokens[cursor].text == "with" && next != null && next.type == AzoraTokenTypes.IDENTIFIER) {
                names += next.text
            }
            cursor++
        }
        return names
    }

    /**
     * `Case(capture) ->` - what a pattern takes apart, it binds.
     *
     * The arrow is what tells a pattern from a call: the same parentheses with
     * a body after them are arguments, and those are uses rather than bindings.
     */
    private fun patternCaptures(tokens: List<AzoraToken>, start: Int): List<String> {
        val captured = mutableListOf<String>()
        var depth = 0
        var cursor = start
        while (cursor < tokens.size) {
            val current = tokens[cursor]
            if (current.type == AzoraTokenTypes.L_PAREN) depth++
            if (current.type == AzoraTokenTypes.R_PAREN) {
                depth--
                if (depth == 0) break
            }
            if (depth == 1 && current.type == AzoraTokenTypes.IDENTIFIER) captured += current.text
            cursor++
        }
        return if (tokens.getOrNull(cursor + 1)?.text == "->") captured else emptyList()
    }

    /**
     * `{ x: Int -> … }` - a lambda's parameters are the names before its arrow.
     *
     * A block has no arrow, so nothing is read from one; a type after `:` is a
     * use of that type and not a name the lambda binds.
     */
    private fun lambdaParameters(tokens: List<AzoraToken>, start: Int): List<String> {
        val params = mutableListOf<String>()
        var cursor = start + 1
        while (cursor < tokens.size) {
            val current = tokens[cursor]
            if (current.text == "->") return params
            if (current.type == AzoraTokenTypes.L_BRACE ||
                current.type == AzoraTokenTypes.R_BRACE ||
                current.type == AzoraTokenTypes.NEWLINE
            ) return emptyList()
            if (current.type == AzoraTokenTypes.IDENTIFIER && tokens.getOrNull(cursor - 1)?.text != ":") {
                params += current.text
            }
            cursor++
        }
        return emptyList()
    }

    fun unusedDeclarations(
        tokens: List<AzoraToken>,
        symbols: AzoraSemanticSymbols = AzoraSemanticSymbols.EMPTY,
    ): List<UnusedDeclaration> {
        val semantics = collectSemantics(tokens, symbols)
        val declarationTokens = declarationTokens(semantics)

        fun isUsed(name: String, tokenIndex: Int, scope: IntRange, mustFollow: Boolean): Boolean =
            scope.any { index ->
                index != tokenIndex &&
                    (!mustFollow || index > tokenIndex) &&
                    index !in declarationTokens &&
                    names(tokens[index], name)
            }

        // Every name a body can bind, so a use can be attributed to the one
        // declaration that owns it. Without this an inner `fin value` reading
        // its own binding would keep an outer `value` alive.
        val bindings = semantics.variables + semantics.parameters + semantics.contextParameters

        /** The innermost binding of [name] that is in scope at [index]. */
        fun ownerAt(name: String, index: Int): ScopedDeclaration? =
            bindings.filter { it.name == name && it.tokenIndex < index && index in it.scope }
                .maxByOrNull { it.tokenIndex }

        fun isUsedAsBinding(declaration: ScopedDeclaration): Boolean =
            declaration.scope.any { index ->
                index > declaration.tokenIndex &&
                    index !in declarationTokens &&
                    names(tokens[index], declaration.name) &&
                    ownerAt(declaration.name, index) === declaration
            }

        val result = mutableListOf<UnusedDeclaration>()

        // `@Supress(kind: .Unused)` answers for what it stands in front of.
        val suppression = unusedSuppression(tokens)
        val callableBodies = declarationBodies(tokens, "func") + declarationBodies(tokens, "prop")
        fun suppressed(tokenIndex: Int): Boolean =
            suppression.covers(tokenIndex) { index -> callableBodies.any { index in it } }

        /** Reports a declaration the fix can delete outright. */
        fun removable(name: String, tokenIndex: Int, head: Int, kind: String) {
            if (suppressed(tokenIndex)) return
            val removal = removalRange(tokens, if (head >= 0) head else tokenIndex)
            result.add(
                UnusedDeclaration(
                    name, kind, UnusedRemedy.REMOVE,
                    tokens[tokenIndex].start, tokens[tokenIndex].end,
                    removal.first, removal.second,
                )
            )
        }

        /** Reports a declaration whose name is all the fix may touch. */
        fun renameable(name: String, tokenIndex: Int, kind: String) {
            if (suppressed(tokenIndex)) return
            result.add(
                UnusedDeclaration(
                    name, kind, UnusedRemedy.RENAME_TO_HOLE,
                    tokens[tokenIndex].start, tokens[tokenIndex].end,
                )
            )
        }

        val whole = 0..tokens.lastIndex
        for (declaration in semantics.functionDeclarations) {
            if (declaration.context == MemberContext.OVERRIDE || declaration.name == "main") continue
            if (!isUsed(declaration.name, declaration.tokenIndex, whole, mustFollow = false)) {
                removable(declaration.name, declaration.tokenIndex, declaration.head, kindOf(declaration, "Function"))
            }
        }
        for (declaration in semantics.propertyDeclarations) {
            if (declaration.context == MemberContext.OVERRIDE) continue
            if (!isUsed(declaration.name, declaration.tokenIndex, whole, mustFollow = false)) {
                removable(declaration.name, declaration.tokenIndex, declaration.head, kindOf(declaration, "Property"))
            }
        }
        for (index in semantics.typeDeclarations) {
            if (!isUsed(tokens[index].text, index, whole, mustFollow = false)) {
                removable(tokens[index].text, index, semantics.typeHeads[index] ?: -1, "Type")
            }
        }
        for (index in semantics.enumCaseDeclarations) {
            if (!isUsed(tokens[index].text, index, whole, mustFollow = false)) {
                removable(tokens[index].text, index, -1, "Enum case")
            }
        }
        for (index in semantics.errorCaseDeclarations) {
            if (!isUsed(tokens[index].text, index, whole, mustFollow = false)) {
                removable(tokens[index].text, index, -1, "Error case")
            }
        }
        for (declaration in semantics.variables) {
            if (!isUsedAsBinding(declaration)) {
                removable(declaration.name, declaration.tokenIndex, declaration.head, "Binding")
            }
        }
        // A receiver is used where it is written, and nowhere else. A body used
        // to be able to reach state without naming it - `size == 0` meaning
        // `self.size == 0` - so a bare member name had to count as a use.
        // UPGRADE_PLAN S7.2 made that an error: a field and a method both belong
        // to the receiver and both spell it, which leaves a body that never
        // writes `self` genuinely not using the one it declared.
        // A declaration with no body has nowhere to name what it declares, so
        // neither its receiver nor its parameters can be unused there.
        for (declaration in semantics.contextParameters) {
            if (!hasImplementation(tokens, declaration.scope.first)) continue
            if (!isUsedAsBinding(declaration)) {
                renameable(declaration.name, declaration.tokenIndex, "Receiver")
            }
        }
        for (declaration in semantics.parameters) {
            if (!hasImplementation(tokens, declaration.scope.first)) continue
            if (!isUsedAsBinding(declaration)) {
                renameable(declaration.name, declaration.tokenIndex, "Parameter")
            }
        }
        return result
            .filterNot { it.name == HOLE_NAME }
            .distinctBy { it.start }
            .sortedBy { it.start }
    }

    /**
     * The offsets a "remove this declaration" fix must cut.
     *
     * A declaration is not only its keyword: the modifiers in front of it and
     * the decorators above it belong to it too, and leaving either behind would
     * turn a tidy-up into a syntax error. The cut also takes the line's leading
     * indentation and its closing newline, so removing a member does not leave
     * a blank, indented line where it stood.
     */
    private fun removalRange(tokens: List<AzoraToken>, head: Int): Pair<Int, Int> {
        var first = head
        while (true) {
            val previous = prevMeaningful(tokens, first, sameLine = false) ?: break
            val token = tokens[previous]
            when {
                AzoraTokenTypes.KEYWORDS.contains(token.type) && token.text in DECLARATION_MODIFIERS ->
                    first = previous
                else -> {
                    val decorator = decoratorStartEndingAt(tokens, previous) ?: break
                    first = decorator
                }
            }
        }
        val last = declarationScope(tokens, head).last
        var start = tokens[first].start
        // Take the indentation this declaration sits on, but not the newline
        // that ended the line before it.
        tokens.getOrNull(first - 1)
            ?.takeIf { it.type == AzoraTokenTypes.WHITE_SPACE && !it.text.contains('\n') }
            ?.let { start = it.start }
        var end = tokens[last].end
        tokens.getOrNull(last + 1)
            ?.takeIf { it.type == AzoraTokenTypes.WHITE_SPACE && it.text.contains('\n') }
            ?.let { end = it.start + it.text.indexOf('\n') + 1 }
        return start to end
    }

    /** The `@` of a decorator application whose last token is [index], if that is one. */
    private fun decoratorStartEndingAt(tokens: List<AzoraToken>, index: Int): Int? {
        var cursor = index
        if (tokens[cursor].type == AzoraTokenTypes.R_PAREN) {
            cursor = openingParen(tokens, cursor) ?: return null
            cursor = prevMeaningful(tokens, cursor, sameLine = true) ?: return null
        }
        if (!isSigilName(tokens[cursor])) return null
        while (true) {
            val previous = prevMeaningful(tokens, cursor, sameLine = true) ?: return null
            if (tokens[previous].type == AzoraTokenTypes.DECORATOR && tokens[previous].text == "@") return previous
            if (!isOperator(tokens[previous], "::")) return null
            val segment = prevMeaningful(tokens, previous, sameLine = true) ?: return null
            if (!isSigilName(tokens[segment])) return null
            cursor = segment
        }
    }

    /** The `(` matching the `)` at [close]. */
    private fun openingParen(tokens: List<AzoraToken>, close: Int): Int? {
        var depth = 0
        for (index in close downTo 0) {
            when (tokens[index].type) {
                AzoraTokenTypes.R_PAREN -> depth++
                AzoraTokenTypes.L_PAREN -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** The name that says "this value arrives and is deliberately ignored". */
    const val HOLE_NAME = "_"

    private val DECLARATION_MODIFIERS = AzoraLanguageFacts.modifierKeywords

    /** `@Supress(kind: .Unused)` - the decorator `std/core.az` declares. */
    private const val SUPPRESS_DECORATOR = "Supress"

    /** The `SupressKind` case that silences "is never used". */
    private const val UNUSED_KIND = "Unused"

    /** The word a module header opens with; it is not a keyword token. */
    private const val MODULE_KEYWORD = "module"

    private fun isOperator(token: AzoraToken, value: String): Boolean =
        token.type == AzoraTokenTypes.OPERATOR && token.text == value

    private val IMPLICIT_PARAMETERS = AzoraLanguageFacts.implicitParameters

    private const val SELF = AzoraLanguageFacts.receiverName

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

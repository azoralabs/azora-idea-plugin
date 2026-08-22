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

package org.azora.lang.idea.symbol

import com.intellij.openapi.project.Project

/**
 * Where an identifier sits in its source, as far as name resolution cares.
 *
 * @param name the identifier itself.
 * @param qualifier the receiver path written before it, outermost first —
 *   `["config", "server"]` for `config.server.port`.
 * @param separator the `.` or `::` that attached [name] to its [qualifier],
 *   or `null` when the name is unqualified.
 */
data class AzoraReference(
    val name: String,
    val qualifier: List<String> = emptyList(),
    val separator: String? = null,
) {
    /** Whether the name is written against a receiver. */
    val isQualified: Boolean get() = qualifier.isNotEmpty()
}

/**
 * Resolves Azora names to their declarations.
 *
 * Resolution walks outward the way the language does — locals and parameters,
 * then `self`, then the file, then imported modules, then the rest of the
 * project, then the standard library and dependencies — so a local binding
 * shadows a top-level function of the same name instead of jumping to whichever
 * declaration the index happened to list first.
 */
class AzoraResolver(
    private val project: Project?,
    private val service: AzoraSymbolService,
) {

    /**
     * Resolves the reference at [offset] to its declarations, best match first.
     *
     * @param filePath the file being edited.
     * @param content the file's full text.
     * @param offset a caret offset inside the identifier.
     * @return the matching declarations; empty when nothing resolves.
     */
    fun resolve(filePath: String, content: String, offset: Int): List<SymbolInfo> {
        val reference = referenceAt(content, offset) ?: return emptyList()
        return resolve(reference, filePath, content, offset)
    }

    /** Resolves an already-extracted [reference]. */
    fun resolve(
        reference: AzoraReference,
        filePath: String,
        content: String,
        offset: Int,
    ): List<SymbolInfo> {
        if (reference.isQualified) {
            val members = membersOfQualifier(reference.qualifier, filePath, content, offset)
            val hit = preferredCandidates(
                members.filter { it.name == reference.name },
                content,
                offset,
                qualified = true,
            )
            if (hit.isNotEmpty()) return hit
            // A qualified name may still be a module member reached by its
            // short path, so fall through to the unqualified search.
        }

        val name = reference.name
        val fileSymbols = service.getSymbolsForFile(filePath, content)
        declarationSymbolAt(fileSymbols, name, offset)?.let { return listOf(it) }
        declarationAt(content, offset)?.takeIf { it.name == name }?.let { return listOf(it) }
        localsInScope(content, offset).lastOrNull { it.name == name }?.let { return listOf(it) }

        selfType(content, offset)?.let { type ->
            service.getMembersForType(type, filePath, content, project)
                .filter { it.name == name }
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        preferredCandidates(fileSymbols.filter { it.name == name }, content, offset)
            .takeIf { it.isNotEmpty() }?.let { return it }
        preferredCandidates(findInMembers(fileSymbols, name), content, offset, qualified = true)
            .takeIf { it.isNotEmpty() }?.let { return it }

        val visible = service.getAllVisibleSymbols(project, filePath, content)
        val imported = importedModules(content)
        if (imported.isNotEmpty()) {
            visible.asSequence()
                .filter { it.kind == SymbolKind.SCOPE && it.name in imported }
                .flatMap { it.members.asSequence() }
                .filter { it.name == name }
                .toList()
                .let { preferredCandidates(it, content, offset) }
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        preferredCandidates(visible.filter { it.name == name && it.filePath != null }, content, offset)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        return preferredCandidates(findInMembers(visible, name), content, offset, qualified = true)
    }

    /**
     * Narrows same-spelled candidates by the use site before returning them.
     * This keeps navigation tied to a symbol's role rather than to a text
     * match: `Thing(...)` asks for a type, `make(...)` asks for a callable,
     * and `value.field` asks for a value-like member.
     */
    private fun preferredCandidates(
        candidates: List<SymbolInfo>,
        content: String,
        offset: Int,
        qualified: Boolean = false,
    ): List<SymbolInfo> {
        if (candidates.isEmpty()) return emptyList()
        val start = wordStart(content, offset)
        val end = wordEnd(content, start)
        val next = nextNonWhitespace(content, end)
        val previous = previousWordAt(content, start)
        val callable = candidates.filter { it.kind in CALLABLE_KINDS }
        val types = candidates.filter { it.kind in TYPE_KINDS }
        val values = candidates.filter { it.kind in VALUE_KINDS }
        val annotations = candidates.filter { it.kind == SymbolKind.ANNOT }
        val macros = candidates.filter { it.kind == SymbolKind.MACRO }

        if (isSigilReference(content, start)) {
            if (content.substring(start, end).firstOrNull()?.isUpperCase() == true && annotations.isNotEmpty()) return annotations
            if (macros.isNotEmpty()) return macros
        }
        if (isTypeUse(content, start, previous) && types.isNotEmpty()) return types
        if (next == '(') {
            if (callable.isNotEmpty()) return callable
            if (types.isNotEmpty()) return types
        }
        // Generic call/property syntax (`reflect<T>`, `map<T>(…)`) belongs to
        // the resolved callable whenever one exists. A generic type remains a
        // type in a syntactic type context handled above.
        if (next == '<') {
            if (callable.isNotEmpty()) return callable
            if (types.isNotEmpty()) return types
        }
        if (qualified && values.isNotEmpty()) return values
        return candidates
    }

    /**
     * The members offered after a `.` or `::` on the given receiver path.
     *
     * The path may name a variable (whose type's members are offered), a type,
     * a zone, a module — including one reached through its short alias after an
     * import — or a chain of those.
     */
    fun membersOfQualifier(
        qualifier: List<String>,
        filePath: String,
        content: String,
        offset: Int,
    ): List<SymbolInfo> {
        if (qualifier.isEmpty()) return emptyList()

        // A dotted module or zone path resolves as a whole.
        service.resolveScopePath(qualifier, filePath, content, project)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        var members = membersOfRoot(qualifier.first(), filePath, content, offset)
        for (segment in qualifier.drop(1)) {
            val next = members.firstOrNull { it.name == segment } ?: return emptyList()
            members = when {
                next.members.isNotEmpty() -> next.members
                next.type != null -> service.getMembersForType(
                    simpleTypeName(next.type), filePath, content, project
                )
                else -> return emptyList()
            }
        }
        return members
    }

    /** The members of the first segment of a receiver path. */
    private fun membersOfRoot(
        root: String,
        filePath: String,
        content: String,
        offset: Int,
    ): List<SymbolInfo> {
        if (root == "self") {
            val type = selfType(content, offset) ?: return emptyList()
            return service.getMembersForType(type, filePath, content, project)
        }

        // A local binding or parameter: offer the members of its type.
        localsInScope(content, offset).lastOrNull { it.name == root }?.type?.let { type ->
            val members = service.getMembersForType(simpleTypeName(type), filePath, content, project)
            if (members.isNotEmpty()) return members
        }

        // A type, enum, zone or solo named directly.
        val direct = service.getMembersForType(root, filePath, content, project)
        if (direct.isNotEmpty()) return direct

        val visible = service.getAllVisibleSymbols(project, filePath, content)
        visible.firstOrNull { it.name == root && it.members.isNotEmpty() }?.let { return it.members }

        // A field or variable declared elsewhere: follow its declared type.
        visible.firstOrNull { it.name == root && it.type != null }?.type?.let { type ->
            return service.getMembersForType(simpleTypeName(type), filePath, content, project)
        }
        return emptyList()
    }

    /**
     * Every binding in scope at [offset]: parameters of the enclosing
     * declaration, loop bindings, and current `var`/`val`/`fin`/`let`
     * declarations. A declaration is included only when its lexical brace
     * scope contains the use site, so a same-named binding in a completed
     * sibling block cannot steal navigation.
     */
    fun localsInScope(content: String, offset: Int): List<SymbolInfo> {
        val caret = offset.coerceIn(0, content.length)
        val code = codeOnly(content)
        val blocks = sourceBlocks(code)
        val result = mutableListOf<SymbolInfo>()

        for (match in BINDING.findAll(code)) {
            val nameOffset = match.groups[2]!!.range.first
            if (nameOffset >= caret || !scopeContains(blocks, nameOffset, caret)) continue
            val keyword = match.groupValues[1]
            val declaredType = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
            // The scope scanner masks literals to preserve brace structure.
            // Read the initializer back from the original source so
            // `fin label = "hello"` still infers `String`.
            val initializer = match.groups[4]?.range
                ?.let { content.substring(it.first, it.last + 1) }
                ?.trim().orEmpty()
            val kind = if (keyword in MUTABLE_BINDINGS) SymbolKind.VAR else SymbolKind.FIN
            result.add(
                SymbolInfo(
                    name = match.groupValues[2],
                    kind = kind,
                    type = declaredType ?: inferLiteralType(initializer),
                    line = lineOf(content, nameOffset),
                    offset = nameOffset,
                    isMutable = kind == SymbolKind.VAR,
                    defaultValueText = initializer.takeIf { it.isNotEmpty() },
                )
            )
        }

        for (match in FOR_BINDING.findAll(code)) {
            val nameOffset = match.groups[1]!!.range.first
            if (nameOffset >= caret) continue
            val bodyOpen = code.indexOf('{', match.range.last + 1)
            val body = bodyOpen.takeIf { it >= 0 }?.let { open -> blocks.firstOrNull { it.open == open } }
            if (body == null || caret !in (body.open + 1)..body.close) continue
            result.add(
                SymbolInfo(
                    name = match.groupValues[1],
                    kind = SymbolKind.FIN,
                    line = lineOf(content, nameOffset),
                    offset = nameOffset,
                )
            )
        }

        for (signature in callableSignatures(code)) {
            if (caret in signature.scopeStart..signature.scopeEnd || caret in signature.headerStart..signature.headerEnd) {
                result.addAll(parameterSymbols(signature, content))
                result.addAll(typeParameterSymbols(signature, content))
            }
        }

        return result.distinctBy { Triple(it.offset, it.name, it.kind) }.sortedBy { it.offset }
    }

    /** Returns a local/parameter declaration when the caret is on its symbol. */
    private fun declarationAt(content: String, offset: Int): SymbolInfo? {
        val caret = offset.coerceIn(0, content.length)
        val code = codeOnly(content)
        BINDING.findAll(code).firstOrNull { match ->
            match.groups[2]?.range?.contains(caret) == true
        }?.let { match ->
            val keyword = match.groupValues[1]
            val kind = if (keyword in MUTABLE_BINDINGS) SymbolKind.VAR else SymbolKind.FIN
            val nameOffset = match.groups[2]!!.range.first
            return SymbolInfo(
                name = match.groupValues[2],
                kind = kind,
                type = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    ?: match.groups[4]?.range
                        ?.let { content.substring(it.first, it.last + 1) }
                        ?.let(::inferLiteralType),
                line = lineOf(content, nameOffset),
                offset = nameOffset,
                isMutable = kind == SymbolKind.VAR,
            )
        }
        FOR_BINDING.findAll(code).firstOrNull { match ->
            match.groups[1]?.range?.contains(caret) == true
        }?.let { match ->
            val nameOffset = match.groups[1]!!.range.first
            return SymbolInfo(match.groupValues[1], SymbolKind.FIN, line = lineOf(content, nameOffset), offset = nameOffset)
        }
        callableSignatures(code).forEach { signature ->
            (parameterSymbols(signature, content) + typeParameterSymbols(signature, content))
                .firstOrNull { caret in it.offset until (it.offset + it.name.length) }
                ?.let { return it }
        }
        return null
    }

    /** Extracts value parameters with their exact source offsets. */
    private fun parameterSymbols(signature: CallableSignature, content: String): List<SymbolInfo> {
        return parameterRanges(signature).flatMap { (open, close) ->
            val body = content.substring(open + 1, close)
            PARAMETER_NAME.findAll(body).map { match ->
                val name = match.groupValues[1]
                val nameOffset = open + 1 + match.groups[1]!!.range.first
                val partEnd = topLevelParameterEnd(body, match.range.last + 1)
                // PARAMETER_NAME consumes the separating colon, so `part`
                // already begins with the type. Looking for another `:` made
                // every ordinary and contextual-receiver parameter untyped.
                val part = body.substring(match.range.last + 1, partEnd)
                val type = part.substringBefore('=').trim()
                    .removePrefix("return ").trimStart().takeIf { it.isNotEmpty() }
                SymbolInfo(name, SymbolKind.PARAM, type = type, line = lineOf(content, nameOffset), offset = nameOffset)
            }.toList()
        }
    }

    /** Generic parameters share the callable's lexical scope but are type symbols. */
    private fun typeParameterSymbols(signature: CallableSignature, content: String): List<SymbolInfo> {
        val open = signature.genericsOpen ?: return emptyList()
        val close = signature.genericsClose ?: return emptyList()
        val body = content.substring(open + 1, close)
        return GENERIC_PARAMETER.findAll(body).map { match ->
            val name = match.groupValues[1]
            val nameOffset = open + 1 + match.groups[1]!!.range.first
            SymbolInfo(name, SymbolKind.PARAM, type = "type parameter", line = lineOf(content, nameOffset), offset = nameOffset)
        }.toList()
    }

    /** The type `self` refers to at [offset], from the enclosing `impl` or `pack`. */
    fun selfType(content: String, offset: Int): String? {
        val caret = offset.coerceIn(0, content.length)
        val code = codeOnly(content)
        val blocks = sourceBlocks(code)
        val candidates = IMPL_LINE.findAll(code).mapNotNull { match ->
            val open = code.indexOf('{', match.range.last + 1)
            val block = blocks.firstOrNull { it.open == open } ?: return@mapNotNull null
            if (caret !in (block.open + 1)..block.close) return@mapNotNull null
            implTarget(match.value)?.let { Triple(match.range.first, block.depth, it) }
        }.toList()
        return candidates.maxWithOrNull(compareBy<Triple<Int, Int, String>> { it.second }.thenBy { it.first })?.third
    }

    /** The module paths this file imports, plus their short aliases. */
    fun importedModules(content: String): Set<String> {
        val modules = linkedSetOf<String>()
        for (match in IMPORT.findAll(content)) {
            val path = match.groupValues[1].trim().removeSuffix("*").removeSuffix(".").trim()
            if (path.isEmpty()) continue
            // Grouped imports: `import std.{math, io}`.
            val grouped = GROUPED_IMPORT.find(path)
            if (grouped != null) {
                val base = grouped.groupValues[1]
                grouped.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    modules.add("$base.$it")
                    modules.add(it)
                }
                continue
            }
            modules.add(path)
            modules.add(path.substringAfterLast('.'))
        }
        return modules
    }

    /**
     * Reads the reference sitting at [offset]: the identifier under the caret
     * plus the receiver path written before it.
     */
    fun referenceAt(content: String, offset: Int): AzoraReference? {
        if (content.isEmpty()) return null
        val caret = offset.coerceIn(0, content.length)

        var start = caret
        while (start > 0 && isNameChar(content[start - 1])) start--
        var end = caret
        while (end < content.length && isNameChar(content[end])) end++
        var name = content.substring(start, end)
        if (name.isEmpty()) return null
        // In a short interpolation (`"hello $name"`) the `$` is syntax, not
        // part of the referenced symbol. Keeping it in the reference made
        // hover/go-to and unused-variable analysis miss an otherwise ordinary
        // local binding.
        if (name.length > 1 && name.startsWith('$')) {
            start++
            name = name.drop(1)
        }

        val (qualifier, separator) = qualifierBefore(content, start)
        return AzoraReference(name, qualifier, separator)
    }

    /** The receiver path immediately before [position], outermost segment first. */
    fun qualifierBefore(content: String, position: Int): Pair<List<String>, String?> {
        var i = position
        val segments = ArrayDeque<String>()
        var separator: String? = null

        while (i > 0) {
            var j = i
            while (j > 0 && content[j - 1].isWhitespace()) j--
            val sep = when {
                j >= 2 && content[j - 1] == ':' && content[j - 2] == ':' -> "::"
                j >= 1 && content[j - 1] == '.' && !(j >= 2 && content[j - 2] == '.') -> "."
                else -> null
            } ?: break

            var k = j - sep.length
            while (k > 0 && content[k - 1].isWhitespace()) k--
            val segmentEnd = k
            while (k > 0 && isNameChar(content[k - 1])) k--
            if (k == segmentEnd) break

            segments.addFirst(content.substring(k, segmentEnd))
            if (separator == null) separator = sep
            i = k
        }

        return segments.toList() to separator
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun findInMembers(symbols: List<SymbolInfo>, name: String): List<SymbolInfo> {
        val found = mutableListOf<SymbolInfo>()
        fun walk(list: List<SymbolInfo>) {
            for (symbol in list) {
                if (symbol.name == name) found.add(symbol)
                if (symbol.members.isNotEmpty()) walk(symbol.members)
            }
        }
        for (symbol in symbols) walk(symbol.members)
        return found
    }

    /** Finds the declaration token itself, including nested fields/members. */
    private fun declarationSymbolAt(symbols: List<SymbolInfo>, name: String, offset: Int): SymbolInfo? {
        fun visit(items: List<SymbolInfo>): SymbolInfo? {
            for (symbol in items) {
                val sourceName = symbol.name.substringAfterLast("::").substringAfterLast('.')
                if ((symbol.name == name || sourceName == name) &&
                    offset in symbol.offset until (symbol.offset + sourceName.length)
                ) return symbol
                visit(symbol.members)?.let { return it }
            }
            return null
        }
        return visit(symbols)
    }

    private fun isSigilReference(content: String, start: Int): Boolean {
        val lineStart = content.lastIndexOf('\n', start - 1) + 1
        val prefix = content.substring(lineStart, start)
        return SIGIL_PATH_SUFFIX.containsMatchIn(prefix)
    }

    private fun isTypeUse(content: String, start: Int, previousWord: String?): Boolean {
        var previous = start - 1
        while (previous >= 0 && content[previous].isWhitespace()) previous--
        if (previous >= 0) {
            when (content[previous]) {
                ':' -> if (previous == 0 || content.getOrNull(previous - 1) != ':') return true
                '<', ',' -> return true
            }
        }
        return previousWord in TYPE_CONTEXT_WORDS
    }

    private fun wordStart(content: String, offset: Int): Int {
        var start = offset.coerceIn(0, content.length)
        while (start > 0 && isNameChar(content[start - 1])) start--
        return start
    }

    private fun wordEnd(content: String, start: Int): Int {
        var end = start
        while (end < content.length && isNameChar(content[end])) end++
        return end
    }

    private fun nextNonWhitespace(content: String, from: Int): Char? {
        var index = from
        while (index < content.length && content[index].isWhitespace()) index++
        return content.getOrNull(index)
    }

    private fun previousWordAt(content: String, start: Int): String? {
        var index = start - 1
        while (index >= 0 && content[index].isWhitespace()) index--
        if (index < 0) return null
        if (content[index] == ':') return ":"
        if (content[index] == '<' || content[index] == ',' || content[index] == '(') return content[index].toString()
        if (!isNameChar(content[index])) return null
        val end = index + 1
        while (index >= 0 && isNameChar(content[index])) index--
        return content.substring(index + 1, end)
    }

    private data class SourceBlock(val open: Int, val close: Int, val depth: Int)

    private data class CallableSignature(
        val headerStart: Int,
        val headerEnd: Int,
        val paramsOpen: Int,
        val paramsClose: Int,
        val receiverOpen: Int?,
        val receiverClose: Int?,
        val genericsOpen: Int?,
        val genericsClose: Int?,
        val scopeStart: Int,
        val scopeEnd: Int,
    )

    /** Masks literals/comments without changing offsets, so regexes only see code. */
    private fun codeOnly(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        var blockDepth = 0
        fun blank(at: Int) {
            if (masked[at] != '\n' && masked[at] != '\r') masked[at] = ' '
        }
        while (index < source.length) {
            when {
                blockDepth > 0 -> when {
                    source.startsWith("/*", index) -> {
                        blank(index); blank(index + 1); index += 2; blockDepth++
                    }
                    source.startsWith("*/", index) -> {
                        blank(index); blank(index + 1); index += 2; blockDepth--
                    }
                    else -> blank(index++)
                }
                source.startsWith("//", index) -> {
                    while (index < source.length && source[index] != '\n') blank(index++)
                }
                source.startsWith("/*", index) -> {
                    blank(index); blank(index + 1); index += 2; blockDepth = 1
                }
                source.startsWith("\"\"\"", index) -> {
                    repeat(3) { blank(index++) }
                    while (index < source.length && !source.startsWith("\"\"\"", index)) blank(index++)
                    repeat(minOf(3, source.length - index)) { blank(index++) }
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    blank(index++)
                    while (index < source.length) {
                        val char = source[index]
                        blank(index++)
                        if (char == '\\' && index < source.length) blank(index++)
                        else if (char == quote) break
                    }
                }
                else -> index++
            }
        }
        return masked.concatToString()
    }

    private fun sourceBlocks(code: String): List<SourceBlock> {
        val stack = ArrayDeque<Pair<Int, Int>>()
        val blocks = mutableListOf<SourceBlock>()
        for (index in code.indices) {
            when (code[index]) {
                '{' -> stack.addLast(index to stack.size)
                '}' -> stack.removeLastOrNull()?.let { (open, depth) -> blocks += SourceBlock(open, index, depth) }
            }
        }
        // A half-typed file still has a useful scope through EOF.
        while (stack.isNotEmpty()) {
            val (open, depth) = stack.removeLast()
            blocks += SourceBlock(open, code.length, depth)
        }
        return blocks.sortedBy { it.open }
    }

    private fun containingBlock(blocks: List<SourceBlock>, position: Int): SourceBlock? =
        blocks.filter { position in (it.open + 1)..it.close }
            .maxWithOrNull(compareBy<SourceBlock> { it.depth }.thenBy { it.open })

    private fun scopeContains(blocks: List<SourceBlock>, declaration: Int, use: Int): Boolean {
        val scope = containingBlock(blocks, declaration) ?: return true
        return use in (scope.open + 1)..scope.close
    }

    /** Current callable headers, including bracketed contextual receivers. */
    private fun callableSignatures(code: String): List<CallableSignature> {
        val blocks = sourceBlocks(code)
        data class Head(
            val start: Int,
            val end: Int,
            val paramsOpen: Int,
            val paramsClose: Int,
            val receiverOpen: Int?,
            val receiverClose: Int?,
            val genericsOpen: Int?,
            val genericsClose: Int?,
            val owner: SourceBlock?,
        )

        val heads = CALLABLE_HEAD.findAll(code).mapNotNull { match ->
            var cursor = match.range.last + 1
            while (code.getOrNull(cursor)?.isWhitespace() == true) cursor++
            var genericOpen: Int? = null
            var genericClose: Int? = null
            if (code.getOrNull(cursor) == '<') {
                genericOpen = cursor
                genericClose = matching(code, cursor, '<', '>') ?: return@mapNotNull null
                cursor = genericClose + 1
                while (code.getOrNull(cursor)?.isWhitespace() == true) cursor++
            }
            var receiverOpen: Int? = null
            var receiverClose: Int? = null
            if (code.getOrNull(cursor) == '[') {
                receiverOpen = cursor
                receiverClose = matching(code, cursor, '[', ']') ?: return@mapNotNull null
                cursor = receiverClose + 1
                while (code.getOrNull(cursor)?.isWhitespace() == true) cursor++
            }
            if (code.getOrNull(cursor) != '(') return@mapNotNull null
            val paramsClose = matching(code, cursor, '(', ')') ?: return@mapNotNull null
            Head(
                match.range.first, paramsClose, cursor, paramsClose,
                receiverOpen, receiverClose, genericOpen, genericClose,
                containingBlock(blocks, match.range.first),
            )
        }.toList()

        return heads.mapIndexed { index, head ->
            val next = heads.drop(index + 1).firstOrNull { it.owner?.open == head.owner?.open }?.start
            val limit = minOf(next ?: Int.MAX_VALUE, head.owner?.close ?: code.length)
            val bodies = blocks.filter { block ->
                block.open > head.paramsClose && block.open < limit && block.depth == (head.owner?.depth?.plus(1) ?: 0)
            }
            val scopeEnd = bodies.maxOfOrNull { it.close }
                ?: code.indexOf('\n', head.paramsClose).takeIf { it >= 0 }
                ?: head.paramsClose
            CallableSignature(
                head.start, head.end, head.paramsOpen, head.paramsClose,
                head.receiverOpen, head.receiverClose, head.genericsOpen, head.genericsClose,
                head.start, scopeEnd,
            )
        }
    }

    private fun parameterRanges(signature: CallableSignature): List<Pair<Int, Int>> = buildList {
        signature.receiverOpen?.let { open -> add(open to (signature.receiverClose ?: open)) }
        add(signature.paramsOpen to signature.paramsClose)
    }

    private fun matching(text: String, open: Int, opener: Char, closer: Char): Int? {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                opener -> depth++
                closer -> if (--depth == 0) return index
            }
        }
        return null
    }

    /** End of one parameter, respecting nested function/generic types. */
    private fun topLevelParameterEnd(parameters: String, from: Int): Int {
        var paren = 0
        var angle = 0
        var bracket = 0
        for (index in from until parameters.length) {
            when (parameters[index]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '<' -> angle++
                '>' -> angle = (angle - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                ',', '\n' -> if (paren == 0 && angle == 0 && bracket == 0) return index
            }
        }
        return parameters.length
    }

    private fun implTarget(header: String): String? {
        var text = header.substringAfter("impl", "").trimStart()
        if (text.startsWith("<")) {
            val close = matching(text, 0, '<', '>') ?: return null
            text = text.substring(close + 1).trimStart()
        }
        text = text.removePrefix("pack ").trimStart()
        val target = if (" for " in text) text.substringAfter(" for ") else text
        return target.trimStart().takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
            .takeIf { it.isNotEmpty() }
    }

    private fun inferLiteralType(initializer: String): String? {
        val text = initializer.trim()
        if (text.isEmpty()) return null
        CONSTRUCTOR.find(text)?.let { return it.groupValues[1] }
        return when {
            text.startsWith("\"") -> "String"
            text.startsWith("'") -> "Char"
            text == "true" || text == "false" -> "Bool"
            text.matches(Regex("""[-+]?\d+""")) -> "Int"
            text.matches(Regex("""[-+]?\d+\.\d+.*""")) -> floatLiteralType(text)
            else -> null
        }
    }

    private fun simpleTypeName(type: String): String = type
        .substringBefore('<')
        .substringBefore('?')
        .trim()
        .trim('&', '!')
        .substringAfterLast("::")
        .substringAfterLast('.')
        .trim()

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun lineOf(text: String, offset: Int): Int =
        text.take(offset).count { it == '\n' } + 1

    private companion object {
        val MUTABLE_BINDINGS = setOf("var")

        val CALLABLE_KINDS = setOf(
            SymbolKind.FUNC, SymbolKind.METHOD, SymbolKind.TASK, SymbolKind.FLOW,
            SymbolKind.HOOK, SymbolKind.INFX, SymbolKind.OPERATOR, SymbolKind.BRIDGE_FUNC,
            SymbolKind.CTOR, SymbolKind.DTOR,
        )
        val TYPE_KINDS = setOf(
            SymbolKind.PACK, SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT,
            SymbolKind.SPEC, SymbolKind.WRAP, SymbolKind.TYPEALIAS, SymbolKind.SCOPE,
        )
        val VALUE_KINDS = setOf(
            SymbolKind.FIELD, SymbolKind.PROPERTY, SymbolKind.VAR, SymbolKind.FIN,
            SymbolKind.PARAM, SymbolKind.VARIANT,
        )
        val TYPE_CONTEXT_WORDS = setOf(
            ":", "is", "as", "->", "derives", "requires", "binds", "impl", "typealias",
        )

        val BINDING = Regex(
            """\b(var|val|fin|let)[ \t]+([A-Za-z_$][\w$]*)(?:[ \t]*:[ \t]*([^=\n{]+))?(?:[ \t]*=[ \t]*([^\n]+))?"""
        )
        val FOR_BINDING = Regex("""\bfor\s+([A-Za-z_$][\w$]*)\s+in\b""")
        val CALLABLE_HEAD = Regex(
            """(?m)^[ \t]*(?:(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|threadlocal|react|async|bridge)\s+)*(?:func\s+[A-Za-z_$][\w$]*|ctor\b|oper[^\s\[(]*)""",
        )
        val PARAMETER_NAME = Regex("""(?m)(?:^|,|\n)\s*(?:\.\.\.)?([A-Za-z_$][\w$]*)\s*:""")
        val GENERIC_PARAMETER = Regex("""(?:^|,)\s*(?:\.\.\.)?(?:out\s+)?([A-Za-z_$][\w$]*)""")
        val IMPL_LINE = Regex(
            """(?m)^[ \t]*(?:(?:bridge|exposed|protected|confined|unsafe)\s+)*impl\b[^\n{]*""",
        )
        val IMPORT = Regex("""(?m)^\s*(?:exposed\s+)?(?:import|use)\s+([^\n/]+)""")
        val GROUPED_IMPORT = Regex("""^([\w.]+)\.\{([^}]*)}""")
        val CONSTRUCTOR = Regex("""^([A-Z][\w]*)\s*(?:<[^>]+>)?\s*\(""")
        val SIGIL_PATH_SUFFIX = Regex("""@(?:[A-Za-z_$][\w$]*::)*\s*$""")
    }

    /**
     * The type of a float literal, which is `Double` wherever nothing says
     * otherwise.
     *
     * A literal carries no width - the suffixes are gone and the target names
     * the width - so this is what a literal standing alone is. `Real` is not a
     * type Azora declares, so a hint that answered "Real" named something the
     * user could not write down.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun floatLiteralType(text: String): String = "Double"
}

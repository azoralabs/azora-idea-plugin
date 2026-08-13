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
        declarationAt(content, offset)?.takeIf { it.name == name }?.let { return listOf(it) }
        localsInScope(content, offset).lastOrNull { it.name == name }?.let { return listOf(it) }

        selfType(content, offset)?.let { type ->
            service.getMembersForType(type, filePath, content, project)
                .filter { it.name == name }
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        val fileSymbols = service.getSymbolsForFile(filePath, content)
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
        if (candidates.size < 2) return candidates
        val start = wordStart(content, offset)
        val end = wordEnd(content, start)
        val next = nextNonWhitespace(content, end)
        val previous = previousWordAt(content, start)
        val callable = candidates.filter { it.kind in CALLABLE_KINDS }
        val types = candidates.filter { it.kind in TYPE_KINDS }
        val values = candidates.filter { it.kind in VALUE_KINDS }

        if (next == '(') {
            if (callable.isNotEmpty()) return callable
            if (content.substring(start, end).firstOrNull()?.isUpperCase() == true && types.isNotEmpty()) {
                return types
            }
        }
        if (previous in TYPE_CONTEXT_WORDS && types.isNotEmpty()) return types
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
     * declaration, `for`/`when` bindings, and `var`/`fin`/`let`/`mem`/`rem`/`ret`
     * declarations, in source order so the nearest one wins.
     */
    fun localsInScope(content: String, offset: Int): List<SymbolInfo> {
        val before = content.take(offset.coerceIn(0, content.length))
        val result = mutableListOf<SymbolInfo>()

        for (match in BINDING.findAll(before)) {
            val keyword = match.groupValues[1]
            val declaredType = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
            val initializer = match.groupValues[4].trim()
            val kind = if (keyword in MUTABLE_BINDINGS) SymbolKind.VAR else SymbolKind.FIN
            result.add(
                SymbolInfo(
                    name = match.groupValues[2],
                    kind = kind,
                    type = declaredType ?: inferLiteralType(initializer),
                    line = lineOf(content, match.groups[2]!!.range.first),
                    offset = match.groups[2]!!.range.first,
                    isMutable = kind == SymbolKind.VAR,
                    defaultValueText = initializer.takeIf { it.isNotEmpty() },
                )
            )
        }

        for (match in FOR_BINDING.findAll(before)) {
            result.add(
                SymbolInfo(
                    name = match.groupValues[1],
                    kind = SymbolKind.FIN,
                    line = lineOf(content, match.groups[1]!!.range.first),
                    offset = match.groups[1]!!.range.first,
                )
            )
        }

        SIGNATURE.findAll(before).lastOrNull()?.let { signature ->
            result.addAll(parameterSymbols(signature, content))
        }

        return result
    }

    /** Returns a local/parameter declaration when the caret is on its symbol. */
    private fun declarationAt(content: String, offset: Int): SymbolInfo? {
        val caret = offset.coerceIn(0, content.length)
        BINDING.findAll(content).firstOrNull { match ->
            match.groups[2]?.range?.contains(caret) == true
        }?.let { match ->
            val keyword = match.groupValues[1]
            val kind = if (keyword in MUTABLE_BINDINGS) SymbolKind.VAR else SymbolKind.FIN
            val nameOffset = match.groups[2]!!.range.first
            return SymbolInfo(
                name = match.groupValues[2],
                kind = kind,
                type = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    ?: inferLiteralType(match.groupValues[4]),
                line = lineOf(content, nameOffset),
                offset = nameOffset,
                isMutable = kind == SymbolKind.VAR,
            )
        }
        FOR_BINDING.findAll(content).firstOrNull { match ->
            match.groups[1]?.range?.contains(caret) == true
        }?.let { match ->
            val nameOffset = match.groups[1]!!.range.first
            return SymbolInfo(match.groupValues[1], SymbolKind.FIN, line = lineOf(content, nameOffset), offset = nameOffset)
        }
        SIGNATURE.findAll(content).forEach { signature ->
            parameterSymbols(signature, content).firstOrNull { caret in it.offset until (it.offset + it.name.length) }
                ?.let { return it }
        }
        return null
    }

    /** Extracts parameter symbols with source offsets from a matched signature. */
    private fun parameterSymbols(signature: MatchResult, content: String): List<SymbolInfo> {
        val text = signature.value
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return emptyList()
        val body = text.substring(open + 1, close)
        var rawOffset = 0
        return body.split(',').mapNotNull { raw ->
            val leading = raw.indexOfFirst { !it.isWhitespace() }
            if (leading < 0) {
                rawOffset += raw.length + 1
                return@mapNotNull null
            }
            val part = raw.substring(leading)
            val name = part.substringBefore(':').substringBefore('=').trim().trimStart('&', '!')
            if (name.isEmpty() || !name.first().let { it.isLetter() || it == '_' }) {
                rawOffset += raw.length + 1
                return@mapNotNull null
            }
            val nameInPart = part.indexOf(name)
            val nameOffset = signature.range.first + open + 1 + rawOffset + leading + nameInPart
            val type = part.substringAfter(':', "").substringBefore('=').trim().takeIf { it.isNotEmpty() }
            rawOffset += raw.length + 1
            SymbolInfo(name, SymbolKind.PARAM, type = type, line = lineOf(content, nameOffset), offset = nameOffset)
        }
    }

    /** The type `self` refers to at [offset], from the enclosing `impl` or `pack`. */
    fun selfType(content: String, offset: Int): String? {
        val before = content.take(offset.coerceIn(0, content.length))
        IMPL_HEAD.findAll(before).lastOrNull()?.let { return it.groupValues[1] }
        return TYPE_HEAD.findAll(before).lastOrNull()?.groupValues?.get(2)
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
        val name = content.substring(start, end)
        if (name.isEmpty()) return null

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

    /** The parameter list of the declaration enclosing the caret, if any. */
    private fun enclosingSignature(before: String): String? =
        SIGNATURE.findAll(before).lastOrNull()?.value

    private fun parametersOf(signature: String): List<Pair<String, String?>> {
        val inner = signature.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isBlank()) return emptyList()
        return inner.split(',').mapNotNull { raw ->
            val part = raw.trim()
            if (part.isEmpty()) return@mapNotNull null
            val name = part.substringBefore(':').substringBefore('=').trim().trimStart('&', '!')
            if (name.isEmpty() || !name.first().let { it.isLetter() || it == '_' }) return@mapNotNull null
            val type = part.substringAfter(':', "").substringBefore('=').trim().takeIf { it.isNotEmpty() }
            name to type
        }
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
            text.matches(Regex("""[-+]?\d+\.\d+.*""")) -> "Real"
            else -> null
        }
    }

    private fun simpleTypeName(type: String): String = type
        .substringBefore('<')
        .substringBefore('?')
        .trim()
        .trimStart('&', '!')
        .substringAfterLast("::")
        .substringAfterLast('.')
        .trim()

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun lineOf(text: String, offset: Int): Int =
        text.take(offset).count { it == '\n' } + 1

    private companion object {
        val MUTABLE_BINDINGS = setOf("var", "val", "mem", "rem")

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
        val TYPE_CONTEXT_WORDS = setOf(":", "is", "as", "->", "<", ",")

        val BINDING = Regex(
            """\b(var|val|fin|let|mem|rem|ret)\s+([A-Za-z_$][\w$]*)(?:\s*:\s*([^=\n{]+))?(?:\s*=\s*([^\n]+))?"""
        )
        val FOR_BINDING = Regex("""\bfor\s+([A-Za-z_$][\w$]*)\s+in\b""")
        val SIGNATURE = Regex("""(?m)^\s*(?:\w+\s+)*(?:func|task|flow|ctor|oper|deco)[^(\n]*\([^)]*\)""")
        val IMPL_HEAD = Regex(
            """(?m)^\s*(?:\w+\s+)*impl(?:\s*<[^>]*>)?(?:\s+[A-Za-z_][\w<>,:\s]*?\s+for)?\s+([A-Z][\w]*)"""
        )
        val TYPE_HEAD = Regex("""(?m)^\s*(?:\w+\s+)*(pack|solo|enum|slot|fail|spec)\s+([A-Z][\w]*)""")
        val IMPORT = Regex("""(?m)^\s*(?:export\s+)?(?:import|use)\s+([^\n/]+)""")
        val GROUPED_IMPORT = Regex("""^([\w.]+)\.\{([^}]*)}""")
        val CONSTRUCTOR = Regex("""^([A-Z][\w]*)\s*(?:<[^>]+>)?\s*\(""")
    }
}

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

package com.azora.lang.idea.symbol

import com.azora.lang.idea.AzoraFileType
import com.azora.lang.idea.AzoraLanguageFacts
import com.azora.lang.idea.StdSymbol
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Categorizes the kind of symbol found during source scanning.
 *
 * Each variant maps to a distinct Azora language construct, used by
 * the symbol service, completion provider, and structure view to
 * classify declarations.
 */
enum class SymbolKind {
    /** A `pack` (product type / struct). */
    PACK,
    /** An `enum` (sum type with named variants). */
    ENUM,
    /** A `fail` (error type with variants). */
    FAIL,
    /** A `slot` (tagged union with parameterized variants). */
    SLOT,
    /** A `func` (function declaration). */
    FUNC,
    /** A `view` (reactive UI component). */
    VIEW,
    /** A `scope` (namespace / module scope). */
    SCOPE,
    /** A `solo` (singleton object). */
    SOLO,
    /** A `wrap` (new type wrapper). */
    WRAP,
    /** A `var` (mutable variable). */
    VAR,
    /** A `fin` (immutable binding). */
    FIN,
    /** A field inside a pack, solo, or similar container. */
    FIELD,
    /** A method inside an impl, spec, or solo block. */
    METHOD,
    /** A computed property declared with `prop`. */
    PROPERTY,
    /** A variant inside an enum, slot, or fail. */
    VARIANT,
    /** An operator overload declared with `oper`. */
    OPERATOR,
    /** A function declared inside a `bridge` block. */
    BRIDGE_FUNC,
    /** A `task` (structured concurrency task). */
    TASK,
    /** A `flow` (reactive stream producer). */
    FLOW,
    /** A `hook` (lifecycle hook). */
    HOOK,
    /** A `test` declaration. */
    TEST,
    /** A `spec` (trait / interface). */
    SPEC,
    /** An `impl ... for Spec` block (spec implementation). */
    IMPL_SPEC,
    /** An `infx` (infix operator). */
    INFX,
    /** A `bridge` block (FFI boundary). */
    BRIDGE,
    /** A `typealias` declaration. */
    TYPEALIAS,
    /** A `package` declaration. */
    PACKAGE,
    /** A `use` import. */
    USE,
    /** A function or constructor parameter. */
    PARAM,
    /** A `ctor` (constructor). */
    CTOR,
    /** A `dtor` (destructor). */
    DTOR,
    /** A binding introduced by a `wrap` declaration. */
    WRAP_BINDING
}

/**
 * Represents a resolved symbol with its name, kind, type information,
 * members, parameters, and source location.
 *
 * @param name the symbol's identifier.
 * @param kind the [SymbolKind] classifying this symbol.
 * @param type the declared or inferred type, or `null` if unknown.
 * @param members child symbols (e.g. fields of a pack, variants of an enum).
 * @param params parameter list as name-type pairs (for functions, constructors, etc.).
 * @param line the 1-based line number in the source file.
 * @param offset the 0-based character offset from the start of the file.
 * @param filePath the absolute path of the source file, or `null` for synthetic symbols.
 * @param genericParams generic type parameter names (e.g. `["T", "U"]`).
 * @param isExposed whether the symbol has the `expose` visibility modifier.
 * @param isMutable whether the symbol is mutable (`var` or `mut`).
 * @param defaultValueText the source text of the default value expression, or `null`.
 * @param documentation documentation comment text attached to this symbol, or `null`.
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val type: String? = null,
    val members: List<SymbolInfo> = emptyList(),
    val params: List<Pair<String, String?>> = emptyList(),
    val line: Int = 0,
    val offset: Int = 0,
    val filePath: String? = null,
    val genericParams: List<String> = emptyList(),
    val isExposed: Boolean = false,
    val isMutable: Boolean = false,
    val defaultValueText: String? = null,
    val documentation: String? = null
)

/**
 * A cached snapshot of the symbols extracted from a single file.
 *
 * @param symbols the list of top-level symbols found in the file.
 * @param timestamp the wall-clock time when extraction occurred (for staleness checks).
 */
data class FileSymbolTable(
    val symbols: List<SymbolInfo>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Project-level service that extracts symbols from Azora source files
 * using text/regex-based scanning (no compiler dependency).
 *
 * Maintains a per-file cache of [FileSymbolTable] entries, invalidated
 * explicitly via [invalidate]. The extraction is purely syntactic,
 * scanning lines for known declaration keywords and extracting names,
 * types, and members from the surrounding text.
 */
@Service(Service.Level.PROJECT)
class AzoraSymbolService {

    /** Per-file cache mapping absolute file paths to their extracted symbol tables. */
    private val fileSymbolTables = ConcurrentHashMap<String, FileSymbolTable>()

    /**
     * Returns the symbols for the given file, using the cache if available.
     *
     * If no cached entry exists, extracts symbols from [content] and caches the result.
     *
     * @param filePath the absolute path of the source file.
     * @param content the full text content of the file.
     * @return the list of top-level symbols in the file.
     */
    fun getSymbolsForFile(filePath: String, content: String): List<SymbolInfo> {
        val cached = fileSymbolTables[filePath]
        if (cached != null) return cached.symbols

        val symbols = extractSymbols(content, filePath)
        fileSymbolTables[filePath] = FileSymbolTable(symbols)
        return symbols
    }

    /**
     * Removes the cached symbol table for the given file, forcing re-extraction on the next access.
     *
     * @param filePath the absolute path of the file to invalidate.
     */
    fun invalidate(filePath: String) {
        fileSymbolTables.remove(filePath)
    }

    /**
     * Returns all symbols visible from the given file.
     *
     * Currently delegates to [getSymbolsForFile]; cross-file resolution
     * may be added in the future.
     *
     * @param filePath the absolute path of the source file.
     * @param content the full text content of the file.
     * @return the list of visible symbols.
     */
    fun getAllVisibleSymbols(filePath: String, content: String): List<SymbolInfo> {
        return getSymbolsForFile(filePath, content)
    }

    /**
     * Returns symbols visible from [filePath], including project `.az` files and
     * synthetic stdlib module/symbol entries used by completion and navigation.
     */
    fun getAllVisibleSymbols(project: Project?, filePath: String, content: String): List<SymbolInfo> {
        if (project == null) return getAllVisibleSymbols(filePath, content)

        val merged = LinkedHashMap<String, SymbolInfo>()
        fun add(symbol: SymbolInfo) {
            val key = "${symbol.filePath}:${symbol.line}:${symbol.kind}:${symbol.name}"
            merged.putIfAbsent(key, symbol)
        }

        getSymbolsForFile(filePath, content).forEach(::add)
        getProjectSymbols(project, filePath).forEach(::add)
        stdlibSymbols().forEach(::add)
        return merged.values.toList()
    }

    /**
     * Returns the members (fields, methods, properties, etc.) of the named type.
     *
     * Searches both the type's own declaration and any `impl` blocks that
     * contribute additional members to it.
     *
     * @param typeName the name of the type to look up.
     * @param filePath the absolute path of the source file.
     * @param content the full text content of the file.
     * @return the combined list of member symbols.
     */
    fun getMembersForType(typeName: String, filePath: String, content: String, project: Project? = null): List<SymbolInfo> {
        val allSymbols = if (project != null) {
            getAllVisibleSymbols(project, filePath, content)
        } else {
            getAllVisibleSymbols(filePath, content)
        }
        val result = mutableListOf<SymbolInfo>()

        for (sym in allSymbols) {
            if (sym.name == typeName && sym.kind in TYPE_KINDS) {
                result.addAll(sym.members)
            }
        }

        // Also look for impl blocks contributing members to this type
        result.addAll(getImplMembers(typeName, allSymbols))

        return result
    }

    /**
     * Collects members contributed to [typeName] by `impl` and `scope` blocks.
     *
     * Scans [allSymbols] for scope blocks containing the type and for
     * top-level packs with matching names, deduplicating by name and kind.
     *
     * @param typeName the name of the target type.
     * @param allSymbols the full list of symbols to search.
     * @return the list of impl-contributed member symbols.
     */
    private fun getImplMembers(typeName: String, allSymbols: List<SymbolInfo>): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        for (sym in allSymbols) {
            if (sym.kind == SymbolKind.SCOPE) {
                for (member in sym.members) {
                    if (member.name == typeName) {
                        result.addAll(member.members.filter {
                            it.kind in setOf(SymbolKind.METHOD, SymbolKind.PROPERTY, SymbolKind.OPERATOR, SymbolKind.CTOR, SymbolKind.DTOR)
                        })
                    }
                }
            }
            // Top-level impl blocks appear as PACK symbols with the type's name
            if (sym.name == typeName && sym.members.isNotEmpty()) {
                for (member in sym.members) {
                    if (member.kind in setOf(SymbolKind.METHOD, SymbolKind.PROPERTY, SymbolKind.OPERATOR, SymbolKind.CTOR, SymbolKind.DTOR)) {
                        if (result.none { it.name == member.name && it.kind == member.kind }) {
                            result.add(member)
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Resolves a dot-separated scope path to the symbols at the deepest level.
     *
     * Walks through nested [SymbolKind.SCOPE] symbols matching each segment
     * of [path] and returns the members of the final scope.
     *
     * @param path the list of scope name segments to resolve.
     * @param filePath the absolute path of the source file.
     * @param content the full text content of the file.
     * @return the symbols at the resolved scope, or an empty list if any segment is unresolved.
     */
    fun resolveScopePath(path: List<String>, filePath: String, content: String, project: Project? = null): List<SymbolInfo> {
        if (path.isEmpty()) return emptyList()
        stdMembersForPath(path)?.let { return it }

        val allSymbols = if (project != null) {
            getAllVisibleSymbols(project, filePath, content)
        } else {
            getAllVisibleSymbols(filePath, content)
        }
        val flattened = path.joinToString("::")
        allSymbols.find { it.name == flattened && it.kind == SymbolKind.SCOPE }?.let {
            return it.members
        }
        var current: List<SymbolInfo> = allSymbols
        for (segment in path) {
            val scope = current.find { it.name == segment && it.kind == SymbolKind.SCOPE }
                ?: return emptyList()
            current = scope.members
        }
        return current
    }

    /**
     * Resolves the declared type of variable or constant by name.
     *
     * @param varName the variable or constant name to look up.
     * @param filePath the absolute path of the source file.
     * @param content the full text content of the file.
     * @return the type annotation string, or `null` if not found or untyped.
     */
    fun resolveVariableType(varName: String, filePath: String, content: String, project: Project? = null): String? {
        val allSymbols = if (project != null) {
            getAllVisibleSymbols(project, filePath, content)
        } else {
            getAllVisibleSymbols(filePath, content)
        }
        return allSymbols.find {
            (it.kind == SymbolKind.VAR || it.kind == SymbolKind.FIN) && it.name == varName
        }?.type
    }

    /**
     * Returns the list of supported bridge target platform names.
     *
     * @return the known FFI target identifiers (e.g. `"C"`, `"JS"`, `"KOTLIN"`).
     */
    fun getBridgeTargets(): List<String> {
        return listOf("C", "JS", "KOTLIN", "LLVM", "WASM")
    }

    private fun getProjectSymbols(project: Project, currentFilePath: String): List<SymbolInfo> {
        return try {
            FileTypeIndex.getFiles(AzoraFileType.INSTANCE, GlobalSearchScope.projectScope(project))
                .asSequence()
                .filter { it.path != currentFilePath }
                .take(500)
                .flatMap { file ->
                    val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    if (text == null) emptySequence() else getSymbolsForFile(file.path, text).asSequence()
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun stdMembersForPath(path: List<String>): List<SymbolInfo>? {
        val joined = path.joinToString(".")
        val normalized = AzoraLanguageFacts.stdAliases[joined] ?: joined
        val modulePath = when {
            normalized == "std" -> "std"
            normalized.startsWith("std.") -> normalized
            AzoraLanguageFacts.stdAliases.containsKey(path.firstOrNull()) -> {
                val aliasRoot = AzoraLanguageFacts.stdAliases[path.first()] ?: return null
                (listOf(aliasRoot) + path.drop(1)).joinToString(".")
            }
            else -> return null
        }

        val children = AzoraLanguageFacts.stdChildren(modulePath)
        if (children.isEmpty()) return null
        val modulePrefix = if (modulePath == "std") "std" else modulePath
        return children.map { child ->
            val childModule = "$modulePrefix.$child"
            val stdSymbol = AzoraLanguageFacts.stdSymbols.find { it.module == modulePath && it.name == child }
            if (stdSymbol != null) {
                stdSymbol.toSymbolInfo()
            } else {
                SymbolInfo(
                    name = child,
                    kind = SymbolKind.SCOPE,
                    type = childModule,
                    filePath = "<stdlib>",
                    documentation = "Stdlib module `$childModule`."
                )
            }
        }
    }

    private fun stdlibSymbols(): List<SymbolInfo> {
        val symbolsByModule = AzoraLanguageFacts.stdSymbols.groupBy { it.module }
        val moduleScopes = AzoraLanguageFacts.stdModules
            .filter { it != "std" }
            .map { module ->
                val shortName = module.substringAfterLast(".")
                SymbolInfo(
                    name = module,
                    kind = SymbolKind.SCOPE,
                    members = symbolsByModule[module].orEmpty().map { it.toSymbolInfo() },
                    filePath = "<stdlib>",
                    documentation = "Stdlib module `$module`."
                ) to SymbolInfo(
                    name = shortName,
                    kind = SymbolKind.SCOPE,
                    type = module,
                    members = symbolsByModule[module].orEmpty().map { it.toSymbolInfo() },
                    filePath = "<stdlib>",
                    documentation = "Stdlib module `$module`."
                )
            }

        val root = SymbolInfo(
            name = "std",
            kind = SymbolKind.SCOPE,
            members = moduleScopes.map { it.second },
            filePath = "<stdlib>",
            documentation = "Azora standard library root zone."
        )

        val aliases = AzoraLanguageFacts.stdAliases.map { (alias, module) ->
            SymbolInfo(
                name = alias,
                kind = SymbolKind.SCOPE,
                type = module,
                members = symbolsByModule[module].orEmpty().map { it.toSymbolInfo() },
                filePath = "<stdlib>",
                documentation = "Alias for `$module` when imported from stdlib."
            )
        }

        val directStdSymbols = AzoraLanguageFacts.stdSymbols.map { it.toSymbolInfo() }
        return listOf(root) + moduleScopes.flatMap { listOf(it.first, it.second) } + aliases + directStdSymbols
    }

    private fun StdSymbol.toSymbolInfo(): SymbolInfo {
        val kind = when (kind) {
            "func" -> SymbolKind.FUNC
            "pack" -> SymbolKind.PACK
            "slot" -> SymbolKind.SLOT
            "spec" -> SymbolKind.SPEC
            "prop" -> SymbolKind.PROPERTY
            "fin" -> SymbolKind.FIN
            else -> SymbolKind.FUNC
        }
        return SymbolInfo(
            name = name,
            kind = kind,
            type = detail,
            filePath = "<stdlib>",
            documentation = "`${module.replace(".", "::")}::$name` - $detail"
        )
    }

    // -----------------------------------------------------------------------
    // Text-based symbol extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts all top-level symbols from the given source [content].
     *
     * Iterates line-by-line, matching known Azora declaration patterns
     * (package, use, pack, enum, func, zone, impl, etc.) and building
     * [SymbolInfo] instances with nested members where applicable.
     *
     * @param content the full source text to scan.
     * @param filePath the absolute path of the source file (attached to each symbol).
     * @return the list of extracted top-level symbols.
     */
    private fun extractSymbols(content: String, filePath: String): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        val lines = content.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val lineNum = i + 1
            val offset = content.lineOffset(i)
            val documentation = extractDocComment(lines, i)

            when {
                trimmed.startsWith("package ") -> {
                    val name = trimmed.removePrefix("package ").trim()
                    result.add(SymbolInfo(name, SymbolKind.PACKAGE, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("use ") -> {
                    val name = trimmed.removePrefix("use ").trim()
                    result.add(SymbolInfo(name, SymbolKind.USE, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "view") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "view")
                    val params = extractParams(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.VIEW, params = params, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "pack") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "pack")
                    val fields = extractBlockFields(lines, i)
                    result.add(SymbolInfo(name, SymbolKind.PACK, members = fields.map { it.copy(filePath = filePath) }, params = fields.map { it.name to it.type }, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "enum") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "enum")
                    val params = extractParams(trimmed)
                    val variants = extractVariants(lines, i).map {
                        SymbolInfo(it, SymbolKind.VARIANT, type = name, filePath = filePath)
                    }
                    result.add(SymbolInfo(name, SymbolKind.ENUM, members = variants, params = params, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "slot") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "slot")
                    val variants = extractSlotVariants(lines, i).map { (vName, vParams) ->
                        SymbolInfo(vName, SymbolKind.VARIANT, type = name, params = vParams, filePath = filePath)
                    }
                    result.add(SymbolInfo(name, SymbolKind.SLOT, members = variants, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "fail") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "fail")
                    val variants = extractVariants(lines, i).map {
                        SymbolInfo(it, SymbolKind.VARIANT, type = name, filePath = filePath)
                    }
                    result.add(SymbolInfo(name, SymbolKind.FAIL, members = variants, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "zone") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "zone")
                    val blockContent = extractBlockContent(lines, i)
                    val members = extractSymbols(blockContent, filePath)
                    result.add(SymbolInfo(name, SymbolKind.SCOPE, members = members, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                trimmed.startsWith("impl oper") -> {
                    val typeName = simpleTypeName(trimmed.substringAfter(" for ", "").substringBefore("{").trim())
                    val operatorName = trimmed.removePrefix("impl ").substringBefore(" for ").substringBefore("(").trim()
                    val member = SymbolInfo(operatorName, SymbolKind.OPERATOR, line = lineNum, offset = offset, filePath = filePath, documentation = documentation)
                    result.add(SymbolInfo(typeName, SymbolKind.PACK, members = listOf(member), line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("impl as ") -> {
                    val typeName = simpleTypeName(trimmed.substringAfter(" for ", "").substringBefore("{").trim())
                    val targetType = trimmed.removePrefix("impl as ").substringBefore(" for ").trim()
                    val member = SymbolInfo("as $targetType", SymbolKind.METHOD, type = targetType, line = lineNum, offset = offset, filePath = filePath, documentation = documentation)
                    result.add(SymbolInfo(typeName, SymbolKind.PACK, members = listOf(member), line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "impl") -> {
                    val typeName = extractImplTypeName(trimmed)
                    val specName = extractImplSpecName(trimmed)
                    val members = extractImplMembers(lines, i, filePath)
                    val kind = if (specName != null) SymbolKind.IMPL_SPEC else SymbolKind.PACK
                    result.add(SymbolInfo(typeName, kind, type = specName, members = members, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                isFuncDecl(trimmed) -> {
                    val (name, exposed) = extractFuncNameAndExposed(trimmed)
                    val params = extractParams(trimmed)
                    val returnType = extractReturnType(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.FUNC, type = returnType, params = params, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "task") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "task")
                    val params = extractParams(trimmed)
                    val returnType = extractReturnType(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.TASK, type = returnType, params = params, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "flow") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "flow")
                    val params = extractParams(trimmed)
                    val returnType = extractReturnType(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.FLOW, type = returnType, params = params, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "solo") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "solo")
                    val fields = extractBlockFields(lines, i)
                    val methods = extractBlockMethods(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.SOLO, members = fields.map { it.copy(filePath = filePath) } + methods, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "wrap") -> {
                    val (name, _) = extractNameAndExposed(trimmed, "wrap")
                    result.add(SymbolInfo(name, SymbolKind.WRAP, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "spec") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "spec")
                    val methods = extractBlockMethods(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.SPEC, members = methods, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "infx") -> {
                    val afterInfx = trimmed.substringAfter("infx ").trim()
                    val name = afterInfx.substringBefore("(").substringBefore("{").substringBefore(" ").trim()
                    result.add(SymbolInfo(name, SymbolKind.INFX, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("bridge ") -> {
                    val target = trimmed.removePrefix("bridge ").substringBefore("{").trim().removePrefix(".")
                    val funcs = extractBridgeFuncs(lines, i, filePath)
                    result.add(SymbolInfo(target, SymbolKind.BRIDGE, members = funcs, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("hook ") -> {
                    val name = trimmed.removePrefix("hook ").substringBefore("(").substringBefore("{").trim()
                    result.add(SymbolInfo(name, SymbolKind.HOOK, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("test ") -> {
                    val name = extractTestName(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.TEST, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("typealias ") -> {
                    val rest = trimmed.removePrefix("typealias ").trim()
                    val name = rest.substringBefore("=").substringBefore(" ").trim()
                    val type = rest.substringAfter("=", "").trim().takeIf { it.isNotEmpty() }
                    result.add(SymbolInfo(name, SymbolKind.TYPEALIAS, type = type, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "deco") -> {
                    val (name, _) = extractNameAndExposed(trimmed, "deco")
                    result.add(SymbolInfo(name, SymbolKind.FUNC, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }

                isTopLevelVarFin(trimmed) -> {
                    val core = stripModifiers(trimmed)
                    val keyword = listOf("var", "fin", "mem", "rem", "ret").firstOrNull { core.startsWith("$it ") } ?: "fin"
                    val isMutable = keyword == "var" || keyword == "mem" || keyword == "rem"
                    val exposed = trimmed.trimStart().startsWith("expose ")
                    val afterKw = core.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val defaultVal = afterKw.substringAfter("=", "").trim().takeIf { it.isNotEmpty() }
                    val kind = if (isMutable) SymbolKind.VAR else SymbolKind.FIN
                    result.add(SymbolInfo(name, kind, type = type, isMutable = isMutable, line = lineNum, offset = offset, filePath = filePath, isExposed = exposed, defaultValueText = defaultVal, documentation = documentation))
                }

                trimmed.startsWith("threadlocal ") -> {
                    val rest = trimmed.removePrefix("threadlocal ").trim()
                    val isMutable = rest.startsWith("var ")
                    val keyword = if (isMutable) "var" else "fin"
                    val afterKw = rest.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val kind = if (isMutable) SymbolKind.VAR else SymbolKind.FIN
                    result.add(SymbolInfo(name, kind, type = type, isMutable = isMutable, line = lineNum, offset = offset, filePath = filePath, documentation = documentation))
                }
            }

            i++
        }

        return result
    }

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    /**
     * Checks whether [trimmed] is a declaration line for the given [keyword].
     *
     * Strips leading modifiers (`expose`, `confine`, `inline`, etc.) and checks
     * that the remaining text starts with the keyword followed by a space or `<`.
     * For `"zone"`, additionally requires an identifier name (not just `{`).
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @param keyword the declaration keyword to match (e.g. `"pack"`, `"func"`).
     * @return `true` if the line declares a symbol of the given kind.
     */
    private fun matchesDecl(trimmed: String, keyword: String): Boolean {
        val core = stripModifiers(trimmed)
        if (!core.startsWith("$keyword ") && !core.startsWith("$keyword<")) return false
        // For "zone", require a name, "zone {" is an unnamed function body, not a declaration.
        if (keyword == "zone") {
            val afterKw = skipGenericParams(core.removePrefix(keyword)).trimStart()
            // Must have an identifier name, not just "{"
            if (afterKw.isEmpty() || afterKw[0] == '{') return false
        }
        return true
    }

    /**
     * Extracts the declaration name and `expose` visibility from a declaration line.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @param keyword the declaration keyword (e.g. `"pack"`, `"enum"`).
     * @return a pair of (name, isExposed).
     */
    private fun extractNameAndExposed(trimmed: String, keyword: String): Pair<String, Boolean> {
        val exposed = trimmed.startsWith("expose ")
        val core = stripModifiers(trimmed)
        val afterKeyword = skipGenericParams(core.removePrefix(keyword)).trimStart()
        val name = if (keyword == "zone") {
            afterKeyword.substringBefore("{").substringBefore(" ").trim()
        } else {
            afterKeyword.substringBefore("(").substringBefore("{")
                .substringBefore("<").substringBefore(":").substringBefore(" ").trim()
        }
        return name to exposed
    }

    /**
     * Strips leading modifiers (`expose`, `confine`, `inline`, etc.) from a line.
     *
     * @param trimmed the source line to strip.
     * @return the line with leading modifiers removed.
     */
    private fun stripModifiers(trimmed: String): String {
        var s = trimmed
        for (mod in listOf("expose ", "confine ", "protect ", "friend ", "inline ", "deepinline ", "noinline ", "unsafe ", "threadlocal ")) {
            if (s.startsWith(mod)) {
                s = s.removePrefix(mod).trimStart()
            }
        }
        return s
    }

    /**
     * Skips a leading `<...>` generic parameter list if present.
     *
     * @param s the string potentially starting with `<`.
     * @return the remainder of the string after the closing `>`, or [s] unchanged.
     */
    private fun skipGenericParams(s: String): String {
        val trimmed = s.trimStart()
        if (!trimmed.startsWith("<")) return trimmed
        var depth = 0
        var i = 0
        while (i < trimmed.length) {
            if (trimmed[i] == '<') depth++
            else if (trimmed[i] == '>') {
                depth--
                if (depth == 0) return trimmed.substring(i + 1)
            }
            i++
        }
        return trimmed // no closing > found, return as-is
    }

    /**
     * Checks whether [trimmed] is a `func` declaration (with optional modifiers).
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return `true` if the line starts a function declaration.
     */
    private fun isFuncDecl(trimmed: String): Boolean {
        val core = stripModifiers(trimmed)
        return core.startsWith("func ") || core.startsWith("func<")
    }

    /**
     * Extracts the function name and `expose` visibility from a `func` declaration.
     *
     * Handles both `func name(...)` and `func<T> name(...)` forms.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return a pair of (name, isExposed).
     */
    private fun extractFuncNameAndExposed(trimmed: String): Pair<String, Boolean> {
        val exposed = trimmed.startsWith("expose ")
        val afterFunc = if (trimmed.contains("func<")) {
            trimmed.substringAfter("func<").substringAfter("> ").substringAfter(">")
        } else {
            trimmed.substringAfter("func ")
        }
        val name = afterFunc.substringBefore("(").substringBefore("{").substringBefore(":").trim()
        return name to exposed
    }

    /**
     * Checks whether [trimmed] is a top-level `var` or `fin` declaration.
     *
     * Excludes lines containing `{` without `=` (which would be blocks, not variables).
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return `true` if the line declares a top-level variable or constant.
     */
    private fun isTopLevelVarFin(trimmed: String): Boolean {
        if (trimmed.contains("{") && !trimmed.contains("=")) return false
        return trimmed.startsWith("var ") || trimmed.startsWith("fin ") ||
               trimmed.startsWith("mem ") || trimmed.startsWith("rem ") || trimmed.startsWith("ret ") ||
               trimmed.startsWith("expose var ") || trimmed.startsWith("expose fin ")
    }

    /**
     * Extracts function/constructor parameters from the parenthesized section of a line.
     *
     * Parses `(name: Type, name2: Type2)` into a list of name-type pairs.
     *
     * @param line the source line containing the parameter list.
     * @return the list of (name, type) pairs, where type may be `null`.
     */
    private fun extractParams(line: String): List<Pair<String, String?>> {
        val parenContent = line.substringAfter("(", "").substringBefore(")", "")
        if (parenContent.isBlank()) return emptyList()
        return parenContent.split(",").mapNotNull { param ->
            val trimmed = param.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val name = trimmed.substringBefore(":").substringBefore("=").trim()
            val type = if (trimmed.contains(":")) trimmed.substringAfter(":").substringBefore("=").trim() else null
            name to type
        }
    }

    /**
     * Extracts the return type from a function signature line.
     *
     * Looks for a `:` after the closing `)` and returns the type text before
     * any `{` or `=`.
     *
     * @param line the source line containing the function signature.
     * @return the return type string, or `null` if none is declared.
     */
    private fun extractReturnType(line: String): String? {
        val afterParen = line.substringAfter(")", "")
        if (afterParen.isBlank()) return null
        val afterColon = afterParen.trimStart().removePrefix(":").takeIf { it != afterParen.trimStart() } ?: return null
        return afterColon.substringBefore("{").substringBefore("=").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts a type annotation from text following a name (e.g. `name: Type = default`).
     *
     * @param afterName the text after the symbol name.
     * @return the type string, or `null` if no `:` is present.
     */
    private fun extractTypeAnnotation(afterName: String): String? {
        if (!afterName.contains(":")) return null
        return afterName.substringAfter(":").substringBefore("=").substringBefore("{").trim().takeIf { it.isNotEmpty() }
    }

    private fun inferTypeFromInitializer(afterName: String): String? {
        val initializer = afterName.substringAfter("=", "").trim()
        if (initializer.isBlank()) return null
        val ctor = Regex("""^([A-Z][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*\(""").find(initializer)
        if (ctor != null) return ctor.groupValues[1]
        return when {
            initializer.startsWith("\"") -> "String"
            initializer == "true" || initializer == "false" -> "Bool"
            initializer.matches(Regex("""[-+]?\d+""")) -> "Int"
            initializer.matches(Regex("""[-+]?\d+\.\d+.*""")) -> "Real"
            else -> null
        }
    }

    private fun extractDocComment(lines: List<String>, declarationLine: Int): String? {
        var i = declarationLine - 1
        while (i >= 0 && lines[i].isBlank()) i--
        if (i < 0) return null

        val line = lines[i].trim()
        if (line.startsWith("///")) {
            val docs = ArrayDeque<String>()
            while (i >= 0 && lines[i].trimStart().startsWith("///")) {
                docs.addFirst(lines[i].trimStart().removePrefix("///").trim())
                i--
            }
            return docs.joinToString("\n").trim().takeIf { it.isNotEmpty() }
        }

        if (!line.endsWith("*/")) return null
        val docs = ArrayDeque<String>()
        while (i >= 0) {
            val trimmed = lines[i].trim()
            docs.addFirst(
                trimmed
                    .removePrefix("/**")
                    .removePrefix("/*")
                    .removeSuffix("*/")
                    .trimStart('*')
                    .trim()
            )
            if (trimmed.startsWith("/**") || trimmed.startsWith("/*")) break
            i--
        }
        return docs.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts the test name from a `test` declaration line.
     *
     * Supports both quoted (`test "name"`) and unquoted (`test name`) forms.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return the extracted test name.
     */
    private fun extractTestName(trimmed: String): String {
        val afterTest = trimmed.removePrefix("test ").trim()
        return if (afterTest.startsWith("\"")) {
            afterTest.substringAfter("\"").substringBefore("\"")
        } else {
            afterTest.substringBefore("{").substringBefore(" ").trim()
        }
    }

    /**
     * Extracts the type name from an `impl` declaration line.
     *
     * Handles generic forms like `impl<T> TypeName`.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return the name of the type being implemented.
     */
    private fun extractImplTypeName(trimmed: String): String {
        val afterImpl = trimmed.substringAfter("impl ").trim()
        if (afterImpl.contains(" for ")) {
            return simpleTypeName(afterImpl.substringAfter(" for ").substringBefore("{").trim())
        }
        // Skip generic params: impl<T> TypeName { or impl<T> ctor() for Storage {
        val afterGenerics = skipGenericParams(afterImpl).trimStart()
        return simpleTypeName(afterGenerics.substringBefore("{").substringBefore(" ").substringBefore(":").substringBefore("(").trim())
    }

    /**
     * Extracts the spec (trait) name from an `impl` declaration, if present.
     *
     * Recognizes `impl Spec for Type` and legacy `impl Type: Spec` syntax.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return the spec name, or `null` if this is a plain impl block.
     */
    private fun extractImplSpecName(trimmed: String): String? {
        // impl SpecName for TypeName { or impl TypeName: SpecName {
        val afterImpl = skipGenericParams(trimmed.substringAfter("impl ").trim()).trimStart()
        return when {
            afterImpl.contains(" for ") -> afterImpl.substringBefore(" for ").substringBefore("<").trim()
            afterImpl.contains(": ") -> {
                val afterColon = afterImpl.substringAfter(": ").substringBefore("{").trim()
                if (afterColon.isNotEmpty() && afterColon[0].isUpperCase()) afterColon else null
            }
            else -> null
        }
    }

    /**
     * Extracts `var`/`fin`/`mut` fields from a braced block (pack, solo).
     *
     * Also handles bare field syntax (`name: Type = default`) for constructor-style packs.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the line containing the opening declaration.
     * @return the list of field symbols found inside the block.
     */
    private fun extractBlockFields(lines: List<String>, startIdx: Int): List<SymbolInfo> {
        val fields = mutableListOf<SymbolInfo>()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth == 1) {
                val memberTrimmed = stripModifiers(l.trimStart())
                val isMutable = memberTrimmed.startsWith("var ") || memberTrimmed.startsWith("mut ")
                val isField = memberTrimmed.startsWith("var ") || memberTrimmed.startsWith("fin ") || memberTrimmed.startsWith("mut ")
                if (isField) {
                    val keyword = when {
                        memberTrimmed.startsWith("var ") -> "var"
                        memberTrimmed.startsWith("fin ") -> "fin"
                        else -> "mut"
                    }
                    val afterKw = memberTrimmed.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val defaultVal = afterKw.substringAfter("=", "").substringBefore(",").trim().takeIf { it.isNotEmpty() }
                    fields.add(SymbolInfo(name, SymbolKind.FIELD, type = type, isMutable = isMutable, line = j + 1, defaultValueText = defaultVal))
                }
                // Also handle bare field syntax: name: Type = default
                if (!isField && memberTrimmed.contains(":") && !memberTrimmed.startsWith("//") && !memberTrimmed.startsWith("func ") && !memberTrimmed.startsWith("}")) {
                    val name = memberTrimmed.substringBefore(":").trim()
                    if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) {
                        val type = memberTrimmed.substringAfter(":").substringBefore("=").substringBefore(",").trim().takeIf { it.isNotEmpty() }
                        val defaultVal = memberTrimmed.substringAfter("=", "").substringBefore(",").trim().takeIf { it.isNotEmpty() }
                        fields.add(SymbolInfo(name, SymbolKind.FIELD, type = type, line = j + 1, defaultValueText = defaultVal))
                    }
                }
            }
            if (started && depth <= 0) break
            j++
        }
        return fields
    }

    /**
     * Extracts methods, properties, operators, constructors, and destructors from a braced block.
     *
     * Used for impl, spec, and solo blocks.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the line containing the opening declaration.
     * @param filePath the absolute path of the source file.
     * @return the list of method-like symbols found inside the block.
     */
    private fun extractBlockMethods(lines: List<String>, startIdx: Int, filePath: String): List<SymbolInfo> {
        val methods = mutableListOf<SymbolInfo>()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth == 1) {
                val memberTrimmed = stripModifiers(l.trimStart())
                when {
                    memberTrimmed.startsWith("func ") || memberTrimmed.startsWith("func<") -> {
                        val (name, _) = extractFuncNameAndExposed(memberTrimmed)
                        val params = extractParams(memberTrimmed)
                        val returnType = extractReturnType(memberTrimmed)
                        methods.add(SymbolInfo(name, SymbolKind.METHOD, type = returnType, params = params, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("task ") || memberTrimmed.startsWith("task<") -> {
                        val (name, _) = extractNameAndExposed(memberTrimmed, "task")
                        val params = extractParams(memberTrimmed)
                        val returnType = extractReturnType(memberTrimmed)
                        methods.add(SymbolInfo(name, SymbolKind.TASK, type = returnType, params = params, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("flow ") || memberTrimmed.startsWith("flow<") -> {
                        val (name, _) = extractNameAndExposed(memberTrimmed, "flow")
                        val params = extractParams(memberTrimmed)
                        val returnType = extractReturnType(memberTrimmed)
                        methods.add(SymbolInfo(name, SymbolKind.FLOW, type = returnType, params = params, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("prop ") -> {
                        val name = memberTrimmed.removePrefix("prop ").substringBefore(":").substringBefore("{").trim()
                        val returnType = extractReturnType(memberTrimmed) ?: extractTypeAnnotation(memberTrimmed.substringAfter("prop "))
                        methods.add(SymbolInfo(name, SymbolKind.PROPERTY, type = returnType, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("oper ") -> {
                        val op = memberTrimmed.removePrefix("oper ").substringBefore("(").substringBefore("{").trim()
                        methods.add(SymbolInfo(op, SymbolKind.OPERATOR, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("ctor") -> {
                        val params = extractParams(memberTrimmed)
                        methods.add(SymbolInfo("ctor", SymbolKind.CTOR, params = params, line = j + 1, filePath = filePath))
                    }
                    memberTrimmed.startsWith("dtor") -> {
                        methods.add(SymbolInfo("dtor", SymbolKind.DTOR, line = j + 1, filePath = filePath))
                    }
                }
            }
            if (started && depth <= 0) break
            j++
        }
        return methods
    }

    /**
     * Extracts impl block members by delegating to [extractBlockMethods].
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the `impl` declaration line.
     * @param filePath the absolute path of the source file.
     * @return the list of members declared in the impl block.
     */
    private fun extractImplMembers(lines: List<String>, startIdx: Int, filePath: String): List<SymbolInfo> {
        return extractBlockMethods(lines, startIdx, filePath)
    }

    private fun simpleTypeName(typeText: String): String {
        return typeText
            .substringBefore("<")
            .substringBefore("?")
            .trim()
            .substringAfterLast("::").substringAfterLast(".")
    }

    /**
     * Extracts function declarations from a `bridge` block.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the `bridge` declaration line.
     * @param filePath the absolute path of the source file.
     * @return the list of bridge function symbols.
     */
    private fun extractBridgeFuncs(lines: List<String>, startIdx: Int, filePath: String): List<SymbolInfo> {
        val funcs = mutableListOf<SymbolInfo>()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth == 1) {
                val memberTrimmed = l.trimStart()
                if (memberTrimmed.startsWith("func ")) {
                    val name = memberTrimmed.removePrefix("func ").substringBefore("(").trim()
                    val params = extractParams(memberTrimmed)
                    val returnType = extractReturnType(memberTrimmed)
                    funcs.add(SymbolInfo(name, SymbolKind.BRIDGE_FUNC, type = returnType, params = params, line = j + 1, filePath = filePath))
                }
            }
            if (started && depth <= 0) break
            j++
        }
        return funcs
    }

    /**
     * Extracts comma-separated variant names from an enum or fail block.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the enum/fail declaration line.
     * @return the list of variant name strings.
     */
    private fun extractVariants(lines: List<String>, startIdx: Int): List<String> {
        val variants = mutableListOf<String>()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth == 1 && j > startIdx) {
                val inner = l.trim().trimEnd(',')
                if (inner.isNotBlank() && !inner.startsWith("}") && !inner.startsWith("{") && !inner.startsWith("//")) {
                    for (part in inner.split(",")) {
                        val name = part.trim().substringBefore("(").substringBefore(" ").trim()
                        if (name.isNotBlank() && name.first().isUpperCase()) {
                            variants.add(name)
                        }
                    }
                }
            }
            if (started && depth <= 0) break
            j++
        }
        return variants
    }

    /**
     * Extracts slot variants with their parameter lists.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the slot declaration line.
     * @return a list of (variantName, parameters) pairs.
     */
    private fun extractSlotVariants(lines: List<String>, startIdx: Int): List<Pair<String, List<Pair<String, String?>>>> {
        val variants = mutableListOf<Pair<String, List<Pair<String, String?>>>>()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth == 1 && j > startIdx) {
                val inner = l.trim().trimEnd(',')
                if (inner.isNotBlank() && !inner.startsWith("}") && !inner.startsWith("{") && !inner.startsWith("//")) {
                    for (part in inner.split(",")) {
                        val trimmedPart = part.trim()
                        val name = trimmedPart.substringBefore("(").trim()
                        if (name.isNotBlank() && name.first().isUpperCase()) {
                            val params = if (trimmedPart.contains("(")) extractParams(trimmedPart) else emptyList()
                            variants.add(name to params)
                        }
                    }
                }
            }
            if (started && depth <= 0) break
            j++
        }
        return variants
    }

    /**
     * Extracts the full text content inside a braced block for recursive symbol extraction.
     *
     * Used by `scope` declarations to recursively parse nested symbols.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the scope declaration line.
     * @return the inner block text (excluding the outer braces).
     */
    private fun extractBlockContent(lines: List<String>, startIdx: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var started = false
        var j = startIdx

        while (j < lines.size) {
            val l = lines[j]
            for (ch in l) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
            }
            if (started && depth >= 1 && j > startIdx) {
                sb.appendLine(l)
            }
            if (started && depth <= 0) break
            j++
        }
        return sb.toString()
    }

    /**
     * Returns the character offset of the given 0-based [lineIndex] in this string.
     *
     * @param lineIndex the 0-based line number.
     * @return the character offset where that line begins.
     */
    private fun String.lineOffset(lineIndex: Int): Int {
        var offset = 0
        var line = 0
        for (ch in this) {
            if (line == lineIndex) return offset
            if (ch == '\n') line++
            offset++
        }
        return offset
    }

    companion object {

        /** The set of [SymbolKind]s that represent named types with members. */
        private val TYPE_KINDS = setOf(
            SymbolKind.PACK, SymbolKind.ENUM, SymbolKind.FAIL,
            SymbolKind.SLOT, SymbolKind.SCOPE, SymbolKind.SOLO
        )

        /**
         * Returns the [AzoraSymbolService] instance for the given [project].
         *
         * @param project the IntelliJ project.
         * @return the project-level symbol service.
         */
        fun getInstance(project: Project): AzoraSymbolService {
            return project.getService(AzoraSymbolService::class.java)
        }
    }
}

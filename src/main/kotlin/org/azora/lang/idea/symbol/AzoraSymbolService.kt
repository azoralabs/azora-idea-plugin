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

import org.azora.lang.idea.AzoraFileType
import org.azora.lang.idea.build.AzoraManifestReader
import org.azora.lang.idea.build.AzoraProjectConfigService
import org.azora.lang.idea.project.AzoraSdkSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
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
    WRAP_BINDING,
    /** An `annot` decorator declaration. */
    ANNOT,
    /** A dependency-injection `graph` declaration. */
    GRAPH,
    /** A named `macro` declaration or arm operator. */
    MACRO,
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
    val documentation: String? = null,
    /** Source module that owns this declaration (`engine.ui.compose`, `std.math`, ...). */
    val modulePath: String? = null,
    /** Whether the owning module was declared `exposed module` and is visible without an import. */
    val isAutoImported: Boolean = false,
)

/**
 * A cached snapshot of the symbols extracted from a single file.
 *
 * @param symbols the list of top-level symbols found in the file.
 * @param timestamp the wall-clock time when extraction occurred (for staleness checks).
 */
data class FileSymbolTable(
    val symbols: List<SymbolInfo>,
    /** Hash of the exact source snapshot [symbols] were extracted from. */
    val sourceHash: Int,
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
class AzoraSymbolService(private val project: Project? = null) {

    /** Per-file cache mapping absolute file paths to their extracted symbol tables. */
    private val fileSymbolTables = ConcurrentHashMap<String, FileSymbolTable>()

    /** Nearest package/workspace root for source files seen by completion. */
    private val packageRoots = ConcurrentHashMap<String, String?>()

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
        val sourceHash = content.hashCode()
        val cached = fileSymbolTables[filePath]
        if (cached != null && cached.sourceHash == sourceHash) return cached.symbols

        val symbols = extractSymbols(content, filePath)
        fileSymbolTables[filePath] = FileSymbolTable(symbols, sourceHash)
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
        val imports = importedModulePaths(content)
        val currentPackage = packageRootFor(filePath)
        getProjectSymbols(project, filePath).asSequence()
            .filter { symbol ->
                symbol.isAutoImported ||
                    belongsToPackage(symbol, currentPackage) ||
                    moduleVisible(symbol.modulePath, imports)
            }
            .forEach(::add)
        stdlibSymbols(imports).forEach(::add)
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

    /** The project's `.az` files, or an empty list while the index is unavailable. */
    private fun projectFiles(project: Project) = runCatching {
        FileTypeIndex.getFiles(AzoraFileType.INSTANCE, GlobalSearchScope.projectScope(project)).toList()
    }.getOrDefault(emptyList())

    private fun getProjectSymbols(project: Project, currentFilePath: String): List<SymbolInfo> {
        return try {
            projectFiles(project)
                .asSequence()
                .filter { it.path != currentFilePath }
                .take(MAX_INDEXED_PROJECT_FILES)
                .flatMap { file ->
                    val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    if (text == null) emptySequence() else getSymbolsForFile(file.path, text).asSequence()
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ── Real stdlib indexing (no hardcoded symbols) ─────────────────────
    //
    // The standard library is discovered from the configured SDK's on-disk
    // sources and parsed with the same extractor used for project files, so
    // completion/navigation/hover always reflect the installed stdlib.

    /** All stdlib module names discovered from the installed SDK (e.g. `std.math`). */
    fun stdModuleNames(): List<String> = stdlibByModule().keys.sorted()

    /**
     * Every module path importable from this project: those declared by the
     * project's own `.az` files plus those found in the SDK and in dependency
     * source roots. Import completion offers exactly what exists on disk.
     */
    fun importableModuleNames(project: Project?): List<String> {
        val modules = sortedSetOf<String>()
        modules.addAll(stdlibByModule().keys)
        if (project != null) {
            for (file in projectFiles(project)) {
                val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: continue
                moduleOf(text)?.let(modules::add)
            }
        }
        return modules.toList()
    }

    /** The module a file declares, if any. Exposed for import-aware tooling. */
    fun moduleOfFile(content: String): String? = moduleOf(content)

    /**
     * Module paths imported by one source unit. Group imports and namespace
     * wildcards are expanded far enough for visibility checks; selected-item
     * imports intentionally retain their full path so [moduleVisible] can match
     * the owning module prefix.
     */
    internal fun importedModulePaths(source: String): Set<String> {
        val imported = linkedSetOf<String>()
        for (line in source.lines()) {
            var text = line.substringBefore("//").trim()
            if (text.startsWith("exposed ")) text = text.removePrefix("exposed ").trimStart()
            val keyword = when {
                text.startsWith("import ") -> "import "
                text.startsWith("use ") -> "use "
                else -> continue
            }
            text = text.removePrefix(keyword).trim()
            for (part in splitImportParts(text)) {
                val path = part.trim().removeSuffix(".*").trimEnd('.')
                if (path.isEmpty()) continue
                val group = IMPORT_GROUP.matchEntire(path)
                if (group != null) {
                    val base = group.groupValues[1]
                    splitImportParts(group.groupValues[2]).map(String::trim)
                        .filter(::validImportName)
                        .forEach { imported.add("$base.$it") }
                } else if (validImportPath(path)) {
                    imported.add(path)
                }
            }
        }
        return imported
    }

    /** A module is visible after importing it, one of its items, or a namespace wildcard. */
    private fun moduleVisible(module: String?, imports: Set<String>): Boolean {
        if (module == null) return false
        return imports.any { imported ->
            module == imported || module.startsWith("$imported.") || imported.startsWith("$module.")
        }
    }

    /** Current package files may see each other without leaking every workspace package. */
    private fun belongsToPackage(symbol: SymbolInfo, currentPackage: String?): Boolean {
        if (currentPackage == null) return false
        val owner = symbol.filePath?.takeUnless { it.startsWith("<") } ?: return false
        return packageRootFor(owner) == currentPackage
    }

    /** Finds the nearest manifest root, which is the package visibility boundary. */
    private fun packageRootFor(filePath: String): String? {
        if (filePath.startsWith("<")) return null
        return packageRoots.computeIfAbsent(filePath) {
            var directory = File(filePath).absoluteFile.parentFile
            while (directory != null) {
                if (AzoraManifestReader.findManifest(directory) != null) {
                    return@computeIfAbsent runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
                }
                directory = directory.parentFile
            }
            null
        }
    }

    private fun splitImportParts(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (index in text.indices) {
            when (text[index]) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
                ',' -> if (depth == 0) {
                    parts.add(text.substring(start, index))
                    start = index + 1
                }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    private fun validImportPath(path: String): Boolean =
        path.isNotEmpty() && path.split('.').all(::validImportName)

    private fun validImportName(name: String): Boolean =
        name.isNotEmpty() && (name.first().isLetter() || name.first() == '_' || name.first() == '$') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }

    /** Short aliases (last path segment) for each stdlib module, usable after import. */
    fun stdModuleAliases(): Map<String, String> = stdlibByModule().keys
        .filter { it != "std" }
        .associateBy({ it.substringAfterLast('.') }, { it })

    private data class StdlibIndex(
        val signature: String,
        val byModule: Map<String, List<SymbolInfo>>,
        val infixNames: Set<String>,
        val autoImportedModules: Set<String>,
    )

    private enum class ScanState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR }

    @Volatile private var stdlibCache: StdlibIndex? = null

    /** When the library tree was last walked, for the staleness throttle. */
    @Volatile private var lastLibraryScan = 0L

    /**
     * The infix operator names the library sources declare.
     *
     * Callers that need the macros visible from a *file* should use
     * [AzoraMacroIndex] instead, which also covers prefix and type macros and
     * the project's own declarations.
     */
    fun infixOperatorNames(filePath: String, content: String): Set<String> {
        val names = linkedSetOf<String>()
        names.addAll(AzoraMacroScanner.scan(content).infix)
        stdlibByModule()
        stdlibCache?.infixNames?.let { names.addAll(it) }
        return names
    }

    /**
     * The source roots indexed in addition to the project's own files: the
     * installed SDK's standard library, and every dependency source root the
     * project's `.azon` manifests point at.
     */
    private fun libraryRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        System.getenv("AZORA_STDLIB")?.takeIf { it.isNotBlank() }
            ?.let(::File)?.takeIf { it.isDirectory }?.let(roots::add)
        System.getenv("AZORA_HOME")?.takeIf { it.isNotBlank() }
            ?.let { File(it, "std") }?.takeIf { it.isDirectory }?.let(roots::add)
        runCatching { AzoraSdkSettings.getInstance().sdkPath() }.getOrNull()?.let { sdk ->
            val base = File(sdk)
            listOf(base, File(base, "std"), File(base, "lib/std"), File(base, "src/std"), File(base, "Internal/Std"))
                .firstOrNull { it.isDirectory && (File(it, "STDLIB_VERSION").isFile || it.name == "Std") }
                ?.let(roots::add)
        }
        project?.let { p ->
            p.basePath?.let(::File)?.let { base ->
                var directory: File? = base
                while (directory != null) {
                    val current = directory
                    val marker = listOf("workspace.azon", "package.azon", "azora.toml")
                        .any { File(current, it).isFile }
                    if (marker) {
                        File(current, "std").takeIf { it.isDirectory }?.let(roots::add)
                        break
                    }
                    directory = current.parentFile
                }
            }
            runCatching { AzoraProjectConfigService.getInstance(p).dependencySourceRoots() }
                .getOrDefault(emptyList())
                .filterTo(roots) { it.isDirectory }
        }
        return roots.map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }.distinct()
    }

    /**
     * module path (`std.math`, `engine.ecs`) → its declared symbols, scanned
     * from the SDK and from the project's dependency source roots and cached
     * until the scanned file set changes.
     */
    private fun stdlibByModule(): Map<String, List<SymbolInfo>> {
        // Completion asks for this on every keystroke. Walking the SDK tree that
        // often would cost more than the completion itself, so a recent scan is
        // reused outright and staleness is only re-checked periodically.
        val cached = stdlibCache
        val now = System.currentTimeMillis()
        if (cached != null && now - lastLibraryScan < LIBRARY_RESCAN_INTERVAL_MS) return cached.byModule

        val roots = libraryRoots()
        if (roots.isEmpty()) return cached?.byModule ?: emptyMap()
        val files = roots.flatMap { root ->
            runCatching {
                root.walkTopDown().filter { it.isFile && it.extension == "az" }.toList()
            }.getOrDefault(emptyList())
        }
        lastLibraryScan = now
        if (files.isEmpty()) return cached?.byModule ?: emptyMap()

        val signature = roots.joinToString("|") { it.path } +
            "|" + files.size + "|" + files.sumOf { it.lastModified() }
        stdlibCache?.let { if (it.signature == signature) return it.byModule }

        val byModule = LinkedHashMap<String, MutableList<SymbolInfo>>()
        val infixNames = linkedSetOf<String>()
        val autoImportedModules = linkedSetOf<String>()
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            infixNames.addAll(AzoraMacroScanner.scan(text).infix)
            val module = moduleOf(text) ?: continue
            if (isAutoImportedModule(text)) autoImportedModules.add(module)
            val decls = flattenStdlibDecls(extractSymbols(text, f.path))
            byModule.getOrPut(module) { mutableListOf() }.addAll(decls)
        }
        stdlibCache = StdlibIndex(signature, byModule, infixNames, autoImportedModules)
        return byModule
    }

    /** The current `module path` header, including its visibility modifiers. */
    private fun moduleOf(text: String): String? = MODULE_HEADER.find(text)
        ?.groups?.get(1)?.value?.takeIf { it.isNotEmpty() }

    /** `exposed module` declarations are injected into every compilation unit. */
    private fun isAutoImportedModule(text: String): Boolean = EXPOSED_MODULE_HEADER.containsMatchIn(text)

    /** Carries the owning module through nested realm/type members. */
    private fun attachModule(symbol: SymbolInfo, module: String?, autoImported: Boolean): SymbolInfo =
        symbol.copy(
            modulePath = module ?: symbol.modulePath,
            isAutoImported = autoImported || symbol.isAutoImported,
            members = symbol.members.map { attachModule(it, module, autoImported) },
        )

    /** Shifts nested realm members back to their locations in the source file. */
    private fun shiftSymbolLocation(symbol: SymbolInfo, lineBase: Int, offsetBase: Int): SymbolInfo =
        symbol.copy(
            line = if (symbol.line > 0) symbol.line + lineBase else symbol.line,
            offset = if (symbol.line > 0) symbol.offset + offsetBase else symbol.offset,
            members = symbol.members.map { shiftSymbolLocation(it, lineBase, offsetBase) },
        )

    /** Collects the referenceable declarations of a stdlib file, descending into
     *  `friend zone` scopes (where most stdlib symbols live). */
    private fun flattenStdlibDecls(symbols: List<SymbolInfo>): List<SymbolInfo> {
        val out = mutableListOf<SymbolInfo>()
        for (s in symbols) {
            if (s.kind == SymbolKind.SCOPE) out.addAll(flattenStdlibDecls(s.members))
            else out.add(s)
        }
        return out.distinctBy { it.name + ":" + it.kind }
    }

    /** Modules directly nested under [modulePath] (`std` → `std.math`, `std.io`, …). */
    private fun stdlibChildModules(modulePath: String): List<String> = stdlibByModule().keys
        .mapNotNull { m ->
            if (m == modulePath || !m.startsWith("$modulePath.")) null
            else m.removePrefix("$modulePath.").substringBefore(".")
        }
        .distinct()

    /** Resolves a `.`/`::` path to the members of the stdlib module it names. */
    private fun stdMembersForPath(path: List<String>): List<SymbolInfo>? {
        val byModule = stdlibByModule()
        if (byModule.isEmpty()) return null
        // Accept the full dotted path, a short alias (`math` → `std.math`), or the
        // root `std`. A module's short (last) segment is a usable alias after import.
        val joined = path.joinToString(".")
        val modulePath = when {
            joined == "std" || byModule.containsKey(joined) -> joined
            else -> byModule.keys.firstOrNull { it.substringAfterLast('.') == path.firstOrNull() }
                ?.let { (listOf(it) + path.drop(1)).joinToString(".") }
                ?: return null
        }
        val members = byModule[modulePath].orEmpty()
        val childScopes = stdlibChildModules(modulePath).map { child ->
            SymbolInfo(
                name = child, kind = SymbolKind.SCOPE, type = "$modulePath.$child",
                filePath = "<stdlib>", documentation = "Stdlib module `$modulePath.$child`."
            )
        }
        val all = members + childScopes
        return all.ifEmpty { null }
    }

    private fun stdlibSymbols(imports: Set<String>): List<SymbolInfo> {
        val byModule = stdlibByModule()
        if (byModule.isEmpty()) return emptyList()
        val automatic = stdlibCache?.autoImportedModules.orEmpty()
        val visibleModules = byModule.keys.filterTo(linkedSetOf()) { module ->
            module in automatic || moduleVisible(module, imports)
        }

        // Per-visible-module scope, once under its full path and once under its short alias.
        val moduleScopes = visibleModules.filter { it != "std" }.map { module ->
            val members = byModule[module].orEmpty()
            val shortName = module.substringAfterLast('.')
            SymbolInfo(
                name = module, kind = SymbolKind.SCOPE, members = members,
                filePath = "<stdlib>", documentation = "Stdlib module `$module`."
            ) to SymbolInfo(
                name = shortName, kind = SymbolKind.SCOPE, type = module, members = members,
                filePath = "<stdlib>", documentation = "Stdlib module `$module`."
            )
        }

        val root = SymbolInfo(
            name = "std", kind = SymbolKind.SCOPE,
            // Azora's `realm std` surface is assembled from the visible std
            // modules. This is what makes `std::String` and, after importing
            // `std.reflection`, `std::reflect` resolve to their real sources.
            members = visibleModules.flatMap { byModule[it].orEmpty() }
                .distinctBy { Triple(it.name, it.kind, it.filePath) } +
                stdlibChildModules("std").map {
                SymbolInfo(it, SymbolKind.SCOPE, type = "std.$it", filePath = "<stdlib>")
            },
            filePath = "<stdlib>", documentation = "Azora standard library root zone."
        )

        // Flat symbols so `std::name` and post-import bare names complete directly.
        val flat = visibleModules.flatMap { byModule[it].orEmpty() }
        return listOf(root) + moduleScopes.flatMap { listOf(it.first, it.second) } + flat
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
        val lineDepths = braceDepthAtLineStarts(content)
        var i = 0

        while (i < lines.size) {
            // Nested declarations are collected by the owning realm/type/impl
            // extractor. Scanning them again as top-level symbols breaks symbol
            // identity and makes same-spelled navigation ambiguous.
            if (lineDepths.getOrElse(i) { 0 } != 0) {
                i++
                continue
            }
            val line = lines[i]
            val trimmed = line.trimStart()
            val lineNum = i + 1
            val lineOffset = content.lineOffset(i)
            fun declarationOffset(name: String): Int = lineOffset + identifierOffsetInLine(line, name)
            val documentation = extractDocComment(lines, i)

            when {
                trimmed.startsWith("use ") || trimmed.startsWith("import ") -> {
                    val name = trimmed.substringAfter(' ').trim()
                    result.add(SymbolInfo(name, SymbolKind.USE, line = lineNum, offset = declarationOffset(name.substringBefore('.')), filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "pack") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "pack")
                    val fields = extractBlockFields(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.PACK, members = fields.map { it.copy(filePath = filePath) }, params = fields.map { it.name to it.type }, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "enum") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "enum")
                    val params = extractParams(trimmed)
                    val payload = stripVisibility(trimmed).startsWith("variant enum ")
                    val variants = if (payload) extractSlotVariants(lines, i, filePath)
                    else extractVariants(lines, i, filePath)
                    val typedVariants = variants.map { it.copy(type = name) }
                    result.add(SymbolInfo(name, SymbolKind.ENUM, members = typedVariants, params = params, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "error") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "error")
                    val payload = stripVisibility(trimmed).startsWith("variant error ")
                    val variants = if (payload) extractSlotVariants(lines, i, filePath)
                    else extractVariants(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.FAIL, members = variants.map { it.copy(type = name) }, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "union") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "union")
                    val fields = extractBlockFields(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.PACK, members = fields, params = fields.map { it.name to it.type }, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "realm") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "realm")
                    val blockContent = extractBlockContent(lines, i)
                    val lineBase = i + 1
                    val offsetBase = content.lineOffset((i + 1).coerceAtMost(lines.lastIndex))
                    val members = extractSymbols(blockContent, filePath)
                        .map { shiftSymbolLocation(it, lineBase, offsetBase) }
                    result.add(SymbolInfo(name, SymbolKind.SCOPE, members = members, line = lineNum, offset = declarationOffset(name.substringBefore("::")), filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "graph") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "graph")
                    result.add(SymbolInfo(name, SymbolKind.GRAPH, line = lineNum, offset = declarationOffset(name), filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                trimmed.startsWith("impl oper") -> {
                    val typeName = simpleTypeName(trimmed.substringAfter(" for ", "").substringBefore("{").trim())
                    val operatorName = trimmed.removePrefix("impl ").substringBefore(" for ").substringBefore("(").trim()
                    val member = SymbolInfo(operatorName, SymbolKind.OPERATOR, line = lineNum, offset = declarationOffset(operatorName.removePrefix("oper")), filePath = filePath, documentation = documentation)
                    result.add(SymbolInfo(typeName, SymbolKind.PACK, members = listOf(member), line = lineNum, offset = declarationOffset(typeName), filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("impl as ") -> {
                    val typeName = simpleTypeName(trimmed.substringAfter(" for ", "").substringBefore("{").trim())
                    val targetType = trimmed.removePrefix("impl as ").substringBefore(" for ").trim()
                    val member = SymbolInfo("as $targetType", SymbolKind.METHOD, type = targetType, line = lineNum, offset = declarationOffset(targetType), filePath = filePath, documentation = documentation)
                    result.add(SymbolInfo(typeName, SymbolKind.PACK, members = listOf(member), line = lineNum, offset = declarationOffset(typeName), filePath = filePath, documentation = documentation))
                }

                matchesDecl(trimmed, "impl") -> {
                    val typeName = extractImplTypeName(trimmed)
                    val specName = extractImplSpecName(trimmed)
                    val members = extractImplMembers(lines, i, filePath)
                    val kind = if (specName != null) SymbolKind.IMPL_SPEC else SymbolKind.PACK
                    result.add(SymbolInfo(typeName, kind, type = specName, members = members, line = lineNum, offset = declarationOffset(typeName), filePath = filePath, genericParams = extractLeadingImplParams(trimmed), documentation = documentation))
                }

                isFuncDecl(trimmed) -> {
                    val (name, exposed) = extractFuncNameAndExposed(trimmed)
                    val header = callableHeader(lines, i)
                    val params = extractParams(header)
                    val returnType = extractReturnType(header)
                    result.add(SymbolInfo(name, SymbolKind.FUNC, type = returnType, params = params, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(header, name), isExposed = exposed, documentation = documentation))
                }

                matchesDecl(trimmed, "spec") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "spec")
                    val methods = extractBlockMethods(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.SPEC, members = methods, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), isExposed = exposed, documentation = documentation))
                }

                Regex("""^bridge\s+\.[A-Za-z_]""").containsMatchIn(trimmed) -> {
                    val target = trimmed.removePrefix("bridge ").substringBefore("{").trim().removePrefix(".")
                    val funcs = extractBridgeFuncs(lines, i, filePath)
                    result.add(SymbolInfo(target, SymbolKind.BRIDGE, members = funcs, line = lineNum, offset = declarationOffset(target), filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("test ") -> {
                    val name = extractTestName(trimmed)
                    result.add(SymbolInfo(name, SymbolKind.TEST, line = lineNum, offset = declarationOffset(name), filePath = filePath, documentation = documentation))
                }

                trimmed.startsWith("typealias ") -> {
                    val rest = trimmed.removePrefix("typealias ").trim()
                    val name = rest.substringBefore("=").substringBefore(" ").trim()
                    val type = rest.substringAfter("=", "").trim().takeIf { it.isNotEmpty() }
                    result.add(SymbolInfo(name, SymbolKind.TYPEALIAS, type = type, line = lineNum, offset = declarationOffset(name), filePath = filePath, genericParams = extractGenericParams(trimmed, name), documentation = documentation))
                }

                matchesDecl(trimmed, "annot") -> {
                    val (name, exposed) = extractNameAndExposed(trimmed, "annot")
                    val fields = extractBlockFields(lines, i, filePath)
                    result.add(SymbolInfo(name, SymbolKind.ANNOT, members = fields, line = lineNum, offset = declarationOffset(name), filePath = filePath, isExposed = exposed, documentation = documentation))
                }

                stripModifiers(trimmed).startsWith("macro ") -> {
                    MACRO_DECLARATION_NAME.find(trimmed)?.let { macro ->
                        val name = macro.groupValues[1]
                        val nameOffset = lineOffset + macro.groups[1]!!.range.first
                        result.add(SymbolInfo(name, SymbolKind.MACRO, line = lineNum, offset = nameOffset, filePath = filePath, documentation = documentation))
                    }
                }

                isTopLevelVarFin(trimmed) -> {
                    val core = stripModifiers(trimmed)
                    val keyword = listOf("var", "val", "fin", "let").firstOrNull { core.startsWith("$it ") } ?: "fin"
                    val isMutable = keyword == "var"
                    val exposed = trimmed.trimStart().startsWith("exposed ")
                    val afterKw = core.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val defaultVal = afterKw.substringAfter("=", "").trim().takeIf { it.isNotEmpty() }
                    val kind = if (isMutable) SymbolKind.VAR else SymbolKind.FIN
                    result.add(SymbolInfo(name, kind, type = type, isMutable = isMutable, line = lineNum, offset = declarationOffset(name), filePath = filePath, isExposed = exposed, defaultValueText = defaultVal, documentation = documentation))
                }

                trimmed.startsWith("threadlocal ") -> {
                    val rest = trimmed.removePrefix("threadlocal ").trim()
                    val isMutable = rest.startsWith("var ")
                    val keyword = if (isMutable) "var" else "fin"
                    val afterKw = rest.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val kind = if (isMutable) SymbolKind.VAR else SymbolKind.FIN
                    result.add(SymbolInfo(name, kind, type = type, isMutable = isMutable, line = lineNum, offset = declarationOffset(name), filePath = filePath, documentation = documentation))
                }
            }

            i++
        }

        val module = moduleOf(content)
        val autoImported = isAutoImportedModule(content)
        return result.map { attachModule(it, module, autoImported) }
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
        return core.startsWith("$keyword ") &&
            core.removePrefix(keyword).trimStart().firstOrNull()?.let { it.isLetter() || it == '_' || it == '$' } == true
    }

    /**
     * Extracts the declaration name and `expose` visibility from a declaration line.
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @param keyword the declaration keyword (e.g. `"pack"`, `"enum"`).
     * @return a pair of (name, isExposed).
     */
    private fun extractNameAndExposed(trimmed: String, keyword: String): Pair<String, Boolean> {
        val exposed = trimmed.startsWith("exposed ")
        val core = stripModifiers(trimmed)
        val afterKeyword = core.removePrefix(keyword).trimStart()
        val name = if (keyword == "realm") {
            afterKeyword.substringBefore("{").trim()
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
        val modifiers = listOf(
            "exposed ", "confined ", "protected ", "inline ", "deepinline ", "noinline ",
            "unsafe ", "threadlocal ", "async ", "react ", "lazy ", "bridge ", "solo ", "variant ",
        )
        while (true) {
            val modifier = modifiers.firstOrNull(s::startsWith) ?: return s
            s = s.removePrefix(modifier).trimStart()
        }
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

    /** Type parameter names in the current `Name<...>` declaration spelling. */
    private fun extractGenericParams(line: String, name: String): List<String> {
        val nameOffset = identifierOffsetInLine(line, name)
        if (nameOffset < 0) return emptyList()
        var start = nameOffset + name.length
        while (start < line.length && line[start].isWhitespace()) start++
        if (line.getOrNull(start) != '<') return emptyList()
        val end = matchingAngle(line, start) ?: return emptyList()
        return splitTopLevel(line.substring(start + 1, end), ',').mapNotNull(::genericParameterName)
    }

    /** Generic parameters written immediately after `impl`: `impl<T> Spec for Type`. */
    private fun extractLeadingImplParams(line: String): List<String> {
        val core = stripModifiers(line.trimStart())
        val implEnd = core.indexOf("impl").takeIf { it >= 0 }?.plus(4) ?: return emptyList()
        var start = implEnd
        while (start < core.length && core[start].isWhitespace()) start++
        if (core.getOrNull(start) != '<') return emptyList()
        val end = matchingAngle(core, start) ?: return emptyList()
        return splitTopLevel(core.substring(start + 1, end), ',').mapNotNull(::genericParameterName)
    }

    private fun genericParameterName(raw: String): String? {
        var text = raw.trim().removePrefix("...").trim()
        for (modifier in listOf("out ")) text = text.removePrefix(modifier).trimStart()
        val name = text.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
        return name.takeIf { it.isNotEmpty() && (it.first().isLetter() || it.first() == '_' || it.first() == '$') }
    }

    private fun matchingAngle(text: String, open: Int): Int? {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return index
            }
        }
        return null
    }

    /** Splits only at separators outside nested generic/call/list syntax. */
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var angle = 0
        var paren = 0
        var bracket = 0
        for (index in text.indices) {
            when (text[index]) {
                '<' -> angle++
                '>' -> angle = (angle - 1).coerceAtLeast(0)
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                separator -> if (angle == 0 && paren == 0 && bracket == 0) {
                    result += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += text.substring(start)
        return result
    }

    /** Exact identifier/path start on one source line, used by navigation. */
    private fun identifierOffsetInLine(line: String, name: String): Int {
        if (name.isEmpty()) return 0
        var from = 0
        while (from <= line.length - name.length) {
            val index = line.indexOf(name, from)
            if (index < 0) break
            val before = line.getOrNull(index - 1)
            val after = line.getOrNull(index + name.length)
            val beforeOk = before == null || !isIdentifierPart(before)
            val afterOk = after == null || !isIdentifierPart(after)
            if (beforeOk && afterOk) return index
            from = index + 1
        }
        val segment = name.substringAfterLast("::").substringAfterLast('.')
        return if (segment != name) identifierOffsetInLine(line, segment) else line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    }

    private fun isIdentifierPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '$'

    /**
     * Checks whether [trimmed] is a `func` declaration (with optional modifiers).
     *
     * @param trimmed the leading-whitespace-stripped source line.
     * @return `true` if the line starts a function declaration.
     */
    private fun isFuncDecl(trimmed: String): Boolean {
        val core = stripModifiers(trimmed)
        return core.startsWith("func ")
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
        val exposed = trimmed.startsWith("exposed ")
        val afterFunc = stripModifiers(trimmed).removePrefix("func ").trimStart()
        val name = afterFunc.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
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
        val core = stripModifiers(trimmed)
        return listOf("var", "val", "fin", "let").any { core.startsWith("$it ") }
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
        val open = line.indexOf('(')
        val close = if (open >= 0) matchingDelimiter(line, open, '(', ')') else null
        if (open < 0 || close == null) return emptyList()

        val sections = mutableListOf<String>()
        // Contextual receivers sit between the callable name and `(`. For
        // operators such as `oper[] [self: Self&](...)`, choose the last
        // bracket pair that actually contains parameter syntax.
        var search = 0
        while (search < open) {
            val bracketOpen = line.indexOf('[', search).takeIf { it in 0 until open } ?: break
            val bracketClose = matchingDelimiter(line, bracketOpen, '[', ']') ?: break
            if (bracketClose < open) {
                line.substring(bracketOpen + 1, bracketClose)
                    .takeIf { ':' in it }
                    ?.let(sections::add)
            }
            search = bracketClose + 1
        }
        sections += line.substring(open + 1, close)

        return sections.flatMap(::splitParameters).mapNotNull { param ->
            val trimmed = param.trim().removePrefix("...").trimStart()
            if (trimmed.isBlank()) return@mapNotNull null
            val name = trimmed.substringBefore(":").substringBefore("=").trim()
            val type = if (trimmed.contains(":")) {
                trimmed.substringAfter(":").substringBefore("=").trim().removePrefix("return ").trimStart()
            } else null
            name to type
        }
    }

    /** Commas and newlines both separate parameters at the outermost level. */
    private fun splitParameters(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var angle = 0
        var paren = 0
        var bracket = 0
        var brace = 0
        var quote: Char? = null
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                if (char == '\\') index++
                else if (char == quote) quote = null
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    '<' -> angle++
                    '>' -> angle = (angle - 1).coerceAtLeast(0)
                    '(' -> paren++
                    ')' -> paren = (paren - 1).coerceAtLeast(0)
                    '[' -> bracket++
                    ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                    '{' -> brace++
                    '}' -> brace = (brace - 1).coerceAtLeast(0)
                    ',', '\n' -> if (angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                        result += text.substring(start, index)
                        start = index + 1
                    }
                }
            }
            index++
        }
        result += text.substring(start)
        return result
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
        val open = line.indexOf('(')
        val close = if (open >= 0) matchingDelimiter(line, open, '(', ')') else null
        if (close == null) return null
        val afterParen = line.substring(close + 1)
        if (afterParen.isBlank()) return null
        val afterColon = afterParen.trimStart().removePrefix(":").takeIf { it != afterParen.trimStart() } ?: return null
        return afterColon.substringBefore(" where ").substringBefore("{").substringBefore("=")
            .lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Joins a multiline callable signature through its matching `)`. */
    private fun callableHeader(lines: List<String>, startIdx: Int): String {
        val header = StringBuilder()
        var index = startIdx
        var sawOpen = false
        var depth = 0
        var quote: Char? = null
        while (index < lines.size) {
            val line = lines[index]
            if (header.isNotEmpty()) header.append('\n')
            header.append(line)
            var cursor = 0
            while (cursor < line.length) {
                val char = line[cursor]
                if (quote != null) {
                    if (char == '\\') cursor++
                    else if (char == quote) quote = null
                } else {
                    when (char) {
                        '"', '\'' -> quote = char
                        '/' -> if (line.getOrNull(cursor + 1) == '/') break
                        '(' -> { sawOpen = true; depth++ }
                        ')' -> if (depth > 0) depth--
                    }
                }
                cursor++
            }
            if (sawOpen && depth == 0) break
            index++
        }
        return header.toString()
    }

    private fun matchingDelimiter(text: String, open: Int, opener: Char, closer: Char): Int? {
        var depth = 0
        var quote: Char? = null
        var index = open
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                if (char == '\\') index++
                else if (char == quote) quote = null
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    opener -> depth++
                    closer -> if (--depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun sourceOffsetOfLine(lines: List<String>, lineIndex: Int): Int {
        var offset = 0
        for (index in 0 until lineIndex.coerceAtMost(lines.size)) offset += lines[index].length + 1
        return offset
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
        var annotationDepth = 0
        while (i >= 0) {
            val trimmed = lines[i].trim()
            if (trimmed.isBlank()) {
                i--
                continue
            }
            val closes = trimmed.count { it == ')' || it == ']' }
            val opens = trimmed.count { it == '(' || it == '[' }
            val annotationTail = annotationDepth == 0 && (trimmed == ")" || trimmed == "]")
            if (annotationDepth > 0 || annotationTail || trimmed.startsWith("@")) {
                annotationDepth = (annotationDepth + closes - opens).coerceAtLeast(0)
                i--
                continue
            }
            break
        }
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
     * Extracts current `var`/`val`/`fin`/`let` and bare fields from a braced block.
     *
     * @param lines all lines of the source file.
     * @param startIdx the index of the line containing the opening declaration.
     * @return the list of field symbols found inside the block.
     */
    private fun extractBlockFields(lines: List<String>, startIdx: Int, filePath: String? = null): List<SymbolInfo> {
        val fields = mutableListOf<SymbolInfo>()
        val depths = braceDepthAtLineStarts(lines.joinToString("\n"))
        val ownerDepth = depths.getOrElse(startIdx) { 0 }
        var j = startIdx + 1

        while (j < lines.size && depths.getOrElse(j) { ownerDepth } > ownerDepth) {
            val l = lines[j]
            if (depths.getOrElse(j) { ownerDepth } == ownerDepth + 1) {
                val memberTrimmed = stripModifiers(l.trimStart())
                val keyword = listOf("var", "val", "fin", "let")
                    .firstOrNull { memberTrimmed.startsWith("$it ") }
                val isField = keyword != null
                if (isField) {
                    val afterKw = memberTrimmed.substringAfter("$keyword ").trim()
                    val name = afterKw.substringBefore(":").substringBefore("=").substringBefore(" ").trim()
                    val type = extractTypeAnnotation(afterKw) ?: inferTypeFromInitializer(afterKw)
                    val defaultVal = afterKw.substringAfter("=", "").substringBefore(" where ").trim().takeIf { it.isNotEmpty() }
                    fields.add(
                        SymbolInfo(
                            name, SymbolKind.FIELD, type = type, isMutable = keyword == "var",
                            line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                            filePath = filePath, defaultValueText = defaultVal,
                            documentation = extractDocComment(lines, j),
                        ),
                    )
                }
                // Bare fields are the ordinary immutable pack form and the only
                // accepted union-member form: `name: Type`.
                if (!isField && memberTrimmed.contains(":") && !memberTrimmed.startsWith("//") &&
                    !memberTrimmed.startsWith("func ") && !memberTrimmed.startsWith("prop ") &&
                    !memberTrimmed.startsWith("ctor") && !memberTrimmed.startsWith("dtor") &&
                    !memberTrimmed.startsWith("}")
                ) {
                    val name = memberTrimmed.substringBefore(":").trim()
                    if (name.isNotEmpty() && name.all(::isIdentifierPart)) {
                        val type = memberTrimmed.substringAfter(":").substringBefore("=").substringBefore(" where ").trim().takeIf { it.isNotEmpty() }
                        val defaultVal = memberTrimmed.substringAfter("=", "").substringBefore(" where ").trim().takeIf { it.isNotEmpty() }
                        fields.add(
                            SymbolInfo(
                                name, SymbolKind.FIELD, type = type, line = j + 1,
                                offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                                filePath = filePath, defaultValueText = defaultVal,
                                documentation = extractDocComment(lines, j),
                            ),
                        )
                    }
                }
            }
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
        val depths = braceDepthAtLineStarts(lines.joinToString("\n"))
        val ownerDepth = depths.getOrElse(startIdx) { 0 }
        var j = startIdx + 1

        while (j < lines.size && depths.getOrElse(j) { ownerDepth } > ownerDepth) {
            val l = lines[j]
            if (depths.getOrElse(j) { ownerDepth } == ownerDepth + 1) {
                val memberTrimmed = stripModifiers(l.trimStart())
                when {
                    memberTrimmed.startsWith("func ") -> {
                        val (name, _) = extractFuncNameAndExposed(memberTrimmed)
                        val header = callableHeader(lines, j)
                        methods.add(
                            SymbolInfo(
                                name, SymbolKind.METHOD, type = extractReturnType(header), params = extractParams(header),
                                line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                                filePath = filePath, genericParams = extractGenericParams(header, name),
                                documentation = extractDocComment(lines, j),
                            ),
                        )
                    }
                    memberTrimmed.startsWith("prop ") -> {
                        val name = memberTrimmed.removePrefix("prop ")
                            .substringBefore("<").substringBefore("[").substringBefore(":").substringBefore("{").trim()
                        val returnType = extractReturnType(memberTrimmed) ?: extractTypeAnnotation(memberTrimmed.substringAfter("prop "))
                        methods.add(SymbolInfo(name, SymbolKind.PROPERTY, type = returnType, line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name), filePath = filePath, genericParams = extractGenericParams(memberTrimmed, name), documentation = extractDocComment(lines, j)))
                    }
                    memberTrimmed.startsWith("oper") -> {
                        val spelling = memberTrimmed.removePrefix("oper").substringBefore("[").substringBefore("(").substringBefore("{").trim()
                        val op = "oper$spelling"
                        methods.add(SymbolInfo(op, SymbolKind.OPERATOR, line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, "oper"), filePath = filePath, documentation = extractDocComment(lines, j)))
                    }
                    memberTrimmed.startsWith("ctor") -> {
                        methods.add(SymbolInfo("ctor", SymbolKind.CTOR, params = extractParams(callableHeader(lines, j)), line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, "ctor"), filePath = filePath, documentation = extractDocComment(lines, j)))
                    }
                    memberTrimmed.startsWith("dtor") -> {
                        methods.add(SymbolInfo("dtor", SymbolKind.DTOR, line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, "dtor"), filePath = filePath, documentation = extractDocComment(lines, j)))
                    }
                }
            }
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
            .trim('&', '!')
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
        val depths = braceDepthAtLineStarts(lines.joinToString("\n"))
        val ownerDepth = depths.getOrElse(startIdx) { 0 }
        var j = startIdx + 1

        while (j < lines.size && depths.getOrElse(j) { ownerDepth } > ownerDepth) {
            val l = lines[j]
            if (depths.getOrElse(j) { ownerDepth } == ownerDepth + 1) {
                val memberTrimmed = stripModifiers(l.trimStart())
                if (memberTrimmed.startsWith("func ")) {
                    val name = extractFuncNameAndExposed(memberTrimmed).first
                    val header = callableHeader(lines, j)
                    funcs.add(
                        SymbolInfo(
                            name, SymbolKind.BRIDGE_FUNC, type = extractReturnType(header), params = extractParams(header),
                            line = j + 1, offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                            filePath = filePath, genericParams = extractGenericParams(header, name),
                            documentation = extractDocComment(lines, j),
                        ),
                    )
                }
            }
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
    private fun extractVariants(lines: List<String>, startIdx: Int, filePath: String): List<SymbolInfo> {
        val variants = mutableListOf<SymbolInfo>()
        val depths = braceDepthAtLineStarts(lines.joinToString("\n"))
        val ownerDepth = depths.getOrElse(startIdx) { 0 }
        var j = startIdx + 1

        while (j < lines.size && depths.getOrElse(j) { ownerDepth } > ownerDepth) {
            val l = lines[j]
            if (depths.getOrElse(j) { ownerDepth } == ownerDepth + 1) {
                val inner = l.trim().trimEnd(',')
                if (inner.isNotBlank() && !inner.startsWith("}") && !inner.startsWith("{") && !inner.startsWith("//")) {
                    // Current enum/error members are newline-separated; commas
                    // inside a payload must never be mistaken for variants.
                    val name = inner.substringBefore("(").substringBefore(" ").trim()
                    if (name.isNotBlank() && (name.first().isLetter() || name.first() == '_')) {
                        variants.add(
                            SymbolInfo(
                                name, SymbolKind.VARIANT, line = j + 1,
                                offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                                filePath = filePath, documentation = extractDocComment(lines, j),
                            ),
                        )
                    }
                }
            }
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
    private fun extractSlotVariants(lines: List<String>, startIdx: Int, filePath: String): List<SymbolInfo> {
        val variants = mutableListOf<SymbolInfo>()
        val depths = braceDepthAtLineStarts(lines.joinToString("\n"))
        val ownerDepth = depths.getOrElse(startIdx) { 0 }
        var j = startIdx + 1

        while (j < lines.size && depths.getOrElse(j) { ownerDepth } > ownerDepth) {
            val l = lines[j]
            if (depths.getOrElse(j) { ownerDepth } == ownerDepth + 1) {
                val inner = l.trim().trimEnd(',')
                if (inner.isNotBlank() && !inner.startsWith("}") && !inner.startsWith("{") && !inner.startsWith("//")) {
                    val name = inner.substringBefore("(").trim()
                    if (name.isNotBlank() && (name.first().isLetter() || name.first() == '_')) {
                        val params = if (inner.contains("(")) extractParams(inner) else emptyList()
                        variants.add(
                            SymbolInfo(
                                name, SymbolKind.VARIANT, params = params, line = j + 1,
                                offset = sourceOffsetOfLine(lines, j) + identifierOffsetInLine(l, name),
                                filePath = filePath, documentation = extractDocComment(lines, j),
                            ),
                        )
                    }
                }
            }
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
            // The depth *before* this line is what says whether the line is a
            // direct member of the block. Reading it after the line has been
            // consumed would miss every member whose body brace is on the same
            // line, e.g. `func f(): Int {`.
            val depthAtLineStart = depth
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

    /** Visibility-only stripping used when the declaration's `variant` modifier matters. */
    private fun stripVisibility(line: String): String {
        var result = line.trimStart()
        while (true) {
            val modifier = listOf("exposed ", "protected ", "confined ").firstOrNull(result::startsWith)
                ?: return result
            result = result.removePrefix(modifier).trimStart()
        }
    }

    /**
     * Brace depth at every physical line start, ignoring comments and literals.
     * It lets the text index keep direct members attached to their owner instead
     * of also publishing them as unrelated top-level declarations.
     */
    private fun braceDepthAtLineStarts(source: String): List<Int> {
        val depths = mutableListOf(0)
        var state = ScanState.CODE
        var blockComments = 0
        var braces = 0
        var index = 0
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                ScanState.CODE -> when {
                    char == '/' && next == '/' -> { state = ScanState.LINE_COMMENT; index++ }
                    char == '/' && next == '*' -> { state = ScanState.BLOCK_COMMENT; blockComments = 1; index++ }
                    source.startsWith("\"\"\"", index) -> { state = ScanState.RAW_STRING; index += 2 }
                    char == '"' -> state = ScanState.STRING
                    char == '\'' -> state = ScanState.CHAR
                    char == '{' -> braces++
                    char == '}' -> braces = (braces - 1).coerceAtLeast(0)
                }
                ScanState.LINE_COMMENT -> if (char == '\n') state = ScanState.CODE
                ScanState.BLOCK_COMMENT -> when {
                    char == '/' && next == '*' -> { blockComments++; index++ }
                    char == '*' && next == '/' -> {
                        blockComments--
                        index++
                        if (blockComments == 0) state = ScanState.CODE
                    }
                }
                ScanState.STRING -> when {
                    char == '\\' -> index++
                    char == '"' -> state = ScanState.CODE
                }
                ScanState.RAW_STRING -> if (source.startsWith("\"\"\"", index)) {
                    state = ScanState.CODE
                    index += 2
                }
                ScanState.CHAR -> when {
                    char == '\\' -> index++
                    char == '\'' -> state = ScanState.CODE
                }
            }
            if (char == '\n') depths += braces
            index++
        }
        return depths
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

        private val MODULE_HEADER = Regex(
            """(?m)^\s*(?:(?:exposed|confined)\s+)*module\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*(?://.*)?$""",
        )

        private val EXPOSED_MODULE_HEADER = Regex(
            """(?m)^\s*exposed\s+module\s+[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*(?://.*)?$""",
        )

        private val IMPORT_GROUP = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.\{(.*)}$""")

        /** The declared operator in `macro @name` or `macro $a @name $b`. */
        private val MACRO_DECLARATION_NAME = Regex(
            """\bmacro\b[^\n{=]*?@([a-z_][A-Za-z0-9_]*[!?&*^]?)""",
        )

        /** Upper bound on project files scanned per completion, to bound latency. */
        private const val MAX_INDEXED_PROJECT_FILES = 500

        /**
         * How long a library scan is trusted before its files are checked for
         * changes again. The SDK and dependency sources change rarely, and
         * re-walking them per keystroke would dominate completion latency.
         */
        private const val LIBRARY_RESCAN_INTERVAL_MS = 30_000L

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

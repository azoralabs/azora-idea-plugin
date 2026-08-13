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
import org.azora.lang.idea.build.AzoraProjectConfigService
import org.azora.lang.idea.project.AzoraSdkSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * The macro names visible from some source position, split by invocation form.
 *
 * @param prefix names invoked before their operand — `vec@[…]`, `res T`, `query [T]`.
 * @param infix names invoked between two operands — `a to b`, `Base with Filter`.
 */
data class AzoraMacros(
    val prefix: Set<String> = emptySet(),
    val infix: Set<String> = emptySet(),
) {
    /** Every macro name regardless of form. */
    val all: Set<String> get() = prefix + infix

    /** Merges two macro sets, e.g. the SDK's with the current file's. */
    operator fun plus(other: AzoraMacros) = AzoraMacros(prefix + other.prefix, infix + other.infix)

    companion object {
        val EMPTY = AzoraMacros()
    }
}

/**
 * Extracts macro declarations from Azora source text.
 *
 * A name is a macro because some `meta` declaration in real source says so —
 * never because this plugin has a list of blessed words. That is what lets a
 * keyword-named macro such as `with` (declared by `azora-engine`'s ECS module
 * as `$Base with $Filter => $Base`) be highlighted as a macro where it is one
 * and as a keyword everywhere else.
 */
object AzoraMacroScanner {

    /** `meta .Prefix("name")` — a value macro invoked `name@[…]` or `name x`. */
    private val prefixMeta = Regex("""meta\s*\.\s*Prefix\s*\(\s*"([^"]*)"\s*\)""")

    /** `meta .Infix("op")` — an operator macro invoked `a op b`. */
    private val infixMeta = Regex("""meta\s*\.\s*Infix\s*\(\s*"([^"]*)"\s*\)""")

    /** `meta .Type {` / `meta type {` — a block of named type-macro arms. */
    private val typeMeta = Regex("""meta\s*(?:\.\s*Type|type)\s*\{""")

    /** `infx name` — the dedicated infix-operator declaration form. */
    private val infxDecl = Regex("""(?m)^\s*(?:\w+\s+)*?infx\s+([A-Za-z_$][\w$]*)""")

    /** `macro $Base @with $Filter => …` — the current arm declaration form. */
    private val macroDecl = Regex("""(?m)^\s*macro\b[^\n]*?@([A-Za-z_$][\w$]*)""")

    /**
     * Scans [text] for every macro it declares.
     *
     * @param text Azora source; may be a whole file or a fragment.
     * @return the prefix and infix macro names declared in it.
     */
    fun scan(text: String): AzoraMacros {
        if (!text.contains("meta") && !text.contains("infx") && !text.contains("macro")) {
            return AzoraMacros.EMPTY
        }

        val prefix = linkedSetOf<String>()
        val infix = linkedSetOf<String>()

        prefixMeta.findAll(text).forEach { normalizeName(it.groupValues[1])?.let(prefix::add) }
        infixMeta.findAll(text).forEach { normalizeName(it.groupValues[1])?.let(infix::add) }
        infxDecl.findAll(text).forEach { normalizeName(it.groupValues[1])?.let(infix::add) }
        macroDecl.findAll(text).forEach { normalizeName(it.groupValues[1])?.let(infix::add) }

        for (match in typeMeta.findAll(text)) {
            val body = blockBodyAfter(text, match.range.last) ?: continue
            scanTypeMacroArms(body, prefix, infix)
        }

        return AzoraMacros(prefix, infix)
    }

    /**
     * Reads the arms of a `meta type { … }` block, each of the form
     * `pattern => expansion`, and records the macro name each one introduces.
     *
     * A pattern beginning with a `$hole` is infix (`$Base with $Filter`), so the
     * word after the hole is the operator; anything else is prefix (`res $T`,
     * `query [...$T]`), so the last word before the first hole is the name.
     */
    private fun scanTypeMacroArms(body: String, prefix: MutableSet<String>, infix: MutableSet<String>) {
        for (rawLine in body.lineSequence()) {
            val line = rawLine.substringBefore("//").trim()
            if (line.isEmpty() || !line.contains("=>")) continue
            val pattern = line.substringBefore("=>").trim()
            if (pattern.isEmpty()) continue

            val words = pattern.split(Regex("""[\s\[\]<>,()]+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue

            // A hole is any word carrying a `$`; variadic holes are spelled
            // `...$T`, so the marker is not always the first character.
            val isHole = { word: String -> word.contains('$') }

            if (isHole(words[0])) {
                // Infix: the first non-hole word after the left hole is the operator.
                words.drop(1).firstOrNull { !isHole(it) }
                    ?.let { normalizeName(it)?.let(infix::add) }
            } else {
                // Prefix: the last plain word before the first hole names the macro.
                words.takeWhile { !isHole(it) }
                    .lastOrNull()
                    ?.let { normalizeName(it)?.let(prefix::add) }
            }
        }
    }

    /**
     * Returns the text between the `{` at [openBraceIndex] and its matching
     * `}`, or `null` if the block never closes.
     */
    private fun blockBodyAfter(text: String, openBraceIndex: Int): String? {
        var depth = 1
        var i = openBraceIndex + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return text.substring(openBraceIndex + 1, i)
            i++
        }
        return null
    }

    /**
     * Reduces a declared macro spelling to the identifier the editor will see.
     *
     * Declarations may carry a sigil prefix or a mutable-variant suffix
     * (`"vec!"`, `"# set"`, `"^ map"`); the highlighted token is always the
     * bare trailing word.
     */
    private fun normalizeName(declared: String): String? {
        val name = declared.trim().substringAfterLast(' ').trimEnd('!').trim()
        if (name.isEmpty()) return null
        if (!name.all { it.isLetterOrDigit() || it == '_' || it == '$' }) return null
        if (!(name[0].isLetter() || name[0] == '_')) return null
        return name
    }
}

/**
 * Project service caching every macro name reachable from a project: those
 * declared in its own `.az` sources, in the installed SDK's standard library,
 * and in any dependency source root the project's `.azon` manifests point at.
 *
 * The index is rebuilt when the set of scanned files changes (by count and
 * modification time), so adding a dependency that declares macros is picked up
 * without restarting the IDE.
 */
@Service(Service.Level.PROJECT)
class AzoraMacroIndex(private val project: Project) {

    private class Snapshot(val signature: String, val macros: AzoraMacros)

    @Volatile private var cached: Snapshot? = null

    /** When the sources were last enumerated, for the staleness throttle. */
    @Volatile private var lastScan = 0L

    /**
     * Returns every macro visible while editing [content].
     *
     * The file's own `meta` declarations are always included, so a macro is
     * highlighted the moment it is typed, before any index refresh.
     */
    fun macrosFor(content: String): AzoraMacros = projectMacros() + AzoraMacroScanner.scan(content)

    /** Every macro declared outside the file currently being edited. */
    fun projectMacros(): AzoraMacros {
        // Highlighting asks for this whenever a file changes. Re-enumerating
        // every source each time would cost more than the highlighting pass, so
        // a recent scan is reused and staleness re-checked periodically.
        val snapshot = cached
        val now = System.currentTimeMillis()
        if (snapshot != null && now - lastScan < RESCAN_INTERVAL_MS) return snapshot.macros

        val roots = externalRoots()
        val projectFiles = projectSourceFiles()
        lastScan = now
        val signature = buildString {
            append(projectFiles.size).append('|')
            append(projectFiles.sumOf { it.timeStamp }).append('|')
            for (root in roots) append(root.path).append(':').append(root.lastModified()).append('|')
        }
        cached?.let { if (it.signature == signature) return it.macros }

        var macros = AzoraMacros.EMPTY
        for (file in projectFiles) {
            val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: continue
            macros += AzoraMacroScanner.scan(text)
        }
        for (root in roots) {
            for (file in azSourcesUnder(root)) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                macros += AzoraMacroScanner.scan(text)
            }
        }

        cached = Snapshot(signature, macros)
        return macros
    }

    private fun projectSourceFiles() = runCatching {
        FileTypeIndex.getFiles(AzoraFileType.INSTANCE, GlobalSearchScope.projectScope(project)).toList()
    }.getOrDefault(emptyList())

    /**
     * Source roots outside the project itself that can declare macros: the
     * installed SDK's standard library and every dependency path the project's
     * `.azon` manifests resolve to.
     */
    private fun externalRoots(): List<File> {
        val roots = mutableListOf<File>()
        runCatching { AzoraSdkSettings.getInstance().sdkPath() }.getOrNull()?.let { sdk ->
            listOf("Internal/Std", "std", "lib/std", "src/std")
                .map { File(sdk, it) }
                .firstOrNull { it.isDirectory }
                ?.let(roots::add)
        }
        runCatching { AzoraProjectConfigService.getInstance(project).dependencySourceRoots() }
            .getOrDefault(emptyList())
            .filterTo(roots) { it.isDirectory }
        return roots
    }

    private fun azSourcesUnder(root: File): List<File> = runCatching {
        root.walkTopDown().maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile && it.extension == "az" }
            .take(MAX_SCANNED_FILES)
            .toList()
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_SCANNED_FILES = 4000

        /**
         * How long a scan is trusted before sources are re-checked. A macro
         * declared in the file being edited is picked up immediately by
         * [macrosFor] regardless, so this only delays noticing macros added
         * elsewhere.
         */
        private const val RESCAN_INTERVAL_MS = 15_000L

        fun getInstance(project: Project): AzoraMacroIndex =
            project.getService(AzoraMacroIndex::class.java)
    }
}

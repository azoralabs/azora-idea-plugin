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
 * @param prefix names invoked before their operand — `@vec[…]`, `@query [T]`.
 * @param infix names invoked between two operands — `a @to b`, `anchor @applyKey key`.
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
 * A name is a macro because some `macro` declaration in real source says so —
 * never because this plugin has a list of blessed words. That is what lets a
 * keyword-named macro such as `with` (declared by `azora-engine`'s ECS module
 * as `$Base with $Filter => $Base`) be highlighted as a macro where it is one
 * and as a keyword everywhere else.
 */
object AzoraMacroScanner {

    /** Current declarations: `macro @query { ... }` and `macro $a @to $b => ...`. */
    private val macroDecl = Regex(
        """(?m)^\s*(?:(?:exposed|protected|confined|inline|deepinline|noinline|bridge)\s+)*macro\b([^\n]*)""",
    )

    private val macroName = Regex("""@([A-Za-z_$][\w$]*)(?:[!?&*^])?""")

    /** Literal clauses in a macro arm (`$Q with $T without $S`). */
    private val armWord = Regex("""(?<![$@A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)""")

    /**
     * Scans [text] for every macro it declares.
     *
     * @param text Azora source; may be a whole file or a fragment.
     * @return the prefix and infix macro names declared in it.
     */
    fun scan(text: String): AzoraMacros {
        if (!text.contains("macro")) {
            return AzoraMacros.EMPTY
        }

        val prefix = linkedSetOf<String>()
        val infix = linkedSetOf<String>()

        for (declaration in macroDecl.findAll(codeOnly(text))) {
            val tail = declaration.groupValues[1]
            val nameMatch = macroName.find(tail) ?: continue
            val name = normalizeName(nameMatch.groupValues[1]) ?: continue
            val beforeName = tail.substring(0, nameMatch.range.first)
            if ('$' in beforeName) infix.add(name) else prefix.add(name)

            // A block macro may name clause fragments in its arm grammar. They
            // are invoked with `@` too (`@query [...] @with T @without U`), so
            // index those literal words as prefix fragments for coloring and
            // completion. The declaration's own name stays excluded.
            val open = text.indexOf('{', declaration.range.first)
            if (open >= 0 && open <= declaration.range.last + 1) {
                val body = blockBodyAfter(text, open) ?: continue
                scanArmClauses(body, prefix)
            }
        }

        return AzoraMacros(prefix, infix)
    }

    /** Collects literal arm words that become `@clause` invocation fragments. */
    private fun scanArmClauses(body: String, prefix: MutableSet<String>) {
        for (rawLine in body.lineSequence()) {
            val line = rawLine.substringBefore("//").trim()
            if (line.isEmpty() || !line.contains("=>")) continue
            val pattern = line.substringBefore("=>").trim()
            if (pattern.isEmpty()) continue
            armWord.findAll(pattern).map { it.groupValues[1] }
                .filterNot { it in ARM_GRAMMAR_WORDS }
                .mapNotNull(::normalizeName)
                .forEach(prefix::add)
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

    /** Masks comments and literals so declarations written in docs never enter the index. */
    private fun codeOnly(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        fun blank(at: Int) { if (chars[at] != '\n' && chars[at] != '\r') chars[at] = ' ' }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> while (index < source.length && source[index] != '\n') blank(index++)
                source.startsWith("/*", index) -> {
                    var depth = 0
                    do {
                        when {
                            source.startsWith("/*", index) -> { blank(index); blank(index + 1); index += 2; depth++ }
                            source.startsWith("*/", index) -> { blank(index); blank(index + 1); index += 2; depth-- }
                            else -> blank(index++)
                        }
                    } while (index < source.length && depth > 0)
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
        return chars.concatToString()
    }

    private val ARM_GRAMMAR_WORDS = setOf("true", "false", "null")
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
     * The file's own `macro` declarations are always included, so a macro is
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

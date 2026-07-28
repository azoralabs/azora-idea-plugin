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

package org.azora.lang.idea.annotator

import org.azora.lang.idea.AzoraLanguageFacts
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.symbol.AzoraSymbolService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Reports the problems the plugin can find without running the compiler, each
 * underlined on the exact text that is wrong rather than on the whole line.
 *
 * The checks are deliberately conservative: every one of them is decidable from
 * the text alone, so nothing here can flag correct code. Where a fix is
 * unambiguous it is offered as an intention.
 */
class AzoraExternalAnnotator : ExternalAnnotator<AzoraAnnotationInfo, AzoraAnnotationResult>() {

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): AzoraAnnotationInfo {
        val project = file.project
        val modules = runCatching {
            AzoraSymbolService.getInstance(project).importableModuleNames(project)
        }.getOrDefault(emptyList())
        return AzoraAnnotationInfo(file.text, file.name, file.virtualFile?.path, modules)
    }

    override fun doAnnotate(info: AzoraAnnotationInfo): AzoraAnnotationResult {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        try {
            val scan = AzoraSourceScanner(info.source).scan()
            diagnostics += scan.diagnostics
            diagnostics += checkBrackets(scan)
            diagnostics += checkImports(info)
            diagnostics += checkDecorators(info)
        } catch (_: Exception) {
            // Half-typed source is normal; never let a scan failure surface.
        }
        return AzoraAnnotationResult(diagnostics, info.filePath)
    }

    override fun apply(file: PsiFile, result: AzoraAnnotationResult, holder: AnnotationHolder) {
        val length = file.textLength

        result.filePath?.let { path ->
            runCatching { AzoraSymbolService.getInstance(file.project).invalidate(path) }
        }

        for (diagnostic in result.diagnostics) {
            val start = diagnostic.range.startOffset.coerceIn(0, length)
            val end = diagnostic.range.endOffset.coerceIn(start, length)
            if (start == end && start >= length) continue

            val range = if (start == end) TextRange(start, (start + 1).coerceAtMost(length)) else TextRange(start, end)
            var annotation = holder.newAnnotation(diagnostic.severity, diagnostic.message)
                .range(range)
                .textAttributes(
                    if (diagnostic.severity == HighlightSeverity.ERROR) AzoraSyntaxHighlighter.ERROR
                    else AzoraSyntaxHighlighter.WARNING
                )
            for (fix in diagnostic.fixes) {
                annotation = annotation.withFix(AzoraReplacementFix(fix.title, range, fix.replacement))
            }
            annotation.create()
        }
    }

    // ── Checks ─────────────────────────────────────────────────────────

    /** Reports brackets that never close and closers with no opener. */
    private fun checkBrackets(scan: AzoraScanResult): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (unmatched in scan.unclosedBrackets) {
            diagnostics.add(
                AzoraDiagnostic(
                    range = TextRange(unmatched.offset, unmatched.offset + 1),
                    message = "'${unmatched.char}' is never closed",
                    severity = HighlightSeverity.ERROR,
                    fixes = listOf(AzoraFix("Insert '${closerFor(unmatched.char)}'", null)),
                )
            )
        }
        for (extra in scan.unopenedBrackets) {
            diagnostics.add(
                AzoraDiagnostic(
                    range = TextRange(extra.offset, extra.offset + 1),
                    message = "'${extra.char}' has no matching opening bracket",
                    severity = HighlightSeverity.ERROR,
                    fixes = listOf(AzoraFix("Delete '${extra.char}'", "")),
                )
            )
        }
        return diagnostics
    }

    /**
     * Reports imports of modules that do not exist, suggesting the closest
     * module that does. Only runs when the module index is populated, so an
     * un-indexed project never lights up red.
     */
    private fun checkImports(info: AzoraAnnotationInfo): List<AzoraDiagnostic> {
        if (info.knownModules.isEmpty()) return emptyList()
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in IMPORT_LINE.findAll(info.source)) {
            val pathGroup = match.groups[1] ?: continue
            val raw = pathGroup.value.trim()
            // Grouped and wildcard forms have their own shapes; check the base.
            val path = raw.substringBefore(".{").removeSuffix(".*").removeSuffix("*").trim()
            if (path.isEmpty() || path.any { !(it.isLetterOrDigit() || it == '_' || it == '.') }) continue
            if (info.knownModules.any { it == path || it.startsWith("$path.") }) continue

            val suggestion = closestModule(path, info.knownModules)
            val start = pathGroup.range.first + (pathGroup.value.length - pathGroup.value.trimStart().length)
            diagnostics.add(
                AzoraDiagnostic(
                    range = TextRange(start, start + path.length),
                    message = "Module '$path' was not found in this project, the SDK, or any dependency",
                    severity = HighlightSeverity.WARNING,
                    fixes = listOfNotNull(suggestion?.let { AzoraFix("Change to '$it'", it) }),
                )
            )
        }
        return diagnostics
    }

    /** Reports decorators that are neither compiler built-ins nor declared here. */
    private fun checkDecorators(info: AzoraAnnotationInfo): List<AzoraDiagnostic> {
        val declared = DECO_DECL.findAll(info.source).map { it.groupValues[1] }.toSet()
        val builtin = AzoraLanguageFacts.builtinAnnotations.map { it.name }.toSet()
        val known = declared + builtin
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in DECORATOR_USE.findAll(info.source)) {
            val name = match.groupValues[1]
            if (name in known) continue
            val suggestion = known.minByOrNull { levenshtein(it, name) }
                ?.takeIf { levenshtein(it, name) <= MAX_SUGGESTION_DISTANCE }
                ?: continue
            val start = match.range.first + 1
            diagnostics.add(
                AzoraDiagnostic(
                    range = TextRange(start, start + name.length),
                    message = "Unknown decorator '@$name'. Did you mean '@$suggestion'?",
                    severity = HighlightSeverity.WEAK_WARNING,
                    fixes = listOf(AzoraFix("Change to '@$suggestion'", suggestion)),
                )
            )
        }
        return diagnostics
    }

    /** The known module closest to [path], or `null` when none is close enough. */
    private fun closestModule(path: String, modules: List<String>): String? =
        modules.minByOrNull { levenshtein(it, path) }
            ?.takeIf { levenshtein(it, path) <= MAX_SUGGESTION_DISTANCE }

    private fun closerFor(opener: Char): Char = when (opener) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    /** Ordinary edit distance, used to turn a typo into a suggestion. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private companion object {
        val IMPORT_LINE = Regex("""(?m)^\s*(?:export\s+)?(?:import|use)\s+([^\n/]+)""")
        val DECO_DECL = Regex("""(?m)^\s*(?:\w+\s+)*deco\s+(\w+)""")
        val DECORATOR_USE = Regex("""@([A-Z]\w*)""")

        /** How far a name may be from a known one and still be called a typo. */
        const val MAX_SUGGESTION_DISTANCE = 3
    }
}

/**
 * Input for the annotation pass.
 *
 * @param source the file's text.
 * @param fileName the file name.
 * @param filePath the absolute path, or `null` for an in-memory file.
 * @param knownModules every module path importable from this project.
 */
data class AzoraAnnotationInfo(
    val source: String,
    val fileName: String,
    val filePath: String?,
    val knownModules: List<String> = emptyList(),
)

/** Output of the annotation pass. */
data class AzoraAnnotationResult(val diagnostics: List<AzoraDiagnostic>, val filePath: String?)

/** A fix offered alongside a diagnostic. */
data class AzoraFix(
    val title: String,
    /** The text to substitute for the highlighted range, or `null` to append a closer. */
    val replacement: String?,
)

/**
 * A single problem.
 *
 * @param range the exact text the problem covers.
 * @param message what is wrong, in plain language.
 * @param severity how loudly to say it.
 * @param fixes fixes the user can apply, if any.
 */
data class AzoraDiagnostic(
    val range: TextRange,
    val message: String,
    val severity: HighlightSeverity,
    val fixes: List<AzoraFix> = emptyList(),
) {
    /** The 1-based line the problem starts on, for callers that report by line. */
    fun lineIn(source: String): Int =
        source.take(range.startOffset.coerceIn(0, source.length)).count { it == '\n' } + 1
}

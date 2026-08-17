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
import org.azora.lang.idea.AzoraLexerAdapter
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
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
        val source = file.text
        val path = file.virtualFile?.path ?: file.name
        val service = AzoraSymbolService.getInstance(project)
        val modules = runCatching { service.importableModuleNames(project) }.getOrDefault(emptyList())
        val visible = runCatching { service.getAllVisibleSymbols(project, path, source) }.getOrDefault(emptyList())
        val annotations = flatten(visible).filter { it.kind == SymbolKind.ANNOT }.mapTo(linkedSetOf()) { it.name }
        val variants = flatten(visible)
            .filter { it.kind in VARIANT_OWNERS }
            .associate { owner -> owner.name to owner.members.filter { it.kind == SymbolKind.VARIANT }.mapTo(linkedSetOf()) { it.name } }
        return AzoraAnnotationInfo(source, file.name, file.virtualFile?.path, modules, annotations, variants)
    }

    override fun doAnnotate(info: AzoraAnnotationInfo): AzoraAnnotationResult {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        try {
            val scan = AzoraSourceScanner(info.source).scan()
            val code = codeOnly(info.source)
            diagnostics += scan.diagnostics
            diagnostics += checkBrackets(scan)
            diagnostics += checkImports(info)
            diagnostics += checkDecorators(info, code)
            diagnostics += checkNames(code)
            diagnostics += checkUnusedLocals(info, code, usageOnly(info.source, code))
            diagnostics += checkExpectedTypeShorthand(info, code)
            diagnostics += checkNumericInitializers(info.source, code)
        } catch (_: Exception) {
            // Half-typed source is normal; never let a scan failure surface.
        }
        return AzoraAnnotationResult(
            diagnostics.distinctBy { listOf(it.range.startOffset, it.range.endOffset, it.message) },
            info.filePath,
        )
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
                annotation = annotation.withFix(
                    AzoraReplacementFix(fix.title, fix.range ?: range, fix.replacement),
                )
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

    /** Reports decorators that are neither compiler built-ins nor visible declarations. */
    private fun checkDecorators(info: AzoraAnnotationInfo, code: String): List<AzoraDiagnostic> {
        val declared = ANNOT_DECL.findAll(code).map { it.groupValues[1] }.toSet()
        val builtin = AzoraLanguageFacts.builtinAnnotations.map { it.name }.toSet()
        val known = declared + builtin + info.knownAnnotations
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in DECORATOR_USE.findAll(code)) {
            val nameGroup = match.groups[1] ?: continue
            val name = nameGroup.value
            // The same spelling declares a macro (`macro @Name`); it is not an
            // annotation application and naming diagnostics handle it below.
            val lineStart = code.lastIndexOf('\n', match.range.first - 1) + 1
            if (code.substring(lineStart, match.range.first).contains(Regex("""\bmacro\b"""))) continue
            if (name in known) continue
            val suggestion = known.minByOrNull { levenshtein(it, name) }
                ?.takeIf { levenshtein(it, name) <= MAX_SUGGESTION_DISTANCE }
                ?: continue
            val start = nameGroup.range.first
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

    /** Compiler identifier restrictions plus the requested Azora casing rules. */
    private fun checkNames(code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        val handled = mutableSetOf<IntRange>()
        val bridgeBodies = BRIDGE_HEAD.findAll(code).mapNotNull { bridge ->
            val open = code.indexOf('{', bridge.range.first)
            val close = if (open >= 0) matchingBrace(code, open) else null
            close?.let { open..it }
        }.toList()

        fun check(match: MatchResult, group: Int, role: String, upper: Boolean, sigil: String = "") {
            val target = match.groups[group] ?: return
            val original = target.value
            val replacement = camelName(
                original,
                upper,
                allowPrivate = role.equals("function", ignoreCase = true),
            )
            if (original == replacement) return
            val expectation = if (upper) "UpperCamelCase" else "lowerCamelCase"
            diagnostics += AzoraDiagnostic(
                TextRange(target.range.first, target.range.last + 1),
                "$role name '$sigil$original' must use $expectation without internal underscores",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Rename to '$sigil$replacement'", replacement)),
            )
            handled += target.range
        }

        // A bridge declaration names an external ABI symbol, whose spelling is
        // owned by that ABI (`CFRelease`, `vkCreateDevice`, ...), not by Azora's
        // source naming convention. Ordinary/private Azora functions are
        // checked, with one leading `_` retained as the privacy marker.
        FUNCTION_DECL.findAll(code)
            .filterNot { declaration ->
                Regex("""\bbridge\b""").containsMatchIn(declaration.value) ||
                    bridgeBodies.any { declaration.range.first in it }
            }
            .forEach { check(it, 1, "Function", upper = false) }
        MACRO_DECL.findAll(code).forEach { check(it, 1, "Macro", upper = false, sigil = "@") }
        ANNOT_DECL.findAll(code).forEach { check(it, 1, "Annotation", upper = true) }

        // A macro declaration without `@` is a common old-syntax typo. The
        // parser cannot reinterpret it safely, but adding the sigil is exact.
        for (match in MACRO_WITHOUT_AT.findAll(code)) {
            val name = match.groups[1] ?: continue
            if ('@' in match.value) continue
            diagnostics += AzoraDiagnostic(
                TextRange(name.range.first, name.range.last + 1),
                "Macro names start with '@'",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Change to '@${camelName(name.value, false, false)}'", "@${camelName(name.value, false, false)}")),
            )
            handled += name.range
        }

        // These are the token-level rules enforced by SourceSymbolValidator:
        // `__` is compiler-owned and an underscore may otherwise occur only as
        // one leading privacy marker.
        for (match in IDENTIFIER.findAll(code)) {
            val range = match.range
            if (range in handled) continue
            val name = match.value
            if (!name.startsWith("__") && '_' !in name.drop(1)) continue
            val upper = name.dropWhile { it == '_' }.firstOrNull()?.isUpperCase() == true
            val replacement = camelName(name, upper, allowPrivate = true)
            diagnostics += AzoraDiagnostic(
                TextRange(range.first, range.last + 1),
                if (name.startsWith("__"))
                    "'$name' is reserved for compiler-generated symbols"
                else "Internal underscores are not allowed in Azora symbols",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Rename to '$replacement'", replacement)),
            )
        }

        return diagnostics
    }

    /** Warns only for local bindings whose resolved symbol has no later use. */
    private fun checkUnusedLocals(
        info: AzoraAnnotationInfo,
        code: String,
        usageCode: String,
    ): List<AzoraDiagnostic> {
        val bodies = callableBodies(code)
        if (bodies.isEmpty()) return emptyList()
        val service = AzoraSymbolService()
        val resolver = AzoraResolver(null, service)
        val path = info.filePath ?: info.fileName
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in LOCAL_BINDING.findAll(code).take(MAX_LOCAL_DIAGNOSTICS)) {
            val nameGroup = match.groups[2] ?: continue
            val declarationOffset = nameGroup.range.first
            if (bodies.none { declarationOffset in it }) continue
            val name = nameGroup.value
            val body = bodies.first { declarationOffset in it }
            val used = Regex("""\b${Regex.escape(name)}\b""").findAll(usageCode, nameGroup.range.last + 1)
                .takeWhile { it.range.first <= body.last }
                .any { use ->
                    resolver.resolve(path, info.source, use.range.first).firstOrNull()?.let { symbol ->
                        symbol.offset == declarationOffset && symbol.name == name
                    } == true
                }
            if (!used) {
                diagnostics += AzoraDiagnostic(
                    TextRange(nameGroup.range.first, nameGroup.range.last + 1),
                    "Variable '$name' is never used",
                    HighlightSeverity.WARNING,
                )
            }
        }
        return diagnostics
    }

    /** Expected-type constructor and enum-member spellings that can be shortened safely. */
    private fun checkExpectedTypeShorthand(info: AzoraAnnotationInfo, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in EXPLICIT_CONSTRUCTOR.findAll(code)) {
            val expected = match.groupValues[1]
            val constructor = match.groupValues[3]
            if (simpleType(expected) != simpleType(constructor)) continue
            val call = match.groups[2] ?: continue
            val args = match.groupValues[4]
            diagnostics += AzoraDiagnostic(
                TextRange(call.range.first, call.range.last + 1),
                "Expected type '${simpleType(expected)}' is already known; prefer '.(${args})'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use inferred constructor '.(${args})'", ".(${args})")),
            )
        }

        for (match in EXPLICIT_ENUM_MEMBER.findAll(code)) {
            val expected = match.groupValues[1]
            val owner = match.groupValues[2]
            if (simpleType(expected) != simpleType(owner)) continue
            val qualifier = match.groups[2] ?: continue
            diagnostics += AzoraDiagnostic(
                TextRange(qualifier.range.first, qualifier.range.last + 2),
                "Expected enum type '${simpleType(expected)}' is already known; prefer '.${match.groupValues[3]}'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use inferred member '.${match.groupValues[3]}'", ".")),
            )
        }

        val localVariants = variantOwners(AzoraSymbolService().getSymbolsForFile(info.filePath ?: info.fileName, info.source))
        val variants = info.knownVariants + localVariants
        for (match in INFERRED_ENUM_BINDING.findAll(code)) {
            val owner = match.groupValues[3]
            val variant = match.groupValues[4]
            if (variant !in variants[owner].orEmpty()) continue
            val declaration = match.groups[0] ?: continue
            val replacement = "${match.groupValues[1]} ${match.groupValues[2]}: $owner = .$variant"
            diagnostics += AzoraDiagnostic(
                TextRange(match.groups[3]!!.range.first, match.groups[4]!!.range.last + 1),
                "Prefer an explicit enum type with inferred member spelling: '${match.groupValues[2]}: $owner = .$variant'",
                HighlightSeverity.WEAK_WARNING,
                listOf(
                    AzoraFix(
                        "Use '${match.groupValues[2]}: $owner = .$variant'",
                        replacement,
                        TextRange(declaration.range.first, declaration.range.last + 1),
                    ),
                ),
            )
        }
        return diagnostics
    }

    /** Numeric literals must agree with their explicit type without implicit widening/narrowing. */
    private fun checkNumericInitializers(source: String, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in TYPED_INTEGER.findAll(code)) {
            val type = simpleType(match.groupValues[1])
            val literal = match.groups[2] ?: continue
            val suffix = when (type) {
                "Double", "Real" -> ".0"
                "Float" -> ".0f"
                "Decimal" -> ".0D"
                else -> null
            } ?: continue
            diagnostics += AzoraDiagnostic(
                TextRange(literal.range.first, literal.range.last + 1),
                "Integer literal does not have the explicitly declared $type type",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Change to '${literal.value}$suffix'", literal.value + suffix)),
            )
        }
        for (match in TYPED_REAL_TO_INTEGER.findAll(code)) {
            val typeText = match.groupValues[1]
            val type = simpleType(typeText)
            if (type !in INTEGER_TYPES) continue
            val literal = match.groups[2] ?: continue
            val original = source.substring(literal.range.first, literal.range.last + 1)
            diagnostics += AzoraDiagnostic(
                TextRange(literal.range.first, literal.range.last + 1),
                "Real literal cannot initialize $type without an explicit cast",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Cast to $typeText", "$original as $typeText")),
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

    /** Source text with comments/literals blanked while preserving every offset. */
    private fun codeOnly(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var blockDepth = 0
        fun blank(at: Int) {
            if (result[at] != '\n' && result[at] != '\r') result[at] = ' '
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
        return result.concatToString()
    }

    /**
     * Code mask used for reference searches. Literal prose and comments stay
     * blank, but identifier tokens inside `$name` and `${expression}` string
     * interpolation are restored so they count as genuine symbol uses.
     */
    private fun usageOnly(source: String, code: String): String {
        val result = code.toCharArray()
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == AzoraTokenTypes.IDENTIFIER) {
                for (index in lexer.tokenStart until lexer.tokenEnd) result[index] = source[index]
            }
            lexer.advance()
        }
        return result.concatToString()
    }

    /** Turns source spelling into one legal camel-case identifier. */
    private fun camelName(name: String, upper: Boolean, allowPrivate: Boolean): String {
        val privatePrefix = if (allowPrivate && name.startsWith('_') && !name.startsWith("__")) "_" else ""
        val core = name.trimStart('_')
        val words = core.split(Regex("_+"), limit = 0).filter { it.isNotEmpty() }
        if (words.isEmpty()) return if (upper) "Name" else "name"
        fun normalize(word: String): String = if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) {
            word.lowercase()
        } else word
        val first = normalize(words.first()).let { word ->
            if (upper) word.replaceFirstChar { it.uppercase() } else word.replaceFirstChar { it.lowercase() }
        }
        val tail = words.drop(1).joinToString("") { word ->
            normalize(word).replaceFirstChar { it.uppercase() }
        }
        return privatePrefix + first + tail
    }

    private fun simpleType(type: String): String = type.trim()
        .trimEnd('!', '&', '?')
        .substringBefore('<')
        .substringAfterLast("::")
        .substringAfterLast('.')

    /** Body ranges of named functions, ctors, dtors and operators. */
    private fun callableBodies(code: String): List<IntRange> = CALLABLE_HEAD.findAll(code).mapNotNull { head ->
        var paren = 0
        var bracket = 0
        var angle = 0
        var cursor = head.range.last + 1
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> paren++
                ')' -> paren = (paren - 1).coerceAtLeast(0)
                '[' -> bracket++
                ']' -> bracket = (bracket - 1).coerceAtLeast(0)
                '<' -> angle++
                '>' -> angle = (angle - 1).coerceAtLeast(0)
                '{' -> if (paren == 0 && bracket == 0 && angle == 0) {
                    val close = matchingBrace(code, cursor) ?: return@mapNotNull null
                    return@mapNotNull (cursor + 1)..(close - 1).coerceAtLeast(cursor)
                }
                '\n' -> if (paren == 0 && bracket == 0 && angle == 0) return@mapNotNull null
            }
            cursor++
        }
        null
    }.toList()

    private fun matchingBrace(code: String, open: Int): Int? {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun variantOwners(symbols: List<SymbolInfo>): Map<String, Set<String>> =
        flatten(symbols).filter { it.kind in VARIANT_OWNERS }.associate { owner ->
            owner.name to owner.members.filter { it.kind == SymbolKind.VARIANT }.mapTo(linkedSetOf()) { it.name }
        }

    private companion object {
        private val MODIFIERS = "(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|threadlocal|react|async|bridge|lazy)"
        val IMPORT_LINE = Regex("""(?m)^\s*(?:exposed\s+)?(?:import|use)\s+([^\n/]+)""")
        val FUNCTION_DECL = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*func\s+([A-Za-z_$][\w$]*)""")
        val BRIDGE_HEAD = Regex("""(?m)^\s*bridge\s+\.[^{\n]+\{""")
        val ANNOT_DECL = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*annot\s+([A-Za-z_$][\w$]*)""")
        val MACRO_DECL = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*macro\b[^\n{=]*?@([A-Za-z_$][\w$]*)""")
        val MACRO_WITHOUT_AT = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*macro\s+([A-Za-z_][\w$]*)""")
        val DECORATOR_USE = Regex("""@(?:[A-Za-z_$][\w$]*::)*([A-Z][\w$]*)""")
        val IDENTIFIER = Regex("""\b[A-Za-z_$][\w$]*\b""")
        val LOCAL_BINDING = Regex("""\b(var|val|fin|let)\s+([A-Za-z_$][\w$]*)""")
        val CALLABLE_HEAD = Regex(
            """(?m)^\s*(?:$MODIFIERS\s+)*(?:func\s+[A-Za-z_$][\w$]*|ctor\b|dtor\b|oper[^\s\[(]*)""",
        )

        private val TYPE_NAME = "[A-Za-z_$][\\w$]*(?:(?:::|\\.)[A-Za-z_$][\\w$]*)*(?:<[^>\\n]+>)?[!?&]?"
        val EXPLICIT_CONSTRUCTOR = Regex(
            """:\s*($TYPE_NAME)\s*=\s*(($TYPE_NAME)\s*\(([^()\n]*)\))""",
        )
        val EXPLICIT_ENUM_MEMBER = Regex(
            """:\s*($TYPE_NAME)\s*=\s*($TYPE_NAME)\.([A-Z][\w$]*)\b""",
        )
        val INFERRED_ENUM_BINDING = Regex(
            """\b(var|val|fin|let)\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Z][\w$]*)\.([A-Z][\w$]*)\b""",
        )
        private val TYPED_VALUE_PREFIX = "(?:\\b(?:var|val|fin|let)\\s+[A-Za-z_$][\\w$]*|\\b[A-Za-z_$][\\w$]*)"
        val TYPED_INTEGER = Regex(
            """$TYPED_VALUE_PREFIX\s*:\s*($TYPE_NAME)\s*=\s*([-+]?\d[\d_]*)\b(?![.A-Za-z_])""",
        )
        val TYPED_REAL_TO_INTEGER = Regex(
            """$TYPED_VALUE_PREFIX\s*:\s*($TYPE_NAME)\s*=\s*([-+]?\d[\d_]*\.\d[\d_]*(?:[eE][-+]?\d+)?)\b(?![fFD])""",
        )
        val INTEGER_TYPES = setOf("Byte", "UByte", "Short", "UShort", "Int", "UInt", "Long", "ULong", "Cent", "UCent", "ISize", "USize")
        val VARIANT_OWNERS = setOf(SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT)

        fun flatten(symbols: List<SymbolInfo>): Sequence<SymbolInfo> = sequence {
            for (symbol in symbols) {
                yield(symbol)
                yieldAll(flatten(symbol.members))
            }
        }

        /** How far a name may be from a known one and still be called a typo. */
        const val MAX_SUGGESTION_DISTANCE = 3
        const val MAX_LOCAL_DIAGNOSTICS = 500
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
    val knownAnnotations: Set<String> = emptySet(),
    val knownVariants: Map<String, Set<String>> = emptyMap(),
)

/** Output of the annotation pass. */
data class AzoraAnnotationResult(val diagnostics: List<AzoraDiagnostic>, val filePath: String?)

/** A fix offered alongside a diagnostic. */
data class AzoraFix(
    val title: String,
    /** The text to substitute for the highlighted range, or `null` to append a closer. */
    val replacement: String?,
    /** Optional edit range when the fix must replace more than the underlined text. */
    val range: TextRange? = null,
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

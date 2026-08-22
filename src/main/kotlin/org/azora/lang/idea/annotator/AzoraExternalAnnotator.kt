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
import org.azora.lang.idea.highlighting.AzoraSemanticModel
import org.azora.lang.idea.highlighting.AzoraToken
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.symbol.AzoraAutoImport
import org.azora.lang.idea.symbol.AzoraImports
import org.azora.lang.idea.symbol.AzoraProjectWords
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
        return AzoraAnnotationInfo(
            source, file.name, file.virtualFile?.path, modules, annotations, variants,
            namedElsewhere(file, source),
            unimported(project, path, source),
            unknown(project, path, source),
        )
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
            diagnostics += checkUnusedDeclarations(info)
            diagnostics += checkExpectedTypeShorthand(info, code)
            diagnostics += checkPurgeRuns(code)
            diagnostics += checkEveryTargetClauses(code)
            diagnostics += checkUnimportedNames(info, code)
            diagnostics += checkUnknownNames(info, code)
            diagnostics += checkRedundantConstructors(info.source, code)
            val owners = implOwners(code)
            diagnostics += checkPropertyShorthand(info.source, code, owners)
            diagnostics += checkPropReceivers(code)
            diagnostics += checkWidthSuffixes(info.source, code)
            diagnostics += checkOwnCaseQualifiers(code, owners)
            diagnostics += checkOneLineBlocks(info.source, code)
            diagnostics += checkOneLineMembers(info.source, code)
            diagnostics += checkReceivers(info, code, owners)
            diagnostics += checkWrittenSelfTypes(code)
            diagnostics += checkStepByOne(code)
            diagnostics += checkRepeatedGroupParts(info.source, code)
            diagnostics += checkContracts(info, code, owners)
            diagnostics += checkSelfAssignment(info.source, code)
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

        fun check(
            match: MatchResult,
            group: Int,
            role: String,
            upper: Boolean,
            sigil: String = "",
            allowPrivate: Boolean = role.equals("function", ignoreCase = true),
            note: String = "",
        ) {
            val target = match.groups[group] ?: return
            val original = target.value
            val replacement = camelName(original, upper, allowPrivate)
            if (original == replacement) return
            val expectation = if (upper) "UpperCamelCase" else "lowerCamelCase"
            diagnostics += AzoraDiagnostic(
                TextRange(target.range.first, target.range.last + 1),
                "$role name '$sigil$original' must use $expectation without internal underscores$note",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Rename to '$sigil$replacement'", replacement)),
            )
            handled += target.range
        }

        // A `func` or `prop` whose return type is `Type` computes a type, so it is
        // named like the type it produces rather than like a function: `Nullable`,
        // not `nullable`. One leading `_` still marks it private.
        val typeComputations = TYPE_RETURNING_DECL.findAll(code).toList()
        val typeComputationNames = typeComputations.mapNotNull { it.groups[2]?.range }.toSet()
        typeComputations.forEach {
            check(
                it, 2, "Type computation", upper = true, allowPrivate = true,
                note = ": a 'func' or 'prop' returning 'Type' is named like the type it produces",
            )
        }

        // A bridge declaration names an external ABI symbol, whose spelling is
        // owned by that ABI (`CFRelease`, `vkCreateDevice`, ...), not by Azora's
        // source naming convention. Ordinary/private Azora functions are
        // checked, with one leading `_` retained as the privacy marker.
        FUNCTION_DECL.findAll(code)
            .filterNot { declaration ->
                Regex("""\bbridge\b""").containsMatchIn(declaration.value) ||
                    bridgeBodies.any { declaration.range.first in it } ||
                    declaration.groups[1]?.range in typeComputationNames
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
            // `__int`, `__uint` and `__float` are the compiler's own
            // primitives, written where a pack cannot describe one. They are
            // spelled with the `__` that reserves them, and reading them as a
            // symbol somebody was not allowed to write has it backwards.
            if (name in AzoraLanguageFacts.primitiveWords) continue
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

        // `int`, `_int` and their kind sit one character from the compiler's
        // own, and a reader should not have to work out which is which.
        for (match in IDENTIFIER.findAll(code)) {
            val name = match.value
            if (name in AzoraLanguageFacts.primitiveWords) continue
            if (name.trimStart('_') !in AzoraLanguageFacts.primitiveWordStems) continue
            diagnostics += AzoraDiagnostic(
                TextRange(match.range.first, match.range.last + 1),
                "'$name' is too close to the compiler's own '__${name.trimStart('_')}'",
                HighlightSeverity.ERROR,
            )
        }

        return diagnostics
    }

    /** The names this file declares that some *other* project file also writes. */
    /**
     * The names this file writes without importing them, and where each lives.
     *
     * Only names the file does not bind itself are asked about, and only ones
     * some module really declares are reported: a typo resolves to nothing
     * anywhere, and "did you mean to import a module that does not exist" is
     * not a thing worth saying.
     */
    private fun unimported(project: com.intellij.openapi.project.Project, path: String, source: String): Map<String, String> {
        val tokens = lex(source)
        if (tokens.isEmpty()) return emptyMap()
        val bound = runCatching { AzoraSemanticModel.boundNames(tokens) }.getOrDefault(emptySet())
        val written = tokens.asSequence()
            .filter { it.type == AzoraTokenTypes.IDENTIFIER && it.text !in bound }
            .mapTo(linkedSetOf()) { it.text }
        return runCatching {
            AzoraSymbolService.getInstance(project).unimportedNames(project, path, source, written)
        }.getOrDefault(emptyMap())
    }

    /**
     * The names this file writes that nothing anywhere declares.
     *
     * Every name, whatever it starts with: a misspelled local is as broken as a
     * misspelled type, and the compiler only says so once something calls the
     * function it sits in - which for a library is often never. What keeps this
     * quiet is [AzoraSemanticModel.boundNames] knowing every form that binds,
     * so a loop's row and a pattern's capture are answers rather than errors.
     *
     * Left out: a member (its receiver's type decides it), a macro or decorator
     * behind its `@`, a segment of a module path, and a contextual keyword.
     */
    private fun unknown(project: com.intellij.openapi.project.Project, path: String, source: String): Set<String> {
        val tokens = lex(source)
        if (tokens.isEmpty()) return emptySet()
        val bound = runCatching { AzoraSemanticModel.boundNames(tokens) }.getOrDefault(emptySet())
        val builtin = AzoraLanguageFacts.builtinTypes +
            AzoraLanguageFacts.builtinAnnotations.map { it.name } +
            AzoraLanguageFacts.implicitParameters + SELF_TYPE
        val candidates = linkedSetOf<String>()
        for ((index, token) in tokens.withIndex()) {
            if (token.type != AzoraTokenTypes.IDENTIFIER) continue
            val name = token.text
            if (name.length < 2 || name in bound || name in builtin || '$' in name) continue
            // A member is reached through its receiver, whose type decides it.
            val previous = tokens.take(index).lastOrNull { it.type != AzoraTokenTypes.WHITE_SPACE }
            if (previous?.type == AzoraTokenTypes.DOT ||
                (previous?.type == AzoraTokenTypes.OPERATOR && previous.text == "::")
            ) continue
            if (AzoraImports.isInsideClause(source, token.start)) continue
            // `@arr[…]`, `@Since(…)` - the sigil says this is a macro or a
            // decorator, and each has its own check.
            if (previous?.type == AzoraTokenTypes.DECORATOR ||
                (previous?.type == AzoraTokenTypes.OPERATOR && previous.text == "@")
            ) continue
            // `std.io::println` - a path segment names a module, not a value.
            val following = tokens.drop(index + 1)
                .firstOrNull { it.type != AzoraTokenTypes.WHITE_SPACE }
            if (following?.type == AzoraTokenTypes.DOT ||
                (following?.type == AzoraTokenTypes.OPERATOR && following.text == "::")
            ) continue
            if (name in AzoraLanguageFacts.softKeywords) continue
            candidates.add(name)
        }
        return runCatching {
            AzoraSymbolService.getInstance(project).unknownNames(project, path, source, candidates)
        }.getOrDefault(emptySet())
    }

    /**
     * Reports each name that resolves to nothing anywhere.
     *
     * No fix: what to write instead is the author's to decide, and the nearest
     * spelling is offered by the naming check when there is one.
     */
    private fun checkUnknownNames(info: AzoraAnnotationInfo, code: String): List<AzoraDiagnostic> {
        if (info.unknown.isEmpty()) return emptyList()
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in IDENTIFIER.findAll(code)) {
            if (match.value !in info.unknown) continue
            val before = code.lastIndexOf('.', match.range.first - 1)
            if (before >= 0 && code.substring(before + 1, match.range.first).isBlank()) continue
            diagnostics += AzoraDiagnostic(
                TextRange(match.range.first, match.range.last + 1),
                "'${match.value}' is not declared in this project, the SDK, or any dependency",
                HighlightSeverity.ERROR,
            )
            if (diagnostics.size >= MAX_LOCAL_DIAGNOSTICS) break
        }
        return diagnostics
    }

    /**
     * Reports each name that is written here but reachable only after an
     * import, and offers the import as the fix.
     *
     * The name is not underlined on the strength of a spelling: the module that
     * declares it is known, and the one line that would make it resolve is
     * written by the fix.
     */
    private fun checkUnimportedNames(info: AzoraAnnotationInfo, code: String): List<AzoraDiagnostic> {
        if (info.unimported.isEmpty()) return emptyList()
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in IDENTIFIER.findAll(code)) {
            val module = info.unimported[match.value] ?: continue
            // `x.IndexError` reaches through a receiver; the import would not
            // be what made that resolve.
            val before = code.lastIndexOf('.', match.range.first - 1)
            if (before >= 0 && code.substring(before + 1, match.range.first).isBlank()) continue
            val edit = AzoraAutoImport.importEdit(info.source, module) ?: continue
            diagnostics += AzoraDiagnostic(
                TextRange(match.range.first, match.range.last + 1),
                "'${match.value}' is declared in '$module' and this file does not import it",
                HighlightSeverity.ERROR,
                listOf(
                    AzoraFix(
                        "Import '${match.value}' from '$module'",
                        edit.text,
                        TextRange(edit.offset, edit.offset),
                    )
                ),
            )
            if (diagnostics.size >= MAX_LOCAL_DIAGNOSTICS) break
        }
        return diagnostics
    }

    private fun namedElsewhere(file: PsiFile, source: String): Set<String> {
        val candidates = AzoraSemanticModel.unusedDeclarations(lex(source))
            .mapTo(linkedSetOf()) { it.name }
        return AzoraProjectWords.writtenOutside(file, candidates)
    }

    /**
     * Warns on every declaration nothing names, and offers the fix that suits it.
     *
     * The file decides on its own locals; anything a caller elsewhere could
     * reach is checked against the project's word index first, so a `func` whose
     * only caller is a test is used, not dead.
     */
    private fun checkUnusedDeclarations(info: AzoraAnnotationInfo): List<AzoraDiagnostic> {
        val tokens = lex(info.source)
        if (tokens.isEmpty()) return emptyList()
        return AzoraSemanticModel.unusedDeclarations(tokens)
            .asSequence()
            .filterNot { it.name in info.namedElsewhere }
            .take(MAX_LOCAL_DIAGNOSTICS)
            .map { unused ->
                val range = TextRange(unused.start, unused.end)
                val fix = when (unused.remedy) {
                    AzoraSemanticModel.UnusedRemedy.REMOVE -> AzoraFix(
                        "Remove unused ${unused.kind.lowercase()} '${unused.name}'",
                        "",
                        TextRange(unused.removalStart, unused.removalEnd),
                    )
                    AzoraSemanticModel.UnusedRemedy.RENAME_TO_HOLE -> AzoraFix(
                        "Rename to '${AzoraSemanticModel.HOLE_NAME}'",
                        AzoraSemanticModel.HOLE_NAME,
                        range,
                    )
                }
                AzoraDiagnostic(
                    range,
                    "${unused.kind} '${unused.name}' is never used",
                    HighlightSeverity.WARNING,
                    listOf(fix),
                )
            }
            .toList()
    }

    /** Lexes [source] into the flat token list the semantic model works on. */
    private fun lex(source: String): List<AzoraToken> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<AzoraToken>()
        while (lexer.tokenType != null) {
            tokens.add(
                AzoraToken(
                    lexer.tokenType!!,
                    lexer.tokenStart,
                    lexer.tokenEnd,
                    source.substring(lexer.tokenStart, lexer.tokenEnd),
                )
            )
            lexer.advance()
        }
        return tokens
    }

    /** Expected-type constructor and enum-member spellings that can be shortened safely. */
    private fun checkExpectedTypeShorthand(info: AzoraAnnotationInfo, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in EXPLICIT_CONSTRUCTOR.findAll(code)) {
            val expected = match.groupValues[1]
            val constructor = match.groupValues[3]
            if (simpleType(expected) != simpleType(constructor)) continue
            val call = match.groups[2] ?: continue
            val args = match.groups[4].textIn(info.source)
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

        // The same move for a constructed value: `var v = Queue<Int>()` names
        // its type on the right, `var v: Queue<Int> = .()` names it on the left.
        for (match in INFERRED_CONSTRUCTOR_BINDING.findAll(code)) {
            val type = match.groupValues[3]
            // An enum member spelled `Owner.Case(…)` is the other rule's to fix.
            if (type.contains('.') || type.contains("::")) continue
            val declaration = match.groups[0] ?: continue
            val name = match.groupValues[2]
            val args = match.groupValues[4]
            val replacement = "${match.groupValues[1]} $name: $type = .($args)"
            diagnostics += AzoraDiagnostic(
                TextRange(declaration.range.first, declaration.range.last + 1),
                "Prefer an explicit type with an inferred constructor: '$name: $type = .($args)'",
                HighlightSeverity.WEAK_WARNING,
                listOf(
                    AzoraFix(
                        "Use '$name: $type = .($args)'",
                        replacement,
                        TextRange(declaration.range.first, declaration.range.last + 1),
                    ),
                ),
            )
        }
        return diagnostics
    }

    /**
     * A run of `purge x.a` lines that release the buffers of one value.
     *
     * Releasing what a type owns is one act, and the language has one statement
     * for it: `with x purge [a, b, c]` names the receiver once and the fields in
     * the order they go. Two lines are already a run - the repetition is the
     * point, not the length.
     */
    /**
     * `annot @Name for .*` - every target, which is already the default.
     *
     * The spelling is legal and means exactly what leaving the clause off
     * means. Two ways to write one thing is one too many, and the shorter is
     * the one the standard library uses.
     */
    private fun checkEveryTargetClauses(code: String): List<AzoraDiagnostic> =
        ANNOT_ALL_TARGETS.findAll(code).mapNotNull { match ->
            val clause = match.groups[1] ?: return@mapNotNull null
            val range = TextRange(clause.range.first, clause.range.last + 1)
            AzoraDiagnostic(
                range,
                "'for .*' is every target, which a decorator without a 'for' clause already means",
                HighlightSeverity.WARNING,
                listOf(AzoraFix("Remove 'for .*'", "", range)),
            )
        }.toList()

    private fun checkPurgeRuns(code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (run in runsOf(PURGE_MEMBER.findAll(code).toList(), code) { it.groupValues[2] }) {
            val receiver = run.first().groupValues[2]
            val fields = run.joinToString(", ") { it.groupValues[3] }
            diagnostics += purgeRun(run, "with $receiver purge [$fields]")
        }
        // The same run, of names the body owns itself. One `purge` releases as
        // many as are named, and four lines that each release one say four
        // times what the group says once.
        for (run in runsOf(PURGE_LOCAL.findAll(code).toList(), code) { "" }) {
            val names = run.joinToString(", ") { it.groupValues[2] }
            diagnostics += purgeRun(run, "purge [$names]")
        }
        return diagnostics
    }

    /** The one diagnostic a run of `purge` lines turns into. */
    private fun purgeRun(run: List<MatchResult>, statement: String): AzoraDiagnostic {
        // The match starts at the line, indent and all; the warning belongs on
        // the code, and the rewrite replaces the lines whole.
        val start = run.first().range.first
        val stop = run.last().range.last + 1
        val indent = run.first().groupValues[1]
        return AzoraDiagnostic(
            TextRange(start + indent.length, stop),
            "Releasing what one value owns is one statement: '$statement'",
            HighlightSeverity.WEAK_WARNING,
            listOf(AzoraFix("Use '$statement'", indent + statement, TextRange(start, stop))),
        )
    }

    /**
     * Runs of two or more consecutive [matches] that agree on [key].
     *
     * Consecutive means nothing but blank lines between them: a run broken by
     * other code is two runs, because whatever sits in the middle may be the
     * reason the lines are apart.
     */
    private fun runsOf(
        matches: List<MatchResult>,
        code: String,
        key: (MatchResult) -> String,
    ): List<List<MatchResult>> {
        val runs = mutableListOf<List<MatchResult>>()
        var index = 0
        while (index < matches.size) {
            var end = index + 1
            while (end < matches.size &&
                key(matches[end]) == key(matches[index]) &&
                onlyBlanksBetween(code, matches[end - 1].range.last + 1, matches[end].range.first)
            ) {
                end++
            }
            if (end - index >= 2) runs.add(matches.subList(index, end))
            index = end
        }
        return runs
    }

    /**
     * A one-line `prop` whose braces hold nothing but a `return`.
     *
     * `prop isLess[self: Self&]: Bool { return self == .Less }` and
     * `prop isLess[self: Self&]: Bool = self == .Less` are the same property;
     * the second says so with less punctuation. The written type stays either
     * way - a property declares what it is, and the `=` form is about the body,
     * not the signature.
     *
     * Only one-liners. A `prop` whose body spans lines is doing enough that the
     * rewrite would have to guess where to break the expression.
     */
    private fun checkPropertyShorthand(source: String, code: String, owner: (Int) -> String?): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in ONE_LINE_PROPERTY_RETURN.findAll(code)) {
            val header = match.groups[1].textIn(source).trimEnd()
            val type = match.groups[2].textIn(source).trim()
            val expression = shortenOwnCases(match.groups[3].textIn(source).trim(), owner(match.range.first))
            if (expression.isEmpty()) continue
            val replacement = "$header$type = $expression"
            val range = TextRange(match.range.first, match.range.last + 1)
            diagnostics += AzoraDiagnostic(
                range,
                "This property is one expression; write '${replacement.trim()}'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use '${replacement.trim()}'", replacement, range)),
            )
        }
        return diagnostics
    }

    /**
     * `Compare.Less` written inside `impl Compare` is `.Less`.
     *
     * The enclosing `impl` already says which type, so naming it again is the
     * qualifier a reader has to check rather than read. Applied both on its own
     * and inside any other rewrite, so a fix never hands back a line that would
     * immediately be flagged again.
     */
    private fun checkOwnCaseQualifiers(code: String, owner: (Int) -> String?): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in QUALIFIED_CASE.findAll(code)) {
            if (match.groupValues[1] != owner(match.range.first)) continue
            val case = match.groupValues[2]
            val range = TextRange(match.range.first, match.range.last + 1)
            diagnostics += AzoraDiagnostic(
                range,
                "'${match.value}' is inside 'impl ${match.groupValues[1]}'; write '.$case'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use '.$case'", ".$case", range)),
            )
        }
        return diagnostics
    }

    /** Drops the `Owner.` from every case of [owner] named in [expression]. */
    private fun shortenOwnCases(expression: String, owner: String?): String {
        if (owner.isNullOrEmpty()) return expression
        return QUALIFIED_CASE.replace(expression) { match ->
            if (match.groupValues[1] == owner) ".${match.groupValues[2]}" else match.value
        }
    }

    /**
     * The type whose `impl` block encloses a given offset, as a lookup.
     *
     * Built once per file by walking the `impl` heads and their brace depths, so
     * asking about an offset is a scan of a short list rather than a re-parse.
     */
    private fun implOwners(code: String): (Int) -> String? {
        val blocks = IMPL_HEAD.findAll(code).mapNotNull { match ->
            val open = code.indexOf('{', match.range.last)
            if (open < 0) return@mapNotNull null
            val close = matchingBrace(code, open) ?: return@mapNotNull null
            // `impl Spec for Type` implements a spec *on* a type; either way the
            // last name in the head is the type the body belongs to.
            (open..close) to match.groupValues.drop(1).last { it.isNotEmpty() }
        }.toList()
        return { offset -> blocks.lastOrNull { offset in it.first }?.second }
    }

    /**
     * A `ctor` that only writes each field the value its pack already declares.
     *
     * `pack Queue<T> { var size: Int = 0 }` already builds a `Queue` with
     * `size` at `0`; a `ctor` whose whole body is `self.size = 0` restates the
     * declaration and then has to be kept in step with it. It is offered for
     * removal only when *every* statement is such a restatement, so a ctor that
     * does one extra thing is left alone.
     */
    private fun checkRedundantConstructors(source: String, code: String): List<AzoraDiagnostic> {
        val defaults = FIELD_DEFAULT.findAll(code)
            .associate { it.groupValues[2] to it.groupValues[3].trim() }
        if (defaults.isEmpty()) return emptyList()

        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in CTOR_HEAD.findAll(code)) {
            // A ctor that takes arguments is doing something with them.
            if (match.groupValues[2].isNotBlank()) continue
            val open = code.indexOf('{', match.range.last)
            if (open < 0) continue
            val close = matchingBrace(code, open) ?: continue
            val body = code.substring(open + 1, close)
            val assignments = SELF_FIELD_ASSIGNMENT.findAll(body).toList()
            if (assignments.isEmpty()) continue
            // Nothing may be left once the assignments are taken out, or the
            // ctor does more than restate the pack.
            val remainder = SELF_FIELD_ASSIGNMENT.replace(body, "")
            if (remainder.any { !it.isWhitespace() }) continue
            if (assignments.any { defaults[it.groupValues[1]] != it.groupValues[2].trim() }) continue

            val removal = TextRange(declarationStart(source, match.range.first), lineEnd(source, close))
            diagnostics += AzoraDiagnostic(
                TextRange(match.range.first + match.groupValues[1].length, open + 1),
                "This constructor only restates the defaults '${assignments.joinToString(", ") { it.groupValues[1] }}' " +
                    "already declared on the pack; building the value does that",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Remove the redundant constructor", "", removal)),
            )
        }
        return diagnostics
    }

    /**
     * The offset a declaration really starts at: past the indent of its own
     * line, but before the decorators and doc comment written above it, which
     * belong to it and would be left dangling by a removal that ignored them.
     */
    private fun declarationStart(source: String, head: Int): Int {
        var start = source.lastIndexOf('\n', head - 1) + 1
        while (start > 0) {
            val previousEnd = start - 1
            val previousStart = source.lastIndexOf('\n', previousEnd - 1) + 1
            val line = source.substring(previousStart, previousEnd).trim()
            val attached = line.startsWith("@") || line.startsWith("/**") ||
                line.startsWith("*") || line.startsWith("//")
            if (!attached) break
            start = previousStart
        }
        return start
    }

    /** The offset just past the line break that ends the line containing [offset]. */
    private fun lineEnd(source: String, offset: Int): Int {
        val newline = source.indexOf('\n', offset)
        return if (newline < 0) source.length else newline + 1
    }

    /**
     * Asserts that are really a contract, and the clause each belongs in.
     *
     * A contract says what a callable requires and what it promises, where a
     * caller can read it: in the signature. An `assert` buried at the top of a
     * body says the same thing where only the implementation can see it.
     *
     *  * `in { … }` holds the **preconditions**: leading asserts whose condition
     *    names nothing but the parameters and the receiver - the inputs, which
     *    is exactly what a caller is responsible for.
     *  * `out { … }` holds the **postcondition**: the assert standing between
     *    the result and the `return` that hands it back. The clause binds the
     *    result as `it`, so the check is rewritten to name it that way.
     *
     * Only what is unambiguous moves. An assert in the middle of a body is a
     * check on the work rather than on the contract, and stays where it is.
     */
    private fun checkContracts(
        info: AzoraAnnotationInfo,
        code: String,
        owner: (Int) -> String?,
    ): List<AzoraDiagnostic> {
        val fieldsByType = fieldNames(info)
        // What this file's members ask of their receiver: calling one that
        // mutates is mutating, whatever the calling body writes itself.
        val mutating = MUTATING_MEMBER.findAll(code).mapTo(linkedSetOf()) { it.groupValues[1] }
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in CONTRACTABLE_MEMBER.findAll(code)) {
            val open = match.range.last
            val close = matchingBrace(code, open) ?: continue
            val indent = match.groupValues[1]
            val signature = match.groupValues[2].trimEnd()
            // A member that already states a contract is saying it in the right
            // place; there is nothing to move.
            if (CONTRACT_CLAUSE.containsMatchIn(signature)) continue

            // Boundaries come from the blanked copy, so a brace inside a string
            // cannot close a body; the text comes from the source, or the
            // rewrite would hand back an assert whose message had been blanked.
            val lines = info.source.substring(open + 1, close).trim('\n').lines()
            if (lines.isEmpty()) continue
            val known = inputNames(signature, fieldsByType[owner(match.range.first)].orEmpty())

            val preconditions = lines.takeWhile { isAssertOver(it, known) }
            val postcondition = trailingResultAssert(lines.drop(preconditions.size))
            if (preconditions.isEmpty() && postcondition == null) continue

            val remaining = lines.drop(preconditions.size).toMutableList()
            postcondition?.let { remaining.removeAt(it.first) }
            while (remaining.isNotEmpty() && remaining.last().isBlank()) remaining.removeAt(remaining.lastIndex)
            while (remaining.isNotEmpty() && remaining.first().isBlank()) remaining.removeAt(0)
            if (remaining.isEmpty()) continue

            // The first clause opens its own line, under the signature it
            // qualifies: a contract is read before the body, not squeezed onto
            // the end of the line that declares the member. The clauses after it
            // chain off the closing brace, so the three read as one block.
            val rebuilt = StringBuilder().append(indent).append(signature)
            var opened = false
            fun clause(keyword: String, lines: List<String>) {
                rebuilt.append(if (opened) " $keyword {\n" else "\n$indent$keyword {\n")
                lines.forEach { rebuilt.append(reindent(it, indent)).append('\n') }
                rebuilt.append(indent).append('}')
                opened = true
            }
            if (preconditions.isNotEmpty()) clause("in", preconditions)
            postcondition?.let { (_, text) -> clause("out", listOf(text)) }
            rebuilt.append(" scope {\n")
            remaining.forEach { rebuilt.append(it).append('\n') }
            rebuilt.append(indent).append('}')

            val clauses = listOfNotNull(
                "in".takeIf { preconditions.isNotEmpty() },
                "out".takeIf { postcondition != null },
            ).joinToString(" and ") { "'$it'" }
            val range = TextRange(match.range.first + indent.length, close + 1)
            diagnostics += AzoraDiagnostic(
                range,
                "These checks are this member's contract; state them in $clauses so a caller can read them",
                HighlightSeverity.WEAK_WARNING,
                listOf(
                    AzoraFix(
                        "Move them into $clauses",
                        rebuilt.toString(),
                        TextRange(match.range.first, close + 1),
                    ),
                ),
            )
        }
        return diagnostics
    }

    /** The names a caller supplies: the parameters, the receiver, and its fields. */
    private fun inputNames(signature: String, fields: Set<String>): Set<String> = buildSet {
        addAll(fields)
        for (match in DECLARED_NAME.findAll(signature.substringAfter('[', signature))) {
            add(match.groupValues[1])
        }
    }

    /** Whether [line] is an `assert` whose condition names nothing but [known]. */
    private fun isAssertOver(line: String, known: Set<String>): Boolean {
        val condition = ASSERT_LINE.matchEntire(line.trim())?.groupValues?.get(1) ?: return false
        val named = IDENTIFIER.findAll(condition).map { it.value }.filterNot { it in ASSERT_LITERALS }
        return named.any() && named.all { it in known }
    }

    /**
     * The assert that checks the result, as its index among [lines] and its text.
     *
     * It is the one immediately before a `return <name>` whose condition names
     * that same `<name>`, rewritten to call it `it` - which is what the `out`
     * clause binds the result to.
     */
    private fun trailingResultAssert(lines: List<String>): Pair<Int, String>? {
        val returnAt = lines.indexOfLast { RETURN_NAME.matchEntire(it.trim()) != null }
        if (returnAt < 1) return null
        val returned = RETURN_NAME.matchEntire(lines[returnAt].trim())!!.groupValues[1]
        val assertAt = returnAt - 1
        val condition = ASSERT_LINE.matchEntire(lines[assertAt].trim())?.groupValues?.get(1) ?: return null
        if (!Regex("""\b${Regex.escape(returned)}\b""").containsMatchIn(condition)) return null
        val renamed = lines[assertAt].replace(Regex("""\b${Regex.escape(returned)}\b"""), "it")
        return assertAt to renamed
    }

    /** Re-indents a body line one step inside [indent]. */
    private fun reindent(line: String, indent: String): String = "$indent    ${line.trim()}"

    /**
     * What a member's receiver claims, against what its body actually does.
     *
     * A receiver says three things at once: which value the call is on, whether
     * the member may write through it, and - inside an `impl` - a type that is
     * never in question. Each has its own answer here:
     *
     *  * `[self: Self&]` repeats the type being implemented, so it shortens to
     *    `[self&]`.
     *  * `[self!]` on a body that writes nothing asks callers for an exclusive
     *    borrow it does not need, which is a promise they may be unable to keep.
     *  * `[self&]` on a body that *does* write is not a preference but an error,
     *    reported where the write is, since that is the line to look at.
     *  * a receiver the body never reaches for needs no name: `[&]`.
     */
    /**
     * A group that says one thing several times.
     *
     * `fin [a: Int, b: Int, c: Int] = …` states one type three times, and
     * `= [0, 0, 0]` writes one value three times. The group already stands for
     * the lines it would have been; saying the shared part once is what it is
     * for.
     */
    private fun checkRepeatedGroupParts(source: String, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in GROUP_STATEMENT.findAll(code)) {
            val entries = groupEntries(match.groups[3].textIn(source))
            val stated = entries.map { it.substringAfter(':', "").trim() }
            val names = entries.map { it.substringBefore(':').trim() }
            val typeClause = match.groups[4].textIn(source).trim()

            // One type, stated once.
            if (entries.size >= 2 && typeClause.isEmpty() && stated.all { it.isNotEmpty() } &&
                stated.distinct().size == 1
            ) {
                val head = match.groups[2].textIn(source)
                val entryGroup = match.groups[3]!!
                val range = TextRange(entryGroup.range.first - 1, entryGroup.range.last + 2)
                val rewritten = "[${names.joinToString(", ")}]: ${stated.first()}"
                diagnostics += AzoraDiagnostic(
                    range,
                    "Every name in the group is a '${stated.first()}'; a group states that once: '$head$rewritten'",
                    HighlightSeverity.WEAK_WARNING,
                    listOf(AzoraFix("Use '$rewritten'", rewritten, range)),
                )
            }

            // One value, written once.
            val value = match.groups[5].textIn(source).trim()
            if (value.startsWith("[") && value.endsWith("]")) {
                val values = groupEntries(value.substring(1, value.length - 1))
                if (values.size >= 2 && values.distinct().size == 1) {
                    val valueGroup = match.groups[5]!!
                    val range = TextRange(valueGroup.range.first, valueGroup.range.last + 1)
                    diagnostics += AzoraDiagnostic(
                        range,
                        "Every name takes '${values.first()}', which one value already says",
                        HighlightSeverity.WEAK_WARNING,
                        listOf(AzoraFix("Use '${values.first()}'", values.first(), range)),
                    )
                }
            }
        }
        return diagnostics
    }

    /** The entries of a group, separated by commas or by the lines they sit on. */
    private fun groupEntries(text: String): List<String> =
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * `i += 1` - the language has a word for that, and it is `i++`.
     *
     * Only a literal `1` counts: `+= 1.0` and `+= 1L` are a different type's
     * step, and `+= n` is not a step at all. The target may be a name, a member
     * or an element, because all three step the same way.
     */
    private fun checkStepByOne(code: String): List<AzoraDiagnostic> =
        STEP_BY_ONE.findAll(code).mapNotNull { match ->
            val target = match.groupValues[1]
            val operator = if (match.groupValues[2] == "+") "++" else "--"
            // The match starts at the line, indent and all; the warning belongs
            // on the code, and so does the rewrite.
            val from = match.groups[1]?.range?.first ?: return@mapNotNull null
            val range = TextRange(from, match.range.last + 1)
            AzoraDiagnostic(
                range,
                "Stepping by one is '$target$operator'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use '$target$operator'", "$target$operator", range)),
            )
        }.toList()

    /**
     * `[self: Self&]` - the `Self` says nothing.
     *
     * A receiver may name a type when the declaration is an extension on one
     * (`func nearest[p: Point&](…)`), but `Self` is never that: it is the type
     * the declaration is already inside. The shorter spelling reads the same.
     *
     * This is separate from [checkReceivers] because it needs neither an owner
     * nor a body, and a `spec` member - which has no body at all - is exactly
     * where the long form gets written.
     */
    private fun checkWrittenSelfTypes(code: String): List<AzoraDiagnostic> =
        MEMBER_RECEIVER.findAll(code).mapNotNull { match ->
            if (match.groupValues[3].isEmpty()) return@mapNotNull null
            val name = match.groupValues[2]
            val borrow = match.groupValues[5]
            val bracket = TextRange(match.groups[1]!!.range.first, match.range.last + 1)
            AzoraDiagnostic(
                bracket,
                "The receiver's type here is always 'Self', so writing it adds nothing: '[$name$borrow]'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use '[$name$borrow]'", "[$name$borrow]", bracket)),
            )
        }.toList()

    private fun checkReceivers(
        info: AzoraAnnotationInfo,
        code: String,
        owner: (Int) -> String?,
    ): List<AzoraDiagnostic> {
        val fieldsByType = fieldNames(info)
        // What this file's members ask of their receiver: calling one that
        // mutates is mutating, whatever the calling body writes itself.
        val mutating = MUTATING_MEMBER.findAll(code).mapTo(linkedSetOf()) { it.groupValues[1] }
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        for (match in MEMBER_RECEIVER.findAll(code)) {
            val type = owner(match.range.first) ?: continue
            val name = match.groupValues[2]
            val written = match.groupValues[3].ifEmpty { match.groupValues[4] }
            val borrow = match.groupValues[5]
            val bracket = TextRange(match.groups[1]!!.range.first, match.range.last + 1)
            val fields = fieldsByType[type].orEmpty()
            val body = memberBody(code, match.range.last) ?: continue
            // A name the body binds for itself is not the receiver's state,
            // however much it looks like a field: `var i = 0; i = i + 1` writes
            // a local, and calling that a write through `self` would turn valid
            // code red.
            val reachable = fields - boundNames(signatureOf(code, match), body.second)
            val writes = writesThrough(body, name, reachable, mutating)

            when {
                // A write through a shared borrow is not a style question.
                borrow != EXCLUSIVE_BORROW && writes != null -> diagnostics += AzoraDiagnostic(
                    TextRange(body.first + writes.first, body.first + writes.last + 1),
                    "'$name' is borrowed for reading ('$borrow'), so this cannot write through it",
                    HighlightSeverity.ERROR,
                    listOf(
                        AzoraFix(
                            "Borrow '$name' exclusively: '[$name$EXCLUSIVE_BORROW]'",
                            "[$name$EXCLUSIVE_BORROW]",
                            bracket,
                        ),
                    ),
                )

                borrow == EXCLUSIVE_BORROW && writes == null -> diagnostics += AzoraDiagnostic(
                    bracket,
                    "'$name' is never written through, so a shared borrow is enough: '[$name$SHARED_BORROW]'",
                    HighlightSeverity.WEAK_WARNING,
                    listOf(AzoraFix("Use '[$name$SHARED_BORROW]'", "[$name$SHARED_BORROW]", bracket)),
                )

                // Nothing in the body reaches for the receiver at all.
                !reaches(body.second, name, fields) -> diagnostics += AzoraDiagnostic(
                    bracket,
                    "'$name' is never used, so it needs no name: '[$borrow]'",
                    HighlightSeverity.WEAK_WARNING,
                    listOf(AzoraFix("Use '[$borrow]'", "[$borrow]", bracket)),
                )

                // The type is the one being implemented; writing it adds
                // nothing. A written `Self` is [checkWrittenSelfTypes]', which
                // needs neither an owner nor a body to know it says nothing.
                match.groupValues[4].isNotEmpty() -> diagnostics += AzoraDiagnostic(
                    bracket,
                    "Inside 'impl $type' the receiver's type is '$written'; write '[$name$borrow]'",
                    HighlightSeverity.WEAK_WARNING,
                    listOf(AzoraFix("Use '[$name$borrow]'", "[$name$borrow]", bracket)),
                )
            }
        }
        return diagnostics
    }

    /** The member signature a receiver match sits in, up to the receiver bracket. */
    private fun signatureOf(code: String, match: MatchResult): String =
        code.substring(match.range.first, match.range.last + 1)

    /**
     * Every name a member binds for itself: its parameters and its locals.
     *
     * These shadow the type's fields for the length of the body, so a write to
     * one of them says nothing about the receiver.
     */
    private fun boundNames(signature: String, body: String): Set<String> = buildSet {
        LOCAL_BINDING.findAll(body).forEach { add(it.groupValues[2]) }
        DECLARED_NAME.findAll(signature).forEach { add(it.groupValues[1]) }
        // `for i in …` binds its variable for the loop.
        LOOP_BINDING.findAll(body).forEach { add(it.groupValues[1]) }
    }

    /**
     * What a member's body is, as an offset and the text there.
     *
     * A braced body is the usual case. An expression-bodied `prop` has none, and
     * its body is the rest of its own declaration - so the search for a `{` stops
     * at the next member's head, or the `{` of the member *after* this one would
     * be read as this one's, and its writes blamed on this one's receiver.
     */
    private fun memberBody(code: String, from: Int): Pair<Int, String>? {
        val limit = MEMBER_HEAD.find(code, from)?.range?.first ?: code.length
        val open = code.indexOf('{', from)
        if (open in 0 until limit) {
            val close = matchingBrace(code, open) ?: return null
            return (open + 1) to code.substring(open + 1, close)
        }
        if (limit <= from) return null
        return from to code.substring(from, limit)
    }

    /**
     * Where [body] writes through the receiver, or `null` when it never does.
     *
     * A write is an assignment, a compound assignment, or an increment - either
     * through the receiver's name or through a field of the type reached without
     * one, which is the same write spelled shorter.
     */
    private fun writesThrough(
        body: Pair<Int, String>,
        name: String,
        fields: Set<String>,
        mutating: Set<String> = emptySet(),
    ): IntRange? {
        val text = body.second
        for (match in RECEIVER_WRITE.findAll(text)) {
            val target = match.groupValues[1]
            val field = match.groupValues[2]
            if (target == name || (target.isEmpty() && field in fields)) return match.range
        }
        // Calling a member that takes an exclusive receiver mutates through
        // this one: `self.grow()` writes whatever `grow` writes, and a body
        // that does it needs the borrow that lets it.
        for (match in RECEIVER_CALL.findAll(text)) {
            if (match.groupValues[1] == name && match.groupValues[2] in mutating) return match.range
        }
        return null
    }

    /** Whether [body] names the receiver, or a field reached through it. */
    private fun reaches(body: String, name: String, fields: Set<String>): Boolean {
        if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(body)) return true
        return fields.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(body) }
    }

    /**
     * Each type declared in this file, mapped to the names of its fields.
     *
     * Keyed on the bare name: `pack Queue<T>` is `Queue`, which is what an
     * `impl Queue<T>` head resolves to.
     */
    private fun fieldNames(info: AzoraAnnotationInfo): Map<String, Set<String>> =
        flatten(AzoraSymbolService().getSymbolsForFile(info.filePath ?: info.fileName, info.source))
            .filter { symbol -> symbol.members.any { it.kind == SymbolKind.FIELD } }
            .associate { owner ->
                owner.name.substringBefore('<') to owner.members
                    .filter { it.kind == SymbolKind.FIELD }
                    .mapTo(linkedSetOf()) { it.name }
            }

    /**
     * A statement `if`, `for`, `while`, `loop` or `when` whose block is on one line.
     *
     * `if size >= capacity { self.growQueue() }` hides a statement inside a
     * line that reads as one: the braces say "here is a body", and a body is
     * something you scan down, not across. Broken open, the condition and what
     * it does are each on their own line.
     *
     * Only the statement forms. `return if c { a } else { b }` and
     * `fin x = when v { … }` are expressions - they produce a value where one
     * is expected, and a value belongs on the line that uses it. They are told
     * apart by position: an expression always has something before it on the
     * line, and a statement starts one.
     */
    /**
     * A `func`, `ctor`, `dtor` or `oper` whose whole body sits on its line.
     *
     * The same rule a one-line `if` answers to: the braces say "here is a
     * body", and a body is something you scan down, not across. A `prop` is
     * left to [checkPropertyShorthand], which has a better answer for it - the
     * `=` form - and two suggestions on one line is one too many.
     */
    private fun checkOneLineMembers(source: String, code: String): List<AzoraDiagnostic> =
        ONE_LINE_MEMBER.findAll(code).mapNotNull { match ->
            // Matched on the masked copy so a brace inside a literal cannot end
            // the body; spliced from the real source so the rewrite keeps it.
            val body = match.groups[3].textIn(source).trim()
            if (body.isEmpty()) return@mapNotNull null
            val indent = match.groupValues[1]
            val head = match.groups[2].textIn(source).trim()
            val keyword = head.split(Regex("\\s+")).first { it in MEMBER_HEADS }
            val whole = TextRange(match.range.first, match.range.last + 1)
            AzoraDiagnostic(
                TextRange(match.range.first + indent.length, match.range.last + 1),
                "A '$keyword' body belongs on its own line",
                HighlightSeverity.WEAK_WARNING,
                listOf(
                    AzoraFix(
                        "Put the body on its own line",
                        "$indent$head {\n$indent    $body\n$indent}",
                        whole,
                    ),
                ),
            )
        }.toList()

    /**
     * A number written with a width suffix.
     *
     * The language has none: `4` is an `__int` and is read at whatever width
     * the place it lands in states, so `4L` is `4` and the `Long` is said by
     * the binding it goes into. The compiler refuses it; this says so while it
     * is being typed, and offers the two ways to mean it.
     */
    private fun checkWidthSuffixes(source: String, code: String): List<AzoraDiagnostic> =
        WIDTH_SUFFIXED_LITERAL.findAll(code).mapNotNull { match ->
            val digits = match.groupValues[1]
            // `0xbeef` is one number: `b`, `c` and `f` are hex digits there.
            if (digits.length > 1 && digits[0] == '0' && digits[1].lowercaseChar() in "xbo") return@mapNotNull null
            val width = WIDTH_OF_SUFFIX[match.groupValues[2]] ?: return@mapNotNull null
            val whole = TextRange(match.range.first, match.range.last + 1)
            AzoraDiagnostic(
                whole,
                "A width suffix is not part of a literal: write '$digits', or name the width - '$width($digits)'",
                HighlightSeverity.ERROR,
                listOf(
                    AzoraFix("Drop the suffix: '$digits'", digits, whole),
                    AzoraFix("Name the width: '$width($digits)'", "$width($digits)", whole),
                ),
            )
        }.toList()

    /**
     * A `prop` observes, so `[self&]` is the only receiver it may take.
     *
     * `[self!]` says that reading the property writes through what it was read
     * from, and `[self]` says that reading it ends the value. A reader who
     * cannot tell which of the three a `prop` is has to check every one of
     * them, which is the whole cost the distinction saves - so the compiler
     * refuses both, and this says so before the compiler is asked.
     */
    private fun checkPropReceivers(code: String): List<AzoraDiagnostic> =
        PROP_RECEIVER.findAll(code).mapNotNull { match ->
            val borrow = match.groupValues[5]
            if (borrow == SHARED_BORROW) return@mapNotNull null
            val name = match.groupValues[1]
            val receiver = match.groupValues[3]
            val bracket = match.groups[2]!!
            val does = if (borrow == EXCLUSIVE_BORROW) "writes through" else "consumes"
            AzoraDiagnostic(
                TextRange(bracket.range.first, bracket.range.last + 1),
                "A 'prop' only observes, so '$name' takes '[$receiver$SHARED_BORROW]'; " +
                    "declare a 'func' for one that $does its receiver",
                HighlightSeverity.ERROR,
                listOf(
                    AzoraFix(
                        "Borrow '$receiver' for reading: '[$receiver$SHARED_BORROW]'",
                        "[$receiver$SHARED_BORROW]",
                        TextRange(bracket.range.first, bracket.range.last + 1),
                    ),
                ),
            )
        }.toList()

    private fun checkOneLineBlocks(source: String, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in ONE_LINE_BLOCK.findAll(code)) {
            val keyword = match.groupValues[2]
            if (keyword !in AzoraLanguageFacts.blockStatementHeads) continue
            // The match is found in the masked copy, where a literal is blanked
            // out so its contents cannot be mistaken for code. The *rewrite* has
            // to come from the real source, or the fix hands back a line with
            // the strings emptied out of it.
            val body = match.groups[4].textIn(source).trim()
            if (body.isEmpty()) continue

            val indent = match.groupValues[1]
            val head = "$keyword${match.groups[3].textIn(source)}".trimEnd()
            val whole = TextRange(match.range.first, match.range.last + 1)
            val range = TextRange(match.range.first + indent.length, match.range.last + 1)

            // `else -> { return .Equal }` is a `when` arm, not a statement body.
            // An arm answers with one thing; the braces around that one thing
            // are what should go, and opening them up would spread a table of
            // arms over three lines each.
            if (head.endsWith("->") && ';' !in body) {
                diagnostics += AzoraDiagnostic(
                    range,
                    "A 'when' arm holding one statement does not need braces",
                    HighlightSeverity.WEAK_WARNING,
                    listOf(AzoraFix("Remove the braces", "$indent$head $body", whole)),
                )
                continue
            }

            val inner = "$indent    "
            val rebuilt = StringBuilder()
                .append(indent).append(head).append(" {\n")
                .append(inner).append(body).append('\n')
                .append(indent).append('}')
            if (match.groupValues[5].isNotEmpty()) {
                val otherwise = match.groups[6].textIn(source).trim()
                rebuilt.append(" else {\n").append(inner).append(otherwise).append('\n').append(indent).append('}')
            }

            diagnostics += AzoraDiagnostic(
                range,
                "A '$keyword' statement's body belongs on its own line",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Put the body on its own line", rebuilt.toString(), whole)),
            )
        }
        return diagnostics
    }

    /**
     * The text a match group covers, read from the *unmasked* source.
     *
     * [codeOnly] blanks comments and literals in place, so every offset in the
     * masked copy is the same offset in the real one: a rule may match on the
     * masked text and still rebuild from what the file actually says.
     */
    private fun MatchGroup?.textIn(source: String): String =
        this?.let { source.substring(it.range.first, it.range.last + 1) }.orEmpty()

    /**
     * `x = x + 1` and its family, which the language spells more directly.
     *
     * `x = x + 1` becomes `x++`, `x = x - 1` becomes `x--`, and any other
     * `x = x <op> e` becomes `x <op>= e`. The rewrite is only offered when the
     * name on the left is repeated as the first operand on the right; `x = 1 + x`
     * is the same value but a different sentence, and `x = y + 1` is not this at
     * all.
     *
     * What is left over must also be a single term. `x = x * 10 + y` reads as
     * `(x * 10) + y`, while `x *= 10 + y` multiplies by `10 + y` - the shorthand
     * puts the whole remainder under one operator, so it is only the same
     * statement when the remainder is one thing to begin with.
     */
    private fun checkSelfAssignment(source: String, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in SELF_ASSIGNMENT.findAll(code)) {
            val indent = match.groupValues[1]
            val target = match.groupValues[2]
            if (match.groupValues[3] != target) continue
            val operator = match.groupValues[4]
            // Checked against the masked copy, so an operator inside a string
            // cannot veto the rewrite; spliced from the real one, so the fix
            // does not hand back a line with its literals emptied out.
            if (hasTopLevelOperator(match.groupValues[5].trim())) continue
            val operand = match.groups[5].textIn(source).trim()
            if (operand.isEmpty()) continue
            val shorter = when {
                operand == "1" && operator == "+" -> "$target++"
                operand == "1" && operator == "-" -> "$target--"
                else -> "$target $operator= $operand"
            }
            // The indent is matched so the statement is found at the start of a
            // line, not so it can be replaced: overwriting it left the rewritten
            // line hard against the margin.
            val range = TextRange(match.range.first + indent.length, match.range.last + 1)
            diagnostics += AzoraDiagnostic(
                range,
                "'$target' is assigned from itself; write '$shorter'",
                HighlightSeverity.WEAK_WARNING,
                listOf(AzoraFix("Use '$shorter'", shorter, range)),
            )
        }
        return diagnostics
    }

    /**
     * Whether [expression] joins two things with an operator of its own.
     *
     * Only what is not inside brackets counts: `f(a + b)` and `xs[i + 1]` are
     * each one term however much arithmetic they contain. A sign is not this -
     * in `-1` and `* -1` the `-` has nothing on its left to join.
     */
    private fun hasTopLevelOperator(expression: String): Boolean {
        var depth = 0
        var previous: Char? = null
        for (char in expression) {
            when {
                char == '(' || char == '[' || char == '{' -> depth++
                char == ')' || char == ']' || char == '}' -> depth--
                depth == 0 && char in "+-*/%" && previous != null && previous !in "+-*/%" -> return true
            }
            if (!char.isWhitespace()) previous = char
        }
        return false
    }

    /** Whether [from] until [to] is blank apart from line breaks. */
    private fun onlyBlanksBetween(code: String, from: Int, to: Int): Boolean =
        from <= to && code.substring(from, to).all { it.isWhitespace() }

    /** Numeric literals must agree with their explicit type without implicit widening/narrowing. */
    private fun checkNumericInitializers(source: String, code: String): List<AzoraDiagnostic> {
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        for (match in TYPED_INTEGER.findAll(code)) {
            val type = simpleType(match.groupValues[1])
            val literal = match.groups[2] ?: continue
            // `5` where a float is declared is written `5.`; no suffix says
            // which float, the declared type does.
            val suffix = if (type in FLOAT_TYPES) "." else continue
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
                "A float literal cannot initialize $type without an explicit cast",
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
        private const val MODIFIERS = "(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|threadlocal|react|async|bridge|lazy)"
        val IMPORT_LINE = Regex("""(?m)^\s*(?:exposed\s+)?(?:import|use)\s+([^\n/]+)""")
        val FUNCTION_DECL = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*func\s+([A-Za-z_$][\w$]*)""")

        /**
         * A `func` or `prop` declared to return `Type` - a computation over types
         * rather than over values. `Type` must be the whole return type, so an
         * ordinary `prop kind: TypeName` is not one of these.
         */
        val TYPE_RETURNING_DECL = Regex(
            """(?m)^\s*(?:$MODIFIERS\s+)*(func|prop)\s+([A-Za-z_$][\w$]*)""" +
                """(?:<[^>\n{]*>)?\s*(?:\[[^\]\n]*\])?\s*(?:\([^)\n]*\))?\s*:\s*Type\b"""
        )
        val BRIDGE_HEAD = Regex("""(?m)^\s*bridge\s+\.[^{\n]+\{""")
        // A decorator is declared as it is written, `annot @Name`.
        val ANNOT_DECL = Regex("""(?m)^\s*(?:$MODIFIERS\s+)*annot\s+@([A-Za-z_$][\w$]*)""")

        /** The same declaration, with the `for .*` that says nothing. */
        val ANNOT_ALL_TARGETS = Regex(
            """(?m)^\s*(?:$MODIFIERS\s+)*annot\s+@[A-Za-z_$][\w$]*(\s+for\s+\.\*)""",
        )
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

        /**
         * `var value = Queue<Int>()` - a binding that names its type only on the
         * right, where the reader has to run the initializer to learn it.
         *
         * The `.()` spelling exists so the type is written once, on the left,
         * where a declaration says what it is. Constrained to a constructor call
         * on a capitalized name so that `var x = compute()` stays untouched.
         */
        val INFERRED_CONSTRUCTOR_BINDING = Regex(
            """\b(var|val|fin|let)\s+([A-Za-z_$][\w$]*)\s*=\s*""" +
                """([A-Z][\w$]*(?:(?:::|\.)[A-Z][\w$]*)*(?:<[^<>\n]*>)?)\s*\(([^()\n]*)\)""",
        )
        private val TYPED_VALUE_PREFIX = "(?:\\b(?:var|val|fin|let)\\s+[A-Za-z_$][\\w$]*|\\b[A-Za-z_$][\\w$]*)"
        val TYPED_INTEGER = Regex(
            """$TYPED_VALUE_PREFIX\s*:\s*($TYPE_NAME)\s*=\s*([-+]?\d[\d_]*)\b(?![.A-Za-z_])""",
        )
        val TYPED_REAL_TO_INTEGER = Regex(
            """$TYPED_VALUE_PREFIX\s*:\s*($TYPE_NAME)\s*=\s*([-+]?\d[\d_]*\.\d[\d_]*(?:[eE][-+]?\d+)?)\b(?![fFD])""",
        )
        /** `purge <receiver>.<field>` - one line of a run this rule collapses. */
        val PURGE_MEMBER =
            Regex("""(?m)^([ \t]*)purge[ \t]+([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)[ \t]*$""")

        /** `purge name` - one the body owns itself, rather than a member of something. */
        val PURGE_LOCAL =
            Regex("""(?m)^([ \t]*)purge[ \t]+([A-Za-z_$][\w$]*)[ \t]*$""")

        /**
         * A grouped binding or assignment written on one line.
         *
         * Group 1 is the indent, 2 the head (`fin `, `self.`, or nothing), 3
         * the entries, 4 the type the whole group states, 5 the value.
         */
        val GROUP_STATEMENT = Regex(
            """(?m)^([ \t]*)((?:fin|var|val|let)[ \t]+|[A-Za-z_$][\w$.]*\.)?\[([^\]\n]*)\]""" +
                """[ \t]*(:[ \t]*[^=\n]+?)?[ \t]*=[ \t]*([^\n]+)$""",
        )

        /**
         * `i += 1` / `i -= 1`, with the target it steps.
         *
         * A literal `1` only: `+= 1.0` and `+= 1L` step a different type, and
         * `++` is not what those say.
         */
        val STEP_BY_ONE = Regex(
            """(?m)^[ \t]*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\[[^\]\n]*\])?)""" +
                """[ \t]*([-+])=[ \t]*1(?![.\w])[ \t]*$""",
        )

        /**
         * `x = x + 1` and friends, up to the end of the line.
         *
         * The operand is whatever follows the operator, so the rewrite carries
         * the whole right-hand side. `==` and `=>` are excluded by requiring a
         * single `=`, and a compound operator cannot match because the operator
         * group is one character.
         */
        /**
         * `x = x + 1`, with the operand running to the end of the line.
         *
         * Nothing is allowed to float between the operator and the operand
         * group: the match is made against a copy with the literals blanked
         * out, and a `\s*` there would eat an emptied `"0"` and leave the group
         * pointing at the wrong characters of the real line.
         */
        val SELF_ASSIGNMENT = Regex(
            """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=(?![=>])\s*""" +
                """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*([-+*/%])([^\n=]*)$""",
        )

        /** A pack field with a declared default: `var size: Int = 0`. */
        val FIELD_DEFAULT = Regex(
            """(?m)^[ \t]*(var|val|fin|let)\s+([A-Za-z_$][\w$]*)\s*:\s*[^=\n]+=\s*([^\n]+?)[ \t]*$""",
        )

        /** A `ctor` declaration head, capturing its indent and its parameter list. */
        val CTOR_HEAD = Regex(
            """(?m)^([ \t]*)(?:$MODIFIERS\s+)*ctor\s*(?:\[[^\]\n]*\])?\s*\(([^)\n]*)\)""",
        )

        /** `self.field = <value>` on a line of its own. */
        val SELF_FIELD_ASSIGNMENT = Regex(
            """(?m)^[ \t]*self\.([A-Za-z_$][\w$]*)\s*=(?![=>])\s*([^\n]+?)[ \t]*$""",
        )

        /**
         * The part of a `prop` header before its type: everything from the
         * indent through the receiver, kept verbatim by a rewrite.
         */
        private const val PROPERTY_HEADER =
            """(?:$MODIFIERS\s+)*prop\s+[A-Za-z_$][\w$]*\s*(?:<[^<>\n]*>)?\s*(?:\[[^\]\n]*\]\s*)?"""

        /** `prop p[…]: T { return e }` - all on one line. */
        val ONE_LINE_PROPERTY_RETURN = Regex(
            """(?m)^([ \t]*$PROPERTY_HEADER)(:\s*[^={\n]+?)\s*\{\s*return\s+([^\n}]+?)\s*\}[ \t]*$""",
        )

        /** `impl Type` / `impl Spec for Type`, up to the name the body belongs to. */
        val IMPL_HEAD = Regex(
            """(?m)^[ \t]*(?:$MODIFIERS\s+)*impl\s+([A-Za-z_$][\w$]*)(?:<[^<>\n]*>)?""" +
                """(?:\s+for\s+([A-Za-z_$][\w$]*)(?:<[^<>\n]*>)?)?""",
        )

        /** `Owner.Case` - a case named through the type that declares it. */
        val QUALIFIED_CASE = Regex("""\b([A-Z][\w$]*)\.([A-Z][\w$]*)\b""")

        /**
         * A control head starting a line, with its whole braced body on it.
         *
         * The body may not itself contain braces: a nested block on one line is
         * two problems, and rebuilding it would need to know where the inner one
         * should break.
         */
        /** The declaration heads whose body is a block, `prop` excepted. */
        val MEMBER_HEADS = setOf("func", "ctor", "dtor", "oper")

        /** One of those, with its whole body between braces on the same line. */
        val ONE_LINE_MEMBER = Regex(
            """(?m)^([ \t]*)((?:$MODIFIERS\s+)*(?:func|ctor|dtor|oper)\b[^{}\n]*?)[ \t]*\{([^{}\n]*)\}[ \t]*$""",
        )

        val ONE_LINE_BLOCK = Regex(
            """(?m)^([ \t]*)([a-z]+)([^{}\n]*)\{([^{}\n]*)\}[ \t]*(?:(else)[ \t]*\{([^{}\n]*)\}[ \t]*)?$""",
        )

        const val SHARED_BORROW = "&"
        const val EXCLUSIVE_BORROW = "!"

        /**
         * A `func`/`prop`/`ctor` receiver bracket, long or short.
         *
         * Group 1 is the bracket, 2 the receiver's name, 3 the written type of
         * the long form, 4 the written type of a `Self`-less long form, and 5
         * the borrow sigil.
         */
        /** A number and the width suffix that is no longer part of one. */
        val WIDTH_SUFFIXED_LITERAL = Regex(
            """(?<![\w$.])((?:0[xXbBoO][\da-fA-F][\da-fA-F_]*|\d[\d_]*)""" +
                """(?:\.\d[\d_]*)?(?:[eE][-+]?\d+)?)(ub|us|uL|uc|u|b|s|L|c|f|D)\b""",
        )

        /** What each removed suffix used to mean; see [checkWidthSuffixes]. */
        val WIDTH_OF_SUFFIX = mapOf(
            "b" to "Byte", "ub" to "UByte", "s" to "Short", "us" to "UShort",
            "u" to "UInt", "L" to "Long", "uL" to "ULong", "c" to "Cent",
            "uc" to "UCent", "f" to "Float", "D" to "Quad",
        )

        /** A `prop` and the receiver bracket it declares; see [checkPropReceivers]. */
        val PROP_RECEIVER = Regex(
            """(?m)^[ \t]*(?:$MODIFIERS\s+)*prop\s+([A-Za-z_$][\w$]*)""" +
                """(?:<[^<>\n]*>)?\s*(\[\s*([A-Za-z_$][\w$]*)\s*""" +
                """(?::\s*([A-Za-z_$][\w$<>, ]*?))?\s*([&!]?)\s*\])""",
        )

        val MEMBER_RECEIVER = Regex(
            """(?m)^[ \t]*(?:$MODIFIERS\s+)*(?:func|prop|ctor|dtor)\s+[A-Za-z_$][\w$]*""" +
                """(?:<[^<>\n]*>)?\s*(\[\s*([A-Za-z_$][\w$]*)\s*""" +
                """(?::\s*(Self)|:\s*([A-Za-z_$][\w$]*))?\s*([&!]?)\s*\])""",
        )

        /** The head of any member, which is where the previous member's body ends. */
        val MEMBER_HEAD = Regex(
            """(?m)^[ \t]*(?:@|(?:$MODIFIERS\s+)*(?:func|prop|ctor|dtor|oper)\b)""",
        )

        /** `for <name> in …` - a name the loop binds. */
        val LOOP_BINDING = Regex("""\bfor\s+([A-Za-z_$][\w$]*)\s+in\b""")

        /**
         * A write that rebinds the receiver's own field: `self.f = …`, `f += …`.
         *
         * Indexing is deliberately excluded. `self.buffer[i] = v` writes through
         * whatever `buffer` points at, and whether a shared borrow of `self`
         * reaches that far is the compiler's question about the pointer, not a
         * question a text scan can answer.
         */
        // `self.x = …`, and the grouped target `self.[x, y] = …` - which writes
        // through the receiver exactly as the members spelled out one per line
        // would. The group may span lines, so its brackets are matched across
        // them.
        val RECEIVER_WRITE = Regex(
            """(?m)^[ \t]*(?:([A-Za-z_$][\w$]*)\.)?(?:([A-Za-z_$][\w$]*)|\[[^\]]*\])""" +
                """\s*(?:=(?![=>])|[-+*/%]=|\+\+|--)""",
        )

        /** A member of this file declared with an exclusive receiver. */
        val MUTATING_MEMBER = Regex(
            """(?m)^[ \t]*(?:$MODIFIERS\s+)*(?:func|prop)\s+([A-Za-z_$][\w$]*)""" +
                """(?:<[^<>\n]*>)?\s*\[\s*(?:[A-Za-z_$][\w$]*\s*)?(?::\s*[A-Za-z_$][\w$]*\s*)?!\s*\]""",
        )

        /** `receiver.member(` - a call through something. */
        val RECEIVER_CALL = Regex("""\b([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\s*\(""")

        /** A `func`/`prop`/`ctor` with a braced body, signature on one line. */
        val CONTRACTABLE_MEMBER = Regex(
            """(?m)^([ \t]*)((?:$MODIFIERS\s+)*(?:func|prop|ctor|dtor)\s+[^{\n]*?)\s*\{""",
        )

        /** A contract this member already states. */
        val CONTRACT_CLAUSE = Regex("""\b(?:in|out|scope)\s*$""")

        /** `name:` in a signature - a parameter or a receiver. */
        val DECLARED_NAME = Regex("""([A-Za-z_$][\w$]*)\s*:""")

        /** `assert <condition> { "message" }`. */
        val ASSERT_LINE = Regex("""assert\s+(.+?)\s*\{[^{}]*\}""")

        /** `return <name>` - a result handed back by name. */
        val RETURN_NAME = Regex("""return\s+([A-Za-z_$][\w$]*)""")

        /** Words in a condition that name nothing. */
        val ASSERT_LITERALS = setOf("true", "false", "null")

        val INTEGER_TYPES = setOf("Byte", "UByte", "Short", "UShort", "Int", "UInt", "Long", "ULong", "Cent", "UCent", "ISize", "USize")
        val FLOAT_TYPES = setOf("Half", "Float", "Double", "Quad")
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

        /** The type a member's receiver has inside the declaration it belongs to. */
        const val SELF_TYPE = "Self"
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
    /** Names this file declares that some other project file also mentions. */
    val namedElsewhere: Set<String> = emptySet(),
    /** Names the file writes that no import reaches, each with the module that declares it. */
    val unimported: Map<String, String> = emptyMap(),
    /** Names the file writes that nothing anywhere declares. */
    val unknown: Set<String> = emptySet(),
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

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

package com.azora.lang.idea.completion

import com.azora.lang.idea.AzoraLanguage
import com.azora.lang.idea.AzoraLanguageFacts
import com.azora.lang.idea.symbol.*
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * [CompletionContributor] for the Azora language.
 *
 * Provides keyword completions, context-aware symbol completions from
 * the [AzoraSymbolService], and code snippets, all without depending
 * on the compiler.
 */
class AzoraCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AzoraLanguage),
            AzoraCompletionProvider()
        )
    }

    /**
     * The main [CompletionProvider] that handles all Azora completion logic.
     *
     * Determines the context from the current line prefix and delegates
     * to specialized methods for keywords, bridge targets, imports, dot
     * completion, scope access, pack constructors, when variants, general
     * symbols, and snippets.
     */
    private class AzoraCompletionProvider : CompletionProvider<CompletionParameters>() {

        /**
         * Adds completion items based on the current caret context.
         *
         * Analyzes the line prefix to determine which type of completion
         * to offer, then delegates to the appropriate method.
         *
         * @param parameters the completion parameters (editor, offset, file).
         * @param context the processing context (unused).
         * @param result the result set to add completion items to.
         */
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            val document = parameters.editor.document
            val source = document.text
            val offset = parameters.offset
            val project = parameters.editor.project ?: return
            val file = parameters.originalFile
            val filePath = file.virtualFile?.path ?: file.name

            // --- 1. Keyword and annotation completions ---
            addAnnotationCompletions(source, offset, result)
            addKeywordCompletions(source, offset, result)

            // --- 2. Context-aware symbol completions ---
            val lineStart = findLineStart(source, offset)
            val prefix = source.substring(lineStart, offset).trimStart()

            val symbolService = AzoraSymbolService.getInstance(project)

            when {
                // After "bridge ." -- suggest bridge targets
                prefix.startsWith("bridge .") || prefix == "bridge ." -> {
                    addBridgeTargetCompletions(symbolService, result)
                }

                // After "use " -- suggest modules from project files
                prefix == "use " || prefix.startsWith("use ") -> {
                    addUseCompletions(symbolService, filePath, source, project, prefix, result)
                }

                // After "fail ." or "fail return ." -- suggest fail variants from context
                prefix.matches(Regex(".*fail\\s+\\.\\s*$")) ||
                prefix.matches(Regex(".*fail\\s+return\\s+\\.\\s*$")) -> {
                    addFailVariantCompletions(symbolService, filePath, source, project, result)
                }

                // Dot completion: <identifier>.
                isDotCompletion(prefix) -> {
                    val receiver = extractReceiverBeforeDot(prefix)
                    addDotCompletions(receiver, symbolService, filePath, source, project, offset, result)
                }

                // Scope access: <scope>::
                isScopeAccess(prefix) -> {
                    val scopePath = extractScopePathBeforeColonColon(prefix)
                    addScopeAccessCompletions(scopePath, symbolService, filePath, source, project, result)
                }

                // After "(" on a pack name -- suggest named arguments
                isPackConstructorArgs(prefix) -> {
                    val packName = extractPackNameBeforeParen(prefix)
                    addPackFieldCompletions(packName, symbolService, filePath, source, project, result)
                }

                // Inside when body: ".Variant" or "is .Variant"
                isWhenVariantCompletion(prefix) -> {
                    addAllVariantCompletions(symbolService, filePath, source, project, result)
                }

                // General context: suggest all visible symbols
                else -> {
                    addGeneralCompletions(symbolService, filePath, source, project, offset, result)
                }
            }

            // --- 3. Snippet completions ---
            addSnippetCompletions(result)
        }

        // -----------------------------------------------------------------------
        // Keyword completions (standalone, no compiler dependency)
        // -----------------------------------------------------------------------

        /**
         * Adds keyword completion items matching the partially typed word at [offset].
         *
         * @param source the full document text.
         * @param offset the caret offset.
         * @param result the result set to add items to.
         */
        private fun addKeywordCompletions(source: String, offset: Int, result: CompletionResultSet) {
            // Extract the partial word being typed
            var wordStart = offset
            while (wordStart > 0 && (source[wordStart - 1].isLetterOrDigit() || source[wordStart - 1] == '_')) wordStart--
            val partial = source.substring(wordStart, offset)
            if (partial.isEmpty()) return

            for (kw in ALL_KEYWORDS) {
                if (kw.startsWith(partial) && kw != partial) {
                    result.addElement(
                        LookupElementBuilder.create(kw)
                            .withIcon(AllIcons.Nodes.Annotationtype)
                            .withTypeText("keyword", true)
                            .bold()
                    )
                }
            }
        }

        private fun addAnnotationCompletions(source: String, offset: Int, result: CompletionResultSet) {
            val lineStart = findLineStart(source, offset)
            val prefix = source.substring(lineStart, offset)
            val atIndex = prefix.lastIndexOf('@')
            if (atIndex < 0) return
            val typed = prefix.substring(atIndex + 1)
            if (typed.any { !(it.isLetterOrDigit() || it == '_') }) return

            for (annotation in AzoraLanguageFacts.builtinAnnotations) {
                if (annotation.name.startsWith(typed)) {
                    result.addElement(
                        LookupElementBuilder.create(annotation.insertText.removePrefix("@"))
                            .withPresentableText("@${annotation.name}")
                            .withIcon(AllIcons.Nodes.Annotationtype)
                            .withTypeText("builtin annotation", true)
                            .withTailText("  ${annotation.description}", true)
                    )
                }
            }
        }

        // -----------------------------------------------------------------------
        // Bridge targets
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for bridge FFI target platforms (e.g. `C`, `JS`, `KOTLIN`).
         *
         * @param symbolService the project's symbol service.
         * @param result the result set to add items to.
         */
        private fun addBridgeTargetCompletions(symbolService: AzoraSymbolService, result: CompletionResultSet) {
            val targets = symbolService.getBridgeTargets()
            for (target in targets) {
                result.addElement(
                    LookupElementBuilder.create(target)
                        .withIcon(AllIcons.Nodes.Plugin)
                        .withTypeText("bridge target", true)
                )
            }
        }

        // -----------------------------------------------------------------------
        // Use imports
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for `use` import statements.
         *
         * Suggests scopes, types from the current file's symbols, and `.az`
         * module paths discovered by scanning the project directory.
         *
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param project the current IntelliJ project.
         * @param result the result set to add items to.
         */
        private fun addUseCompletions(
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            importPrefix: String,
            result: CompletionResultSet
        ) {
            addStdImportCompletions(importPrefix, result)

            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)
            val scopes = allSymbols.filter { it.kind == SymbolKind.SCOPE }
            for (scope in scopes) {
                result.addElement(
                    LookupElementBuilder.create(scope.name)
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("zone", true)
                )
            }

            val types = allSymbols.filter {
                it.kind in setOf(SymbolKind.PACK, SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT, SymbolKind.SOLO, SymbolKind.SPEC)
            }
            for (type in types) {
                result.addElement(
                    LookupElementBuilder.create(type.name)
                        .withIcon(iconForSymbolKind(type.kind))
                        .withTypeText(type.kind.name.lowercase(), true)
                )
            }

            val baseDir = project.basePath
            if (baseDir != null) {
                scanForAzModules(baseDir, result)
            }
        }

        private fun addStdImportCompletions(importPrefix: String, result: CompletionResultSet) {
            val groupedPrefix = Regex("""std\.\{([^}]*)$""").find(importPrefix)?.groupValues?.get(1)
            if (groupedPrefix != null) {
                val typed = groupedPrefix.substringAfterLast(",").trim()
                for ((alias, module) in AzoraLanguageFacts.stdAliases) {
                    if (alias.startsWith(typed)) {
                        result.addElement(
                            LookupElementBuilder.create(alias)
                                .withIcon(AllIcons.Nodes.Package)
                                .withTypeText(module, true)
                        )
                    }
                }
                return
            }

            val normalizedPrefix = importPrefix.substringAfter("use ").trim()
            for (module in AzoraLanguageFacts.stdModules) {
                if (module.startsWith(normalizedPrefix) || normalizedPrefix.isBlank()) {
                    result.addElement(
                        LookupElementBuilder.create(module)
                            .withIcon(AllIcons.Nodes.Package)
                            .withTypeText("stdlib module", true)
                    )
                }
            }

            if ("std.{math, concurrency}".startsWith(normalizedPrefix) || normalizedPrefix.isBlank()) {
                result.addElement(
                    LookupElementBuilder.create("std.{math, concurrency}")
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("grouped import", true)
                )
            }
            if ("std::*".startsWith(normalizedPrefix) || normalizedPrefix.isBlank()) {
                result.addElement(
                    LookupElementBuilder.create("std::*")
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("wildcard import", true)
                )
            }
        }

        /**
         * Scans the project directory for `.az` files and adds them as module path completions.
         *
         * Converts file paths to dot-separated module names (e.g. `src/utils/Math.az` becomes
         * `src.utils.Math`). Limited to 200 files to avoid performance issues.
         *
         * @param baseDir the project's base directory path.
         * @param result the result set to add items to.
         */
        private fun scanForAzModules(baseDir: String, result: CompletionResultSet) {
            try {
                val baseDirFile = java.io.File(baseDir)
                if (!baseDirFile.isDirectory) return
                baseDirFile.walk()
                    .filter { it.isFile && it.extension == "az" }
                    .take(200)
                    .forEach { file ->
                        val relativePath = file.relativeTo(baseDirFile).path
                            .removeSuffix(".az")
                            .replace(java.io.File.separatorChar.toString(), ".")
                        result.addElement(
                            LookupElementBuilder.create(relativePath)
                                .withIcon(AllIcons.Nodes.Package)
                                .withTypeText("module", true)
                        )
                    }
            } catch (_: Exception) {}
        }

        // -----------------------------------------------------------------------
        // Dot completion
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for dot access on a [receiver].
         *
         * Resolves the receiver as a type name, variable name, scope, or enum/slot/fail
         * and suggests their members (fields, methods, properties, variants).
         *
         * @param receiver the identifier before the dot.
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addDotCompletions(
            receiver: String,
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            offset: Int,
            result: CompletionResultSet
        ) {
            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)

            val typeMembers = symbolService.getMembersForType(receiver, filePath, source, project)
            for (member in typeMembers) {
                result.addElement(buildSymbolLookup(member))
            }

            val localType = resolveLocalVariableType(receiver, source, offset)
            val varType = localType ?: symbolService.resolveVariableType(receiver, filePath, source, project)
            if (varType != null) {
                val varTypeMembers = symbolService.getMembersForType(varType.substringBefore("<"), filePath, source, project)
                for (member in varTypeMembers) {
                    result.addElement(buildSymbolLookup(member))
                }
            }

            if (receiver == "self") {
                val selfType = resolveEnclosingSelfType(source, offset)
                if (selfType != null) {
                    for (member in symbolService.getMembersForType(selfType, filePath, source, project)) {
                        result.addElement(buildSymbolLookup(member))
                    }
                }
            }

            val scopeSym = allSymbols.find { it.name == receiver && it.kind == SymbolKind.SCOPE }
            if (scopeSym != null) {
                for (member in scopeSym.members) {
                    result.addElement(buildSymbolLookup(member))
                }
            }

            val enumSym = allSymbols.find {
                it.name == receiver && it.kind in setOf(SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT)
            }
            if (enumSym != null) {
                for (variant in enumSym.members.filter { it.kind == SymbolKind.VARIANT }) {
                    result.addElement(buildSymbolLookup(variant))
                }
            }
        }

        // -----------------------------------------------------------------------
        // Scope access (::)
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for scope path access via `::`.
         *
         * Resolves the [scopePath] segments and suggests members of the deepest scope.
         *
         * @param scopePath the list of scope name segments before `::`.
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addScopeAccessCompletions(
            scopePath: List<String>,
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            result: CompletionResultSet
        ) {
            val members = symbolService.resolveScopePath(scopePath, filePath, source, project)
            for (member in members) {
                result.addElement(buildSymbolLookup(member))
            }
        }

        // -----------------------------------------------------------------------
        // Pack constructor named arguments
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for named arguments in a pack/view constructor call.
         *
         * Suggests field names with `: ` suffix for packs, and parameter names for views.
         *
         * @param packName the name of the pack or view being constructed.
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addPackFieldCompletions(
            packName: String,
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            result: CompletionResultSet
        ) {
            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)
            val packSym = allSymbols.find {
                it.name == packName && it.kind in setOf(SymbolKind.PACK, SymbolKind.VIEW)
            } ?: return

            for (field in packSym.members.filter { it.kind == SymbolKind.FIELD }) {
                result.addElement(
                    LookupElementBuilder.create("${field.name}: ")
                        .withIcon(AllIcons.Nodes.Field)
                        .withTypeText(field.type ?: "?", true)
                        .withTailText(
                            if (field.defaultValueText != null) " = ${field.defaultValueText}" else "",
                            true
                        )
                )
            }

            if (packSym.kind == SymbolKind.VIEW) {
                for (param in packSym.params) {
                    result.addElement(
                        LookupElementBuilder.create("${param.first}: ")
                            .withIcon(AllIcons.Nodes.Parameter)
                            .withTypeText(param.second ?: "?", true)
                    )
                }
            }
        }

        // -----------------------------------------------------------------------
        // Fail variants
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for fail variant names after `fail .` or `fail return .`.
         *
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addFailVariantCompletions(
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            result: CompletionResultSet
        ) {
            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)
            val failTypes = allSymbols.filter { it.kind == SymbolKind.FAIL }
            for (failType in failTypes) {
                for (variant in failType.members.filter { it.kind == SymbolKind.VARIANT }) {
                    result.addElement(
                        LookupElementBuilder.create(variant.name)
                            .withIcon(AllIcons.Nodes.ExceptionClass)
                            .withTypeText(failType.name, true)
                    )
                }
            }
        }

        // -----------------------------------------------------------------------
        // When/match variant completion
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for all enum, fail, and slot variants.
         *
         * Used inside `when` bodies for pattern matching (e.g. `is .Variant`).
         *
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addAllVariantCompletions(
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            result: CompletionResultSet
        ) {
            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)
            val variantTypes = allSymbols.filter {
                it.kind in setOf(SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT)
            }
            for (typeSym in variantTypes) {
                for (variant in typeSym.members.filter { it.kind == SymbolKind.VARIANT }) {
                    val tailText = if (variant.params.isNotEmpty()) {
                        "(${variant.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                    } else ""
                    result.addElement(
                        LookupElementBuilder.create(variant.name)
                            .withIcon(AllIcons.Nodes.Enum)
                            .withTypeText(typeSym.name, true)
                            .withTailText(tailText, true)
                    )
                }
            }
        }

        // -----------------------------------------------------------------------
        // General completions (all visible symbols)
        // -----------------------------------------------------------------------

        /**
         * Adds completion items for all visible top-level symbols (functions, types, variables, etc.).
         *
         * @param symbolService the project's symbol service.
         * @param filePath the absolute path of the current file.
         * @param source the full text of the current file.
         * @param result the result set to add items to.
         */
        private fun addGeneralCompletions(
            symbolService: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            offset: Int,
            result: CompletionResultSet
        ) {
            val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)
            for (sym in allSymbols) {
                if (sym.kind in setOf(
                        SymbolKind.FUNC, SymbolKind.VIEW, SymbolKind.PACK,
                        SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT,
                        SymbolKind.SOLO, SymbolKind.SCOPE, SymbolKind.VAR, SymbolKind.FIN,
                        SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.WRAP
                    )
                ) {
                    result.addElement(buildSymbolLookup(sym))
                }
            }

            for (local in extractLocalBindings(source, offset)) {
                result.addElement(buildSymbolLookup(local))
            }
        }

        // -----------------------------------------------------------------------
        // Snippets
        // -----------------------------------------------------------------------

        /**
         * Adds code snippet completion items for common Azora constructs.
         *
         * Snippets are triggered by their keyword (e.g. `func`, `view`, `pack`)
         * and insert a multi-line template.
         *
         * @param result the result set to add items to.
         */
        private fun addSnippetCompletions(result: CompletionResultSet) {
            val snippets = listOf(
                Snippet("func", "Function declaration", "func name(param: Type): ReturnType {\n    \n}"),
                Snippet("task main", "Async entry point", "task main() {\n    fin user = await loadUser()\n    fin posts = await loadPosts()\n    render(user, posts)\n}"),
                Snippet("view", "View component", "view Name() {\n    rem state = 0\n\n    effect {\n        \n    }\n\n    Column(modifier: Modifier.padding(16)) {\n        \n    }\n}"),
                Snippet("pack", "Pack (struct) declaration", "pack Name {\n    var field: Type = defaultValue\n}"),
                Snippet("pack empty", "Empty pack declaration", "pack Name<T>"),
                Snippet("enum", "Enum declaration", "enum Name {\n    Variant1, Variant2, Variant3\n}"),
                Snippet("slot", "Slot (tagged union) declaration", "slot Name {\n    Variant1(field: Type),\n    Variant2(field: Type)\n}"),
                Snippet("fail", "Fail set declaration", "fail ErrorName {\n    Variant1,\n    Variant2\n}"),
                Snippet("friend zone", "Shared stdlib zone", "friend zone std::module {\n    \n}"),
                Snippet("zone", "Zone (namespace)", "zone Name {\n    \n}"),
                Snippet("rem", "Reactive state (rem)", "rem name = initialValue"),
                Snippet("mem", "Remembered reactive value", "mem name = initialValue"),
                Snippet("ret", "Retained reactive value", "ret name = initialValue"),
                Snippet("effect", "Effect block", "effect {\n    \n}"),
                Snippet("bridge", "Bridge FFI block", "bridge .C {\n    func name(param: Type): ReturnType\n}"),
                Snippet("impl", "Implementation block", "impl TypeName {\n    \n}"),
                Snippet("impl trait", "Trait implementation", "impl TraitName for TypeName {\n    ref self ->\n    \n}"),
                Snippet("impl deref", "Deref implementation", "impl deref for TypeName {\n    ref self ->\n    \n}"),
                Snippet("impl as", "Cast implementation", "impl as TargetType for TypeName {\n    ref self ->\n    \n}"),
                Snippet("test", "Test block", "test \"description\" {\n    \n}"),
                Snippet("mixin", "Mixin (string to code)", "mixin \"\""),
                Snippet("for", "For loop", "for i in 0..n {\n    \n}"),
                Snippet("loop iterator continue", "Iterator loop continue handler", "loop iterator continue {\n    \n}"),
                Snippet("when", "When (pattern match)", "when value {\n    is Type -> result\n    else -> default\n}"),
                Snippet("task", "Async task", "task name(): ReturnType {\n    fin result = await call()\n    return result\n}"),
                Snippet("flow", "Flow (generator)", "flow name(): Type {\n    yield value\n}"),
                Snippet("solo", "Singleton declaration", "solo Name {\n    fin field: Type = value\n}"),
                Snippet("wrap", "DI container", "wrap Name {\n    bind Service = ServiceImpl()\n}"),
                Snippet("contract", "Function with contracts", "func name(param: Type): ReturnType\nin {\n    assert param > 0 { \"param must be positive\" }\n} out { r ->\n    assert r >= 0 { \"result must be valid\" }\n} zone {\n    return param\n}"),
                Snippet("spec", "Spec (trait/interface)", "spec Name {\n    func method(): ReturnType\n}"),
                Snippet("zone alloc", "Zone allocation block", "zone scratch {\n    fin ptr = alloc Byte(size)\n    defer { drop ptr }\n}"),
                Snippet("inline for", "Compile-time for loop", "inline for i in 0..n {\n    \n}"),
                Snippet("inline if", "Compile-time if", "inline if platform == \"target\" {\n    \n}"),
            )

            for (snippet in snippets) {
                result.addElement(
                    LookupElementBuilder.create(snippet.insertText)
                        .withPresentableText(snippet.trigger)
                        .withTailText("  ${snippet.description}", true)
                        .withIcon(AllIcons.Nodes.Template)
                        .withTypeText("snippet", true)
                        .withInsertHandler { ctx, _ ->
                            ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                        }
                )
            }
        }

        // -----------------------------------------------------------------------
        // Context detection helpers
        // -----------------------------------------------------------------------

        /**
         * Returns `true` if [prefix] ends with a single dot (not `..`), indicating dot completion.
         */
        private fun isDotCompletion(prefix: String): Boolean {
            return prefix.endsWith(".") && !prefix.endsWith("..")
        }

        /**
         * Returns `true` if [prefix] ends with `::`, indicating scope access completion.
         */
        private fun isScopeAccess(prefix: String): Boolean {
            return prefix.endsWith("::")
        }

        /**
         * Returns `true` if [prefix] ends with `(` preceded by an uppercase identifier,
         * indicating a pack/view constructor call where named arguments should be suggested.
         */
        private fun isPackConstructorArgs(prefix: String): Boolean {
            val trimmed = prefix.trimEnd()
            if (!trimmed.endsWith("(")) return false
            val identName = extractPackNameBeforeParen(prefix)
            return identName.isNotEmpty() && identName[0].isUpperCase()
        }

        /**
         * Returns `true` if [prefix] starts with `is .` or `.` followed by an uppercase letter,
         * indicating a `when` variant pattern match context.
         */
        private fun isWhenVariantCompletion(prefix: String): Boolean {
            val trimmed = prefix.trimStart()
            return trimmed.startsWith("is .") || trimmed == "." ||
                    (trimmed.startsWith(".") && trimmed.length > 1 && trimmed[1].isUpperCase())
        }

        /**
         * Extracts the identifier name before a trailing dot in [prefix].
         *
         * @param prefix the line prefix ending with `.`.
         * @return the receiver identifier, e.g. `"point"` from `"point."`.
         */
        private fun extractReceiverBeforeDot(prefix: String): String {
            val beforeDot = prefix.dropLast(1).trimEnd()
            val identChars = StringBuilder()
            for (i in beforeDot.indices.reversed()) {
                val ch = beforeDot[i]
                if (ch.isLetterOrDigit() || ch == '_') identChars.insert(0, ch) else break
            }
            return identChars.toString()
        }

        /**
         * Extracts the scope path segments before a trailing `::` in [prefix].
         *
         * @param prefix the line prefix ending with `::`.
         * @return the list of scope name segments, e.g. `["MathUtils"]` from `"MathUtils::"`.
         */
        private fun extractScopePathBeforeColonColon(prefix: String): List<String> {
            val beforeColons = prefix.dropLast(2).trimEnd()
            val pathChars = StringBuilder()
            for (i in beforeColons.indices.reversed()) {
                val ch = beforeColons[i]
                if (ch.isLetterOrDigit() || ch == '_' || ch == ':') {
                    pathChars.insert(0, ch)
                } else {
                    break
                }
            }
            return pathChars.toString().split("::").map { it.trim() }.filter { it.isNotEmpty() }
        }

        /**
         * Extracts the type name before a trailing `(` in [prefix].
         *
         * @param prefix the line prefix ending with `(`.
         * @return the pack/view name, e.g. `"Point"` from `"Point("`.
         */
        private fun extractPackNameBeforeParen(prefix: String): String {
            val beforeParen = prefix.trimEnd().dropLast(1).trim()
            val identChars = StringBuilder()
            for (i in beforeParen.indices.reversed()) {
                val ch = beforeParen[i]
                if (ch.isLetterOrDigit() || ch == '_') identChars.insert(0, ch) else break
            }
            return identChars.toString()
        }

        private fun extractLocalBindings(source: String, offset: Int): List<SymbolInfo> {
            val beforeCaret = source.take(offset.coerceIn(0, source.length))
            val result = LinkedHashMap<String, SymbolInfo>()
            val bindingRegex = Regex("""\b(var|fin|mem|rem|ret)\s+([A-Za-z_$][A-Za-z0-9_$]*)(?:\s*:\s*([^=\n]+))?(?:\s*=\s*([^\n]+))?""")
            for (match in bindingRegex.findAll(beforeCaret)) {
                val keyword = match.groupValues[1]
                val name = match.groupValues[2]
                val declaredType = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                val initializer = match.groupValues.getOrNull(4)?.trim().orEmpty()
                val type = declaredType ?: inferTypeFromInitializer(initializer)
                val kind = if (keyword == "var" || keyword == "mem" || keyword == "rem") SymbolKind.VAR else SymbolKind.FIN
                result[name] = SymbolInfo(name, kind, type = type, isMutable = kind == SymbolKind.VAR)
            }
            return result.values.toList()
        }

        private fun resolveLocalVariableType(name: String, source: String, offset: Int): String? {
            return extractLocalBindings(source, offset).lastOrNull { it.name == name }?.type
        }

        private fun inferTypeFromInitializer(initializer: String): String? {
            val trimmed = initializer.trim()
            val ctor = Regex("""^([A-Z][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*\(""").find(trimmed)
            if (ctor != null) return ctor.groupValues[1]
            return when {
                trimmed.startsWith("\"") -> "String"
                trimmed == "true" || trimmed == "false" -> "Bool"
                trimmed.matches(Regex("""[-+]?\d+""")) -> "Int"
                trimmed.matches(Regex("""[-+]?\d+\.\d+.*""")) -> "Real"
                else -> null
            }
        }

        private fun resolveEnclosingSelfType(source: String, offset: Int): String? {
            val beforeCaret = source.take(offset.coerceIn(0, source.length))
            val impl = Regex("""(?m)^\s*(?:expose\s+|confine\s+|protect\s+|unsafe\s+|inline\s+)*impl(?:\s+[A-Za-z_][A-Za-z0-9_<>,:\s]*\s+for)?\s+([A-Z][A-Za-z0-9_]*)""")
                .findAll(beforeCaret)
                .lastOrNull()
            if (impl != null) return impl.groupValues[1]

            val pack = Regex("""(?m)^\s*(?:expose\s+|confine\s+|protect\s+)*pack\s+([A-Z][A-Za-z0-9_]*)""")
                .findAll(beforeCaret)
                .lastOrNull()
            return pack?.groupValues?.get(1)
        }

        // -----------------------------------------------------------------------
        // Lookup element builders
        // -----------------------------------------------------------------------

        /**
         * Builds a [LookupElementBuilder] for a [SymbolInfo] with appropriate icon,
         * type text, and tail text based on the symbol's kind.
         *
         * @param sym the symbol to build a lookup element for.
         * @return the configured lookup element.
         */
        private fun buildSymbolLookup(sym: SymbolInfo): LookupElementBuilder {
            val icon = iconForSymbolKind(sym.kind)
            val typeText = when (sym.kind) {
                SymbolKind.FUNC, SymbolKind.TASK, SymbolKind.FLOW -> sym.type ?: "Unit"
                SymbolKind.METHOD -> sym.type ?: "Unit"
                SymbolKind.VAR, SymbolKind.FIN, SymbolKind.FIELD -> sym.type ?: "?"
                SymbolKind.PROPERTY -> sym.type ?: "?"
                SymbolKind.VARIANT -> sym.type ?: ""
                else -> sym.kind.name.lowercase()
            }

            val tailText = when (sym.kind) {
                SymbolKind.FUNC, SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.METHOD -> {
                    "(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                }
                SymbolKind.VIEW -> {
                    "(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                }
                SymbolKind.VARIANT -> {
                    if (sym.params.isNotEmpty()) {
                        "(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                    } else ""
                }
                SymbolKind.FIELD -> {
                    if (sym.defaultValueText != null) " = ${sym.defaultValueText}" else ""
                }
                else -> ""
            }

            return LookupElementBuilder.create(sym.name)
                .withIcon(icon)
                .withTypeText(typeText, true)
                .withTailText(tailText, true)
        }

        // -----------------------------------------------------------------------
        // Utility
        // -----------------------------------------------------------------------

        /**
         * Finds the start offset of the line containing [offset].
         *
         * @param source the full document text.
         * @param offset the caret offset.
         * @return the offset of the first character on the current line.
         */
        private fun findLineStart(source: String, offset: Int): Int {
            var i = (offset - 1).coerceAtLeast(0)
            while (i > 0 && source[i] != '\n') i--
            return if (source.getOrNull(i) == '\n') i + 1 else i
        }

        /**
         * A code snippet template with a [trigger] keyword, a [description], and the [insertText].
         *
         * @param trigger the keyword that triggers this snippet in completion.
         * @param description a short human-readable description.
         * @param insertText the text to insert when the snippet is selected.
         */
        private class Snippet(val trigger: String, val description: String, val insertText: String)
    }

    companion object {
        /** All Azora keywords used for keyword completion. */
        private val ALL_KEYWORDS = AzoraLanguageFacts.allCompletionKeywords
    }
}

/**
 * Maps a [SymbolKind] to the appropriate IntelliJ [Icon] for display in
 * completion lists, structure views, and other UI elements.
 *
 * @param kind the symbol kind to map.
 * @return the corresponding IntelliJ platform icon.
 */
internal fun iconForSymbolKind(kind: SymbolKind): Icon = when (kind) {
    SymbolKind.PACK -> AllIcons.Nodes.Class
    SymbolKind.ENUM -> AllIcons.Nodes.Enum
    SymbolKind.FAIL -> AllIcons.Nodes.ExceptionClass
    SymbolKind.SLOT -> AllIcons.Nodes.AnonymousClass
    SymbolKind.FUNC -> AllIcons.Nodes.Function
    SymbolKind.VIEW -> AllIcons.Nodes.PpWeb
    SymbolKind.SCOPE -> AllIcons.Nodes.Package
    SymbolKind.SOLO -> AllIcons.Nodes.Static
    SymbolKind.WRAP -> AllIcons.Nodes.Module
    SymbolKind.VAR -> AllIcons.Nodes.Variable
    SymbolKind.FIN -> AllIcons.Nodes.FinalMark
    SymbolKind.FIELD -> AllIcons.Nodes.Field
    SymbolKind.METHOD -> AllIcons.Nodes.Method
    SymbolKind.PROPERTY -> AllIcons.Nodes.Property
    SymbolKind.VARIANT -> AllIcons.Nodes.Enum
    SymbolKind.OPERATOR -> AllIcons.Nodes.Method
    SymbolKind.BRIDGE_FUNC -> AllIcons.Nodes.Plugin
    SymbolKind.TASK -> AllIcons.Nodes.Function
    SymbolKind.FLOW -> AllIcons.Nodes.Function
    SymbolKind.HOOK -> AllIcons.Nodes.Function
    SymbolKind.TEST -> AllIcons.Nodes.Test
    SymbolKind.SPEC -> AllIcons.Nodes.Interface
    SymbolKind.IMPL_SPEC -> AllIcons.Nodes.EntryPoints
    SymbolKind.INFX -> AllIcons.Nodes.Method
    SymbolKind.BRIDGE -> AllIcons.Nodes.Plugin
    SymbolKind.TYPEALIAS -> AllIcons.Nodes.Type
    SymbolKind.PACKAGE -> AllIcons.Nodes.Package
    SymbolKind.USE -> AllIcons.Nodes.Include
    SymbolKind.PARAM -> AllIcons.Nodes.Parameter
    SymbolKind.CTOR -> AllIcons.Nodes.Method
    SymbolKind.DTOR -> AllIcons.Nodes.Method
    SymbolKind.WRAP_BINDING -> AllIcons.Nodes.Module
}

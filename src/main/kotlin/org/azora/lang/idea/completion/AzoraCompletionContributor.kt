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

package org.azora.lang.idea.completion

import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.AzoraLanguageFacts
import org.azora.lang.idea.symbol.AzoraAutoImport
import org.azora.lang.idea.symbol.AzoraMacroIndex
import org.azora.lang.idea.symbol.AzoraImports
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * Code completion for Azora.
 *
 * Completion is *contextual*: after a `.` or `::` only the receiver's real
 * members are offered, in an import only real module paths, in a constructor
 * call only that type's fields. Everything offered comes from the symbol index
 * — the project's sources, the installed SDK, and the dependency source roots
 * the manifests point at — so nothing needs to be kept in a hardcoded list.
 */
class AzoraCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AzoraLanguage),
            AzoraCompletionProvider()
        )
    }

    private class AzoraCompletionProvider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            val source = parameters.editor.document.text
            val offset = parameters.offset.coerceIn(0, source.length)
            val project = parameters.editor.project ?: return
            val file = parameters.originalFile
            val filePath = file.virtualFile?.path ?: file.name

            val service = AzoraSymbolService.getInstance(project)
            val resolver = AzoraResolver(project, service)

            val lineStart = findLineStart(source, offset)
            val prefix = source.substring(lineStart, offset)
            val trimmedPrefix = prefix.trimStart()

            // A group may span lines, so what the caret is inside is the whole
            // clause and not the line it sits on. Reading the line alone left
            // the caret in `import std.[\n    reflection::` looking like a bare
            // `reflection::` with no clause around it.
            val importPrefix = enclosingImportPrefix(source, offset) ?: trimmedPrefix

            // `@` introduces either an annotation or a real macro. Both are
            // completed from symbols visible in this file; the sigil itself is
            // already present and is never duplicated by insertion.
            if (addSigilCompletions(project, service, filePath, source, prefix, result)) return

            // After `bridge .`, only FFI targets make sense.
            if (BRIDGE_TARGET.containsMatchIn(trimmedPrefix)) {
                addBridgeTargets(service, result)
                return
            }

            // In an import, only module paths make sense.
            if (IMPORT_LINE.matches(importPrefix)) {
                addImportCompletions(service, project, importPrefix, result)
                return
            }

            // IntelliJ's default camel-hump matcher treats `Anchor` as a
            // suffix match for `TilemapAnchor`. Azora names are exact symbols,
            // so ordinary completion uses a literal prefix and cannot replace
            // an in-scope `Anchor` with an unrelated longer name.
            val typedName = wordBefore(source, offset)
            val scopedResult = if (typedName.isEmpty()) result
            else result.withPrefixMatcher(PlainPrefixMatcher(typedName, true))

            // After `.` or `::`, only the receiver's members make sense.
            val memberContext = memberContext(source, offset)
            if (memberContext != null) {
                addMemberCompletions(memberContext, resolver, service, filePath, source, offset, project, scopedResult)
                return
            }

            // Named arguments inside `Type(` are that type's fields.
            constructorTarget(prefix)?.let { typeName ->
                addConstructorArguments(typeName, service, filePath, source, project, scopedResult)
            }

            addGeneralCompletions(resolver, service, filePath, source, offset, project, scopedResult)
            addKeywordCompletions(source, offset, scopedResult)
            addSnippetCompletions(source, offset, scopedResult)
        }

        // ── Member completion (`.` and `::`) ───────────────────────────

        /**
         * The receiver path when the caret follows a `.` or `::`, or `null`.
         *
         * The already-typed part of the member name is excluded so `point.dis`
         * still resolves against `point`.
         */
        private fun memberContext(source: String, offset: Int): List<String>? {
            var start = offset
            while (start > 0 && isNameChar(source[start - 1])) start--
            val separatorEnd = start
            val hasDot = separatorEnd > 0 && source[separatorEnd - 1] == '.' &&
                !(separatorEnd > 1 && source[separatorEnd - 2] == '.')
            val hasColons = separatorEnd > 1 && source[separatorEnd - 1] == ':' && source[separatorEnd - 2] == ':'
            if (!hasDot && !hasColons) return null

            val resolver = QUALIFIER_READER
            val (qualifier, _) = resolver.qualifierBefore(source, start)
            return qualifier.ifEmpty { null }
        }

        private fun addMemberCompletions(
            qualifier: List<String>,
            resolver: AzoraResolver,
            service: AzoraSymbolService,
            filePath: String,
            source: String,
            offset: Int,
            project: Project,
            result: CompletionResultSet,
        ) {
            val members = resolver.membersOfQualifier(qualifier, filePath, source, offset)
            for (member in members) result.addElement(lookupFor(member))

            // `Enum.` / `.Variant` in a `when` arm: offer variants of every
            // enum-like type when the receiver itself did not resolve.
            if (members.isEmpty() && qualifier.size == 1) {
                val visible = service.getAllVisibleSymbols(project, filePath, source)
                visible.asSequence()
                    .filter { it.name == qualifier.first() && it.kind in VARIANT_OWNERS }
                    .flatMap { it.members.asSequence() }
                    .forEach { result.addElement(lookupFor(it)) }
            }
        }

        /**
         * The `import …` clause the caret sits inside, up to the caret.
         *
         * Returns `null` when the caret is not in one. A clause that closed
         * before the caret is not one it is inside, which is what keeps the line
         * after an import from being completed as part of it.
         */
        private fun enclosingImportPrefix(source: String, offset: Int): String? {
            val clause = AzoraImports.clauses(source.substring(0, offset)).lastOrNull() ?: return null
            if (clause.end < offset && !source.substring(clause.end, offset).isBlank()) return null
            return source.substring(clause.start, offset).trimStart()
        }

        // ── Imports ────────────────────────────────────────────────────

        /**
         * Offers module paths for an `import` / `use` line.
         *
         * Paths come from the modules that actually exist: those declared by
         * the project's files, by the installed SDK, and by dependency sources.
         */
        private fun addImportCompletions(
            service: AzoraSymbolService,
            project: Project,
            prefix: String,
            result: CompletionResultSet,
        ) {
            val typed = prefix.substringAfter("import ").substringAfter("use ").trim()
            val modules = service.importableModuleNames(project)

            // Grouped form: `import std.{math, io}` — complete the leaf names.
            val grouped = GROUPED_IMPORT.find(typed)
            if (grouped != null) {
                val base = grouped.groupValues[1]
                val partial = grouped.groupValues[2].substringAfterLast(',').substringAfterLast('\n').trim()
                // A group holds whatever its separator said it holds: modules
                // under the path when a `.` opened it, names inside the module
                // when a `::` did.
                val selecting = "::[" in typed || "::{" in typed
                if (selecting) {
                    addModuleMembers(service, project, base, partial, result)
                } else {
                    modules.asSequence()
                        .filter { it.startsWith("$base.") }
                        .map { it.removePrefix("$base.").substringBefore('.') }
                        .distinct()
                        .filter { it.startsWith(partial) }
                        .forEach {
                            result.addElement(
                                LookupElementBuilder.create(it)
                                    .withIcon(AllIcons.Nodes.Package)
                                    .withTypeText("$base.$it", true)
                            )
                        }
                }
                return
            }

            // `import std.math::` - the module is settled, so what follows is a
            // name it declares rather than another step down the tree.
            SELECTING_IMPORT.find(typed)?.let { selecting ->
                addModuleMembers(service, project, selecting.groupValues[1], selecting.groupValues[2], result)
                return
            }

            for (module in modules) {
                if (typed.isNotEmpty() && !module.startsWith(typed)) continue
                result.addElement(
                    LookupElementBuilder.create(module)
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("module", true)
                )
                // The wildcard form is a genuine alternative for each module.
                // It selects declarations, so it is spelled with `::`.
                result.addElement(
                    LookupElementBuilder.create("$module::*")
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("all members", true)
                )
            }
        }

        /** The declarations [module] holds, for the name after a `::`. */
        private fun addModuleMembers(
            service: AzoraSymbolService,
            project: Project,
            module: String,
            partial: String,
            result: CompletionResultSet,
        ) {
            for (symbol in service.symbolsOfModule(project, module)) {
                if (partial.isNotEmpty() && !symbol.name.startsWith(partial)) continue
                result.addElement(lookupFor(symbol).withTypeText(module, true))
            }
        }

        // ── Decorators ─────────────────────────────────────────────────

        /** Offers annotations and macros after `@`; returns whether it handled the context. */
        private fun addSigilCompletions(
            project: Project,
            service: AzoraSymbolService,
            filePath: String,
            source: String,
            prefix: String,
            result: CompletionResultSet,
        ): Boolean {
            val at = prefix.lastIndexOf('@')
            if (at < 0) return false
            val typed = prefix.substring(at + 1)
            if (typed.any { !isNameChar(it) }) return false

            val scoped = if (typed.isEmpty()) result
            else result.withPrefixMatcher(PlainPrefixMatcher(typed, true))
            val seen = linkedSetOf<Pair<String, String>>()

            for (annotation in AzoraLanguageFacts.builtinAnnotations) {
                if (!annotation.name.startsWith(typed)) continue
                if (!seen.add("annotation" to annotation.name)) continue
                scoped.addElement(
                    LookupElementBuilder.create(annotation.insertText.removePrefix("@"))
                        .withPresentableText("@${annotation.name}")
                        .withIcon(AllIcons.Nodes.Annotationtype)
                        .withTypeText("annotation", true)
                        .withTailText("  ${annotation.description}", true)
                )
            }

            service.getAllVisibleSymbols(project, filePath, source).asSequence()
                .flatMap(::allSymbols)
                .filter { it.kind == SymbolKind.ANNOT && it.name.startsWith(typed) }
                .forEach { annotation ->
                    if (!seen.add("annotation" to annotation.name)) return@forEach
                    scoped.addElement(
                        LookupElementBuilder.create(annotation.name)
                            .withPresentableText("@${annotation.name}")
                            .withIcon(AllIcons.Nodes.Annotationtype)
                            .withTypeText("annotation", true)
                            .withTailText(annotation.documentation?.firstOrNull()?.let { "  $it" }.orEmpty(), true)
                    )
                }

            val macros = runCatching { AzoraMacroIndex.getInstance(project).macrosFor(source) }
                .getOrDefault(org.azora.lang.idea.symbol.AzoraMacros.EMPTY)
            macros.all.asSequence().filter { it.startsWith(typed) }.sorted().forEach { name ->
                if (!seen.add("macro" to name)) return@forEach
                scoped.addElement(
                    LookupElementBuilder.create(name)
                        .withPresentableText("@$name")
                        .withIcon(AllIcons.Nodes.Tag)
                        .withTypeText(
                            when {
                                name in macros.prefix && name in macros.infix -> "macro"
                                name in macros.infix -> "infix macro"
                                else -> "prefix macro"
                            },
                            true,
                        )
                )
            }
            return true
        }

        /** Recursively exposes nested scope symbols to sigil completion. */
        private fun allSymbols(symbol: SymbolInfo): Sequence<SymbolInfo> = sequence {
            yield(symbol)
            for (member in symbol.members) yieldAll(allSymbols(member))
        }

        // ── Bridge targets ─────────────────────────────────────────────

        private fun addBridgeTargets(service: AzoraSymbolService, result: CompletionResultSet) {
            for (target in service.getBridgeTargets()) {
                result.addElement(
                    LookupElementBuilder.create(target)
                        .withIcon(AllIcons.Nodes.Plugin)
                        .withTypeText("bridge target", true)
                )
            }
        }

        // ── Constructor arguments ──────────────────────────────────────

        /** The type name when the caret sits inside `Type(`, or `null`. */
        private fun constructorTarget(prefix: String): String? {
            val open = prefix.lastIndexOf('(')
            if (open < 0) return null
            if (prefix.indexOf(')', open) >= 0) return null
            var end = open
            while (end > 0 && prefix[end - 1].isWhitespace()) end--
            var start = end
            while (start > 0 && isNameChar(prefix[start - 1])) start--
            val name = prefix.substring(start, end)
            return name.takeIf { it.isNotEmpty() && it.first().isUpperCase() }
        }

        private fun addConstructorArguments(
            typeName: String,
            service: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            result: CompletionResultSet,
        ) {
            val fields = service.getMembersForType(typeName, filePath, source, project)
                .filter { it.kind == SymbolKind.FIELD }
            for (field in fields) {
                result.addElement(
                    LookupElementBuilder.create("${field.name}: ")
                        .withIcon(AllIcons.Nodes.Field)
                        .withTypeText(field.type ?: "?", true)
                        .withTailText(
                            field.defaultValueText?.let { " = $it" }.orEmpty(),
                            true
                        )
                )
            }
        }

        // ── General symbols ────────────────────────────────────────────

        private fun addGeneralCompletions(
            resolver: AzoraResolver,
            service: AzoraSymbolService,
            filePath: String,
            source: String,
            offset: Int,
            project: Project,
            result: CompletionResultSet,
        ) {
            // Locals and parameters first — they are what the caret can see.
            for (local in resolver.localsInScope(source, offset).reversed().distinctBy { it.name }) {
                result.addElement(lookupFor(local))
            }

            // `self` members, when inside an impl or pack.
            resolver.selfType(source, offset)?.let { type ->
                for (member in service.getMembersForType(type, filePath, source, project)) {
                    result.addElement(lookupFor(member))
                }
            }

            val visible = service.getAllVisibleSymbols(project, filePath, source)
            for (symbol in visible) {
                if (symbol.kind in TOP_LEVEL_KINDS) result.addElement(lookupFor(symbol))
            }
            addUnimportedCompletions(service, filePath, source, project, visible, result)
        }

        /**
         * Names that exist but are not imported here, each carrying the import
         * that would make it real.
         *
         * A name is only useful to offer if accepting it leaves working code, so
         * the import is written by the same keystroke that writes the name -
         * that is the whole point of offering something out of scope.
         *
         * These sort last and say where they come from, so an in-scope name is
         * never displaced by a stranger with the same spelling.
         */
        private fun addUnimportedCompletions(
            service: AzoraSymbolService,
            filePath: String,
            source: String,
            project: Project,
            visible: List<SymbolInfo>,
            result: CompletionResultSet,
        ) {
            // Only once something is being typed. With an empty prefix this is
            // every symbol in the SDK, which is a list nobody reads.
            val typed = result.prefixMatcher.prefix
            if (typed.length < MIN_UNIMPORTED_PREFIX) return

            val inScope = visible.mapTo(HashSet()) { it.name }
            val importable = runCatching { service.getImportableSymbols(project, filePath) }
                .getOrDefault(emptyList())
            var offered = 0
            for (symbol in importable) {
                if (offered >= MAX_UNIMPORTED) break
                val module = symbol.modulePath ?: continue
                if (symbol.kind !in TOP_LEVEL_KINDS) continue
                // The session's own matcher, so `deq` finds `Deque` and `hmap`
                // finds `HashMap`. A case-sensitive `startsWith` hid every type
                // in the project behind its own first letter.
                if (symbol.name in inScope || !result.prefixMatcher.prefixMatches(symbol.name)) continue
                if (AzoraAutoImport.importEdit(source, module) == null) continue
                offered++
                result.addElement(
                    PrioritizedLookupElement.withPriority(
                        lookupFor(symbol)
                            .withTypeText(module, true)
                            .withInsertHandler { context, _ ->
                                val edit = AzoraAutoImport.importEdit(context.document.text, module)
                                if (edit != null) {
                                    AzoraAutoImport.apply(context.document, edit)
                                    context.commitDocument()
                                }
                            },
                        UNIMPORTED_PRIORITY,
                    ),
                )
            }
        }

        // ── Keywords, macros and snippets ──────────────────────────────

        /**
         * Offers keywords only once a word is being typed, so an empty-prefix
         * invocation is not buried under eighty keywords.
         */
        private fun addKeywordCompletions(source: String, offset: Int, result: CompletionResultSet) {
            val typed = wordBefore(source, offset)
            if (typed.isEmpty()) return
            for (keyword in AzoraLanguageFacts.allCompletionKeywords) {
                if (keyword == typed || !keyword.startsWith(typed)) continue
                result.addElement(
                    LookupElementBuilder.create(keyword)
                        .withIcon(AllIcons.Nodes.Annotationtype)
                        .withTypeText("keyword", true)
                        .bold()
                )
            }
        }

        /**
         * Offers multi-line templates, but only when what has been typed is a
         * plausible trigger, so they never crowd out real symbols.
         */
        private fun addSnippetCompletions(source: String, offset: Int, result: CompletionResultSet) {
            val typed = wordBefore(source, offset)
            if (typed.length < MIN_SNIPPET_PREFIX) return
            for (snippet in AzoraSnippets.ALL) {
                if (!snippet.trigger.startsWith(typed, ignoreCase = true)) continue
                result.addElement(
                    LookupElementBuilder.create(snippet.body)
                        .withPresentableText(snippet.trigger)
                        .withTailText("  ${snippet.description}", true)
                        .withIcon(AllIcons.Nodes.Template)
                        .withTypeText("template", true)
                        .withInsertHandler { ctx, _ -> ctx.editor.caretModel.moveToOffset(ctx.tailOffset) }
                )
            }
        }

        // ── Lookup rendering ───────────────────────────────────────────

        private fun lookupFor(sym: SymbolInfo): LookupElementBuilder {
            val typeText = when (sym.kind) {
                SymbolKind.FUNC, SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.METHOD -> sym.type ?: "Unit"
                SymbolKind.VAR, SymbolKind.FIN, SymbolKind.FIELD, SymbolKind.PROPERTY,
                SymbolKind.PARAM -> sym.type ?: "?"
                SymbolKind.VARIANT -> sym.type.orEmpty()
                else -> sym.kind.name.lowercase()
            }

            val tailText = when (sym.kind) {
                SymbolKind.FUNC, SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.METHOD,
                SymbolKind.CTOR, SymbolKind.VIEW ->
                    "(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                SymbolKind.VARIANT ->
                    if (sym.params.isEmpty()) ""
                    else "(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})"
                SymbolKind.FIELD -> sym.defaultValueText?.let { " = $it" }.orEmpty()
                else -> ""
            }

            return LookupElementBuilder.create(sym.name)
                .withIcon(iconForSymbolKind(sym.kind))
                .withTypeText(typeText, true)
                .withTailText(tailText, true)
        }

        // ── Text helpers ───────────────────────────────────────────────

        private fun wordBefore(source: String, offset: Int): String {
            var start = offset
            while (start > 0 && isNameChar(source[start - 1])) start--
            return source.substring(start, offset)
        }

        private fun findLineStart(source: String, offset: Int): Int {
            var i = offset - 1
            while (i >= 0 && source[i] != '\n') i--
            return i + 1
        }

        private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

        private companion object {

            /**
             * How much of a name must be typed before out-of-scope symbols are
             * offered.
             *
             * One letter: everything a project declares is a candidate as soon
             * as a word is started, and the matcher plus [MAX_UNIMPORTED] keep
             * the list to what was asked for. Only the empty prefix is refused,
             * where "everything" is the whole SDK and answers no question.
             */
            const val MIN_UNIMPORTED_PREFIX = 1

            /** A ceiling, so a common prefix cannot bury what is in scope. */
            const val MAX_UNIMPORTED = 200

            /** Below everything already reachable - a stranger never wins a tie. */
            const val UNIMPORTED_PRIORITY = -100.0
            /** A resolver used only for its pure text helpers. */
            val QUALIFIER_READER = AzoraResolver(null, AzoraSymbolService())

            /** Everything a module can declare at its top level, and so import. */
            val TOP_LEVEL_KINDS = setOf(
                SymbolKind.FUNC, SymbolKind.BRIDGE_FUNC, SymbolKind.PACK,
                SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SOLO,
                SymbolKind.SCOPE, SymbolKind.SPEC, SymbolKind.GRAPH,
                SymbolKind.VAR, SymbolKind.FIN, SymbolKind.TYPEALIAS,
                SymbolKind.ANNOT, SymbolKind.SLOT, SymbolKind.WRAP, SymbolKind.VIEW,
            )
            val VARIANT_OWNERS = setOf(SymbolKind.ENUM, SymbolKind.FAIL, SymbolKind.SLOT)

            val BRIDGE_TARGET = Regex("""\bbridge\s*\.\s*\w*$""")
            val IMPORT_LINE = Regex("""(?:export\s+)?(?:import|use)\s+[\w.:\[\]{}*,\s]*""")

            /** An open group, bracket or brace, with the base it hangs off. */
            val GROUPED_IMPORT = Regex("""^([\w.]+)(?:\.|::)[\[{]([^\]}]*)$""", RegexOption.DOT_MATCHES_ALL)

            /** An open `::` selection: the module is known, the name is being typed. */
            val SELECTING_IMPORT = Regex("""^([\w.]+)::(\w*)$""")

            /** Templates need at least this much prefix before they are offered. */
            const val MIN_SNIPPET_PREFIX = 2
        }
    }
}

/**
 * Maps a [SymbolKind] to the icon used in completion, the structure view, and
 * other symbol UI.
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
    SymbolKind.ANNOT -> AllIcons.Nodes.Annotationtype
    SymbolKind.GRAPH -> AllIcons.Nodes.Module
    SymbolKind.MACRO -> AllIcons.Nodes.Function
}

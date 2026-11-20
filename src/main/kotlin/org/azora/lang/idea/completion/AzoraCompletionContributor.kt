/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.AzoraLanguageFacts
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.lsp.AzoraLspCodec
import org.azora.lang.idea.lsp.AzoraLspService
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

class AzoraCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(AzoraLanguage),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val position = parameters.position
                    if (position.node.elementType in AzoraTokenTypes.COMMENTS ||
                        position.node.elementType in AzoraTokenTypes.STRINGS
                    ) return
                    val file = parameters.originalFile.virtualFile ?: return
                    val document = parameters.editor.document
                    val source = document.immutableCharSequence
                    val offset = parameters.offset.coerceIn(0, source.length)
                    val prefix = completionPrefix(source, offset)
                    val matcher = result.withPrefixMatcher(PlainPrefixMatcher(prefix, true))
                    val start = offset - prefix.length
                    val afterMemberDot = source.getOrNull(start - 1) == '.' &&
                        source.getOrNull(start - 2) != '.'
                    val nullableAccess = afterMemberDot && source.getOrNull(start - 2) == '?'
                    val lineStart = document.getLineStartOffset(document.getLineNumber(start))
                    val importing = source.subSequence(lineStart, start).trimStart().startsWith("import ")
                    val qualified = afterMemberDot || source.getOrNull(start - 1) == '@' ||
                        start >= 2 && source.subSequence(start - 2, start).toString() == "::"
                    val items = if (nullableAccess) emptyList() else
                        position.project.service<AzoraLspService>().complete(file, document, offset)

                    for (item in items) {
                        if (item.kind == CompletionItemKind.Keyword &&
                            (qualified || prefix.isEmpty() || item.label !in AzoraLanguageFacts.hardKeywords)
                        ) continue
                        addItem(item, document, source, TextRange(start, offset), matcher)
                    }
                    if (afterMemberDot && !importing) {
                        val project = position.project
                        val resolver = AzoraResolver(project, AzoraSymbolService.getInstance(project))
                        val text = source.toString()
                        val (receiver, _) = resolver.qualifierBefore(text, start)
                        val returnedNames = items.mapTo(hashSetOf()) { it.label }
                        resolver.membersOfQualifier(receiver, file.path, text, start)
                            .filter { it.name.startsWith(prefix) && returnedNames.add(it.name) }
                            .forEach { symbol ->
                                matcher.addElement(
                                    LookupElementBuilder.create(symbol.name)
                                        .withIcon(iconForSymbolKind(symbol.kind))
                                        .withTypeText(symbol.type.orEmpty(), true),
                                )
                            }
                    }
                    if (items.isEmpty() && prefix.isNotEmpty() && !qualified && !importing) {
                        AzoraLanguageFacts.allCompletionKeywords.forEach { matcher.addElement(LookupElementBuilder.create(it)) }
                    }
                }
            },
        )
    }

    private fun addItem(
        item: CompletionItem,
        document: Document,
        source: CharSequence,
        prefix: TextRange,
        result: CompletionResultSet,
    ) {
        if (item.label.isNullOrBlank() || item.insertTextFormat == InsertTextFormat.Snippet) return
        val textEdit = item.textEdit
        val editRange = textEdit?.let { if (it.isLeft) it.left.range else it.right.replace }
        val primary = editRange?.let { range(document, it) }
        val replacement = textEdit?.let { if (it.isLeft) it.left.newText else it.right.newText }
            ?: item.insertText ?: item.label
        val extras = item.additionalTextEdits.orEmpty().mapNotNull { edit: TextEdit ->
            range(document, edit.range)?.let { CompletionEdit(it, edit.newText) }
        }
        var lookup = LookupElementBuilder.create(item, item.filterText ?: item.label)
            .withPresentableText(item.label)
            .withTypeText(item.detail.orEmpty(), true)
            .withStrikeoutness(item.tags.orEmpty().contains(CompletionItemTag.Deprecated))
            .withIcon(when (item.kind) {
                CompletionItemKind.Method, CompletionItemKind.Function -> AllIcons.Nodes.Function
                CompletionItemKind.Class, CompletionItemKind.Struct -> AllIcons.Nodes.Class
                CompletionItemKind.Enum, CompletionItemKind.EnumMember -> AllIcons.Nodes.Enum
                CompletionItemKind.Interface -> AllIcons.Nodes.Interface
                CompletionItemKind.Field, CompletionItemKind.Property -> AllIcons.Nodes.Field
                CompletionItemKind.Variable -> AllIcons.Nodes.Variable
                CompletionItemKind.Module -> AllIcons.Nodes.Package
                else -> null
            })
        lookup = lookup.withInsertHandler { insertion, _ ->
            applyCompletion(insertion, source, prefix, primary, replacement, extras)
        }
        val priority = when {
            item.kind == CompletionItemKind.Keyword -> -2.0
            extras.isNotEmpty() -> -1.0
            item.kind in setOf(CompletionItemKind.Variable, CompletionItemKind.Field, CompletionItemKind.Property) -> 1.0
            else -> 0.0
        }
        result.addElement(PrioritizedLookupElement.withPriority(lookup, priority))
    }

    private fun range(document: Document, range: Range): TextRange? {
        val start = AzoraLspCodec.offset(document, range.start) ?: return null
        val end = AzoraLspCodec.offset(document, range.end) ?: return null
        return if (start <= end) TextRange(start, end) else null
    }

    private fun applyCompletion(
        insertion: InsertionContext,
        source: CharSequence,
        prefix: TextRange,
        primary: TextRange?,
        replacement: String,
        extras: List<CompletionEdit>,
    ) {
        val document = insertion.document
        val start = insertion.startOffset
        val tail = insertion.tailOffset
        val extraStart = primary?.let { prefix.startOffset - it.startOffset } ?: 0
        val extraEnd = primary?.let { it.endOffset - prefix.endOffset } ?: 0
        val primaryStart = start - extraStart
        val primaryEnd = tail + extraEnd
        val useServerRange = primaryStart in 0..start && primaryEnd in tail..document.textLength &&
            document.charsSequence.subSequence(primaryStart, start) ==
            source.subSequence(prefix.startOffset - extraStart, prefix.startOffset) &&
            document.charsSequence.subSequence(tail, primaryEnd) ==
            source.subSequence(prefix.endOffset, prefix.endOffset + extraEnd)
        val replaceStart = if (useServerRange) primaryStart else start
        val replaceEnd = if (useServerRange) primaryEnd else tail
        val alreadyOpened = replacement.endsWith('(') && document.charsSequence.getOrNull(replaceEnd) == '('
        val insertedText = if (alreadyOpened) replacement.dropLast(1) else replacement
        val before = if (useServerRange) prefix.startOffset - extraStart else prefix.startOffset
        val after = if (useServerRange) prefix.endOffset + extraEnd else prefix.endOffset

        // The IntelliJ lookup already replaced the prefix. Translate subsequent
        // ranges from the server snapshot and verify the rest of the document
        // before applying edits such as an inserted import.
        val current = document.immutableCharSequence
        val unchanged = current.subSequence(0, replaceStart).toString() == source.subSequence(0, before).toString() &&
            current.subSequence(replaceEnd, current.length).toString() == source.subSequence(after, source.length).toString()
        document.replaceString(replaceStart, replaceEnd, insertedText)
        val caretOffset = replaceStart + insertedText.length + if (alreadyOpened) 1 else 0
        val caret = document.createRangeMarker(caretOffset, caretOffset)
        caret.isGreedyToRight = true
        try {
            if (unchanged) {
                val change = replaceStart + insertedText.length - after
                extras.sortedByDescending { it.range.startOffset }.forEach { edit ->
                    val shift = when {
                        edit.range.endOffset <= before -> 0
                        edit.range.startOffset >= after -> change
                        else -> return@forEach
                    }
                    document.replaceString(edit.range.startOffset + shift, edit.range.endOffset + shift, edit.text)
                }
            }
            insertion.editor.caretModel.moveToOffset(caret.endOffset)
        } finally {
            caret.dispose()
        }
    }

    private data class CompletionEdit(val range: TextRange, val text: String)
}

internal fun completionPrefix(source: CharSequence, offset: Int): String {
    var start = offset
    while (start > 0 && (source[start - 1].isLetterOrDigit() || source[start - 1] in "_$")) start--
    return source.subSequence(start, offset).toString()
}

/** Reuses the platform's auto-popup setting and scheduler for Azora separators. */
class AzoraCompletionAutoPopup : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != AzoraLanguage) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        val trigger = when (c) {
            '.' -> text.getOrNull(offset - 2) != '.'
            ':' -> text.getOrNull(offset - 2) == ':'
            '@' -> true
            else -> false
        }
        if (trigger) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }
}

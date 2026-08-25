/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.lsp.AzoraLspService

/** Completion adapter over `textDocument/completion`. */
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
                    val file = parameters.originalFile.virtualFile ?: return
                    val document = parameters.editor.document
                    parameters.position.project.service<AzoraLspService>()
                        .complete(file, document, parameters.offset)
                        .forEach { item ->
                            var lookup = LookupElementBuilder.create(item.label)
                                .withTypeText(item.detail.orEmpty(), true)
                                .withPresentableText(item.label)
                            val inserted = item.insertText?.takeIf { it.isNotEmpty() } ?: item.label
                            val extraEdits = item.additionalTextEdits.orEmpty()
                            if (inserted != item.label || extraEdits.isNotEmpty()) {
                                lookup = lookup.withInsertHandler { insertion, _ ->
                                    val primaryStart = insertion.startOffset
                                    insertion.document.replaceString(
                                        primaryStart,
                                        insertion.tailOffset,
                                        inserted,
                                    )
                                    var shiftBeforeCaret = 0
                                    extraEdits.sortedByDescending { edit -> offset(insertion.document, edit.range.start) }
                                        .forEach { edit ->
                                            val start = offset(insertion.document, edit.range.start)
                                            val end = offset(insertion.document, edit.range.end)
                                            if (start in 0..end && end <= insertion.document.textLength) {
                                                insertion.document.replaceString(start, end, edit.newText)
                                                if (start <= primaryStart) shiftBeforeCaret += edit.newText.length - (end - start)
                                            }
                                        }
                                    insertion.editor.caretModel.moveToOffset(
                                        (primaryStart + inserted.length + shiftBeforeCaret)
                                            .coerceIn(0, insertion.document.textLength),
                                    )
                                }
                            }
                            result.addElement(lookup)
                        }
                }
            },
        )
    }


    private companion object {
        fun offset(document: com.intellij.openapi.editor.Document, position: org.eclipse.lsp4j.Position): Int {
            if (document.lineCount == 0) return 0
            val line = position.line.coerceIn(0, document.lineCount - 1)
            return (document.getLineStartOffset(line) + position.character)
                .coerceAtMost(document.getLineEndOffset(line))
        }
    }
}

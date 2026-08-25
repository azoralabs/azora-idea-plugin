/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.azora.lang.idea.AzoraLexerAdapter
import org.azora.lang.idea.highlighting.AzoraSemanticModel
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.highlighting.AzoraToken
import org.azora.lang.idea.symbol.AzoraProjectWords

/**
 * Editor-only inspections which are not compiler errors. Compiler diagnostics,
 * ownership/type decisions, and their fixes continue to come exclusively from
 * AZLS; this adapter preserves the mature unused-declaration inspection while
 * that lint is promoted into the server protocol.
 */
class AzoraEditorInspectionAnnotator :
    ExternalAnnotator<AzoraEditorInspectionInfo, List<AzoraDiagnostic>>() {

    override fun collectInformation(
        file: PsiFile,
        editor: Editor,
        hasErrors: Boolean,
    ): AzoraEditorInspectionInfo {
        val source = file.text
        val candidates = unused(source).mapTo(linkedSetOf()) { it.name }
        return AzoraEditorInspectionInfo(source, AzoraProjectWords.writtenOutside(file, candidates))
    }

    override fun doAnnotate(info: AzoraEditorInspectionInfo): List<AzoraDiagnostic> =
        unused(info.source).asSequence()
            // Local bindings are compiler diagnostics now, including their
            // version-checked rename fix. Keep this adapter for declarations
            // the compiler cannot yet classify across the workspace.
            .filterNot { it.kind == "Binding" }
            .filterNot { it.name in info.namedElsewhere }
            .take(MAX_DIAGNOSTICS)
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
            }.toList()

    override fun apply(file: PsiFile, result: List<AzoraDiagnostic>, holder: AnnotationHolder) {
        val length = file.textLength
        result.forEach { diagnostic ->
            val start = diagnostic.range.startOffset.coerceIn(0, length)
            val end = diagnostic.range.endOffset.coerceIn(start, length)
            if (end <= start) return@forEach
            var annotation = holder.newAnnotation(diagnostic.severity, diagnostic.message)
                .range(TextRange(start, end))
                .textAttributes(AzoraSyntaxHighlighter.WARNING)
            diagnostic.fixes.forEach { fix ->
                annotation = annotation.withFix(
                    AzoraReplacementFix(fix.title, fix.range ?: diagnostic.range, fix.replacement),
                )
            }
            annotation.create()
        }
    }

    private fun unused(source: String): List<AzoraSemanticModel.UnusedDeclaration> = runCatching {
        AzoraSemanticModel.unusedDeclarations(lex(source))
    }.getOrDefault(emptyList())

    private fun lex(source: String): List<AzoraToken> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        return buildList {
            while (lexer.tokenType != null) {
                add(AzoraToken(
                    lexer.tokenType!!,
                    lexer.tokenStart,
                    lexer.tokenEnd,
                    source.substring(lexer.tokenStart, lexer.tokenEnd),
                ))
                lexer.advance()
            }
        }
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 100
    }
}

data class AzoraEditorInspectionInfo(val source: String, val namedElsewhere: Set<String>)

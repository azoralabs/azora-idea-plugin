/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.lsp.AzoraLspService
import org.azora.lang.idea.lsp.LspSnapshot
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position

/** Renders compiler-owned diagnostics and fixes received from AZLS. */
class AzoraExternalAnnotator : ExternalAnnotator<AzoraLspAnnotationInfo, AzoraLspAnnotationInfo>() {
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): AzoraLspAnnotationInfo? {
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.extension.equals("az", true)) return null
        val snapshot = file.project.service<AzoraLspService>().snapshot(virtualFile, editor.document)
        return AzoraLspAnnotationInfo(editor.document, snapshot)
    }

    override fun doAnnotate(collectedInfo: AzoraLspAnnotationInfo): AzoraLspAnnotationInfo = collectedInfo

    override fun apply(file: PsiFile, result: AzoraLspAnnotationInfo, holder: AnnotationHolder) {
        for (diagnostic in result.snapshot.diagnostics) {
            val range = textRange(result.document, diagnostic) ?: continue
            val severity = when (diagnostic.severity) {
                DiagnosticSeverity.Error -> HighlightSeverity.ERROR
                DiagnosticSeverity.Warning -> HighlightSeverity.WARNING
                DiagnosticSeverity.Information -> HighlightSeverity.INFORMATION
                DiagnosticSeverity.Hint, null -> HighlightSeverity.WEAK_WARNING
            }
            var builder = holder.newAnnotation(severity, diagnostic.message).range(range)
            if (severity == HighlightSeverity.ERROR) builder = builder.textAttributes(AzoraSyntaxHighlighter.ERROR)
            if (severity == HighlightSeverity.WARNING) builder = builder.textAttributes(AzoraSyntaxHighlighter.WARNING)
            matchingActions(diagnostic, result.snapshot.actions).forEach { action ->
                builder = builder.withFix(AzoraLspCodeAction(action))
            }
            builder.create()
        }
    }

    internal fun matchingActions(diagnostic: Diagnostic, actions: List<CodeAction>): List<CodeAction> =
        actions.filter { action ->
            val attached = action.diagnostics.orEmpty()
            // Diagnostic quick fixes must be explicitly attached by AZLS.
            // A source-wide action with no diagnostics is not an intention for
            // every red underline in the file.
            attached.any {
                it.code == diagnostic.code && it.range == diagnostic.range && it.message == diagnostic.message
            }
        }

    private fun textRange(document: Document, diagnostic: Diagnostic): TextRange? {
        val start = offset(document, diagnostic.range.start)
        val end = offset(document, diagnostic.range.end).coerceAtLeast(start)
        if (start !in 0..document.textLength || end > document.textLength) return null
        return if (start == end && start < document.textLength) TextRange(start, start + 1) else TextRange(start, end)
    }

    private fun offset(document: Document, position: Position): Int {
        if (document.lineCount == 0) return 0
        val line = position.line.coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(line) + position.character)
            .coerceAtMost(document.getLineEndOffset(line))
    }
}

data class AzoraLspAnnotationInfo(val document: Document, val snapshot: LspSnapshot)

private class AzoraLspCodeAction(private val action: CodeAction) : IntentionAction {
    override fun getText(): String = action.title
    override fun getFamilyName(): String = "Azora"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file?.virtualFile?.extension.equals("az", true) && action.disabled == null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        project.service<AzoraLspService>().apply(action)
    }

    override fun startInWriteAction(): Boolean = false
}

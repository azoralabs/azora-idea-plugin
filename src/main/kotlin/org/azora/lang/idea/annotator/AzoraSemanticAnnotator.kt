/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.lsp.AzoraLspService

/** Applies semantic-token roles emitted by AZLS; no symbols are re-resolved here. */
class AzoraSemanticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return
        val file = element.containingFile?.virtualFile ?: return
        if (!file.extension.equals("az", true)) return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val leaf = element.textRange
        val service = element.project.service<AzoraLspService>()
        val diagnosticRanges = service.snapshot(file, document).diagnostics.mapNotNull { diagnostic ->
            lspRange(document, diagnostic.range.start.line, diagnostic.range.start.character,
                diagnostic.range.end.line, diagnostic.range.end.character)
        }
        service.tokens(file, document).forEach { token ->
            // Lexical colors remain the syntax highlighter's responsibility.
            // Semantic tokens refine identifier roles and must not repaint a
            // whole string/comment with a stale or partial semantic response.
            if (token.type in LEXICAL_TYPES || token.line !in 0 until document.lineCount) return@forEach
            val start = document.getLineStartOffset(token.line) + token.character
            val end = (start + token.length).coerceAtMost(document.textLength)
            val intersection = intersect(leaf, start, end) ?: return@forEach
            // Compiler diagnostics own their underline/effect. Applying a
            // semantic foreground key over the same range can cause IntelliJ
            // to discard the diagnostic effect depending on annotator order.
            // An unresolved name therefore keeps lexical styling until it is
            // imported and the compiler stops reporting it.
            if (diagnosticRanges.any { diagnostic -> overlaps(intersection, diagnostic) }) return@forEach
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(intersection)
                .textAttributes(key(token.type))
                .create()
        }
    }

    /** The part of one AZLS semantic span contained by this PSI leaf. */
    internal fun intersect(leaf: TextRange, tokenStart: Int, tokenEnd: Int): TextRange? {
        val start = maxOf(leaf.startOffset, tokenStart)
        val end = minOf(leaf.endOffset, tokenEnd)
        return if (end > start) TextRange(start, end) else null
    }

    internal fun overlaps(left: TextRange, right: TextRange): Boolean =
        left.startOffset < right.endOffset && right.startOffset < left.endOffset

    private fun lspRange(
        document: com.intellij.openapi.editor.Document,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
    ): TextRange? {
        if (startLine !in 0 until document.lineCount || endLine !in 0 until document.lineCount) return null
        val start = (document.getLineStartOffset(startLine) + startCharacter)
            .coerceAtMost(document.getLineEndOffset(startLine))
        val end = (document.getLineStartOffset(endLine) + endCharacter)
            .coerceAtMost(document.getLineEndOffset(endLine))
        return if (end > start) TextRange(start, end) else null
    }

    /**
     * The plugin is deliberately a renderer for AZLS roles. Keep this map
     * exhaustive: silently falling back to IDENTIFIER makes a server feature
     * disappear without an error, which is how receivers, generics and enum
     * cases all lost their established styles during the LSP migration.
     */
    internal fun key(type: String): TextAttributesKey = when (type) {
        "keyword" -> AzoraSyntaxHighlighter.KEYWORD
        "string" -> AzoraSyntaxHighlighter.STRING
        "number" -> AzoraSyntaxHighlighter.NUMBER
        "comment" -> AzoraSyntaxHighlighter.LINE_COMMENT
        "function" -> AzoraSyntaxHighlighter.FUNCTION_CALL
        "functionDeclaration" -> AzoraSyntaxHighlighter.FUNCTION_DECLARATION
        "specFunction" -> AzoraSyntaxHighlighter.SPEC_FUNCTION
        "overrideFunction" -> AzoraSyntaxHighlighter.OVERRIDE_FUNCTION
        "type", "associatedType" -> AzoraSyntaxHighlighter.TYPE_NAME
        "typeDeclaration" -> AzoraSyntaxHighlighter.TYPE_DECLARATION
        "specType" -> AzoraSyntaxHighlighter.SPEC_TYPE
        "generic" -> AzoraSyntaxHighlighter.TYPE_PARAMETER
        "parameter" -> AzoraSyntaxHighlighter.PARAMETER
        "contextParameter" -> AzoraSyntaxHighlighter.CONTEXT_PARAMETER
        "field" -> AzoraSyntaxHighlighter.FIELD
        "property" -> AzoraSyntaxHighlighter.PROPERTY
        "propertyDeclaration" -> AzoraSyntaxHighlighter.PROPERTY
        "specProperty" -> AzoraSyntaxHighlighter.SPEC_PROPERTY
        "overrideProperty" -> AzoraSyntaxHighlighter.OVERRIDE_PROPERTY
        "enumMember" -> AzoraSyntaxHighlighter.ENUM_CASE
        "errorMember" -> AzoraSyntaxHighlighter.ERROR_CASE
        "label" -> AzoraSyntaxHighlighter.LOOP_LABEL
        "scope" -> AzoraSyntaxHighlighter.ZONE_USAGE
        "modulePath" -> AzoraSyntaxHighlighter.MODULE_PATH
        "decorator", "annotation" -> AzoraSyntaxHighlighter.DECORATOR
        "macro" -> AzoraSyntaxHighlighter.MACRO
        "wildcard" -> AzoraSyntaxHighlighter.WILDCARD
        "macroHole" -> AzoraSyntaxHighlighter.MACRO_HOLE
        "smartCast" -> AzoraSyntaxHighlighter.SMART_CAST
        "deprecated" -> AzoraSyntaxHighlighter.DEPRECATED
        "doc" -> AzoraSyntaxHighlighter.DOC_COMMENT
        "docTag" -> AzoraSyntaxHighlighter.DOC_TAG
        "docTagValue" -> AzoraSyntaxHighlighter.DOC_TAG_VALUE
        "interpolation" -> AzoraSyntaxHighlighter.INTERPOLATION
        else -> AzoraSyntaxHighlighter.IDENTIFIER
    }

    private companion object {
        val LEXICAL_TYPES = setOf("keyword", "string", "number", "comment")
    }
}

/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import org.azora.lang.idea.lsp.AzoraLspService
import org.eclipse.lsp4j.Hover

/** Quick-documentation adapter over `textDocument/hover`. */
class AzoraDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        return hover(target)?.let(::render)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        return hover(target)?.let(::plain)
    }

    override fun generateRenderedDoc(comment: PsiDocCommentBase): String? {
        val text = comment.text.removePrefix("/**").removeSuffix("*/")
            .lines().joinToString("\n") { it.trim().removePrefix("*").trim() }.trim()
        if (text.isBlank()) return null
        val rendered = StringUtil.escapeXmlEntities(text)
            .replace(Regex("""@([A-Za-z][A-Za-z0-9_]*)""")) { "<b>@${it.groupValues[1]}</b>" }
            .replace("\n", "<br/>")
        return "<p>$rendered</p>"
    }

    private fun hover(target: PsiElement): Hover? {
        val file = target.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return target.project.service<AzoraLspService>().hover(file, document, target.textRange.startOffset)
    }

    private fun plain(hover: Hover): String = when {
        hover.contents.isRight -> hover.contents.right.value
        else -> hover.contents.left.joinToString("\n") { value ->
            if (value.isLeft) value.left else value.right.value
        }
    }

    private fun render(hover: Hover): String = buildString {
        append(DocumentationMarkup.CONTENT_START)
        append("<pre>")
        append(StringUtil.escapeXmlEntities(plain(hover)))
        append("</pre>")
        append(DocumentationMarkup.CONTENT_END)
    }
}

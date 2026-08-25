/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.azora.lang.idea.lsp.AzoraLspService
import java.net.URI

/** Navigation adapter over `textDocument/definition`. */
class AzoraGoToDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val activeEditor = editor ?: return null
        val sourceFile = element.containingFile?.virtualFile ?: return null
        val project = element.project
        val targets = project.service<AzoraLspService>()
            .definition(sourceFile, activeEditor.document, offset)
            .mapNotNull { location ->
                val targetFile = VirtualFileManager.getInstance().findFileByUrl(location.uri)
                    ?: runCatching { URI(location.uri) }.getOrNull()
                        ?.takeIf { it.scheme == "file" }
                        ?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Path.of(it)) }
                    ?: return@mapNotNull null
                val psi = PsiManager.getInstance(project).findFile(targetFile) ?: return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return@mapNotNull psi
                val line = location.range.start.line.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
                val targetOffset = (document.getLineStartOffset(line) + location.range.start.character)
                    .coerceIn(0, (document.textLength - 1).coerceAtLeast(0))
                psi.findElementAt(targetOffset) ?: psi
            }
            .distinct()
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}

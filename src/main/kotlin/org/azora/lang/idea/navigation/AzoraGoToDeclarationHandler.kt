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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.lsp.AzoraLspCodec
import org.azora.lang.idea.lsp.AzoraLspService
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService

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
        if (element.language != AzoraLanguage || element.node.elementType !in REFERENCE_TOKENS) return null
        val project = element.project
        val psiManager = PsiManager.getInstance(project)
        val source = activeEditor.document.text

        fun target(file: VirtualFile, start: Int): PsiElement? =
            psiManager.findFile(file)?.findElementAt(start)

        val resolver = AzoraResolver(project, AzoraSymbolService.getInstance(project))
        val reference = resolver.referenceAt(source, offset)
        if (reference?.isQualified == true) {
            val members = resolver.membersOfQualifier(reference.qualifier, sourceFile.path, source, offset)
                .filter { it.name == reference.name }
                .mapNotNull { symbol ->
                    val file = symbol.filePath?.let(LocalFileSystem.getInstance()::findFileByPath) ?: sourceFile
                    target(file, symbol.offset)
                }.distinct()
            if (members.isNotEmpty()) return members.toTypedArray()
        }
        val targets = project.service<AzoraLspService>()
            .definition(sourceFile, activeEditor.document, element.textRange.startOffset)
            .mapNotNull { location ->
                val file = AzoraLspCodec.findFile(location.uri) ?: return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                val position = location.range.start
                val start = AzoraLspCodec.offset(document, position) ?: return@mapNotNull null
                val namedOffset = if (position.character == 0) {
                    val end = document.getLineEndOffset(position.line)
                    val name = Regex.escape(reference?.name ?: element.text)
                    Regex("(?<![\\w$])$name(?![\\w$])").find(document.charsSequence.subSequence(start, end))
                        ?.range?.first?.plus(start)
                } else null
                target(file, namedOffset ?: start)
            }.distinct()
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        val REFERENCE_TOKENS = setOf(AzoraTokenTypes.IDENTIFIER, AzoraTokenTypes.TYPE_PARAMETER, AzoraTokenTypes.MACRO)
    }
}

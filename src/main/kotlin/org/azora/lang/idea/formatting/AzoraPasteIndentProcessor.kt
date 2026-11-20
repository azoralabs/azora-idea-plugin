/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.azora.lang.idea.AzoraLanguage
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

/**
 * IntelliJ's block indent needs the first line's original column even when the
 * selection starts at its first token and leaves its leading whitespace behind.
 */
internal class AzoraPasteIndentProcessor : CopyPastePostProcessor<PastedAzoraIndent>() {
    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray,
    ): List<PastedAzoraIndent> {
        if (file.language != AzoraLanguage || startOffsets.size != 1 || endOffsets.size != 1) return emptyList()
        val document = editor.document
        val text = document.immutableCharSequence
        val first = (startOffsets.single() until endOffsets.single()).firstOrNull { !text[it].isWhitespace() }
            ?: return emptyList()
        val line = document.getLineNumber(first)
        val start = document.getLineStartOffset(line)
        if ((start until first).any { !text[it].isIndentWhitespace() }) return emptyList()
        val tabSize = CodeStyle.getIndentOptions(file.project, document).TAB_SIZE.coerceAtLeast(1)
        return listOf(PastedAzoraIndent(visualIndent(text, start, first, tabSize), tabSize))
    }

    override fun extractTransferableData(content: Transferable): List<PastedAzoraIndent> = try {
        listOfNotNull(content.getTransferData(PastedAzoraIndent.FLAVOR) as? PastedAzoraIndent)
    } catch (_: UnsupportedFlavorException) {
        emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<PastedAzoraIndent>,
    ) {
        if (CodeInsightSettings.getInstance().REFORMAT_ON_PASTE != CodeInsightSettings.INDENT_BLOCK ||
            indented.get() == true || !bounds.isValid
        ) return
        val document = editor.document
        if (PsiDocumentManager.getInstance(project).getPsiFile(document)?.language != AzoraLanguage) return
        val origin = values.singleOrNull() ?: return
        val source = document.immutableCharSequence
        val start = bounds.startOffset
        val end = bounds.endOffset
        if (source.subSequence(start, end).none { it == '\n' }) return
        val first = (start until end).firstOrNull { !source[it].isWhitespace() } ?: return
        val firstLine = document.getLineNumber(first)
        val lastLine = document.getLineNumber(end - 1)
        val lineStart = document.getLineStartOffset(firstLine)
        if ((lineStart until first).any { !source[it].isIndentWhitespace() }) return

        val options = CodeStyle.getIndentOptions(project, document)
        val indentation = AzoraIndentation(source, options, lastLine)
        val destination = indentation.widthAtLine(firstLine) ?: return
        val delta = destination - origin.width

        WriteAction.run<RuntimeException> {
            // Walk backwards so changing one line's whitespace leaves earlier
            // offsets valid. Raw strings and block comment contents are data.
            for (line in lastLine downTo firstLine) {
                if (indentation.widthAtLine(line) == null) continue
                val from = document.getLineStartOffset(line)
                val limit = minOf(document.getLineEndOffset(line), end)
                var to = from
                while (to < limit && source[to].isIndentWhitespace()) to++
                if (to == limit) continue
                val width = if (line == firstLine) destination else
                    (visualIndent(source, from, to, origin.tabSize) + delta).coerceAtLeast(0)
                val replacement = azoraIndentText(width, options)
                if (source.subSequence(from, to).toString() != replacement) {
                    document.replaceString(from, to, replacement)
                }
            }
        }
        indented.set(true)
    }

    private fun visualIndent(text: CharSequence, start: Int, end: Int, tabSize: Int): Int {
        var width = 0
        for (offset in start until end) {
            width += if (text[offset] == '\t') tabSize - width % tabSize else 1
        }
        return width
    }

    private fun Char.isIndentWhitespace() = this == ' ' || this == '\t'
}

internal data class PastedAzoraIndent(val width: Int, val tabSize: Int) : TextBlockTransferableData {
    override fun getFlavor(): DataFlavor = FLAVOR
    override fun getOffsetCount(): Int = 0
    override fun getOffsets(offsets: IntArray, index: Int): Int = index
    override fun setOffsets(offsets: IntArray, index: Int): Int = index

    companion object {
        val FLAVOR = DataFlavor(
            "${DataFlavor.javaJVMLocalObjectMimeType};class=${PastedAzoraIndent::class.java.name}",
            "Azora block indentation",
            PastedAzoraIndent::class.java.classLoader,
        )
    }
}

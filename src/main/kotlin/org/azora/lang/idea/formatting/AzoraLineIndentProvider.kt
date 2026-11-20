/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import org.azora.lang.idea.AzoraLanguage

class AzoraLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language?.isKindOf(AzoraLanguage) == true

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) return null
        val document = editor.document
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val options = CodeStyle.getIndentOptions(project, document)
        val width = AzoraIndentation(document.immutableCharSequence, options, line).widthAtLine(line) ?: return null
        if (!options.USE_TAB_CHARACTER) return " ".repeat(width)
        val tabSize = options.TAB_SIZE.coerceAtLeast(1)
        return "\t".repeat(width / tabSize) + " ".repeat(width % tabSize)
    }
}

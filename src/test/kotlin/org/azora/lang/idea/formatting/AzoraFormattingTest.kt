/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.azora.lang.idea.AzoraFileType

class AzoraFormattingTest : BasePlatformTestCase() {
    fun testEnterIndentsWhenArmAndNestedCall() {
        myFixture.configureByText(AzoraFileType.INSTANCE, """
            func show() {
                fin tint = when studio.statusKind {
                    3 -> theme(
                        studioTheme.error,<caret>
        """.trimIndent())

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("""
            func show() {
                fin tint = when studio.statusKind {
                    3 -> theme(
                        studioTheme.error,
                        <caret>
        """.trimIndent())
    }

    fun testReformatIndentsContinuationsAndPreservesRawStringContent() {
        val file = myFixture.configureByText(AzoraFileType.INSTANCE, listOf(
            "func show() {",
            "fin text = \"\"\"",
            "  preserve indentation {",
            "\"\"\"",
            "/* Do not count ] or } here. */",
            "fin tint = when studio.statusKind {",
            "3 ->",
            "studioTheme.error",
            "else -> studioTheme.faint",
            "}",
            "using (",
            "studioTheme,",
            "other",
            ") {",
            "showColor(tint)",
            "}",
            "}",
        ).joinToString("\n"))

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }

        assertEquals(listOf(
            "func show() {",
            "    fin text = \"\"\"",
            "  preserve indentation {",
            "\"\"\"",
            "    /* Do not count ] or } here. */",
            "    fin tint = when studio.statusKind {",
            "        3 ->",
            "            studioTheme.error",
            "        else -> studioTheme.faint",
            "    }",
            "    using (",
            "        studioTheme,",
            "        other",
            "    ) {",
            "        showColor(tint)",
            "    }",
            "}",
        ).joinToString("\n"), file.text)
    }
}

/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.codeInsight.CodeInsightSettings
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

    fun testReformatMultilineUiCall() {
        val file = myFixture.configureByText(AzoraFileType.INSTANCE, uiCall.lineSequence().joinToString("\n") { it.trimStart() })
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        assertEquals(uiCall, file.text)
    }

    fun testClosingWhenThenEnteringNextNamedArgument() {
        val before = uiCall.substringBefore("    action: 31").removeSuffix("    }\n")
        myFixture.configureByText(AzoraFileType.INSTANCE, before + "        <caret>")

        myFixture.type('}')
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult(uiCall.substringBefore("action: 31") + "<caret>")
    }

    fun testEnterBetweenWhenBracesInNamedArgument() {
        myFixture.configureByText(AzoraFileType.INSTANCE, "Button(\n    modifier: when {<caret>}\n)")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        myFixture.checkResult("Button(\n    modifier: when {\n        <caret>\n    }\n)")
    }

    fun testEnterIndentsNewLineInModifierChain() {
        myFixture.configureByText(
            AzoraFileType.INSTANCE,
            "Button(\n    modifier: when {\n        active -> ::width(110.0)<caret>.height(26.0)\n    }\n)",
        )
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        myFixture.checkResult(
            "Button(\n    modifier: when {\n        active -> ::width(110.0)\n            <caret>.height(26.0)\n    }\n)",
        )
    }

    fun testReformatAlignsChainedCallsAndVariantArms() {
        val expected = """
            Button(
                modifier: when kind {
                    .Active ->
                        ::width(110.0)
                            .padding(
                                6.0
                            )
                            .raised()
                    .Inactive(
                        value
                    ) -> ::width(110.0)
                        .ghost()
                }
            )
        """.trimIndent()
        val file = myFixture.configureByText(
            AzoraFileType.INSTANCE,
            expected.lineSequence().joinToString("\n") { it.trimStart() },
        )
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        assertEquals(expected, file.text)
    }

    fun testCutAndPasteBlockPreservesRawStringAndManualAlignment() {
        val settings = CodeInsightSettings.getInstance()
        val originalMode = settings.REFORMAT_ON_PASTE
        try {
            settings.REFORMAT_ON_PASTE = CodeInsightSettings.INDENT_BLOCK
            val input = listOf(
                "func source() {",
                "    Column {",
                "        <selection>Button(",
                "                modifier: customAlignment",
                "            text: \"\"\"",
                "  literal indentation stays",
                "\"\"\"",
                "        )",
                "</selection>    }",
                "}",
                "",
                "func destination() {",
                "    Spacer()",
                "}",
            )
            myFixture.configureByText(AzoraFileType.INSTANCE, input.joinToString("\n"))
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_CUT)
            val document = myFixture.editor.document
            myFixture.editor.caretModel.moveToOffset(document.text.indexOf("    Spacer()"))
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
            assertEquals(listOf(
                "func destination() {",
                "    Button(",
                "            modifier: customAlignment",
                "        text: \"\"\"",
                "  literal indentation stays",
                "\"\"\"",
                "    )",
                "    Spacer()",
                "}",
            ).joinToString("\n"), document.text.substringAfter("\n\n"))
        } finally {
            settings.REFORMAT_ON_PASTE = originalMode
        }
    }

    fun testCutAndPasteUiCallWithIndentEachLine() {
        checkMoveUiCall(CodeInsightSettings.INDENT_EACH_LINE)
    }

    fun testCutAndPasteUiCallWithIndentBlock() {
        checkMoveUiCall(CodeInsightSettings.INDENT_BLOCK)
    }

    fun testCutAndPasteUiCallWithReformatBlock() {
        checkMoveUiCall(CodeInsightSettings.REFORMAT_BLOCK)
    }

    private fun checkMoveUiCall(pasteMode: Int) {
        val settings = CodeInsightSettings.getInstance()
        val originalMode = settings.REFORMAT_ON_PASTE
        try {
            settings.REFORMAT_ON_PASTE = pasteMode
            myFixture.configureByText(
                AzoraFileType.INSTANCE,
                "func source() {\n    <selection>" + uiCall.prependIndent("    ").trimStart() +
                    "\n</selection>}\n\nfunc destination() {\n    Column {\n        Spacer()\n    }\n}",
            )
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_CUT)
            // Place at column zero, as when pasting before an existing line.
            val document = myFixture.editor.document
            assertFalse(document.text.contains("Button("))
            val destination = document.text.indexOf("        Spacer()")
            assertTrue(destination >= 0)
            myFixture.editor.caretModel.moveToOffset(destination)
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
            assertEquals(
                "func destination() {\n    Column {\n" +
                    uiCall.prependIndent("        ") +
                    "\n        Spacer()\n    }\n}",
                document.text.substringAfter("\n\n"),
            )
        } finally {
            settings.REFORMAT_ON_PASTE = originalMode
        }
    }

    private val uiCall = """
        Button(
            modifier: when {
                view.dockTab == 1 -> ::width(110.0).height(26.0).rounded(6.0).raised()
                else -> ::width(110.0).height(26.0).rounded(6.0).ghost()
            }
            action: 31
            key: "problems-tab"
        ) {
            Text(
                problemsLabel
                modifier: when {
                    view.errorCount() > 0 -> ::fontSize(12.0).textColor(0.95, 0.45, 0.40)
                    view.dockTab == 1 -> ::fontSize(12.0)
                    else -> ::fontSize(12.0).mutedText()
                }
            )
        }
    """.trimIndent()
}

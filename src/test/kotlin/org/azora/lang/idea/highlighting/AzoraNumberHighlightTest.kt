/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import org.azora.lang.idea.AzoraTokenTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AzoraNumberHighlightTest {
    @Test
    fun `integer and floating point literals use pastel cyan`() {
        val highlighter = AzoraSyntaxHighlighter()
        val integerKeys: Array<TextAttributesKey> = highlighter.getTokenHighlights(AzoraTokenTypes.INT_LITERAL)
        assertEquals(1, integerKeys.size)
        assertSame(AzoraSyntaxHighlighter.NUMBER, integerKeys.single())
        assertEquals(
            AzoraPalette.NUMBER,
            AzoraSyntaxHighlighter.NUMBER.defaultAttributes.foregroundColor,
        )

        val realKeys: Array<TextAttributesKey> = highlighter.getTokenHighlights(AzoraTokenTypes.REAL_LITERAL)
        assertEquals(1, realKeys.size)
        assertSame(AzoraSyntaxHighlighter.REAL_NUMBER, realKeys.single())
        assertEquals(
            AzoraPalette.NUMBER,
            AzoraSyntaxHighlighter.REAL_NUMBER.defaultAttributes.foregroundColor,
        )
    }
}

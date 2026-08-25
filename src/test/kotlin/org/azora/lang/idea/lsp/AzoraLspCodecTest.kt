/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.lsp

import com.intellij.openapi.util.TextRange
import org.azora.lang.idea.annotator.AzoraExternalAnnotator
import org.azora.lang.idea.annotator.AzoraSemanticAnnotator
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AzoraLspCodecTest {
    @Test
    fun `semantic token deltas become absolute positions`() {
        val tokens = AzoraLspCodec.decodeSemanticTokens(
            listOf(
                2, 4, 8, 5, 0,
                0, 11, 4, 10, 0,
                1, 3, 5, 4, 0,
            ),
        )

        assertEquals(LspSemanticToken(2, 4, 8, "type"), tokens[0])
        assertEquals(LspSemanticToken(2, 15, 4, "associatedType"), tokens[1])
        assertEquals(LspSemanticToken(3, 3, 5, "function"), tokens[2])
    }

    @Test
    fun `new architecture preserves every styled semantic role`() {
        val tokens = AzoraLspCodec.decodeSemanticTokens(
            listOf(
                0, 0, 1, 11, 0,
                0, 2, 4, 12, 0,
                0, 5, 4, 14, 0,
                0, 5, 4, 18, 0,
            ),
        )
        assertEquals(listOf("generic", "contextParameter", "enumMember", "modulePath"), tokens.map { it.type })

        val annotator = AzoraSemanticAnnotator()
        val mappings = mapOf(
            "generic" to AzoraSyntaxHighlighter.TYPE_PARAMETER,
            "contextParameter" to AzoraSyntaxHighlighter.CONTEXT_PARAMETER,
            "enumMember" to AzoraSyntaxHighlighter.ENUM_CASE,
            "errorMember" to AzoraSyntaxHighlighter.ERROR_CASE,
            "property" to AzoraSyntaxHighlighter.PROPERTY,
            "modulePath" to AzoraSyntaxHighlighter.MODULE_PATH,
            "decorator" to AzoraSyntaxHighlighter.DECORATOR,
            "associatedType" to AzoraSyntaxHighlighter.TYPE_NAME,
        )
        mappings.forEach { (role, expected) ->
            assertEquals(expected, annotator.key(role), role)
            assertNotEquals(AzoraSyntaxHighlighter.IDENTIFIER, annotator.key(role), role)
        }
    }

    @Test
    fun `semantic decorator span paints every intersecting PSI leaf`() {
        val annotator = AzoraSemanticAnnotator()

        assertEquals(TextRange(0, 1), annotator.intersect(TextRange(0, 1), 0, 8))
        assertEquals(TextRange(1, 8), annotator.intersect(TextRange(1, 8), 0, 8))
        assertNull(annotator.intersect(TextRange(9, 12), 0, 8))
        assertTrue(annotator.overlaps(TextRange(1, 8), TextRange(3, 6)))
        assertFalse(annotator.overlaps(TextRange(1, 8), TextRange(8, 10)))
    }

    @Test
    fun `each underline receives only its explicitly attached import action`() {
        fun diagnostic(start: Int, end: Int, message: String) = Diagnostic().apply {
            range = Range(Position(0, start), Position(0, end))
            code = Either.forLeft("AZ-SYM-0001")
            this.message = message
        }
        fun action(title: String, diagnostic: Diagnostic?) = CodeAction(title).apply {
            diagnostics = diagnostic?.let(::listOf)
        }

        val equal = diagnostic(44, 49, "undefined spec or decorator 'Equal'")
        val order = diagnostic(51, 56, "undefined spec or decorator 'Order'")
        val actions = listOf(
            action("Import 'Equal' from 'std.traits'", equal),
            action("Import 'Order' from 'std.traits'", order),
            action("Source-wide action", null),
        )

        assertEquals(
            listOf("Import 'Order' from 'std.traits'"),
            AzoraExternalAnnotator().matchingActions(order, actions).map(CodeAction::getTitle),
        )
    }
}

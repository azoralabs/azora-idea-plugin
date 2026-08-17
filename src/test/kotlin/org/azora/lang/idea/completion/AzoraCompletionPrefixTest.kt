/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.completion

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regressions for symbol completion matching. */
class AzoraCompletionPrefixTest {

    @Test
    fun `Anchor does not suffix-match TilemapAnchor`() {
        val matcher = PlainPrefixMatcher("Anchor", true)

        assertTrue(matcher.prefixMatches("Anchor"))
        assertFalse(matcher.prefixMatches("TilemapAnchor"))
    }
}

/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/** Data shapes retained for the lexical scanner and its unit tests. */
data class AzoraFix(val title: String, val replacement: String?, val range: TextRange? = null)

data class AzoraDiagnostic(
    val range: TextRange,
    val message: String,
    val severity: HighlightSeverity,
    val fixes: List<AzoraFix> = emptyList(),
) {
    fun lineIn(source: String): Int =
        source.take(range.startOffset.coerceIn(0, source.length)).count { it == '\n' } + 1
}

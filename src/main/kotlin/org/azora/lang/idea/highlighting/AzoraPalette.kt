/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.idea.highlighting

import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * The official Azora editor palette, shared with the Azora code playground
 * (`azora-lang-code-website/src/codemirror/azora-theme.js`) so a snippet looks
 * the same in the browser and in the IDE.
 *
 * Every entry carries a dark tone (the playground's own value) and a light
 * counterpart of the same hue, wrapped in a [JBColor] so a single key definition
 * renders correctly under both IDE themes. These are *defaults*: users can
 * override any of them under **Editor | Color Scheme | Azora**.
 */
object AzoraPalette {

    // ── Brand ──────────────────────────────────────────────────────────

    /** Azora primary (`--color-az-primary`). */
    val PRIMARY = pair(0x9B22B4, 0xD14EEA)

    /** Azora secondary (`--color-az-secondary`). */
    val SECONDARY = pair(0x2A6FC4, 0x4E93EA)

    // ── Syntax ─────────────────────────────────────────────────────────

    /** Keywords — pastel pink, bold. */
    val KEYWORD = pair(0xA8305A, 0xD16B8E)

    /** Macros (prefix, infix and type) — pastel purple, bold. */
    val MACRO = pair(0x8A3F86, 0xB06FA8)

    /** Types, packs, specs, enums, zones — pastel teal. */
    val TYPE = pair(0x2F6F68, 0x5FA89F)

    /** Decorators and decorator declarations — pastel yellow. */
    val DECORATOR = pair(0x8A6A0A, 0xE6C96B)

    /** Strings and char literals — pastel green. */
    val STRING = pair(0x3C7C4C, 0x7DBF8A)

    /** Escape sequences inside literals — brand secondary. */
    val STRING_ESCAPE = pair(0x2A6FC4, 0x4E93EA)

    /** Comments — neutral grey, italic. */
    val COMMENT = pair(0x8A8A8A, 0x676767)

    /** Ordinary identifiers, numbers, operators and punctuation. */
    val FOREGROUND = pair(0x262626, 0xD9DADA)

    /** Functions use the ordinary foreground; callability comes from context, not hue. */
    val FUNCTION = FOREGROUND

    /** Unused declarations and parameters. */
    val UNUSED = pair(0x6D6D6D, 0xB8B8B8)

    /** Generic type parameters — pastel orange. */
    val TYPE_PARAMETER = pair(0xA65310, 0xE8944A)

    /** Loop labels and their jump targets — blue. */
    val LABEL = pair(0x2A6FC4, 0x4E93EA)

    /** Invalid / unrecognized input. */
    val INVALID = pair(0xC1121F, 0xE63946)

    // ── Diagnostics ────────────────────────────────────────────────────

    /** Error underline colour (`.cm-lintRange-error`). */
    val ERROR = pair(0xD32F45, 0xFF667A)

    /** Warning underline colour (`.cm-lintRange-warning`). */
    val WARNING = pair(0x9A7A12, 0xE6C96B)

    /** Smart-cast background wash — a faint tint of the secondary accent. */
    val SMART_CAST_BACKGROUND = pair(0xE3EDFA, 0x22303F)

    // ── Attribute builders ─────────────────────────────────────────────

    /** Plain foreground colour. */
    fun fg(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.PLAIN)

    /** Bold foreground colour, as the playground styles keywords and macros. */
    fun bold(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.BOLD)

    /** Italic foreground colour, as the playground styles comments. */
    fun italic(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.ITALIC)

    /** Foreground colour with a solid underline. */
    fun underlined(color: JBColor, style: Int = Font.PLAIN): TextAttributes =
        TextAttributes(color, null, color, EffectType.LINE_UNDERSCORE, style)

    /** Italic foreground with a solid underline. */
    fun italicUnderlined(color: JBColor): TextAttributes =
        underlined(color, Font.ITALIC)

    /** Foreground colour with a wavy underline, used for diagnostics. */
    fun wavy(color: JBColor): TextAttributes =
        TextAttributes(null, null, color, EffectType.WAVE_UNDERSCORE, Font.PLAIN)

    /** A background wash with no foreground override. */
    fun background(color: JBColor): TextAttributes =
        TextAttributes(null, color, null, null, Font.PLAIN)

    private fun pair(light: Int, dark: Int) = JBColor(Color(light), Color(dark))
}

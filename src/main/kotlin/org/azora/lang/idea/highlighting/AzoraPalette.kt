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
 *
 * **A syntax color is a design token, not a judgment.** The dark tone of an
 * entry that colors code is `--color-pastel-*` from
 * `azora-lang-code-website/src/index.css` verbatim; only the light counterparts
 * are computed, by holding the hue and dropping the lightness. Where an editor
 * role has no token of its own — a macro hole, a receiver, a doc comment — the
 * tone is derived from the nearest token and says so. Nothing here is picked by
 * eye: lightening a pastel "so calls read brighter" is what put a blue in the
 * editor that no other Azora surface uses.
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

    /**
     * Macro holes (`$items`, `$key`) — dark gold, derived from `--color-pastel-yellow`.
     *
     * Deeper than [DECORATOR]'s pastel yellow, which it sits beside in hue: a
     * hole is a *slot* in a pattern rather than a name, and reads as the one
     * thing in a macro body that will not be there after expansion.
     */
    val MACRO_HOLE = pair(0x7A5C08, 0xC49A2E)

    /** Strings and char literals — pastel green. */
    val STRING = pair(0x3C7C4C, 0x7DBF8A)

    /**
     * Integer and floating-point literals — pastel cyan.
     *
     * Cyan sits halfway between the teal used for types and the blue used for
     * functions. Both theme tones retain the same 188° hue and 45% saturation;
     * only lightness changes (36% on a light editor, 59% on a dark editor), so
     * `1`, `0xFF`, `0b1010`, `3.14`, and `1.5e3` remain calm rather than
     * turning neon.
     */
    val NUMBER = pair(0x327A85, 0x67B9C5)

    /** Escape sequences inside literals — brand secondary. */
    val STRING_ESCAPE = pair(0x2A6FC4, 0x4E93EA)

    /** Comments — neutral gray, italic. */
    val COMMENT = pair(0x8A8A8A, 0x676767)

    /**
     * Documentation comments — `--color-pastel-green` taken down a step.
     *
     * A doc comment is prose about the code rather than code, so it sits below
     * the literals it shares a hue with: darker in the light theme, less bright
     * in the dark one. Its tags read at full [STRING] strength, which is what
     * makes `@param` stand out from the surrounding sentence.
     */
    val DOC = pair(0x2F6138, 0x5E9068)

    /** The name a doc tag documents (`capacity` in `@param capacity`) — `--color-az-35`. */
    val DOC_TAG_VALUE = pair(0x777777, 0xC4C4C4)

    /** Ordinary identifiers, operators and punctuation. */
    val FOREGROUND = pair(0x262626, 0xD9DADA)

    /**
     * Functions, declared and called — `--color-pastel-blue`.
     *
     * The syntax colors come from the pastel family and nowhere else; this one
     * was briefly lightened to `#7FBBEF` to sit above [SECONDARY], which put a
     * blue in the editor that the design system does not contain. [LABEL] keeps
     * the brand blue instead, so the two stay apart without inventing a tone.
     */
    val FUNCTION = pair(0x2E6FA8, 0x5BA3D0)

    /**
     * Computed properties, declared and read — [FOREGROUND], worn italic.
     *
     * It was briefly taken a step brighter than [FOREGROUND] on the reasoning
     * that a member should read above a local, which only made `prop` names the
     * whitest thing on the line. A property is a name like any other, so it
     * takes the ordinary identifier tone; the italic and the underline are what
     * say which kind of name it is.
     */
    val PROPERTY = FOREGROUND

    /**
     * Function and constructor parameters — a light gray-blue.
     *
     * They wore the gold [MACRO_HOLE] wears, which read as a second kind of
     * hole in every signature and left the gold saying two things. A parameter
     * is a value the call site hands over: it sits a step off [FOREGROUND],
     * cool rather than warm, and takes no color any other role needs.
     */
    val PARAMETER = pair(0x4B5A6A, 0xA9B7C6)

    /**
     * Context receiver parameters — a light orange.
     *
     * The warm counterpart of [PARAMETER], and made the same way: hold
     * [TYPE_PARAMETER]'s hue, lift the lightness and take most of the chroma
     * out. A receiver and a parameter are both values a call site hands over,
     * so they read as the same kind of thing in two temperatures — and the
     * receiver stays the cooler-headed relative of the `T` it so often carries.
     */
    val CONTEXT_PARAMETER = pair(0x80603F, 0xDAC2A9)

    /**
     * Generic type parameters — `--color-pastel-orange`, bold.
     *
     * The token verbatim, and the same one the playground already spends on
     * `.cm-azls-generic`, so a `T` is the same color in the browser and in the
     * IDE. It wore the [MACRO] purple for a while on the reasoning that a `T`
     * and a macro are both stand-ins; that made every signature read as though
     * it were half macro, and left the purple saying two things.
     */
    val TYPE_PARAMETER = pair(0xA36C32, 0xD4A574)

    /**
     * The cases of an `error` declaration — a red of its own.
     *
     * Redder than the pastel red it started from. A case of an `error` is the
     * one name in a declaration that says something went wrong, and the muted
     * tone left it reading as a variant like any other. [ERROR]'s brighter red
     * still separates a diagnostic that has to shout from a case that is
     * simply named.
     */
    val ERROR_CASE = pair(0xA81F29, 0xE05C63)

    /**
     * Loop labels and their jump targets — the [SECONDARY] brand blue.
     *
     * A label is a mark *about* the code rather than code, which is what it
     * shares with a `${…}` hole and a string escape: all three wear the brand
     * blue, leaving the pastel blue to [FUNCTION] alone.
     */
    val LABEL = SECONDARY

    /** Invalid / unrecognized input. */
    val INVALID = pair(0xC1121F, 0xE63946)

    // ── Diagnostics ────────────────────────────────────────────────────

    /** Error underline color (`.cm-lintRange-error`). */
    val ERROR = pair(0xD32F45, 0xFF667A)

    /** Warning underline color (`.cm-lintRange-warning`). */
    val WARNING = pair(0x9A7A12, 0xE6C96B)

    /** Smart-cast background wash — a faint tint of the secondary accent. */
    val SMART_CAST_BACKGROUND = pair(0xE3EDFA, 0x22303F)

    // ── Attribute builders ─────────────────────────────────────────────

    /** Plain foreground color. */
    fun fg(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.PLAIN)

    /** Bold foreground color, as the playground styles keywords and macros. */
    fun bold(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.BOLD)

    /** Italic foreground color, as the playground styles comments. */
    fun italic(color: JBColor): TextAttributes =
        TextAttributes(color, null, null, null, Font.ITALIC)

    /** Foreground color with a solid underline. */
    fun underlined(color: JBColor, style: Int = Font.PLAIN): TextAttributes =
        TextAttributes(color, null, color, EffectType.LINE_UNDERSCORE, style)

    /** Italic foreground with a solid underline. */
    fun italicUnderlined(color: JBColor): TextAttributes =
        underlined(color, Font.ITALIC)

    /**
     * A strike through the text, and nothing else.
     *
     * No foreground: this is layered *over* whatever color the name already
     * has, so a deprecated `func` stays function-blue and reads as struck
     * through. Giving it a color of its own would say "deprecated" twice and
     * lose what the name is.
     */
    fun struckThrough(): TextAttributes =
        TextAttributes(null, null, null, EffectType.STRIKEOUT, Font.PLAIN)

    /** Foreground color with a wavy underline, used for diagnostics. */
    fun wavy(color: JBColor): TextAttributes =
        TextAttributes(null, null, color, EffectType.WAVE_UNDERSCORE, Font.PLAIN)

    /** A background wash with no foreground override. */
    fun background(color: JBColor): TextAttributes =
        TextAttributes(null, color, null, null, Font.PLAIN)

    private fun pair(light: Int, dark: Int) = JBColor(Color(light), Color(dark))
}

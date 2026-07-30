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

package org.azora.lang.idea

/**
 * The lexical vocabulary of the Azora language, mirroring the compiler's
 * `frontend/Lexer.kt` keyword table for language version [LANGUAGE_VERSION].
 *
 * Only *keywords* live here. Everything else the plugin knows about — the
 * standard library, macros, user types — is indexed from real sources at
 * runtime, never hardcoded.
 */
object AzoraLanguageFacts {

    /** The Azora language version this plugin's lexical model tracks. */
    const val LANGUAGE_VERSION = "0.0.4"

    val declarationKeywords = setOf(
        "var", "fin", "let", "func", "task", "flow", "test",
        "enum", "slot", "pack", "impl", "spec", "type", "typealias",
        "prop", "oper", "ctor", "dtor", "deco", "meta", "infx",
        "zone", "friend", "module", "export", "import", "use",
        "solo", "wrap", "bridge", "fail", "threadlocal",
    )

    val controlKeywords = setOf(
        "if", "else", "for", "loop", "while", "when",
        "in", "by", "reverse", "with",
        "return", "break", "continue", "try", "catch", "throw", "defer",
        "guard", "is", "as", "await", "launch", "yield",
        "flip", "flop", "assert", "trace", "panic", "rescue",
    )

    val modifierKeywords = setOf(
        "expose", "confine", "protect", "protected", "shield", "opaque",
        "inline", "deepinline", "noinline",
        "isolated", "bind", "inject", "unsafe", "out",
    )

    val memoryKeywords = setOf(
        "alloc", "drop", "deref", "unsafe",
    )

    val reactiveKeywords = setOf(
        "mem", "rem", "ret", "effect",
    )

    val literalKeywords = setOf("true", "false", "null")

    /**
     * Implicit receiver parameters. They remain identifiers in the lexer and
     * are styled semantically as parameters only where they are used.
     */
    val implicitParameters = setOf("self", "it")

    val hardKeywords = declarationKeywords + controlKeywords + modifierKeywords +
        memoryKeywords + reactiveKeywords + literalKeywords

    /**
     * Words that are keywords only in some positions and ordinary identifiers
     * elsewhere. `where` is not in the compiler's keyword table at all — the
     * parser matches it contextually — so it must never be colored blindly.
     */
    val softKeywords = setOf("where")

    val allCompletionKeywords = (hardKeywords + softKeywords).sorted()

    /**
     * Compiler-provided (`bridge deco`) decorators. These come from the
     * compiler itself rather than any `.az` source, so they are the one thing
     * that legitimately lives in this table.
     */
    val builtinAnnotations = listOf(
        BuiltinAnnotation("Stable", "Marks an API as stable from a version onward.", """@Stable(sinceAzora: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("Experimental", "Marks an API as experimental.", """@Experimental(sinceAzora: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("SinceAzora", "Records the first version containing a declaration.", """@SinceAzora(version: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("Deprecated", "Marks an API as deprecated.", """@Deprecated(replacement: "…")"""),
        BuiltinAnnotation("Derive", "Connects a library decorator to a compiler derive generator.", """@Derive(generator: "serializer", role: "all")"""),
        BuiltinAnnotation("EnforceNumFields", "Allows generated packs to declare numeric field names.", "@EnforceNumFields"),
        BuiltinAnnotation("Reactive", "Marks a function/task/infix as reactive.", "@Reactive"),
        BuiltinAnnotation("UncheckedCast", "Suppresses cast checks in the annotated declaration.", "@UncheckedCast"),
    )

    // NOTE: the standard library is not hardcoded. Module and symbol
    // information is indexed from the installed SDK's real `.az` sources by
    // `AzoraSymbolService`, so completion, navigation, and hover always
    // reflect the installed stdlib. Macros are likewise discovered from real
    // `meta` declarations in the project and SDK — see `AzoraMacroIndex`.
}

data class BuiltinAnnotation(
    val name: String,
    val description: String,
    val insertText: String,
)

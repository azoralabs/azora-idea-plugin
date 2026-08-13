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
    const val LANGUAGE_VERSION = "0.0.5"

    /** Reserved declaration, binding, and namespace words in the current lexer. */
    val declarationKeywords = setOf(
        "var", "val", "let", "fin", "func", "pack", "enum", "variant", "impl", "spec", "typealias",
        "prop", "ctor", "dtor", "oper", "annot", "bind", "bridge", "solo",
        "graph", "inject", "import", "use", "export", "realm", "zone", "scope", "macro", "test",
        "error", "type", "wrap", "threadlocal", "friend", "meta", "infx",
        // Compatibility spellings still present in installed 0.0.x SDKs.
        "task", "flow", "slot", "fail", "deco",
    )

    /** Reserved words that introduce expressions, control flow, or contracts. */
    val controlKeywords = setOf(
        "return", "if", "else", "for", "while", "loop", "in", "by", "reverse",
        "break", "continue", "when", "throw", "try", "catch", "rescue", "defer",
        "await", "delay", "as", "is", "with", "assert", "trace", "panic",
        "launch", "yield",
    )

    /** Reserved words that alter declaration visibility or evaluation. */
    val modifierKeywords = setOf(
        "expose", "exposed", "protect", "protected", "confine", "confined",
        "inline", "deepinline", "noinline",
        "threadlocal", "lazy", "factory", "derive", "out", "react",
    )

    /** Reserved words for allocation, ownership, and unsafe operations. */
    val memoryKeywords = setOf(
        "alloc", "purge", "take", "unsafe", "scope", "drop", "deref",
    )

    /** Reserved words for reactive state and effects. */
    val reactiveKeywords = setOf(
        "mem", "rem", "ret", "remember", "retain", "preserve", "effect",
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
     * Contextual words from the compiler vocabulary. The lexer colors these
     * only where the grammar gives them keyword meaning; elsewhere they remain
     * ordinary identifiers so names such as `Set.union` continue to work.
     */
    val softKeywords = setOf(
        "mod", "module", "union", "async", "where", "without", "replace", "escaping",
        "derives", "includes", "binds", "requires", "lend", "reflect",
    )

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
    // `meta`, `infx`, and `macro` declarations in the project and SDK — see
    // `AzoraMacroIndex`.
}

data class BuiltinAnnotation(
    val name: String,
    val description: String,
    val insertText: String,
)

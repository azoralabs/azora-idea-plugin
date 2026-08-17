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

    /** Reserved declaration, binding, and namespace words in the compiler lexer. */
    val declarationKeywords = setOf(
        "var", "val", "let", "fin", "func", "pack", "enum", "variant", "impl", "spec", "typealias",
        "prop", "ctor", "dtor", "oper", "annot", "bind", "graph", "realm", "scope", "macro", "test",
        "error", "import", "use",
    )

    /** Reserved words that introduce expressions, control flow, or contracts. */
    val controlKeywords = setOf(
        "return", "if", "else", "for", "while", "loop", "in", "by", "reverse",
        "break", "continue", "when", "throw", "try", "catch", "rescue", "defer",
        "await", "delay", "as", "is", "with", "without", "assert", "trace", "panic",
    )

    /** Reserved words that alter declaration visibility or evaluation. */
    val modifierKeywords = setOf(
        "exposed", "protected", "confined", "inline", "deepinline", "noinline",
        "threadlocal", "lazy", "factory", "derive", "out", "react", "bridge", "solo",
    )

    /** Reserved words for allocation, ownership, and unsafe operations. */
    val memoryKeywords = setOf(
        "alloc", "purge", "take", "unsafe", "inject", "preserve",
    )

    /** Reserved words for reactive state and effects. */
    val reactiveKeywords = setOf(
        "remember", "retain", "effect",
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
        "module", "union", "async", "where", "replace", "escaping",
        "derives", "includes", "binds", "requires", "assoc", "lend", "seal",
    )

    val allCompletionKeywords = (hardKeywords + softKeywords).sorted()

    /**
     * Compiler-provided (`bridge annot`) annotations. These come from the
     * compiler itself rather than any `.az` source, so they are the one thing
     * that legitimately lives in this table.
     */
    val builtinAnnotations = listOf(
        BuiltinAnnotation("Stable", "Marks an API as stable from a version onward.", """@Stable(sinceAzora: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("Experimental", "Marks an API as experimental.", """@Experimental(sinceAzora: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("Since", "Records the first Azora version containing a declaration.", """@Since(version: "$LANGUAGE_VERSION")"""),
        BuiltinAnnotation("Deprecated", "Marks an API as deprecated.", """@Deprecated(replacement: "…")"""),
        BuiltinAnnotation("Derive", "Connects a library decorator to a compiler derive generator.", """@Derive(generator: "serializer", role: "all")"""),
        BuiltinAnnotation("EnforceNumFields", "Allows generated packs to declare numeric field names.", "@EnforceNumFields"),
        BuiltinAnnotation("SignatureOnly", "Declares a bridge callable whose body is supplied by its target.", "@SignatureOnly"),
        BuiltinAnnotation("ProvidesAccess", "Declares access capabilities provided by a callable or property.", "@ProvidesAccess"),
        BuiltinAnnotation("DeclaresAccess", "Declares access capabilities described by an annotation.", "@DeclaresAccess"),
        BuiltinAnnotation("UncheckedCast", "Suppresses cast checks in the annotated declaration.", "@UncheckedCast"),
    )

    // NOTE: the standard library is not hardcoded. Module and symbol
    // information is indexed from the installed SDK's real `.az` sources by
    // `AzoraSymbolService`, so completion, navigation, and hover always
    // reflect the installed stdlib. Macros are likewise discovered from real
    // `macro` declarations in the project and SDK — see
    // `AzoraMacroIndex`.
}

data class BuiltinAnnotation(
    val name: String,
    val description: String,
    val insertText: String,
)

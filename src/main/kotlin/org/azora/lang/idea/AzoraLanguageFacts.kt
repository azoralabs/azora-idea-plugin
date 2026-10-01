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

    /**
     * The compiler's own primitives: `__int`, `__uint`, `__float`.
     *
     * A pack cannot describe them - `Int<N: __uint>` needs a width before there
     * is an `Int` to state one with - so they are written with the `__` that
     * reserves them, and they read as the primitives they are rather than as
     * symbols nobody was allowed to write.
     */
    val primitiveWords = setOf("__int", "__uint", "__float")

    /** Those words without their `__`, which nothing else may be called. */
    val primitiveWordStems = setOf("int", "uint", "float")


    /** The Azora language version this plugin's lexical model tracks. */
    const val LANGUAGE_VERSION = "0.0.5"

    /** Reserved declaration, binding, and namespace words in the compiler lexer. */
    val declarationKeywords = setOf(
        "var", "val", "let", "fin", "func", "pack", "enum", "variant", "impl", "spec", "typealias",
        "prop", "ctor", "dtor", "oper", "annot", "bind", "graph", "scope", "macro", "test",
        "error", "import", "module", "union",
        // Reserved, with no grammar of its own yet: `using` has no meaning
        // outside a macro name, and is a word set aside rather than a word
        // spent. It reads as a keyword because that is what it is.
        "using",
    )

    /** Reserved words that introduce expressions, control flow, or contracts. */
    val controlKeywords = setOf(
        "return", "if", "else", "for", "while", "loop", "in", "by", "then",
        "break", "continue", "when", "throw", "try", "catch", "rescue", "defer",
        "await", "delay", "as", "is", "with", "without", "assert", "trace", "panic",
        "where", "derives", "includes", "binds", "requires", "assoc", "seal",
    )

    /**
     * Control keywords that open a braced body when written as a statement.
     *
     * These are the constructs whose body is a *block* rather than a value, so
     * a reader looks inside them for statements. Their expression twins - the
     * `if` of `return if c { a } else { b }`, the `when` of `x = when v { … }` -
     * are the same words in a position where a value is expected, which is why
     * the distinction is made by position and not by this list.
     */
    val blockStatementHeads = setOf("if", "else", "for", "while", "loop", "when")

    /** Reserved words that alter declaration visibility or evaluation. */
    val modifierKeywords = setOf(
        "exposed", "protected", "confined", "inline", "deepinline", "noinline",
        "threadlocal", "lazy", "factory", "derive", "out", "react", "bridge", "solo",
        "async", "escaping",
        // `direct spec Number { … }` - the spec's members belong to the type.
        "direct",
        // `scoped Type(args)` in a `graph` - one value per active scope. A word of
        // its own, because `scope` declares a namespace.
        "scoped",
    )

    /** Reserved words for allocation, ownership, and unsafe operations. */
    val memoryKeywords = setOf(
        "alloc", "purge", "take", "unsafe", "inject", "preserve", "lend",
    )

    /** Reserved words for reactive state and effects. */
    val reactiveKeywords = setOf(
        "remember", "retain", "effect",
    )

    val literalKeywords = setOf("true", "false", "null")

    /**
     * Doc tags whose first word names something the signature also names.
     *
     * `@param capacity` documents a parameter, so `capacity` is a reference and
     * reads as one. `@return`, `@file` and `@since` are followed by prose, and
     * singling out its first word would only suggest a link that is not there.
     */
    val docNamingTags = setOf("param", "member", "generic", "receiver", "throws")

    /**
     * The receiver a member is called on. It is the only name that may leave
     * out its type in a receiver list - `[self&]` says the one thing that
     * varies where `[self: Self&]` repeats the type being implemented.
     */
    const val receiverName = "self"

    /**
     * Implicit receiver parameters. They remain identifiers in the lexer and
     * are styled semantically as parameters only where they are used.
     */
    val implicitParameters = setOf(receiverName, "it")

    /**
     * Every reserved word.
     *
     * There are no contextual ones. The compiler's lexer reads a word and
     * answers with `keywords[text] ?: IDENTIFIER` - `derives` is `derives`
     * wherever it appears, and a keyword-spelled *name* is admitted by the
     * parser at the one place it can be (after a `.`, or where a declaration
     * head can only be followed by a name), never by the lexer changing its
     * mind. The editor reads the same words the same way.
     */
    val hardKeywords = declarationKeywords + controlKeywords + modifierKeywords +
        memoryKeywords + reactiveKeywords + literalKeywords

    /**
     * The keywords the parser also accepts as a *name*.
     *
     * `Parser.consumeIdentifierLike` names them one by one, and this is that
     * list: `cursor.take()` calls a method, `module std.error` ends in a module
     * segment, `value.union(other)` is a set operation. Every other keyword is
     * a keyword in every position - there is no shape that turns `derives` or
     * `where` back into a name, so the editor never pretends otherwise.
     */
    val nameCapableKeywords = setOf(
        "prop", "purge", "remember", "retain", "preserve",
        "alloc", "test", "macro", "take", "union", "async", "error",
    )

    val allCompletionKeywords = hardKeywords.sorted()

    /**
     * Types the compiler provides itself.
     *
     * No `.az` source declares these, so the index cannot find them however
     * fresh it is, and `Array<String>` in a signature read as an unknown word
     * beside the `List<String>` on the next line. They come from the compiler
     * rather than the stdlib, which is what makes them belong in this table -
     * see [builtinAnnotations] for the same reasoning. Having no declaration
     * also means there is nowhere for go-to-definition to go.
     */
    val builtinTypes = setOf(
        "Array", "Self",
        // The named widths are aliases of one parameterised integer: `Byte` is
        // `Int<8>`. A width without a name is written as one - `Int<256>`.
        "Int", "UInt", "Byte", "UByte", "Short", "UShort", "Long", "ULong",
        "Cent", "UCent", "ISize", "USize",
        "Half", "Float", "Double", "Quad",
        "Bool", "Char", "String", "Unit", "Nothing", "Any",
    ) + primitiveWords

    /**
     * What a real literal is where nothing says which width to read it at.
     *
     * A literal carries no width of its own - the suffixes are gone and the
     * target names the width - so `7.` is this, and `var x: Double = 7.` is a
     * `Double`. The compiler's own default is the same one; both answer from a
     * single place so a hint never disagrees with the build.
     */
    const val DEFAULT_FLOAT_TYPE = "Float"

    /** What an integer literal is where nothing says which width to read it at. */
    const val DEFAULT_INT_TYPE = "Int"

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

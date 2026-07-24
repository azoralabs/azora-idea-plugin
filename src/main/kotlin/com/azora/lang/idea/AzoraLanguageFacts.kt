/*
 * Copyright 2026 AzoraTech
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

package com.azora.lang.idea

object AzoraLanguageFacts {
    // Keyword sets mirror the Azora 0.0.4 lexer (compiler/.../frontend/Lexer.kt).
    val declarationKeywords = setOf(
        "var", "fin", "let", "func", "task", "flow", "test",
        "enum", "slot", "pack", "impl", "spec", "type", "typealias",
        "prop", "oper", "ctor", "dtor", "deco", "meta",
        "zone", "friend", "module", "export", "import", "use",
        "solo", "wrap", "bridge", "fail", "threadlocal",
    )

    val controlKeywords = setOf(
        "if", "else", "for", "loop", "while", "when",
        "in", "where", "by", "reverse",
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
    val specialKeywords = setOf("self")

    val hardKeywords = declarationKeywords + controlKeywords + modifierKeywords +
        memoryKeywords + reactiveKeywords + literalKeywords + specialKeywords

    // Contextual words that are only keywords in some positions (also usable as
    // identifiers / user-defined infix macro operators elsewhere).
    val softKeywords = setOf(
        "where", "with", "by", "reverse", "out", "friend",
    )

    val allCompletionKeywords = hardKeywords.sorted()

    // Compiler-provided (`bridge deco`) decorators. Names are PascalCase.
    val builtinAnnotations = listOf(
        BuiltinAnnotation("Stable", "Marks an API as stable from a version onward.", """@Stable(sinceAzora: "0.0.4")"""),
        BuiltinAnnotation("Experimental", "Marks an API as experimental.", """@Experimental(sinceAzora: "0.0.4")"""),
        BuiltinAnnotation("SinceAzora", "Records the first version containing a declaration.", """@SinceAzora(version: "0.0.4")"""),
        BuiltinAnnotation("Deprecated", "Marks an API as deprecated.", """@Deprecated(replacement: "…")"""),
        BuiltinAnnotation("Derive", "Connects a library decorator to a compiler derive generator.", """@Derive(generator: "serializer", role: "all")"""),
        BuiltinAnnotation("EnforceNumFields", "Allows generated packs to declare numeric field names.", "@EnforceNumFields"),
        BuiltinAnnotation("Reactive", "Marks a function/task/infix as reactive.", "@Reactive"),
        BuiltinAnnotation("UncheckedCast", "Suppresses cast checks in the annotated declaration.", "@UncheckedCast"),
    )

    // NOTE: the standard library is no longer hardcoded here. Module and symbol
    // information is indexed from the installed SDK's real `.az` sources by
    // `AzoraSymbolService` (see `stdModuleNames()` / `stdlibByModule()`), so
    // completion, navigation, and hover always reflect the installed stdlib.
}

data class BuiltinAnnotation(
    val name: String,
    val description: String,
    val insertText: String,
)

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
    val declarationKeywords = setOf(
        "var", "fin", "func", "task", "flow", "hook", "test",
        "enum", "slot", "pack", "impl", "spec", "type", "typealias",
        "prop", "oper", "ctor", "dtor", "infx", "deco",
        "zone", "friend", "module", "package", "use",
        "solo", "wrap", "bridge", "fail", "view",
    )

    val controlKeywords = setOf(
        "if", "else", "for", "loop", "while", "when",
        "in", "out", "where", "with", "by", "reverse", "each",
        "return", "break", "continue", "try", "catch", "defer",
        "guard", "is", "as", "await", "async", "launch", "yield",
        "flip", "flop", "assert", "trace", "mixin", "panic",
    )

    val modifierKeywords = setOf(
        "expose", "confine", "protect", "protected", "shield",
        "inline", "deepinline", "noinline",
        "mut", "ref", "shared", "weak", "isolated", "threadlocal",
        "lazy", "bind", "inject", "unsafe", "friend",
    )

    val memoryKeywords = setOf(
        "alloc", "drop", "deref", "unsafe",
    )

    val reactiveKeywords = setOf(
        "mem", "rem", "ret", "effect", "view",
    )

    val literalKeywords = setOf("true", "false", "null")
    val specialKeywords = setOf("self", "it", "base")

    val hardKeywords = declarationKeywords + controlKeywords + modifierKeywords +
        memoryKeywords + reactiveKeywords + literalKeywords + specialKeywords

    val softKeywords = setOf(
        "where", "with", "by", "reverse", "each",
        "friend", "base", "out",
    )

    val allCompletionKeywords = hardKeywords.sorted()

    val builtinAnnotations = listOf(
        BuiltinAnnotation("experimental", "Marks an API as experimental.", """@experimental(since: "0.0.3")"""),
        BuiltinAnnotation("stable", "Marks an API as stable.", """@stable(since: "0.0.3")"""),
        BuiltinAnnotation("deprecated", "Marks an API as deprecated.", """@deprecated(message: "Use replacement")"""),
        BuiltinAnnotation("enforceNumFields", "Requires generated pack fields to match the declared field count.", "@enforceNumFields"),
        BuiltinAnnotation("target", "Restricts a declaration to a backend or platform.", """@target("llvm")"""),
        BuiltinAnnotation("entry", "Marks an executable entry point.", "@entry"),
        BuiltinAnnotation("builtin", "Marks compiler-provided stdlib implementation details.", "@builtin"),
    )

    val stdModules = listOf(
        "std",
        "std.algorithm",
        "std.allocator",
        "std.char",
        "std.concurrency",
        "std.container",
        "std.convert",
        "std.functional",
        "std.io",
        "std.math",
        "std.memory",
        "std.parallelism",
        "std.random",
        "std.result",
        "std.string",
        "std.traits",
    )

    val stdAliases = mapOf(
        "math" to "std.math",
        "container" to "std.container",
        "convert" to "std.convert",
        "io" to "std.io",
        "memory" to "std.memory",
        "concurrency" to "std.concurrency",
        "parallelism" to "std.parallelism",
        "result" to "std.result",
        "string" to "std.string",
        "traits" to "std.traits",
        "algorithm" to "std.algorithm",
        "allocator" to "std.allocator",
        "functional" to "std.functional",
        "random" to "std.random",
        "char" to "std.char",
    )

    val stdSymbols = listOf(
        StdSymbol("std.math", "abs", "func", "abs(x: Number): Number"),
        StdSymbol("std.math", "min", "func", "min(a: T, b: T): T"),
        StdSymbol("std.math", "max", "func", "max(a: T, b: T): T"),
        StdSymbol("std.math", "clamp", "func", "clamp(x: T, lo: T, hi: T): T"),
        StdSymbol("std.math", "sqrt", "func", "sqrt(x: Real): Real"),
        StdSymbol("std.math", "sin", "func", "sin(x: Real): Real"),
        StdSymbol("std.math", "cos", "func", "cos(x: Real): Real"),
        StdSymbol("std.math", "PI", "fin", "Real"),
        StdSymbol("std.container", "List", "pack", "immutable ordered collection"),
        StdSymbol("std.container", "MutableList", "pack", "mutable growable list"),
        StdSymbol("std.container", "Map", "pack", "immutable key-value map"),
        StdSymbol("std.container", "MutableMap", "pack", "mutable key-value map"),
        StdSymbol("std.container", "Set", "pack", "immutable unique-value set"),
        StdSymbol("std.container", "MutableSet", "pack", "mutable unique-value set"),
        StdSymbol("std.container", "Tuple", "pack", "heterogeneous tuple"),
        StdSymbol("std.container", "listOf", "func", "listOf(elements: ...T): List<T>"),
        StdSymbol("std.container", "emptyList", "prop", "List<T>"),
        StdSymbol("std.container", "setOf", "func", "setOf(elements: ...T): Set<T>"),
        StdSymbol("std.container", "emptyMap", "func", "emptyMap(): Map<K, V>"),
        StdSymbol("std.container", "tupleOf", "func", "tupleOf(elements: ...T): Tuple<...T>"),
        StdSymbol("std.convert", "Into", "spec", "conversion to another type"),
        StdSymbol("std.convert", "From", "spec", "conversion from another type"),
        StdSymbol("std.memory", "Ptr", "pack", "raw pointer wrapper"),
        StdSymbol("std.memory", "Arc", "pack", "atomic reference-counted pointer"),
        StdSymbol("std.memory", "Weak", "pack", "non-owning weak reference"),
        StdSymbol("std.memory", "Unique", "pack", "unique owning pointer"),
        StdSymbol("std.memory", "Slice", "pack", "pointer plus length view"),
        StdSymbol("std.concurrency", "Task", "pack", "structured async task"),
        StdSymbol("std.concurrency", "async", "func", "async { ... }"),
        StdSymbol("std.concurrency", "await", "func", "await task"),
        StdSymbol("std.parallelism", "Thread", "pack", "native thread handle"),
        StdSymbol("std.parallelism", "Channel", "pack", "typed communication channel"),
        StdSymbol("std.result", "Result", "slot", "Result<T, E>"),
    )

    fun stdChildren(path: String): List<String> {
        val normalized = normalizeModulePath(path)
        val directChildren = stdModules.mapNotNull { module ->
            if (module == normalized) return@mapNotNull null
            if (!module.startsWith("$normalized.")) return@mapNotNull null
            module.removePrefix("$normalized.").substringBefore(".")
        }
        val symbols = stdSymbols.filter { it.module == normalized }.map { it.name }
        return (directChildren + symbols).distinct().sorted()
    }

    fun normalizeModulePath(text: String): String =
        text.trim().removeSuffix(".*").replace("::", ".").replace("..", ".")
}

data class BuiltinAnnotation(
    val name: String,
    val description: String,
    val insertText: String,
)

data class StdSymbol(
    val module: String,
    val name: String,
    val kind: String,
    val detail: String,
)

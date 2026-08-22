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

package org.azora.lang.idea.completion

/**
 * A code template offered in completion.
 *
 * @param trigger the word that surfaces the template.
 * @param description a short summary shown beside it.
 * @param body the text inserted when it is chosen.
 */
data class AzoraSnippet(val trigger: String, val description: String, val body: String)

/**
 * Current Azora code templates. The same set is mirrored as IDE live templates in
 * `liveTemplates/Azora.xml`, which supports tab stops; these completion entries
 * exist so the forms are discoverable while typing.
 */
object AzoraSnippets {

    val ALL: List<AzoraSnippet> = listOf(
        // Declarations
        AzoraSnippet("func", "Function", "func name(param: Type): ReturnType {\n    \n}"),
        AzoraSnippet("asyncfunc", "Asynchronous function", "async func name(): ReturnType {\n    return await call()\n}"),
        AzoraSnippet("reactfunc", "Reactive function", "react func name(): ReturnType {\n    remember var value = initialValue\n    return value\n}"),
        AzoraSnippet("reactasyncfunc", "Reactive asynchronous function", "react async func name(): ReturnType {\n    remember fin value = await call()\n    return value\n}"),
        AzoraSnippet("main", "Entry point", "func main() {\n    \n}"),
        AzoraSnippet("asyncmain", "Asynchronous entry point", "async func main() {\n    \n}"),
        AzoraSnippet("pack", "Product type", "pack Name {\n    var field: Type\n}"),
        AzoraSnippet("solopack", "Singleton product type", "solo pack Name {\n    fin field: Type\n}"),
        AzoraSnippet("enum", "Enumeration", "enum Name {\n    First\n    Second\n}"),
        AzoraSnippet("variantenum", "Payload enumeration", "variant enum Name {\n    First(value: Type)\n    Second\n}"),
        AzoraSnippet("error", "Error set", "error Name {\n    First\n    Second\n}"),
        AzoraSnippet("varianterror", "Payload error set", "variant error Name {\n    Failed(reason: String)\n}"),
        AzoraSnippet("spec", "Specification", "spec Name {\n    func method(): ReturnType\n}"),
        AzoraSnippet("impl", "Implementation block", "impl TypeName {\n    \n}"),
        AzoraSnippet("implfor", "Spec implementation", "impl SpecName for TypeName {\n    \n}"),
        AzoraSnippet("annot", "Annotation declaration", "annot @Name for .Pack {\n    fin value: Type\n}"),
        AzoraSnippet("graph", "Dependency graph", "graph Name {\n    solo Service()\n}"),
        AzoraSnippet("typealias", "Type alias", "typealias Name = Type"),
        AzoraSnippet("prop", "Computed property", "prop name[self: Self&]: Type = value"),
        AzoraSnippet("ctor", "Constructor", "ctor[self: Self!](value: Type) {\n    self.field = value\n}"),
        AzoraSnippet("reactctor", "Reactive constructor", "react ctor[self: Self!, anchor: Anchor&](value: Type): Entity {\n    return anchor.pass.create(value)\n}"),
        AzoraSnippet("oper", "Operator overload", "oper+ [self: Self&](other: Self&): Self {\n    return self\n}"),

        // Modules and scopes
        AzoraSnippet("module", "Module declaration", "module app.name"),
        AzoraSnippet("import", "Import", "import std.io"),
        AzoraSnippet("scope", "Qualified namespace", "scope app::name {\n    \n}"),
        AzoraSnippet("bridge", "Foreign function block", "bridge .C {\n    func name(param: Type): ReturnType\n}"),

        // Macros
        AzoraSnippet("macroprefix", "Prefix macro", "macro @name {\n    \$value => \$value\n}"),
        AzoraSnippet("macroinfix", "Infix macro", "macro \$left @name \$right => combine(\$left, \$right)"),
        AzoraSnippet("inline", "Compile-time splice", "inline \"\""),

        // Control flow
        AzoraSnippet("if", "Conditional", "if condition {\n    \n}"),
        AzoraSnippet("for", "For loop", "for item in items {\n    \n}"),
        AzoraSnippet("reversefor", "Reverse for loop", "reverse for item in items {\n    \n}"),
        AzoraSnippet("labeledfor", "Labeled for loop", "label: for item in items {\n    continue:label\n}"),
        AzoraSnippet("while", "While loop", "while condition {\n    \n}"),
        AzoraSnippet("loop", "Unbounded loop", "loop {\n    \n}"),
        AzoraSnippet("when", "Pattern match", "when value {\n    is Type -> result\n    else -> default\n}"),
        AzoraSnippet("defer", "Deferred cleanup", "defer {\n    \n}"),
        AzoraSnippet("trycatch", "Fallible call", "try call() catch fallback"),

        // Reactive
        AzoraSnippet("remember", "Remembered reactive binding", "remember var name = initialValue"),
        AzoraSnippet("retain", "Retained reactive binding", "retain var name = initialValue"),
        AzoraSnippet("effect", "Effect block", "effect {\n    \n}"),
        AzoraSnippet("effecton", "Effect with dependencies", "effect [first, second] {\n    \n}"),
        AzoraSnippet("effectdefer", "Reactive cleanup", "effect defer {\n    \n}"),

        // Memory
        AzoraSnippet("alloc", "Allocate and release memory", "var buffer = alloc Byte[size]\ndefer { purge buffer }"),
        AzoraSnippet("unsafe", "Unsafe block", "unsafe {\n    \n}"),

        // Contracts and tests
        AzoraSnippet("contract", "Function with contracts", "func name(param: Type): ReturnType\nin {\n    assert condition { \"precondition\" }\n} out { result ->\n    assert condition { \"postcondition\" }\n} scope {\n    return value\n}"),
        AzoraSnippet("test", "Test block", "test \"description\" {\n    \n}"),
        AzoraSnippet("assert", "Assertion", "assert condition { \"message\" }"),
        AzoraSnippet("query", "ECS query type", "@query [Transform!, Velocity&] @with Player @without Disabled"),
    )
}

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
 * The Azora code templates, covering every declaration form in the 0.0.4
 * grammar. The same set is mirrored as IDE live templates in
 * `liveTemplates/Azora.xml`, which supports tab stops; these completion entries
 * exist so the forms are discoverable while typing.
 */
object AzoraSnippets {

    val ALL: List<AzoraSnippet> = listOf(
        // Declarations
        AzoraSnippet("func", "Function", "func name(param: Type): ReturnType {\n    \n}"),
        AzoraSnippet("task", "Asynchronous function", "task name(): ReturnType {\n    fin result = await call()\n    return result\n}"),
        AzoraSnippet("flow", "Generator", "flow name(): Type {\n    yield value\n}"),
        AzoraSnippet("main", "Entry point", "func main() {\n    \n}"),
        AzoraSnippet("taskmain", "Asynchronous entry point", "task main() {\n    \n}"),
        AzoraSnippet("pack", "Product type", "pack Name {\n    var field: Type\n}"),
        AzoraSnippet("enum", "Enumeration", "enum Name {\n    First, Second\n}"),
        AzoraSnippet("slot", "Tagged union", "slot Name {\n    First(value: Type),\n    Second(value: Type)\n}"),
        AzoraSnippet("fail", "Error set", "fail Name {\n    First,\n    Second\n}"),
        AzoraSnippet("spec", "Specification", "spec Name {\n    func method(): ReturnType\n}"),
        AzoraSnippet("impl", "Implementation block", "impl TypeName {\n    \n}"),
        AzoraSnippet("implfor", "Spec implementation", "impl SpecName for TypeName {\n    \n}"),
        AzoraSnippet("solo", "Singleton", "solo Name {\n    fin field: Type = value\n}"),
        AzoraSnippet("wrap", "Dependency-injection container", "wrap Name {\n    bind Service = ServiceImpl()\n}"),
        AzoraSnippet("typealias", "Type alias", "typealias Name = Type"),
        AzoraSnippet("prop", "Computed property", "prop name: Type = value"),
        AzoraSnippet("ctor", "Constructor", "ctor(param: Type) {\n    self.field = param\n}"),
        AzoraSnippet("oper", "Operator overload", "oper+(other: Type): Type {\n    \n}"),

        // Modules and zones
        AzoraSnippet("module", "Module declaration", "module app.name"),
        AzoraSnippet("import", "Import", "import std.io"),
        AzoraSnippet("zone", "Namespace", "zone Name {\n    \n}"),
        AzoraSnippet("friendzone", "Shared zone", "friend zone std::name {\n    \n}"),
        AzoraSnippet("bridge", "Foreign function block", "bridge .C {\n    func name(param: Type): ReturnType\n}"),

        // Macros
        AzoraSnippet("metaprefix", "Prefix macro", "meta .Prefix(\"name\") {\n    [...\$items] => \n}"),
        AzoraSnippet("metainfix", "Infix macro", "meta .Infix(\"op\") {\n    \$a \$b => \n}"),
        AzoraSnippet("metatype", "Type macro", "meta type {\n    name \$T => \$T\n}"),
        AzoraSnippet("inline", "Compile-time splice", "inline \"\""),

        // Control flow
        AzoraSnippet("if", "Conditional", "if condition {\n    \n}"),
        AzoraSnippet("guard", "Early exit", "guard condition else {\n    return\n}"),
        AzoraSnippet("for", "For loop", "for item in items {\n    \n}"),
        AzoraSnippet("while", "While loop", "while condition {\n    \n}"),
        AzoraSnippet("loop", "Unbounded loop", "loop {\n    \n}"),
        AzoraSnippet("when", "Pattern match", "when value {\n    is Type -> result\n    else -> default\n}"),
        AzoraSnippet("defer", "Deferred cleanup", "defer {\n    \n}"),
        AzoraSnippet("trycatch", "Fallible call", "try call() catch fallback"),

        // Reactive
        AzoraSnippet("mem", "Remembered value", "mem name = initialValue"),
        AzoraSnippet("rem", "Reactive state", "rem name = initialValue"),
        AzoraSnippet("ret", "Retained value", "ret name = initialValue"),
        AzoraSnippet("effect", "Effect block", "effect {\n    \n}"),

        // Memory
        AzoraSnippet("alloc", "Scoped allocation", "zone scratch {\n    fin buffer = alloc Byte(size)\n    defer { drop buffer }\n}"),
        AzoraSnippet("unsafe", "Unsafe block", "unsafe {\n    \n}"),

        // Contracts and tests
        AzoraSnippet("contract", "Function with contracts", "func name(param: Type): ReturnType\nin {\n    assert param > 0 { \"param must be positive\" }\n} out { r ->\n    assert r >= 0 { \"result must be valid\" }\n} zone {\n    return param\n}"),
        AzoraSnippet("test", "Test block", "test \"description\" {\n    \n}"),
        AzoraSnippet("assert", "Assertion", "assert condition { \"message\" }"),
    )
}

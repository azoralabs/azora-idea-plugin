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

package com.azora.lang.idea.structure

import com.azora.lang.idea.AzoraFile
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.*
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.*
import javax.swing.Icon

/**
 * [PsiStructureViewFactory] for Azora files.
 *
 * Provides an outline panel showing top-level declarations:
 * package, imports, functions, views, packs, enums, slots, fail sets,
 * zones, impls, solos, wraps, tests, specs, bridges, and hooks.
 *
 * Since the plugin uses a flat token-based PSI (no full AST), the
 * structure is extracted by scanning source text for declaration patterns.
 */
class AzoraStructureViewFactory : PsiStructureViewFactory {

    /**
     * Creates a [StructureViewBuilder] for the given [psiFile] if it is an [AzoraFile].
     *
     * @param psiFile the file to build a structure view for.
     * @return a [TreeBasedStructureViewBuilder], or `null` if [psiFile] is not an Azora file.
     */
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is AzoraFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return AzoraStructureViewModel(psiFile)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Structure view model
// ---------------------------------------------------------------------------

/**
 * [StructureViewModel] for Azora files.
 *
 * Uses [AzoraFileStructureElement] as the root and provides alphabetical
 * sorting via [Sorter.ALPHA_SORTER].
 *
 * @param file the PSI file backing this model.
 */
private class AzoraStructureViewModel(file: PsiFile) :
    StructureViewModelBase(file, AzoraFileStructureElement(file)),
    StructureViewModel.ElementInfoProvider {

    /** Returns the available sorters, currently only alphabetical. */
    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    /** Returns `false` because nodes do not always have children to expand. */
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = false

    /** Returns `false` because any node may potentially have children. */
    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean = false
}

// ---------------------------------------------------------------------------
// Root file element
// ---------------------------------------------------------------------------

/**
 * The root [StructureViewTreeElement] representing an entire Azora file.
 *
 * Extracts top-level declarations from the file's text to populate its children.
 *
 * @param file the PSI file this element represents.
 */
private class AzoraFileStructureElement(file: PsiFile) : PsiTreeElementBase<PsiFile>(file) {

    /** Returns the file name as the display text, falling back to `"Azora file"`. */
    override fun getPresentableText(): String = element?.name ?: "Azora file"

    /**
     * Extracts and returns the top-level declarations from the file's source text
     * as child [StructureViewTreeElement] nodes.
     */
    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val file = element ?: return emptyList()
        val text = file.text
        return extractDeclarations(text, file)
    }
}

// ---------------------------------------------------------------------------
// Declaration elements
// ---------------------------------------------------------------------------

/**
 * A [StructureViewTreeElement] representing a single Azora declaration in the outline.
 *
 * @param psiElement the PSI element this node is anchored to (typically the file).
 * @param label the display text shown in the structure view (e.g. `"func main"`).
 * @param icon the icon displayed next to the label.
 * @param children nested declaration elements (e.g. fields of a pack, variants of an enum).
 */
private class AzoraDeclarationElement(
    psiElement: PsiElement,
    private val label: String,
    private val icon: Icon,
    private val children: List<AzoraDeclarationElement> = emptyList()
) : PsiTreeElementBase<PsiElement>(psiElement) {

    /** Returns the declaration label (e.g. `"pack Point"`, `"func main"`). */
    override fun getPresentableText(): String = label

    /** Returns the icon for this declaration kind. */
    override fun getIcon(open: Boolean): Icon = icon

    /** Returns the nested child declarations. */
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = children
}

// ---------------------------------------------------------------------------
// Declaration extraction from source text
// ---------------------------------------------------------------------------

/**
 * Scans the source [text] for top-level declaration patterns and returns
 * [AzoraDeclarationElement] nodes for the structure outline.
 *
 * Iterates line-by-line, matching known Azora declaration keywords
 * and building tree elements with appropriate icons and nested children.
 *
 * @param text the full source text of the file.
 * @param psiFile the PSI file used as the anchor element for each node.
 * @return the list of top-level declaration elements.
 */
private fun extractDeclarations(text: String, psiFile: PsiFile): List<AzoraDeclarationElement> {
    val result = mutableListOf<AzoraDeclarationElement>()
    val lines = text.lines()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // Package declaration
            trimmed.startsWith("package ") -> {
                val name = trimmed.removePrefix("package ").trim()
                result.add(AzoraDeclarationElement(psiFile, "package $name", AllIcons.Nodes.Package))
            }

            // Import / use
            trimmed.startsWith("use ") -> {
                val name = trimmed.removePrefix("use ").trim()
                result.add(AzoraDeclarationElement(psiFile, "use $name", AllIcons.Nodes.Include))
            }

            // View
            matchesDeclPattern(trimmed, "view") -> {
                val name = extractDeclName(trimmed, "view")
                result.add(AzoraDeclarationElement(psiFile, "view $name", AllIcons.Nodes.PpWeb))
            }

            // Pack
            matchesDeclPattern(trimmed, "pack") -> {
                val name = extractDeclName(trimmed, "pack")
                val fields = extractBlockMembers(lines, i, "var ", "fin ")
                val children = fields.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.Field) }
                result.add(AzoraDeclarationElement(psiFile, "pack $name", AllIcons.Nodes.Class, children))
            }

            // Enum
            matchesDeclPattern(trimmed, "enum") -> {
                val name = extractDeclName(trimmed, "enum")
                val variants = extractEnumVariants(lines, i)
                val children = variants.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.Enum) }
                result.add(AzoraDeclarationElement(psiFile, "enum $name", AllIcons.Nodes.Enum, children))
            }

            // Slot
            matchesDeclPattern(trimmed, "slot") -> {
                val name = extractDeclName(trimmed, "slot")
                val variants = extractEnumVariants(lines, i)
                val children = variants.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.Enum) }
                result.add(AzoraDeclarationElement(psiFile, "slot $name", AllIcons.Nodes.AnonymousClass, children))
            }

            // Fail set
            matchesDeclPattern(trimmed, "fail") -> {
                val name = extractDeclName(trimmed, "fail")
                val variants = extractEnumVariants(lines, i)
                val children = variants.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.ExceptionClass) }
                result.add(AzoraDeclarationElement(psiFile, "fail $name", AllIcons.Nodes.ExceptionClass, children))
            }

            // Zone
            matchesDeclPattern(trimmed, "zone") -> {
                val name = extractDeclName(trimmed, "zone")
                result.add(AzoraDeclarationElement(psiFile, "zone $name", AllIcons.Nodes.Package))
            }

            // Impl
            matchesDeclPattern(trimmed, "impl") -> {
                val name = extractDeclName(trimmed, "impl")
                val members = extractBlockMembers(lines, i, "func ", "prop ", "oper", "ctor", "dtor")
                val children = members.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.Method) }
                result.add(AzoraDeclarationElement(psiFile, "impl $name", AllIcons.Nodes.EntryPoints, children))
            }

            // Solo
            matchesDeclPattern(trimmed, "solo") -> {
                val name = extractDeclName(trimmed, "solo")
                result.add(AzoraDeclarationElement(psiFile, "solo $name", AllIcons.Nodes.Static))
            }

            // Wrap
            matchesDeclPattern(trimmed, "wrap") -> {
                val name = extractDeclName(trimmed, "wrap")
                result.add(AzoraDeclarationElement(psiFile, "wrap $name", AllIcons.Nodes.Module))
            }

            // Spec
            matchesDeclPattern(trimmed, "spec") -> {
                val name = extractDeclName(trimmed, "spec")
                val members = extractBlockMembers(lines, i, "func ", "prop ")
                val children = members.map { AzoraDeclarationElement(psiFile, it, AllIcons.Nodes.AbstractMethod) }
                result.add(AzoraDeclarationElement(psiFile, "spec $name", AllIcons.Nodes.Interface, children))
            }

            // Bridge
            trimmed.startsWith("bridge ") -> {
                val target = trimmed.removePrefix("bridge ").substringBefore("{").trim()
                result.add(AzoraDeclarationElement(psiFile, "bridge $target", AllIcons.Nodes.Plugin))
            }

            // Deco
            matchesDeclPattern(trimmed, "deco") -> {
                val name = extractDeclName(trimmed, "deco")
                result.add(AzoraDeclarationElement(psiFile, "deco $name", AllIcons.Nodes.Annotationtype))
            }

            // Typealias
            trimmed.startsWith("typealias ") -> {
                val rest = trimmed.removePrefix("typealias ").trim()
                val name = rest.substringBefore("=").trim()
                result.add(AzoraDeclarationElement(psiFile, "typealias $name", AllIcons.Nodes.Type))
            }

            // Test
            trimmed.startsWith("test ") -> {
                val testName = extractTestName(trimmed)
                result.add(AzoraDeclarationElement(psiFile, "test $testName", AllIcons.Nodes.Test))
            }

            // Hook
            trimmed.startsWith("hook ") -> {
                val name = extractDeclName(trimmed, "hook")
                result.add(AzoraDeclarationElement(psiFile, "hook $name", AllIcons.Nodes.Function))
            }

            // Top-level function (possibly with modifiers)
            isFuncDeclaration(trimmed) -> {
                val name = extractFuncName(trimmed)
                result.add(AzoraDeclarationElement(psiFile, "func $name", AllIcons.Nodes.Function))
            }

            // Task
            trimmed.startsWith("task ") || (trimmed.startsWith("expose ") && trimmed.contains("task ")) -> {
                val name = extractDeclName(trimmed, "task")
                result.add(AzoraDeclarationElement(psiFile, "task $name", AllIcons.Nodes.Function))
            }

            // Flow
            trimmed.startsWith("flow ") || (trimmed.startsWith("expose ") && trimmed.contains("flow ")) -> {
                val name = extractDeclName(trimmed, "flow")
                result.add(AzoraDeclarationElement(psiFile, "flow $name", AllIcons.Nodes.Function))
            }

            // Top-level var/fin
            (trimmed.startsWith("var ") || trimmed.startsWith("fin ")) && !trimmed.contains("{") -> {
                val keyword = if (trimmed.startsWith("var")) "var" else "fin"
                val name = trimmed.removePrefix("$keyword ").substringBefore(":").substringBefore("=").trim()
                val icon = if (keyword == "fin") AllIcons.Nodes.FinalMark else AllIcons.Nodes.Variable
                result.add(AzoraDeclarationElement(psiFile, "$keyword $name", icon))
            }
        }

        i++
    }

    return result
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Checks whether a trimmed line starts a declaration of the given [keyword],
 * possibly preceded by modifiers (`expose`, `confine`, `inline`).
 *
 * @param trimmed the leading-whitespace-stripped source line.
 * @param keyword the declaration keyword to match (e.g. `"pack"`, `"enum"`).
 * @return `true` if the line declares a symbol of the given kind.
 */
private fun matchesDeclPattern(trimmed: String, keyword: String): Boolean {
    val core = stripLeadingModifiers(trimmed)
    return core.startsWith("$keyword ") || core.startsWith("$keyword<")
}

/**
 * Extracts the declaration name after a [keyword].
 *
 * For example, `"pack Point {"` with keyword `"pack"` returns `"Point"`.
 *
 * @param trimmed the leading-whitespace-stripped source line.
 * @param keyword the declaration keyword preceding the name.
 * @return the extracted name, trimmed of surrounding delimiters.
 */
private fun extractDeclName(trimmed: String, keyword: String): String {
    val core = stripLeadingModifiers(trimmed)
    val afterKeyword = core.substringAfter("$keyword ").substringAfter("$keyword<")
    if (keyword == "zone") return afterKeyword.substringBefore("{").substringBefore(" ").trim()
    // Name is everything up to ( { < : or end of line
    return afterKeyword.substringBefore("(").substringBefore("{").substringBefore("<")
        .substringBefore(":").substringBefore(" ").trim()
}

/**
 * Checks if a trimmed line is a function declaration, with possible modifiers.
 *
 * @param trimmed the leading-whitespace-stripped source line.
 * @return `true` if the line starts a `func` declaration.
 */
private fun isFuncDeclaration(trimmed: String): Boolean {
    val core = stripLeadingModifiers(trimmed)
    return core.startsWith("func ") || core.startsWith("func<")
}

/**
 * Extracts the function name from a `func` declaration line.
 *
 * Handles both `func name(...)` and `func<T> name(...)` forms.
 *
 * @param trimmed the leading-whitespace-stripped source line.
 * @return the extracted function name.
 */
private fun extractFuncName(trimmed: String): String {
    val core = stripLeadingModifiers(trimmed)
    val afterFunc = if (core.contains("func<")) {
        core.substringAfter("func<").substringAfter("> ").substringAfter(">")
    } else {
        core.substringAfter("func ")
    }
    return afterFunc.substringBefore("(").substringBefore("{").substringBefore(":").trim()
}

private fun stripLeadingModifiers(trimmed: String): String {
    var s = trimmed
    var changed: Boolean
    do {
        changed = false
        for (modifier in listOf("expose ", "confine ", "protect ", "friend ", "inline ", "deepinline ", "noinline ", "unsafe ", "threadlocal ")) {
            if (s.startsWith(modifier)) {
                s = s.removePrefix(modifier).trimStart()
                changed = true
            }
        }
    } while (changed)
    return s
}

/**
 * Extracts the test name from a `test` declaration line.
 *
 * Supports both quoted (`test "name"`) and unquoted (`test name`) forms.
 *
 * @param trimmed the leading-whitespace-stripped source line.
 * @return the extracted test name.
 */
private fun extractTestName(trimmed: String): String {
    val afterTest = trimmed.removePrefix("test ").trim()
    return if (afterTest.startsWith("\"")) {
        afterTest.substringAfter("\"").substringBefore("\"")
    } else {
        afterTest.substringBefore("{").substringBefore(" ").trim()
    }
}

/**
 * Extracts member declarations from a brace-delimited block.
 *
 * Scans lines at brace depth 1 for any that start with one of the given [prefixes]
 * and returns their signatures (up to `{` or `=`).
 *
 * @param lines all lines of the source file.
 * @param startLineIdx the index of the line containing the opening declaration.
 * @param prefixes the set of line prefixes to match (e.g. `"func "`, `"var "`).
 * @return the list of matched member signature strings.
 */
private fun extractBlockMembers(lines: List<String>, startLineIdx: Int, vararg prefixes: String): List<String> {
    val members = mutableListOf<String>()
    var depth = 0
    var started = false
    var j = startLineIdx
    while (j < lines.size) {
        val l = lines[j]
        for (ch in l) {
            if (ch == '{') { depth++; started = true }
            if (ch == '}') depth--
        }
        if (started && depth == 1) {
            val memberTrimmed = l.trimStart()
            for (prefix in prefixes) {
                if (memberTrimmed.startsWith(prefix)) {
                    val sig = memberTrimmed.substringBefore("{").substringBefore("=").trim()
                    members.add(sig)
                    break
                }
            }
        }
        if (started && depth <= 0) break
        j++
    }
    return members
}

/**
 * Extracts comma-separated variant names from an enum, slot, or fail block.
 *
 * Only names starting with an uppercase letter are recognized as variants.
 *
 * @param lines all lines of the source file.
 * @param startLineIdx the index of the enum/slot/fail declaration line.
 * @return the list of variant name strings.
 */
private fun extractEnumVariants(lines: List<String>, startLineIdx: Int): List<String> {
    val variants = mutableListOf<String>()
    var depth = 0
    var started = false
    var j = startLineIdx
    while (j < lines.size) {
        val l = lines[j]
        for (ch in l) {
            if (ch == '{') { depth++; started = true }
            if (ch == '}') depth--
        }
        if (started && depth == 1 && j > startLineIdx) {
            // Parse comma-separated variant names
            val inner = l.trim().trimEnd(',')
            if (inner.isNotBlank() && !inner.startsWith("}") && !inner.startsWith("{")) {
                // Could be "Variant1, Variant2" or "Variant(field: Type)"
                for (part in inner.split(",")) {
                    val name = part.trim().substringBefore("(").substringBefore(" ").trim()
                    if (name.isNotBlank() && name.first().isUpperCase()) {
                        variants.add(name)
                    }
                }
            }
        }
        if (started && depth <= 0) break
        j++
    }
    return variants
}

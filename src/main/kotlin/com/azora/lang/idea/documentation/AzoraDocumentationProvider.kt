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

package com.azora.lang.idea.documentation

import com.azora.lang.idea.AzoraTokenTypes
import com.azora.lang.idea.AzoraLanguageFacts
import com.azora.lang.idea.symbol.*
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

/**
 * [AbstractDocumentationProvider] for the Azora language.
 *
 * Shows quick documentation (Ctrl+Q / F1) for Azora identifiers by looking up
 * the symbol in the [AzoraSymbolService] and rendering its type, parameters,
 * members, and source location as HTML.
 */
class AzoraDocumentationProvider : AbstractDocumentationProvider() {

    /**
     * Generates full HTML documentation for the given [element].
     *
     * Looks up the identifier in [AzoraSymbolService] and renders a documentation
     * panel showing the symbol's kind, type, parameters, members, and source location.
     *
     * @param element the resolved target element (can be `null`).
     * @param originalElement the element under the caret (can be `null`).
     * @return the HTML documentation string, or `null` if the element is not an identifier
     *   or no matching symbol is found.
     */
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val elementType = target.node?.elementType
        if (elementType == AzoraTokenTypes.DECORATOR) {
            return renderAnnotationDoc(target.text.removePrefix("@"))
        }
        if (elementType != AzoraTokenTypes.IDENTIFIER) return null

        val name = target.text
        val project = target.project
        val file = target.containingFile ?: return null
        val filePath = file.virtualFile?.path ?: file.name
        val source = file.text

        val symbolService = AzoraSymbolService.getInstance(project)
        val symbol = findSymbolByName(name, symbolService, target.project, filePath, source) ?: return null

        return renderDocumentation(symbol)
    }

    /**
     * Generates a short one-line navigation info string for the given [element].
     *
     * Shown in the status bar or as a tooltip when hovering over an identifier
     * with Ctrl held.
     *
     * @param element the resolved target element (can be `null`).
     * @param originalElement the element under the caret (can be `null`).
     * @return the quick info string, or `null` if unavailable.
     */
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val elementType = target.node?.elementType
        if (elementType == AzoraTokenTypes.DECORATOR) {
            val annotation = AzoraLanguageFacts.builtinAnnotations.find { it.name == target.text.removePrefix("@") }
            return annotation?.description
        }
        if (elementType != AzoraTokenTypes.IDENTIFIER) return null

        val name = target.text
        val project = target.project
        val file = target.containingFile ?: return null
        val filePath = file.virtualFile?.path ?: file.name
        val source = file.text

        val symbolService = AzoraSymbolService.getInstance(project)
        val symbol = findSymbolByName(name, symbolService, target.project, filePath, source) ?: return null

        return renderQuickInfo(symbol)
    }

    /**
     * Finds a symbol by [name] from visible symbols and their nested members.
     *
     * Searches top-level symbols first, then recursively checks members.
     *
     * @param name the identifier name to look up.
     * @param symbolService the project's symbol service.
     * @param filePath the absolute path of the current file.
     * @param source the full text of the current file.
     * @return the matching [SymbolInfo], or `null` if not found.
     */
    private fun findSymbolByName(
        name: String,
        symbolService: AzoraSymbolService,
        project: com.intellij.openapi.project.Project,
        filePath: String,
        source: String
    ): SymbolInfo? {
        val allSymbols = symbolService.getAllVisibleSymbols(project, filePath, source)

        // Direct match
        val direct = allSymbols.find { it.name == name }
        if (direct != null) return direct

        // Search in members
        for (sym in allSymbols) {
            val nested = findInMembers(name, sym)
            if (nested != null) return nested
        }
        return null
    }

    /**
     * Recursively searches through [sym]'s members for a symbol matching [name].
     *
     * @param name the identifier name to find.
     * @param sym the parent symbol whose members are searched.
     * @return the matching [SymbolInfo], or `null` if not found.
     */
    private fun findInMembers(name: String, sym: SymbolInfo): SymbolInfo? {
        for (member in sym.members) {
            if (member.name == name) return member
            val nested = findInMembers(name, member)
            if (nested != null) return nested
        }
        return null
    }

    /**
     * Renders full HTML documentation for [sym].
     *
     * Includes the symbol's kind badge, name, type, parameters table,
     * members (fields, variants, methods depending on kind), and source location.
     *
     * @param sym the symbol to render documentation for.
     * @return the complete HTML string.
     */
    private fun renderDocumentation(sym: SymbolInfo): String {
        val sb = StringBuilder()
        sb.append("<html><body>")

        // Kind badge
        sb.append("<b>${kindLabel(sym.kind)}</b> <code>${sym.name}</code>")

        // Type
        if (sym.type != null) {
            sb.append(": <code>${escapeHtml(sym.type)}</code>")
        }

        sb.append("<br/>")

        if (!sym.documentation.isNullOrBlank()) {
            sb.append("<br/>")
            sb.append(escapeHtml(sym.documentation).replace("\n", "<br/>"))
            sb.append("<br/>")
        }

        // Parameters
        if (sym.params.isNotEmpty()) {
            sb.append("<br/><b>Parameters:</b><br/>")
            sb.append("<table>")
            for ((pName, pType) in sym.params) {
                sb.append("<tr><td><code>${escapeHtml(pName)}</code></td>")
                sb.append("<td>: <code>${escapeHtml(pType ?: "?")}</code></td></tr>")
            }
            sb.append("</table>")
        }

        // Members
        if (sym.members.isNotEmpty()) {
            when (sym.kind) {
                SymbolKind.PACK -> {
                    sb.append("<br/><b>Fields:</b><br/>")
                    for (field in sym.members.filter { it.kind == SymbolKind.FIELD }) {
                        sb.append("&nbsp;&nbsp;")
                        sb.append(if (field.isMutable) "<code>var</code>" else "<code>fin</code>")
                        sb.append(" <code>${escapeHtml(field.name)}</code>")
                        if (field.type != null) sb.append(": <code>${escapeHtml(field.type)}</code>")
                        if (field.defaultValueText != null) sb.append(" = <code>${escapeHtml(field.defaultValueText)}</code>")
                        sb.append("<br/>")
                    }
                }
                SymbolKind.ENUM, SymbolKind.FAIL -> {
                    sb.append("<br/><b>Variants:</b><br/>")
                    for (variant in sym.members.filter { it.kind == SymbolKind.VARIANT }) {
                        sb.append("&nbsp;&nbsp;<code>${escapeHtml(variant.name)}</code><br/>")
                    }
                }
                SymbolKind.SLOT -> {
                    sb.append("<br/><b>Variants:</b><br/>")
                    for (variant in sym.members.filter { it.kind == SymbolKind.VARIANT }) {
                        sb.append("&nbsp;&nbsp;<code>${escapeHtml(variant.name)}</code>")
                        if (variant.params.isNotEmpty()) {
                            sb.append("(${variant.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})")
                        }
                        sb.append("<br/>")
                    }
                }
                SymbolKind.SCOPE -> {
                    sb.append("<br/><b>Members:</b><br/>")
                    for (member in sym.members.take(20)) {
                        sb.append("&nbsp;&nbsp;<code>${kindLabel(member.kind)} ${escapeHtml(member.name)}</code><br/>")
                    }
                    if (sym.members.size > 20) {
                        sb.append("&nbsp;&nbsp;<i>... and ${sym.members.size - 20} more</i><br/>")
                    }
                }
                SymbolKind.SOLO -> {
                    sb.append("<br/><b>Fields:</b><br/>")
                    for (field in sym.members.filter { it.kind == SymbolKind.FIELD }) {
                        sb.append("&nbsp;&nbsp;<code>${escapeHtml(field.name)}</code>")
                        if (field.type != null) sb.append(": <code>${escapeHtml(field.type)}</code>")
                        sb.append("<br/>")
                    }
                    val methods = sym.members.filter { it.kind == SymbolKind.METHOD }
                    if (methods.isNotEmpty()) {
                        sb.append("<br/><b>Methods:</b><br/>")
                        for (method in methods) {
                            sb.append("&nbsp;&nbsp;<code>${escapeHtml(method.name)}")
                            sb.append("(${method.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})")
                            if (method.type != null) sb.append(": ${escapeHtml(method.type)}")
                            sb.append("</code><br/>")
                        }
                    }
                }
                else -> {}
            }
        }

        // Source location
        if (sym.filePath != null && sym.line > 0) {
            sb.append("<br/><i>Defined at line ${sym.line}")
            if (sym.filePath != "<stdlib>") {
                val fileName = sym.filePath.substringAfterLast('/')
                sb.append(" in $fileName")
            } else {
                sb.append(" in stdlib")
            }
            sb.append("</i>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Renders a short one-line info string for [sym].
     *
     * Format: `kind name(params): type`, e.g. `func square(n: Int): Int`.
     *
     * @param sym the symbol to render.
     * @return the quick info string.
     */
    private fun renderQuickInfo(sym: SymbolInfo): String {
        val sb = StringBuilder()
        sb.append("${kindLabel(sym.kind)} ${sym.name}")
        if (sym.params.isNotEmpty()) {
            sb.append("(${sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }})")
        }
        if (sym.type != null) {
            sb.append(": ${sym.type}")
        }
        return sb.toString()
    }

    /**
     * Returns a human-readable label for the given [SymbolKind].
     *
     * Used in both full documentation and quick info rendering.
     *
     * @param kind the symbol kind to label.
     * @return the Azora keyword or short name for the kind.
     */
    private fun kindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.PACK -> "pack"
        SymbolKind.ENUM -> "enum"
        SymbolKind.FAIL -> "fail"
        SymbolKind.SLOT -> "slot"
        SymbolKind.FUNC -> "func"
        SymbolKind.VIEW -> "view"
        SymbolKind.SCOPE -> "zone"
        SymbolKind.SOLO -> "solo"
        SymbolKind.WRAP -> "wrap"
        SymbolKind.VAR -> "var"
        SymbolKind.FIN -> "fin"
        SymbolKind.FIELD -> "field"
        SymbolKind.METHOD -> "func"
        SymbolKind.PROPERTY -> "prop"
        SymbolKind.VARIANT -> "variant"
        SymbolKind.OPERATOR -> "oper"
        SymbolKind.BRIDGE_FUNC -> "bridge func"
        SymbolKind.TASK -> "task"
        SymbolKind.FLOW -> "flow"
        SymbolKind.HOOK -> "hook"
        SymbolKind.TEST -> "test"
        SymbolKind.SPEC -> "spec"
        SymbolKind.IMPL_SPEC -> "impl spec"
        SymbolKind.INFX -> "infx"
        SymbolKind.BRIDGE -> "bridge"
        SymbolKind.TYPEALIAS -> "typealias"
        SymbolKind.PACKAGE -> "package"
        SymbolKind.USE -> "use"
        SymbolKind.PARAM -> "param"
        SymbolKind.CTOR -> "ctor"
        SymbolKind.DTOR -> "dtor"
        SymbolKind.WRAP_BINDING -> "bind"
    }

    /**
     * Escapes HTML special characters in [text] for safe embedding in documentation HTML.
     *
     * @param text the raw text to escape.
     * @return the HTML-safe string.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun renderAnnotationDoc(name: String): String? {
        val annotation = AzoraLanguageFacts.builtinAnnotations.find { it.name == name } ?: return null
        return """
            <html><body>
            <b>annotation</b> <code>@${escapeHtml(annotation.name)}</code><br/>
            <br/>${escapeHtml(annotation.description)}<br/>
            <br/><code>${escapeHtml(annotation.insertText)}</code>
            </body></html>
        """.trimIndent()
    }
}

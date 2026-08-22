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

package org.azora.lang.idea.documentation

import org.azora.lang.idea.AzoraLanguageFacts
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement

/**
 * Quick documentation (Ctrl+Q / hover) for Azora.
 *
 * The symbol under the caret is resolved with [AzoraResolver], so hovering a
 * field shows *that* field rather than an unrelated same-named declaration, and
 * the panel is laid out with the platform's documentation markup: a signature
 * at the top, the doc comment beneath, then parameters, members, and where the
 * declaration lives.
 */
class AzoraDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val effective = annotationIdentifier(target) ?: target
        resolve(effective)?.let { return renderDocumentation(it) }
        return annotationName(effective)?.let(::renderAnnotationDoc)
    }

    /**
     * The text a `/** … */` shows when the editor renders it in place.
     *
     * The same prose the popup shows, without the signature: the declaration is
     * on the next line, so repeating it in the box above would say it twice.
     */
    override fun generateRenderedDoc(comment: PsiDocCommentBase): String? {
        val body = stripDocMarkers(comment.text).takeIf { it.isNotBlank() } ?: return null
        return renderDocComment(body)
    }

    /**
     * A doc comment's prose, with the `/**`, the `*/` and the leading `*` of
     * each line taken off - none of which is what was written.
     */
    private fun stripDocMarkers(text: String): String = text
        .removePrefix("/**").removeSuffix("*/")
        .lines()
        .joinToString("\n") { it.trim().removePrefix("*").trim() }
        .trim()

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val effective = annotationIdentifier(target) ?: target
        resolve(effective)?.let { return signatureOf(it) }
        return annotationName(effective)?.let { name ->
            AzoraLanguageFacts.builtinAnnotations.find { it.name == name }?.description
        }
    }

    /** Resolves the symbol under [target], or `null` when it is not a name. */
    private fun resolve(target: PsiElement): SymbolInfo? {
        val type = target.node?.elementType
        if (type != AzoraTokenTypes.IDENTIFIER && type != AzoraTokenTypes.TYPE_PARAMETER) return null

        val file = target.containingFile ?: return null
        val project = target.project
        val service = AzoraSymbolService.getInstance(project)
        val resolver = AzoraResolver(project, service)
        return resolver.resolve(
            file.virtualFile?.path ?: file.name,
            file.text,
            target.textRange.startOffset,
        ).firstOrNull()
    }

    /** The final name token following an `@`, including qualified paths. */
    private fun annotationIdentifier(target: PsiElement): PsiElement? {
        if (target.node?.elementType != AzoraTokenTypes.DECORATOR || target.text != "@") return null
        val file = target.containingFile ?: return null
        val source = file.text
        val match = ANNOTATION_AT.find(source, target.textRange.startOffset)
            ?.takeIf { it.range.first == target.textRange.startOffset }
            ?: return null
        val name = match.groups[1] ?: return null
        return file.findElementAt(name.range.first)
    }

    /** Built-in annotation name when [target] belongs to an `@...::Name` path. */
    private fun annotationName(target: PsiElement): String? {
        if (target.node?.elementType != AzoraTokenTypes.IDENTIFIER) return null
        val file = target.containingFile ?: return null
        val lineStart = file.text.lastIndexOf('\n', target.textRange.startOffset - 1) + 1
        return ANNOTATION_AT.findAll(file.text, lineStart)
            .firstOrNull { match -> target.textRange.startOffset in match.range }
            ?.groups?.get(1)?.value
    }

    /** The one-line signature shown in the tooltip and at the top of the panel. */
    private fun signatureOf(sym: SymbolInfo): String = buildString {
        append(kindLabel(sym.kind))
        append(' ')
        append(sym.name)
        if (sym.genericParams.isNotEmpty()) {
            append('<').append(sym.genericParams.joinToString(", ")).append('>')
        }
        if (sym.kind in CALLABLE_KINDS || sym.params.isNotEmpty()) {
            append('(')
            append(sym.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" })
            append(')')
        }
        sym.type?.let { append(": ").append(it) }
    }

    private fun renderDocumentation(sym: SymbolInfo): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(escapeHtml(signatureOf(sym)))
        append(DocumentationMarkup.DEFINITION_END)

        if (!sym.documentation.isNullOrBlank()) {
            append(DocumentationMarkup.CONTENT_START)
            append(renderDocComment(sym.documentation))
            append(DocumentationMarkup.CONTENT_END)
        }

        val sections = buildSections(sym)
        if (sections.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            for ((header, body) in sections) {
                append(DocumentationMarkup.SECTION_HEADER_START)
                append(escapeHtml(header)).append(':')
                append(DocumentationMarkup.SECTION_SEPARATOR)
                append(body)
                append(DocumentationMarkup.SECTION_END)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /** The labelled sections shown under the doc comment. */
    private fun buildSections(sym: SymbolInfo): List<Pair<String, String>> {
        val sections = mutableListOf<Pair<String, String>>()

        if (sym.params.isNotEmpty()) {
            sections += "Parameters" to sym.params.joinToString("<br/>") { (name, type) ->
                "<code>${escapeHtml(name)}</code>: <code>${escapeHtml(type ?: "?")}</code>"
            }
        }

        val fields = sym.members.filter { it.kind == SymbolKind.FIELD }
        if (fields.isNotEmpty()) {
            sections += "Fields" to fields.joinToString("<br/>") { field ->
                buildString {
                    append("<code>").append(if (field.isMutable) "var" else "fin").append("</code> ")
                    append("<code>").append(escapeHtml(field.name)).append("</code>")
                    field.type?.let { append(": <code>").append(escapeHtml(it)).append("</code>") }
                    field.defaultValueText?.let { append(" = <code>").append(escapeHtml(it)).append("</code>") }
                }
            }
        }

        val variants = sym.members.filter { it.kind == SymbolKind.VARIANT }
        if (variants.isNotEmpty()) {
            sections += "Variants" to variants.joinToString("<br/>") { variant ->
                buildString {
                    append("<code>").append(escapeHtml(variant.name))
                    if (variant.params.isNotEmpty()) {
                        append('(')
                        append(escapeHtml(variant.params.joinToString(", ") { "${it.first}: ${it.second ?: "?"}" }))
                        append(')')
                    }
                    append("</code>")
                }
            }
        }

        val callables = sym.members.filter { it.kind in CALLABLE_KINDS || it.kind == SymbolKind.PROPERTY }
        if (callables.isNotEmpty()) {
            val shown = callables.take(MAX_LISTED_MEMBERS)
            sections += "Members" to buildString {
                append(shown.joinToString("<br/>") { "<code>${escapeHtml(signatureOf(it))}</code>" })
                if (callables.size > shown.size) {
                    append("<br/><i>… and ${callables.size - shown.size} more</i>")
                }
            }
        }

        val scopeMembers = if (sym.kind == SymbolKind.SCOPE) sym.members else emptyList()
        if (scopeMembers.isNotEmpty() && callables.isEmpty()) {
            val shown = scopeMembers.take(MAX_LISTED_MEMBERS)
            sections += "Contains" to buildString {
                append(shown.joinToString("<br/>") {
                    "<code>${escapeHtml(kindLabel(it.kind))} ${escapeHtml(it.name)}</code>"
                })
                if (scopeMembers.size > shown.size) {
                    append("<br/><i>… and ${scopeMembers.size - shown.size} more</i>")
                }
            }
        }

        sourceLocation(sym)?.let { sections += "Declared in" to it }
        return sections
    }

    /** A human-readable "where does this live" line. */
    private fun sourceLocation(sym: SymbolInfo): String? {
        val path = sym.filePath ?: return null
        if (path == "<stdlib>") return "the Azora standard library"
        val fileName = path.substringAfterLast('/')
        return if (sym.line > 0) "<code>${escapeHtml(fileName)}</code>:${sym.line}"
        else "<code>${escapeHtml(fileName)}</code>"
    }

    /**
     * Renders a doc comment as HTML.
     *
     * Doc comments are plain prose with `@param`-style tags and `code` spans;
     * blank lines become paragraphs so a multi-paragraph comment stays readable.
     */
    private fun renderDocComment(doc: String): String {
        val html = escapeHtml(doc)
            .replace(INLINE_CODE) { "<code>${it.groupValues[1]}</code>" }
            .replace(DOC_TAG) { "<br/><b>@${it.groupValues[1]}</b>" }
        return html.split(Regex("\n\\s*\n")).joinToString("") { paragraph ->
            "<p>${paragraph.trim().replace("\n", " ")}</p>"
        }
    }

    private fun renderAnnotationDoc(name: String): String? {
        val annotation = AzoraLanguageFacts.builtinAnnotations.find { it.name == name } ?: return null
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append("annot ").append(escapeHtml(annotation.name))
            append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START)
            append("<p>").append(escapeHtml(annotation.description)).append("</p>")
            append("<p><code>").append(escapeHtml(annotation.insertText)).append("</code></p>")
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** The Azora keyword that introduces a declaration of this kind. */
    private fun kindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.PACK -> "pack"
        SymbolKind.ENUM -> "enum"
        SymbolKind.FAIL -> "error"
        SymbolKind.SLOT -> "variant enum"
        SymbolKind.FUNC -> "func"
        SymbolKind.VIEW -> "react func"
        SymbolKind.SCOPE -> "scope"
        SymbolKind.SOLO -> "solo"
        SymbolKind.WRAP -> "typealias"
        SymbolKind.VAR -> "var"
        SymbolKind.FIN -> "fin"
        SymbolKind.FIELD -> "field"
        SymbolKind.METHOD -> "func"
        SymbolKind.PROPERTY -> "prop"
        SymbolKind.VARIANT -> "variant"
        SymbolKind.OPERATOR -> "oper"
        SymbolKind.BRIDGE_FUNC -> "bridge func"
        SymbolKind.TASK -> "async func"
        SymbolKind.FLOW -> "func"
        SymbolKind.HOOK -> "react func"
        SymbolKind.TEST -> "test"
        SymbolKind.SPEC -> "spec"
        SymbolKind.IMPL_SPEC -> "impl"
        SymbolKind.INFX -> "macro"
        SymbolKind.BRIDGE -> "bridge"
        SymbolKind.TYPEALIAS -> "typealias"
        SymbolKind.PACKAGE -> "module"
        SymbolKind.USE -> "import"
        SymbolKind.PARAM -> "param"
        SymbolKind.CTOR -> "ctor"
        SymbolKind.DTOR -> "dtor"
        SymbolKind.WRAP_BINDING -> "bind"
        SymbolKind.ANNOT -> "annot"
        SymbolKind.GRAPH -> "graph"
        SymbolKind.MACRO -> "macro"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        val CALLABLE_KINDS = setOf(
            SymbolKind.FUNC, SymbolKind.METHOD, SymbolKind.TASK, SymbolKind.FLOW,
            SymbolKind.CTOR, SymbolKind.OPERATOR, SymbolKind.BRIDGE_FUNC,
        )
        const val MAX_LISTED_MEMBERS = 20
        val INLINE_CODE = Regex("""`([^`]+)`""")
        val DOC_TAG = Regex("""(?m)^@(\w+)""")
        val ANNOTATION_AT = Regex("""@(?:[A-Za-z_$][\w$]*::)*([A-Za-z_$][\w$]*)""")
    }
}

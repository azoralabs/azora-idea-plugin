/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.azora.lang.idea.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.azora.lang.idea.AzoraFile
import org.azora.lang.idea.completion.iconForSymbolKind
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import javax.swing.Icon

/** Structure view backed by the same symbol model as completion and navigation. */
class AzoraStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is AzoraFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                AzoraStructureViewModel(psiFile)
        }
    }
}

private class AzoraStructureViewModel(file: PsiFile) :
    StructureViewModelBase(file, AzoraFileStructureElement(file)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean = false
}

private class AzoraFileStructureElement(file: PsiFile) : PsiTreeElementBase<PsiFile>(file) {

    override fun getPresentableText(): String = element?.name ?: "Azora file"

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val file = element ?: return emptyList()
        val path = file.virtualFile?.path ?: file.name
        val nodes = mutableListOf<StructureViewTreeElement>()

        MODULE_DECLARATION.find(file.text)?.groups?.get(1)?.let { module ->
            val anchor = file.findElementAt(module.range.first) ?: file
            nodes += AzoraDeclarationElement(anchor, "module ${module.value}", AllIcons.Nodes.Module)
        }

        AzoraSymbolService.getInstance(file.project)
            .getSymbolsForFile(path, file.text)
            .mapTo(nodes) { symbolElement(file, it) }
        return nodes
    }
}

private class AzoraDeclarationElement(
    psiElement: PsiElement,
    private val label: String,
    private val nodeIcon: Icon,
    private val children: List<AzoraDeclarationElement> = emptyList(),
) : PsiTreeElementBase<PsiElement>(psiElement) {

    override fun getPresentableText(): String = label
    override fun getIcon(open: Boolean): Icon = nodeIcon
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = children
}

private fun symbolElement(file: PsiFile, symbol: SymbolInfo): AzoraDeclarationElement {
    val anchorOffset = symbol.offset.coerceIn(0, (file.textLength - 1).coerceAtLeast(0))
    val anchor = file.findElementAt(anchorOffset) ?: file
    return AzoraDeclarationElement(
        anchor,
        symbolLabel(file.text, symbol),
        iconForSymbolKind(symbol.kind),
        symbol.members.map { symbolElement(file, it) },
    )
}

private fun symbolLabel(source: String, symbol: SymbolInfo): String {
    val declaration = declarationLine(source, symbol.offset)
    val prefix = when (symbol.kind) {
        SymbolKind.PACK -> if (declaration.startsWith("impl ")) "impl" else "pack"
        SymbolKind.ENUM -> if (declaration.startsWith("variant enum ")) "variant enum" else "enum"
        SymbolKind.FAIL -> if (declaration.startsWith("variant error ")) "variant error" else "error"
        SymbolKind.SLOT -> "variant enum"
        SymbolKind.FUNC, SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.HOOK -> "func"
        SymbolKind.VIEW -> "react func"
        SymbolKind.SCOPE -> "scope"
        SymbolKind.SOLO -> "solo pack"
        SymbolKind.WRAP, SymbolKind.TYPEALIAS -> "typealias"
        SymbolKind.VAR -> "var"
        SymbolKind.FIN -> "fin"
        SymbolKind.FIELD -> if (symbol.isMutable) "var" else "field"
        SymbolKind.METHOD -> "func"
        SymbolKind.PROPERTY -> "prop"
        SymbolKind.VARIANT -> "variant"
        SymbolKind.OPERATOR -> "oper"
        SymbolKind.BRIDGE_FUNC -> "func"
        SymbolKind.TEST -> "test"
        SymbolKind.SPEC -> "spec"
        SymbolKind.IMPL_SPEC -> "impl"
        SymbolKind.INFX, SymbolKind.MACRO -> "macro @"
        SymbolKind.BRIDGE -> "bridge ."
        SymbolKind.PACKAGE -> "package"
        SymbolKind.USE -> if (declaration.startsWith("use ")) "use" else "import"
        SymbolKind.PARAM -> "parameter"
        SymbolKind.CTOR -> "ctor"
        SymbolKind.DTOR -> "dtor"
        SymbolKind.WRAP_BINDING -> "binding"
        SymbolKind.ANNOT -> "annot"
        SymbolKind.GRAPH -> "graph"
    }
    return when (symbol.kind) {
        SymbolKind.MACRO, SymbolKind.INFX, SymbolKind.BRIDGE -> "$prefix${symbol.name}"
        else -> "$prefix ${symbol.name}"
    }
}

private fun declarationLine(source: String, offset: Int): String {
    val safe = offset.coerceIn(0, source.length)
    val start = source.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)) + 1
    val end = source.indexOf('\n', safe).takeIf { it >= 0 } ?: source.length
    return source.substring(start, end).trimStart().replace(LEADING_MODIFIERS, "")
}

private val MODULE_DECLARATION = Regex(
    """(?m)^\s*(?:(?:exposed|confined)\s+)*module\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""",
)
private val LEADING_MODIFIERS = Regex(
    """^(?:(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|threadlocal|react|async|bridge|lazy)\s+)+""",
)

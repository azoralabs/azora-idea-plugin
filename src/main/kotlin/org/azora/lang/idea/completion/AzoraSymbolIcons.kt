/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.completion

import com.intellij.icons.AllIcons
import org.azora.lang.idea.symbol.SymbolKind
import javax.swing.Icon

/** Icons shared by the structure view and non-semantic project UI. */
internal fun iconForSymbolKind(kind: SymbolKind): Icon = when (kind) {
    SymbolKind.PACK -> AllIcons.Nodes.Class
    SymbolKind.ENUM -> AllIcons.Nodes.Enum
    SymbolKind.FAIL -> AllIcons.Nodes.ExceptionClass
    SymbolKind.SLOT -> AllIcons.Nodes.AnonymousClass
    SymbolKind.FUNC -> AllIcons.Nodes.Function
    SymbolKind.VIEW -> AllIcons.Nodes.PpWeb
    SymbolKind.SCOPE -> AllIcons.Nodes.Package
    SymbolKind.SOLO -> AllIcons.Nodes.Static
    SymbolKind.WRAP -> AllIcons.Nodes.Module
    SymbolKind.VAR -> AllIcons.Nodes.Variable
    SymbolKind.FIN -> AllIcons.Nodes.FinalMark
    SymbolKind.FIELD -> AllIcons.Nodes.Field
    SymbolKind.METHOD -> AllIcons.Nodes.Method
    SymbolKind.PROPERTY -> AllIcons.Nodes.Property
    SymbolKind.VARIANT -> AllIcons.Nodes.Enum
    SymbolKind.OPERATOR -> AllIcons.Nodes.Method
    SymbolKind.BRIDGE_FUNC, SymbolKind.BRIDGE -> AllIcons.Nodes.Plugin
    SymbolKind.TASK, SymbolKind.FLOW, SymbolKind.HOOK -> AllIcons.Nodes.Function
    SymbolKind.TEST -> AllIcons.Nodes.Test
    SymbolKind.SPEC -> AllIcons.Nodes.Interface
    SymbolKind.IMPL_SPEC -> AllIcons.Nodes.EntryPoints
    SymbolKind.INFX -> AllIcons.Nodes.Method
    SymbolKind.TYPEALIAS -> AllIcons.Nodes.Type
    SymbolKind.PACKAGE -> AllIcons.Nodes.Package
    SymbolKind.USE -> AllIcons.Nodes.Include
    SymbolKind.PARAM -> AllIcons.Nodes.Parameter
    SymbolKind.CTOR, SymbolKind.DTOR -> AllIcons.Nodes.Method
    SymbolKind.WRAP_BINDING -> AllIcons.Nodes.Module
    SymbolKind.ANNOT -> AllIcons.Nodes.Annotationtype
    SymbolKind.GRAPH -> AllIcons.Nodes.Module
    SymbolKind.MACRO -> AllIcons.Nodes.Function
}

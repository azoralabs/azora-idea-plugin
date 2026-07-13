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

package com.azora.lang.idea.navigation

import com.azora.lang.idea.*
import com.azora.lang.idea.symbol.*
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.psi.*
import com.intellij.psi.search.*

/**
 * [GotoDeclarationHandler] for the Azora language.
 *
 * When the user Ctrl+clicks (Cmd+clicks on macOS) an identifier, this handler
 * resolves the symbol using the [AzoraSymbolService] and navigates to its
 * declaration in the same or another `.az` file.
 */
class AzoraGoToDeclarationHandler : GotoDeclarationHandler {

    /**
     * Resolves the declaration targets for the identifier at the given [offset].
     *
     * Looks up the identifier name via [AzoraSymbolService], searching the
     * current file's symbols (including nested members). If a match is found,
     * resolves it to a navigable PSI element.
     *
     * @param sourceElement the PSI leaf element under the caret.
     * @param offset the caret offset in the editor.
     * @param editor the active editor.
     * @return an array containing the target PSI element, or `null` if unresolved.
     */
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null || editor == null) return null

        val project = sourceElement.project
        val elementType = sourceElement.node?.elementType
        if (elementType != AzoraTokenTypes.IDENTIFIER) return null

        val identifierName = sourceElement.text
        val currentFile = sourceElement.containingFile ?: return null
        val currentFilePath = currentFile.virtualFile?.path ?: currentFile.name
        val currentSource = currentFile.text

        val symbolService = AzoraSymbolService.getInstance(project)

        // Search for the symbol in the current file and stdlib
        val symbol = findSymbolByName(identifierName, symbolService, project, currentFilePath, currentSource)
            ?: return null

        // Navigate to the declaration
        val targetElement = resolveToElement(symbol, project, currentFile, currentFilePath)
            ?: return null

        return arrayOf(targetElement)
    }

    /**
     * Finds a symbol by [name] in the current file's symbol table.
     *
     * First checks top-level symbols for a direct match, then recursively
     * searches nested members (fields, methods, variants, etc.).
     *
     * @param name the identifier name to look up.
     * @param symbolService the project's symbol service.
     * @param currentFilePath the absolute path of the current file.
     * @param currentSource the full text of the current file.
     * @return the matching [SymbolInfo], or `null` if not found.
     */
    private fun findSymbolByName(
        name: String,
        symbolService: AzoraSymbolService,
        project: Project,
        currentFilePath: String,
        currentSource: String
    ): SymbolInfo? {
        // 1. Search in current file symbols
        val allSymbols = symbolService.getAllVisibleSymbols(project, currentFilePath, currentSource)
        val directMatch = allSymbols.find { it.name == name }
        if (directMatch != null) return directMatch

        // 2. Search in nested members (e.g., methods, fields, variants)
        for (sym in allSymbols) {
            val nestedMatch = findInMembers(name, sym)
            if (nestedMatch != null) return nestedMatch
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
     * Resolves a [SymbolInfo] to a navigable [PsiElement].
     *
     * If the symbol is in the current file, navigates directly to its line.
     * If it is in another project file, locates the virtual file first.
     * Stdlib symbols (`<stdlib>`) are not yet navigable.
     *
     * @param symbol the symbol to resolve.
     * @param project the current IntelliJ project.
     * @param currentFile the PSI file the user is navigating from.
     * @param currentFilePath the absolute path of the current file.
     * @return the target PSI element, or `null` if navigation is not possible.
     */
    private fun resolveToElement(
        symbol: SymbolInfo,
        project: Project,
        currentFile: PsiFile,
        currentFilePath: String
    ): PsiElement? {
        val targetFilePath = symbol.filePath ?: return null
        val targetLine = symbol.line

        // If the symbol is in the current file
        if (targetFilePath == currentFilePath || targetFilePath == currentFile.name) {
            return navigateToLine(currentFile, targetLine)
        }

        // If the symbol is from stdlib, we cannot navigate to it directly,
        // but we can show a read-only view
        if (targetFilePath == "<stdlib>") {
            return null // Stdlib navigation not supported yet
        }

        // Try to find the file in the project
        val targetVirtualFile = findVirtualFile(targetFilePath, project) ?: return null
        val targetPsiFile = PsiManager.getInstance(project).findFile(targetVirtualFile) ?: return null
        return navigateToLine(targetPsiFile, targetLine)
    }

    /**
     * Finds the first meaningful PSI element at the given [line] in [file].
     *
     * Converts the 1-based line number to a document offset, then locates
     * the element at that position, skipping leading whitespace.
     *
     * @param file the PSI file to search in.
     * @param line the 1-based line number to navigate to.
     * @return the PSI element at the line, or [file] itself as a fallback.
     */
    private fun navigateToLine(file: PsiFile, line: Int): PsiElement {
        if (line <= 0) return file

        val document = file.viewProvider.document ?: return file
        val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
        val lineStartOffset = document.getLineStartOffset(lineIndex)

        // Find the first meaningful element at this line
        var element = file.findElementAt(lineStartOffset)
        if (element != null) {
            // Skip whitespace to find the actual declaration element
            while (element != null && element.text.isBlank() && element.nextSibling != null) {
                element = element.nextSibling
            }
        }
        return element ?: file
    }

    /**
     * Locates a [VirtualFile] by its [path], trying the filesystem first,
     * then searching all `.az` files in the project scope.
     *
     * @param path the absolute path or file name to find.
     * @param project the current IntelliJ project.
     * @return the matching virtual file, or `null` if not found.
     */
    private fun findVirtualFile(path: String, project: Project): VirtualFile? {
        // Try direct path
        val direct = LocalFileSystem.getInstance().findFileByPath(path)
        if (direct != null) return direct

        // Search project .az files for one matching the path
        val azFiles = FileTypeIndex.getFiles(AzoraFileType.INSTANCE, GlobalSearchScope.projectScope(project))
        for (file in azFiles) {
            if (file.path == path || file.name == path) {
                return file
            }
        }

        return null
    }
}

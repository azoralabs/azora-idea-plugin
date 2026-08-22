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

package org.azora.lang.idea.navigation

import org.azora.lang.idea.AzoraFileType
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraImports
import org.azora.lang.idea.symbol.AzoraResolver
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Ctrl/Cmd-click navigation for Azora.
 *
 * Resolution is delegated to [AzoraResolver], so a name is looked up the way
 * the language scopes it — locals and parameters before file-level
 * declarations, members resolved against their receiver's type, imports
 * consulted before the rest of the project — rather than jumping to the first
 * same-named symbol anywhere in the index.
 *
 * Declarations in the standard library and in dependencies are navigable too:
 * those are read from real `.az` files on disk, so they open like any other
 * source file.
 */
class AzoraGoToDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val effectiveElement = annotationIdentifier(sourceElement) ?: sourceElement
        val elementType = effectiveElement.node?.elementType
        if (elementType != AzoraTokenTypes.IDENTIFIER && elementType != AzoraTokenTypes.TYPE_PARAMETER) {
            return null
        }

        val file = effectiveElement.containingFile ?: return null
        val project = effectiveElement.project
        val filePath = file.virtualFile?.path ?: file.name
        val content = file.text

        val service = AzoraSymbolService.getInstance(project)
        val resolver = AzoraResolver(project, service)

        // Anchor on the identifier itself: `offset` can land on either edge.
        val anchor = effectiveElement.textRange.startOffset

        // On an import clause the dotted path names modules and the symbols one
        // declares - not a value and its members, which is what the ordinary
        // resolver reads. A segment names everything up to and including itself,
        // so `std.container.[list]` navigates to `std`, `std.container` or
        // `std.container.list` depending on which word was clicked.
        AzoraImports.pathAt(content, anchor)?.let { path ->
            val target = service.locateImportPath(project, path)
                ?.let { toElement(it, project, file, filePath) }
            return if (target == null) PsiElement.EMPTY_ARRAY else arrayOf(target)
        }

        val symbols = resolver.resolve(filePath, content, anchor)
        if (symbols.isEmpty()) return null

        val targets = symbols.asSequence()
            .distinctBy { "${it.filePath}:${it.offset}:${it.kind}:${it.name}" }
            .mapNotNull { toElement(it, project, file, filePath) }
            .take(MAX_TARGETS)
            .toList()

        return targets.ifEmpty { null }?.toTypedArray()
    }

    /** Lets Ctrl-click on the `@` itself navigate to the declared annotation/macro. */
    private fun annotationIdentifier(element: PsiElement): PsiElement? {
        if (element.node?.elementType != AzoraTokenTypes.DECORATOR || element.text != "@") return null
        val file = element.containingFile ?: return null
        val match = ANNOTATION_AT.find(file.text, element.textRange.startOffset)
            ?.takeIf { it.range.first == element.textRange.startOffset }
            ?: return null
        val finalName = match.groups[1] ?: return null
        return file.findElementAt(finalName.range.first)
    }

    /** Turns a resolved symbol into a navigable element, or `null`. */
    private fun toElement(
        symbol: SymbolInfo,
        project: Project,
        currentFile: PsiFile,
        currentFilePath: String,
    ): PsiElement? {
        val targetPath = symbol.filePath

        // A local binding or parameter has no file of its own; it lives here.
        if (targetPath == null) {
            return exactElement(currentFile, symbol) ?: elementAtLine(currentFile, symbol.line, symbol.name)
        }
        if (targetPath == SYNTHETIC) return null
        if (targetPath == currentFilePath || targetPath == currentFile.name) {
            return exactElement(currentFile, symbol) ?: elementAtLine(currentFile, symbol.line, symbol.name)
        }

        val virtualFile = findVirtualFile(targetPath, project) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        return exactElement(psiFile, symbol) ?: elementAtLine(psiFile, symbol.line, symbol.name)
    }

    /** Uses the indexer's exact identifier offset when it matches this snapshot. */
    private fun exactElement(file: PsiFile, symbol: SymbolInfo): PsiElement? {
        if (symbol.offset !in 0 until file.textLength) return null
        val sourceName = symbol.name.substringAfterLast("::").substringAfterLast('.')
        val end = symbol.offset + sourceName.length
        if (end > file.textLength || file.text.substring(symbol.offset, end) != sourceName) return null
        return elementAtOffset(file, symbol.offset)
    }

    /**
     * Finds the declaration's own name token on [line], so navigation lands on
     * the identifier rather than the start of the line.
     */
    private fun elementAtLine(file: PsiFile, line: Int, name: String): PsiElement? {
        if (line <= 0) return file
        val document = file.viewProvider.document ?: return file
        val lineIndex = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val start = document.getLineStartOffset(lineIndex)
        val end = document.getLineEndOffset(lineIndex)

        val lineText = document.charsSequence.subSequence(start, end).toString()
        val nameIndex = indexOfWord(lineText, name)
        if (nameIndex >= 0) {
            file.findElementAt(start + nameIndex)?.let { return it }
        }

        var element = file.findElementAt(start)
        while (element != null && element.text.isBlank() && element.nextSibling != null) {
            element = element.nextSibling
        }
        return element ?: file
    }

    private fun elementAtOffset(file: PsiFile, offset: Int): PsiElement? =
        file.findElementAt(offset.coerceIn(0, (file.textLength - 1).coerceAtLeast(0)))

    /** The index of [word] in [text] as a whole word, or `-1`. */
    private fun indexOfWord(text: String, word: String): Int {
        if (word.isEmpty()) return -1
        var from = 0
        while (true) {
            val index = text.indexOf(word, from)
            if (index < 0) return -1
            val beforeOk = index == 0 || !isNameChar(text[index - 1])
            val afterIndex = index + word.length
            val afterOk = afterIndex >= text.length || !isNameChar(text[afterIndex])
            if (beforeOk && afterOk) return index
            from = index + 1
        }
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * Finds the file at [path], first on disk — which covers the SDK and
     * dependency sources that live outside the project — and then by searching
     * the project's own `.az` files.
     */
    private fun findVirtualFile(path: String, project: Project): VirtualFile? {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(path)?.let { return it }
        return FileTypeIndex.getFiles(AzoraFileType.INSTANCE, GlobalSearchScope.allScope(project))
            .firstOrNull { it.path == path || it.name == path }
    }

    private companion object {
        /** File path used by symbols that stand for a module rather than a declaration. */
        const val SYNTHETIC = "<stdlib>"

        /** Ctrl-click shows a chooser beyond one target; keep the list short. */
        const val MAX_TARGETS = 8
        val ANNOTATION_AT = Regex("""@(?:[A-Za-z_$][\w$]*::)*([A-Za-z_$][\w$]*)""")
    }
}

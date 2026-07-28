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

package org.azora.lang.idea

import org.azora.lang.idea.AzoraParserDefinition.Companion.FILE
import com.intellij.lang.*
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.tree.*

/**
 * [ParserDefinition] for the Azora language.
 *
 * Connects the [AzoraLexerAdapter] to IntelliJ's PSI infrastructure.
 * Because full semantic parsing is delegated to the Azora compiler via
 * an external annotator, this definition builds a flat token-level PSI
 * tree rather than a rich AST. Syntax highlighting and basic editor
 * features (brace matching, commenting, etc.) work through the lexer alone.
 */
class AzoraParserDefinition : ParserDefinition {

    /**
     * Creates a new [AzoraLexerAdapter] instance for tokenizing Azora source files.
     *
     * @param project the current project (unused, may be `null`).
     * @return a fresh lexer adapter wrapping the Azora compiler lexer.
     */
    override fun createLexer(project: Project?): Lexer = AzoraLexerAdapter()

    /**
     * Creates a stub [PsiParser] that consumes all tokens into a single root node.
     *
     * No structural parsing is performed, every token is absorbed sequentially
     * and wrapped under the root marker. Real parsing is handled externally by
     * the Azora compiler.
     *
     * @param project the current project (unused, may be `null`).
     * @return a parser that produces a flat AST with all tokens under the root.
     */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        marker.done(root)
        builder.treeBuilt
    }

    /**
     * Returns the [IFileElementType] that serves as the root of every Azora PSI tree.
     *
     * @return [FILE], the singleton file element type for Azora.
     */
    override fun getFileNodeType(): IFileElementType = FILE

    /**
     * Returns the set of token types that represent comments in Azora.
     *
     * IntelliJ uses this to identify comment regions for features such as
     * code folding, TODO indexing, and the "Comment with Line/Block Comment" actions.
     *
     * @return [AzoraTokenTypes.COMMENTS] containing line, block, and doc comment tokens.
     */
    override fun getCommentTokens(): TokenSet = AzoraTokenTypes.COMMENTS

    /**
     * Returns the set of token types that represent string literals in Azora.
     *
     * Used by IntelliJ for features like spell-checking inside strings and
     * language injection.
     *
     * @return [AzoraTokenTypes.STRINGS] containing string and character literal tokens.
     */
    override fun getStringLiteralElements(): TokenSet = AzoraTokenTypes.STRINGS

    /**
     * Returns the set of token types treated as whitespace by the parser.
     *
     * Includes both platform whitespace and Azora newline tokens so that
     * the stub parser skips over them during PSI tree construction.
     *
     * @return a [TokenSet] of [AzoraTokenTypes.WHITE_SPACE] and [AzoraTokenTypes.NEWLINE].
     */
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(AzoraTokenTypes.WHITE_SPACE, AzoraTokenTypes.NEWLINE)

    /**
     * Creates a PSI element from the given AST node.
     *
     * Not implemented because this parser definition produces a flat token
     * tree with no composite (non-leaf) nodes. Any call to this method
     * indicates a programming error.
     *
     * @param node the AST node to wrap.
     * @throws UnsupportedOperationException always.
     */
    override fun createElement(node: ASTNode): PsiElement =
        throw UnsupportedOperationException("Not implemented, using flat token PSI")

    /**
     * Creates the [PsiFile] instance that represents an Azora source file.
     *
     * @param viewProvider the file view provider supplying the file's content.
     * @return a new [AzoraFile] backed by the given view provider.
     */
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        AzoraFile(viewProvider)

    companion object {

        /** The singleton [IFileElementType] used as the root of every Azora PSI tree. */
        val FILE = IFileElementType(AzoraLanguage)
    }
}

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

package org.azora.lang.idea.azon

import org.azora.lang.idea.highlighting.AzoraPalette
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** The AZON data language — Azora's comma-free manifest and data format. */
object AzonLanguage : Language("AZON") {
    private fun readResolve(): Any = AzonLanguage
    override fun getDisplayName(): String = "AZON"
}

/** File type for `.azon` documents (`workspace.azon`, `package.azon`, data files). */
class AzonFileType private constructor() : LanguageFileType(AzonLanguage) {

    override fun getName(): String = "AZON"

    override fun getDescription(): String = "Azora Object Notation"

    override fun getDefaultExtension(): String = "azon"

    override fun getIcon(): Icon = AZON_ICON

    companion object {
        @JvmField
        val INSTANCE = AzonFileType()

        private val AZON_ICON: Icon = IconLoader.getIcon("/icons/azora_icon.png", AzonFileType::class.java)
    }
}

/** Token types produced by [AzonLexer]. */
class AzonTokenType(debugName: String) : IElementType(debugName, AzonLanguage)

object AzonTokenTypes {
    @JvmField val KEY = AzonTokenType("KEY")
    @JvmField val STRING = AzonTokenType("STRING")
    @JvmField val NUMBER = AzonTokenType("NUMBER")
    @JvmField val KEYWORD = AzonTokenType("KEYWORD")
    @JvmField val COMMENT = AzonTokenType("COMMENT")
    @JvmField val COLON = AzonTokenType("COLON")
    @JvmField val COMMA = AzonTokenType("COMMA")
    @JvmField val BRACE = AzonTokenType("BRACE")
    @JvmField val BRACKET = AzonTokenType("BRACKET")
    @JvmField val IDENTIFIER = AzonTokenType("IDENTIFIER")

    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
}

/**
 * Lexer for AZON.
 *
 * A bareword is classified as a [AzonTokenTypes.KEY] when the next
 * non-whitespace character is `:`, which is what lets manifest keys be colored
 * differently from the `true` / `false` / `null` literals.
 */
class AzonLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var bufferStart = 0
    private var bufferEnd = 0
    private var tokenStartOffset = 0
    private var tokenEndOffset = 0
    private var currentType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferStart = startOffset
        this.bufferEnd = endOffset
        this.tokenStartOffset = startOffset
        this.tokenEndOffset = startOffset
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = currentType

    override fun getTokenStart(): Int = tokenStartOffset

    override fun getTokenEnd(): Int = tokenEndOffset

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStartOffset = tokenEndOffset
        if (tokenStartOffset >= bufferEnd) {
            currentType = null
            return
        }

        var i = tokenStartOffset
        val c = buffer[i]
        when {
            c.isWhitespace() -> {
                while (i < bufferEnd && buffer[i].isWhitespace()) i++
                currentType = AzonTokenTypes.WHITE_SPACE
            }
            c == '/' && i + 1 < bufferEnd && buffer[i + 1] == '/' -> {
                while (i < bufferEnd && buffer[i] != '\n') i++
                currentType = AzonTokenTypes.COMMENT
            }
            c == '/' && i + 1 < bufferEnd && buffer[i + 1] == '*' -> {
                i += 2
                while (i + 1 < bufferEnd && !(buffer[i] == '*' && buffer[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(bufferEnd)
                currentType = AzonTokenTypes.COMMENT
            }
            c == '"' -> {
                i++
                while (i < bufferEnd && buffer[i] != '"' && buffer[i] != '\n') {
                    if (buffer[i] == '\\' && i + 1 < bufferEnd) i++
                    i++
                }
                if (i < bufferEnd && buffer[i] == '"') i++
                currentType = if (isKeyPosition(i)) AzonTokenTypes.KEY else AzonTokenTypes.STRING
            }
            c == ':' -> { i++; currentType = AzonTokenTypes.COLON }
            c == ',' -> { i++; currentType = AzonTokenTypes.COMMA }
            c == '{' || c == '}' -> { i++; currentType = AzonTokenTypes.BRACE }
            c == '[' || c == ']' -> { i++; currentType = AzonTokenTypes.BRACKET }
            c.isDigit() || ((c == '-' || c == '+') && i + 1 < bufferEnd && buffer[i + 1].isDigit()) -> {
                i++
                while (i < bufferEnd && (buffer[i].isDigit() || buffer[i] in ".eE+-")) i++
                currentType = AzonTokenTypes.NUMBER
            }
            c.isLetter() || c == '_' -> {
                while (i < bufferEnd && (buffer[i].isLetterOrDigit() || buffer[i] in "_-.")) i++
                val word = buffer.subSequence(tokenStartOffset, i).toString()
                currentType = when {
                    isKeyPosition(i) -> AzonTokenTypes.KEY
                    word == "true" || word == "false" || word == "null" -> AzonTokenTypes.KEYWORD
                    else -> AzonTokenTypes.IDENTIFIER
                }
            }
            else -> { i++; currentType = AzonTokenTypes.BAD_CHARACTER }
        }
        tokenEndOffset = i
    }

    /** True when the next non-whitespace character after [from] is `:`. */
    private fun isKeyPosition(from: Int): Boolean {
        var i = from
        while (i < bufferEnd && buffer[i].isWhitespace() && buffer[i] != '\n') i++
        return i < bufferEnd && buffer[i] == ':'
    }
}

/** Syntax highlighting for AZON, using the shared Azora palette. */
class AzonSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = AzonLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        AzonTokenTypes.KEY -> KEY_KEYS
        AzonTokenTypes.STRING -> STRING_KEYS
        AzonTokenTypes.NUMBER -> NUMBER_KEYS
        AzonTokenTypes.KEYWORD -> KEYWORD_KEYS
        AzonTokenTypes.COMMENT -> COMMENT_KEYS
        AzonTokenTypes.IDENTIFIER -> IDENTIFIER_KEYS
        AzonTokenTypes.COLON, AzonTokenTypes.COMMA -> PUNCTUATION_KEYS
        AzonTokenTypes.BRACE -> BRACE_KEYS
        AzonTokenTypes.BRACKET -> BRACKET_KEYS
        AzonTokenTypes.BAD_CHARACTER -> BAD_CHAR_KEYS
        else -> emptyArray()
    }

    companion object {
        val KEY = createTextAttributesKey("AZON_KEY", AzoraPalette.fg(AzoraPalette.TYPE))
        val STRING = createTextAttributesKey("AZON_STRING", AzoraPalette.fg(AzoraPalette.STRING))
        val NUMBER = createTextAttributesKey("AZON_NUMBER", AzoraPalette.fg(AzoraPalette.FOREGROUND))
        val KEYWORD = createTextAttributesKey("AZON_KEYWORD", AzoraPalette.bold(AzoraPalette.KEYWORD))
        val COMMENT = createTextAttributesKey("AZON_COMMENT", AzoraPalette.italic(AzoraPalette.COMMENT))
        val IDENTIFIER = createTextAttributesKey("AZON_IDENTIFIER", AzoraPalette.fg(AzoraPalette.FOREGROUND))
        val PUNCTUATION = createTextAttributesKey("AZON_PUNCTUATION", AzoraPalette.fg(AzoraPalette.FOREGROUND))
        val BRACE = createTextAttributesKey("AZON_BRACE", AzoraPalette.fg(AzoraPalette.FOREGROUND))
        val BRACKET = createTextAttributesKey("AZON_BRACKET", AzoraPalette.fg(AzoraPalette.FOREGROUND))
        val BAD_CHAR = createTextAttributesKey("AZON_BAD_CHARACTER", AzoraPalette.underlined(AzoraPalette.INVALID))

        private val KEY_KEYS = arrayOf(KEY)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val PUNCTUATION_KEYS = arrayOf(PUNCTUATION)
        private val BRACE_KEYS = arrayOf(BRACE)
        private val BRACKET_KEYS = arrayOf(BRACKET)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHAR)
    }
}

/** Supplies [AzonSyntaxHighlighter] to the platform. */
class AzonSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        AzonSyntaxHighlighter()
}

/** PSI file for `.azon` documents. */
class AzonFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AzonLanguage) {
    override fun getFileType() = AzonFileType.INSTANCE
    override fun toString(): String = "AZON File"
}

/**
 * Flat token-level [ParserDefinition] for AZON. Structure is recovered by
 * [AzonParser] on demand rather than being modelled as PSI, which keeps the
 * manifest tooling simple while still giving highlighting and brace matching.
 */
class AzonParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = AzonLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = AzonTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = AzonTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement =
        throw UnsupportedOperationException("AZON uses a flat token PSI")

    override fun createFile(viewProvider: FileViewProvider): PsiFile = AzonFile(viewProvider)

    companion object {
        val FILE = IFileElementType(AzonLanguage)
    }
}

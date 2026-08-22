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

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILeafElementType
import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.AzoraTokenTypes

/**
 * The token type of a `/** … */`, which builds a comment that knows it is one.
 *
 * The platform renders a doc comment in place - formatted, in a box, turning
 * back into text the moment the caret enters it - for any comment that is a
 * [PsiDocCommentBase]. Azora's PSI is a flat run of leaves, so there is no
 * composite node to make one of; the token type builds the leaf instead.
 */
class AzoraDocCommentType(debugName: String) :
    IElementType(debugName, AzoraLanguage), ILeafElementType {

    override fun createLeafNode(leafText: CharSequence): ASTNode = AzoraDocComment(this, leafText)
}

/**
 * A `/** … */` comment.
 *
 * [getOwner] is what the comment documents - the next declaration under it.
 * The platform shows the rendered text where the comment was, so the owner is
 * only used to decide that the comment belongs to something at all.
 */
class AzoraDocComment(type: IElementType, text: CharSequence) :
    PsiCommentImpl(type, text), PsiDocCommentBase {

    override fun getTokenType(): IElementType = elementType

    /**
     * The declaration this comment is written above.
     *
     * Everything between is whitespace, another comment, or a decorator - none
     * of which is what is being documented, so the search steps over them and
     * stops at the first thing that is.
     */
    override fun getOwner(): PsiElement? {
        var sibling: PsiElement? = nextSibling
        while (sibling != null) {
            val type = sibling.node?.elementType
            val skippable = type == AzoraTokenTypes.WHITE_SPACE ||
                type == AzoraTokenTypes.NEWLINE ||
                type in AzoraTokenTypes.COMMENTS ||
                type == AzoraTokenTypes.DECORATOR
            if (!skippable) return sibling
            sibling = sibling.nextSibling
        }
        return null
    }
}

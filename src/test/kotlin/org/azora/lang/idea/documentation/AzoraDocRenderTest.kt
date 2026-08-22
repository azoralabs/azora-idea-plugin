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

import com.intellij.psi.PsiDocCommentBase
import org.azora.lang.idea.AzoraTokenTypes
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `/** … */` is a comment the platform can render in place.
 *
 * The rendering is the platform's, but only for a comment that *is* a
 * [PsiDocCommentBase] and whose language answers with the text to show.
 */
class AzoraDocRenderTest {

    @Test
    fun `a doc comment leaf is a doc comment`() {
        // Azora's PSI is a flat run of leaves, so the token type is what has to
        // build one - there is no composite node to make it of.
        val type = AzoraTokenTypes.DOC_COMMENT
        assertTrue(
            type is AzoraDocCommentType,
            "DOC_COMMENT must build a doc-comment leaf, was ${type::class.simpleName}",
        )
        val node = (type as AzoraDocCommentType).createLeafNode("/** Adds two numbers. */")
        assertTrue(node is PsiDocCommentBase, "the leaf must be a PsiDocCommentBase")
    }

    @Test
    fun `the rendered text is the prose without its markers`() {
        val comment = AzoraDocComment(
            AzoraTokenTypes.DOC_COMMENT,
            "/**\n * Adds two numbers.\n *\n * @param a the first\n */",
        )

        val rendered = AzoraDocumentationProvider().generateRenderedDoc(comment)

        assertNotNull(rendered)
        assertTrue("Adds two numbers." in rendered!!, rendered)
        // The markers are how a doc comment is written, not what it says.
        assertTrue("/**" !in rendered, rendered)
        assertTrue("*/" !in rendered, rendered)
        assertTrue("<b>@param</b>" in rendered, rendered)
    }
}

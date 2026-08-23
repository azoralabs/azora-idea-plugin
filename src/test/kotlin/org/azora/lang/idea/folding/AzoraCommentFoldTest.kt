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

package org.azora.lang.idea.folding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which block comments fold, and to what.
 *
 * A doc comment folds exactly as an ordinary one does. The licence at the top
 * of a file is the case worth pinning: it used to be built *non-expandable*,
 * which is not a fold that starts closed but a fold that can never be opened -
 * clicking it did nothing and the text was gone.
 */
class AzoraCommentFoldTest {

    private val builder = AzoraFoldingBuilder()

    private fun folds(text: String) = builder.blockCommentFolds(text)

    private fun textOf(source: String, fold: AzoraFoldingBuilder.CommentFold) =
        source.substring(fold.range.first, fold.range.last + 1)

    @Test
    fun `a licence header folds`() {
        val source = "/*\n * Copyright 2026 AzoraLabs\n */\nmodule app.main\n"
        val fold = folds(source).single()

        assertEquals("/*\n * Copyright 2026 AzoraLabs\n */", textOf(source, fold))
        assertEquals("/* ... */", fold.placeholder)
    }

    @Test
    fun `a doc comment folds the same way`() {
        val source = "/**\n * What it does.\n */\nfunc main() {}\n"
        val fold = folds(source).single()

        assertEquals("/**\n * What it does.\n */", textOf(source, fold))
        assertEquals("/** ... */", fold.placeholder)
    }

    @Test
    fun `a header and the doc comments under it all fold`() {
        val source = listOf(
            "/*\n * Copyright 2026 AzoraLabs\n */",
            "module app.main",
            "/**\n * The entry point.\n */",
            "func main() {}",
            "/**\n * A helper.\n */",
            "func helper() {}",
        ).joinToString("\n")

        val placeholders = folds(source).map { it.placeholder }

        assertEquals(listOf("/* ... */", "/** ... */", "/** ... */"), placeholders)
    }

    @Test
    fun `a one-line comment does not fold`() {
        assertTrue(folds("/* just this */\nfunc main() {}\n").isEmpty())
        assertTrue(folds("/** just this */\nfunc main() {}\n").isEmpty())
    }

    @Test
    fun `a nested comment does not end the outer one early`() {
        val source = "/*\n * outer /* inner */ still outer\n */\nfunc main() {}\n"
        val fold = folds(source).single()

        assertTrue(textOf(source, fold).endsWith("*/"), textOf(source, fold))
        assertTrue("still outer" in textOf(source, fold), textOf(source, fold))
    }

    @Test
    fun `an unterminated comment folds to the end of the file`() {
        // Half-typed source is normal. Running off the end is better than
        // looping, and better than dropping the region while it is being typed.
        val source = "/**\n * still writing this\n"
        val fold = folds(source).single()

        assertEquals(source.length - 1, fold.range.last)
    }

    @Test
    fun `a file with no block comment folds nothing`() {
        assertTrue(folds("module app.main\n\nfunc main() {}\n").isEmpty())
    }
}

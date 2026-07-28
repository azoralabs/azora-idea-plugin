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

import com.intellij.lang.Commenter

/**
 * [Commenter] implementation for the Azora language.
 *
 * Enables the "Comment with Line Comment" (`Ctrl+/`) and
 * "Comment with Block Comment" (`Ctrl+Shift+/`) editor actions
 * using Azora's `//` line and block comment syntax.
 */
class AzoraCommenter : Commenter {

    /** Returns the line comment prefix for Azora (`//`). */
    override fun getLineCommentPrefix(): String = "//"

    /** Returns the block comment opening delimiter for Azora. */
    override fun getBlockCommentPrefix(): String = "/*"

    /** Returns the block comment closing delimiter for Azora. */
    override fun getBlockCommentSuffix(): String = "*/"

    /** Returns `null` because Azora does not use a special prefix for nested block comments. */
    override fun getCommentedBlockCommentPrefix(): String? = null

    /** Returns `null` because Azora does not use a special suffix for nested block comments. */
    override fun getCommentedBlockCommentSuffix(): String? = null
}

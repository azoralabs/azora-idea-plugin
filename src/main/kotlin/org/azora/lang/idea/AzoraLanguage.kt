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

import com.intellij.lang.Language

/**
 * Singleton [Language] instance representing the Azora programming language.
 *
 * Registered with the IntelliJ platform to associate Azora-specific services
 * (lexer, parser, highlighter, etc.) with `.azora` files. All Azora
 * [com.intellij.psi.tree.IElementType] instances reference this object as
 * their owning language.
 */
object AzoraLanguage : Language("Azora") {

    /**
     * Ensures deserialization returns the singleton [AzoraLanguage] instance
     * rather than creating a duplicate object.
     */
    private fun readResolve(): Any = AzoraLanguage

    /** Returns `"Azora"` as the human-readable language name shown in the IDE. */
    override fun getDisplayName(): String = "Azora"

    /** Returns `true` because Azora identifiers and keywords are case-sensitive. */
    override fun isCaseSensitive(): Boolean = true
}

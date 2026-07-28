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

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * [LanguageFileType] for Azora source files (`.az` extension).
 *
 * Registered as a singleton via [INSTANCE] in `plugin.xml` so that
 * IntelliJ can associate `.az` files with the Azora language, enabling
 * syntax highlighting, parsing, and all other language-specific features.
 */
class AzoraFileType private constructor() : LanguageFileType(AzoraLanguage) {

    /** Returns `"Azora"` as the unique file type identifier used by IntelliJ internally. */
    override fun getName(): String = "Azora"

    /** Returns a human-readable description shown in file type settings and dialogs. */
    override fun getDescription(): String = "Azora language file"

    /** Returns `"az"` as the default file extension for Azora source files. */
    override fun getDefaultExtension(): String = "az"

    /** Returns the Azora icon displayed in editor tabs, the project tree, and file choosers. */
    override fun getIcon(): Icon = AZORA_ICON

    companion object {

        /** The singleton [AzoraFileType] instance referenced by `plugin.xml` and other services. */
        @JvmField
        val INSTANCE = AzoraFileType()

        /** The icon for Azora files, loaded once from the plugin resources. */
        private val AZORA_ICON: Icon = IconLoader.getIcon("/icons/azora_icon.png", AzoraFileType::class.java)
    }
}

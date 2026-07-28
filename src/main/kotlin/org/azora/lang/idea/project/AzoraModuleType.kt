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

package org.azora.lang.idea.project

import org.azora.lang.idea.AzoraIcons
import com.intellij.openapi.module.ModuleType
import javax.swing.Icon

/**
 * [ModuleType] for Azora modules.
 *
 * Registers the "Azora" module type in the IDE so that Azora projects
 * are recognized and displayed with the correct name, description,
 * and icon in the project structure and module settings.
 */
class AzoraModuleType : ModuleType<AzoraModuleBuilder>(ID) {

    /** Creates a new [AzoraModuleBuilder] for configuring Azora modules. */
    override fun createModuleBuilder(): AzoraModuleBuilder = AzoraModuleBuilder()

    /** Returns `"Azora"` as the display name for this module type. */
    override fun getName(): String = "Azora"

    /** Returns a short description shown in module type selection dialogs. */
    override fun getDescription(): String = "Azora language project"

    /**
     * Returns the Azora icon for the module node in the project tree.
     *
     * @param isOpened whether the node is in its expanded state.
     */
    override fun getNodeIcon(isOpened: Boolean): Icon = AzoraIcons.AZORA

    companion object {

        /** The unique identifier string for the Azora module type. */
        const val ID = "AZORA_MODULE"

        /** The singleton [AzoraModuleType] instance. */
        val INSTANCE = AzoraModuleType()
    }
}

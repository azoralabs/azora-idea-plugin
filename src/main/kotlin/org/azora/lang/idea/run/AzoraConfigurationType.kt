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

package org.azora.lang.idea.run

import org.azora.lang.idea.AzoraIcons
import com.intellij.execution.configurations.*
import javax.swing.Icon

/**
 * [ConfigurationType] for Azora run configurations.
 *
 * Registers the "Azora" entry in the "Run/Debug Configurations" dialog,
 * providing the display name, description, icon, and the
 * [AzoraRunConfigurationFactory] used to create new configurations.
 */
class AzoraConfigurationType : ConfigurationType {

    /** Returns `"Azora"` as the name shown in the configuration type list. */
    override fun getDisplayName(): String = "Azora"

    /** Returns a short description displayed in the configuration dialog. */
    override fun getConfigurationTypeDescription(): String = "Run an Azora file"

    /** Returns the Azora icon displayed next to the configuration type. */
    override fun getIcon(): Icon = AzoraIcons.AZORA

    /** Returns the unique [ID] string identifying this configuration type. */
    override fun getId(): String = ID

    /** Returns an array containing a single [AzoraRunConfigurationFactory]. */
    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(AzoraRunConfigurationFactory(this))

    companion object {

        /** The unique identifier for the Azora run configuration type. */
        const val ID = "AzoraRunConfiguration"
    }
}

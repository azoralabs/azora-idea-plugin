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

import com.intellij.execution.configurations.*
import com.intellij.openapi.project.Project

/**
 * [ConfigurationFactory] for creating [AzoraRunConfiguration] instances.
 *
 * Registered under [AzoraConfigurationType] and used by IntelliJ to
 * instantiate new run configurations from the "Run/Debug Configurations" dialog
 * or from [AzoraRunInterpretProducer] / [AzoraRunNativeProducer].
 *
 * @param type the parent [ConfigurationType] this factory belongs to.
 */
class AzoraRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    /** Returns the unique identifier for this factory, matching [AzoraConfigurationType.ID]. */
    override fun getId(): String = AzoraConfigurationType.ID

    /**
     * Creates a default template [AzoraRunConfiguration] for the given [project].
     *
     * This template is cloned whenever the user creates a new Azora run configuration.
     *
     * @param project the current IntelliJ project.
     * @return a new [AzoraRunConfiguration] with default settings.
     */
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        AzoraRunConfiguration(project, this, "Azora")

    /** Returns [AzoraRunConfigurationOptions] as the persistence class for configuration state. */
    override fun getOptionsClass(): Class<out RunConfigurationOptions> = AzoraRunConfigurationOptions::class.java
}

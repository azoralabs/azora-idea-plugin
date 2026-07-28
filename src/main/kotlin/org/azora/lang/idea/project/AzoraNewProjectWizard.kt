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
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.*
import javax.swing.Icon

/**
 * [GeneratorNewProjectWizard] for creating new Azora projects.
 *
 * Appears as the "Azora" entry in the IDE's "New Project" dialog.
 * The wizard chains the standard base step (project name and location)
 * with [AzoraSdkStep] for SDK path configuration.
 */
class AzoraNewProjectWizard : GeneratorNewProjectWizard {

    /** The unique identifier for this wizard, used internally by IntelliJ. */
    override val id: String = "azora"

    /** The display name shown in the "New Project" generator list. */
    override val name: String = "Azora"

    /** The Azora icon shown next to the wizard name. */
    override val icon: Icon = AzoraIcons.AZORA

    /**
     * Creates the wizard step chain for the given [context].
     *
     * Chains the base step (project name/location) with [AzoraSdkStep]
     * so the user configures both general project settings and the Azora SDK path.
     *
     * @param context the wizard context providing project and IDE state.
     * @return the root [NewProjectWizardStep] of the chained wizard flow.
     */
    override fun createStep(context: WizardContext): NewProjectWizardStep {
        return NewProjectWizardChainStep(NewProjectWizardBaseStep(RootNewProjectWizardStep(context)))
            .nextStep(::AzoraSdkStep)
    }
}

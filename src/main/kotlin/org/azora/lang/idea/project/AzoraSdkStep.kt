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

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import java.awt.Component
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

/**
 * New Project Wizard step that prompts the user for the Azora SDK path.
 *
 * Displays a project-kind chooser and a folder-picker field with validation
 * ensuring the selected directory exists and contains `bin/azora`. On project
 * creation, persists the SDK path to [AzoraSdkSettings] and scaffolds the
 * chosen project shape through [AzoraProjectScaffolder].
 *
 * @param parentStep the parent wizard step this step is nested under.
 */
class AzoraSdkStep(parentStep: NewProjectWizardStep) : AbstractNewProjectWizardStep(parentStep) {

    /** Observable property bound to the SDK path text field, initialized from persisted settings. */
    private val langPathProperty = propertyGraph.property(AzoraSdkSettings.getInstance().state.langPath ?: "")

    /** Chooses between an executable, a library, and a workspace. */
    private val kindCombo = JComboBox(AzoraProjectKind.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
                if (value is AzoraProjectKind) text = value.label
            }
        }
    }

    /** The project shape the user picked. */
    private val projectKind: AzoraProjectKind
        get() = kindCombo.selectedItem as? AzoraProjectKind ?: AzoraProjectKind.EXECUTABLE

    /**
     * Builds the UI for this wizard step.
     *
     * Adds an "Azora SDK path:" row with a folder-picker text field that
     * validates the path on apply (must be non-empty, an existing directory,
     * and contain `bin/azora`).
     *
     * @param builder the DSL panel builder to add UI components to.
     */
    override fun setupUI(builder: Panel) {
        builder.row("Project:") {
            cell(kindCombo)
                .comment("An executable or library is one package; a workspace is several, wired together by AZON manifests.")
        }
        builder.row("Azora SDK path:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            )
                .bindText(langPathProperty)
                .columns(COLUMNS_LARGE)
                .validationOnApply {
                    val path = it.text.trim()
                    when {
                        path.isEmpty() -> error("Azora SDK path must be specified.")
                        !File(path).isDirectory -> error("Azora SDK path does not exist.")
                        !File(path, "bin/azora").exists() -> error("Invalid Azora SDK: bin/azora not found.")
                        else -> null
                    }
                }
                .comment("Directory containing the Azora executable and standard library (e.g. ~/.azoralang)")
        }
    }

    /**
     * Called after the wizard finishes to configure the newly created project.
     *
     * Persists the selected SDK path and scaffolds the chosen project shape:
     * an AZON manifest, sources, and a test block.
     *
     * @param project the newly created IntelliJ project.
     */
    override fun setupProject(project: Project) {
        super.setupProject(project)

        AzoraSdkSettings.getInstance().state.langPath = langPathProperty.get()

        val basePath = project.basePath ?: return
        val root = File(basePath)
        AzoraProjectScaffolder.scaffold(root, root.name, projectKind)
    }
}

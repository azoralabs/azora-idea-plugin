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
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.*
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import java.nio.file.Path
import javax.swing.*

/**
 * Action that opens the [AzoraNewProjectDialog] and scaffolds a new Azora
 * project of the chosen kind — executable, library, or multi-module workspace —
 * with AZON manifests, sources, and tests, then opens it.
 */
class AzoraNewProjectAction : AnAction(
    "Azora Project",
    "Create a new Azora project",
    AzoraIcons.AZORA
) {
    override fun actionPerformed(e: AnActionEvent) {
        val dialog = AzoraNewProjectDialog()
        if (!dialog.showAndGet()) return

        AzoraSdkSettings.getInstance().state.langPath = dialog.langPath

        val projectDir = File(dialog.projectLocation, dialog.projectName)
        AzoraProjectScaffolder.scaffold(projectDir, dialog.projectName, dialog.projectKind)

        ProjectUtil.openOrImport(Path.of(projectDir.absolutePath), null, true)
    }
}

/**
 * Modal dialog for creating a new Azora project.
 *
 * Presents three fields:
 * - **Name** - the project name (defaults to `"untitled"`).
 * - **Location** - the parent directory (defaults to `~/AzoraProjects`).
 * - **Azora SDK path** - the SDK root directory (defaults to persisted or `~/.azoralang`).
 *
 * Validates that all fields are filled, the SDK path exists, and `bin/azora` is present.
 */
private class AzoraNewProjectDialog : DialogWrapper(true) {

    /** Text field for entering the project name. */
    private val nameField = JTextField("untitled")

    /** Text field with browse button for selecting the project parent directory. */
    private val locationField = TextFieldWithBrowseButton()

    /** Text field with browse button for selecting the Azora SDK root directory. */
    private val langPathField = TextFieldWithBrowseButton()

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

    /** Explains what the selected project kind generates. */
    private val kindDescription = JLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    /** The project shape the user picked. */
    val projectKind: AzoraProjectKind
        get() = kindCombo.selectedItem as? AzoraProjectKind ?: AzoraProjectKind.EXECUTABLE

    /** The trimmed project name entered by the user. */
    val projectName: String get() = nameField.text.trim()

    /** The trimmed project location path entered by the user. */
    val projectLocation: String get() = locationField.text.trim()

    /** The trimmed Azora SDK path entered by the user. */
    val langPath: String get() = langPathField.text.trim()

    init {
        title = "New Azora Project"
        val defaultLocation = System.getProperty("user.home") + File.separator + "AzoraProjects"
        locationField.text = defaultLocation

        locationField.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        val savedPath = AzoraSdkSettings.getInstance().state.langPath
        langPathField.text = if (savedPath.isNullOrEmpty())
            System.getProperty("user.home") + "/.azoralang"
        else savedPath
        langPathField.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        kindCombo.addActionListener { updateKindDescription() }
        updateKindDescription()

        init()
    }

    private fun updateKindDescription() {
        kindDescription.text = "<html><small>${projectKind.description}</small></html>"
    }

    /**
     * Builds the dialog's center panel with a [GridBagLayout] containing
     * the name, location, and SDK path fields plus a help label.
     *
     * @return the root [JComponent] of the dialog content.
     */
    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(560, 260)
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
        }

        // Project name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        panel.add(nameField, gbc)

        // Location
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JLabel("Location:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        panel.add(locationField, gbc)

        // Project kind
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JLabel("Project:"), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0
        panel.add(kindCombo, gbc)

        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 1.0
        panel.add(kindDescription, gbc)

        // SDK path
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0
        panel.add(JLabel("Azora SDK path:"), gbc)
        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 1.0
        panel.add(langPathField, gbc)

        // Help text
        gbc.gridx = 1; gbc.gridy = 5; gbc.weightx = 1.0
        val helpLabel = JLabel("<html><small>Directory containing the Azora executable and standard library (e.g. ~/.azoralang)</small></html>")
        helpLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(helpLabel, gbc)

        // Spacer
        gbc.gridx = 0; gbc.gridy = 6; gbc.weighty = 1.0; gbc.gridwidth = 2
        panel.add(JPanel(), gbc)

        return panel
    }

    /**
     * Validates the dialog fields before allowing the user to proceed.
     *
     * Checks that the project name and location are non-empty, the SDK path
     * exists as a directory, and contains `bin/azora`.
     *
     * @return a [ValidationInfo] describing the first error found, or `null` if valid.
     */
    override fun doValidate(): ValidationInfo? {
        if (projectName.isEmpty()) {
            return ValidationInfo("Project name must not be empty.", nameField)
        }
        if (projectLocation.isEmpty()) {
            return ValidationInfo("Project location must not be empty.", locationField)
        }
        if (langPath.isEmpty()) {
            return ValidationInfo("Azora SDK path must be specified.", langPathField)
        }
        if (!File(langPath).isDirectory) {
            return ValidationInfo("Azora SDK path does not exist.", langPathField)
        }
        if (!File(langPath, "bin/azora").exists()) {
            return ValidationInfo("Invalid Azora SDK: bin/azora not found.", langPathField)
        }
        return null
    }
}

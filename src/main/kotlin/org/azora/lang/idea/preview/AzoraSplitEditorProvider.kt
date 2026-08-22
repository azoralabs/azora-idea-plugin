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

package org.azora.lang.idea.preview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.azora.lang.idea.AzoraFileType

/**
 * Opens an `.az` file as its source beside [AzoraIrPreview].
 *
 * The same shape a Markdown file opens in, and for the same reason: the second
 * view is derived from the first and is only worth reading next to it. The
 * editor is shown alone until the preview is asked for, so nothing about
 * opening a file changes for someone who never wants it.
 */
class AzoraSplitEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType == AzoraFileType.INSTANCE

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val editor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return TextEditorWithPreview(
            editor,
            AzoraIrPreview(project, file),
            "Azora IR",
            TextEditorWithPreview.Layout.SHOW_EDITOR,
        )
    }

    override fun getEditorTypeId(): String = "azora-source-with-ir"

    /**
     * Hides the plain text editor this one contains.
     *
     * Without it the file opens twice - once as source and once as source with
     * a preview - and the tab a click lands on is whichever registered last.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

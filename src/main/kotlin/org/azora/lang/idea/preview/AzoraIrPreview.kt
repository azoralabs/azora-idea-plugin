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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.Alarm
import org.azora.lang.idea.AzoraFileType
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent

/**
 * The IR a file becomes, beside the file.
 *
 * Two panes: the IR as generated, and the IR after optimization. Both are what
 * `azora compile ir` prints for *this file's* declarations - the standard
 * library a file reaches is most of what a full dump contains and none of what
 * a reader is asking about, so `--file-only` leaves it out.
 *
 * The compiler is asked, not imitated. Nothing here models lowering: a preview
 * that agreed with the editor's guess instead of the compiler would be worse
 * than none, because it would be believed.
 */
class AzoraIrPreview(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val generated = pane()
    private val optimized = pane()

    private val tabs = JBTabbedPane().apply {
        addTab("IR", generated.component)
        addTab("Optimized IR", optimized.component)
    }

    /**
     * Coalesces typing into one run.
     *
     * A compile is most of a second, so one per keystroke would be a queue that
     * never empties. Waiting for a pause costs a pause and shows the file as it
     * stands rather than as it stood.
     */
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** The text the panes were last filled from, so an idle wake-up does nothing. */
    private var rendered: String? = null

    init {
        document()?.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = schedule()
            },
            this,
        )
        schedule(immediate = true)
    }

    /**
     * A read-only editor, coloured as Azora.
     *
     * The IR *is* Azora - `fin value: __Tuple_Int_String_Double = …` is a
     * declaration a reader reads the same way as the one it came from - so it
     * is shown through the same highlighter rather than as a wall of grey text
     * beside a coloured file.
     */
    private fun pane(): EditorEx {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(factory.createDocument(""), project) as EditorEx
        editor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, AzoraFileType.INSTANCE)
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isIndentGuidesShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        return editor
    }

    private fun document(): Document? = FileDocumentManager.getInstance().getDocument(file)

    private fun schedule(immediate: Boolean = false) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, if (immediate) 0 else REFRESH_DELAY_MS)
    }

    private fun refresh() {
        val source = document()?.text ?: return
        if (source == rendered) return
        rendered = source
        ApplicationManager.getApplication().executeOnPooledThread {
            val ir = AzoraIrDumper.dump(project, file, source, optimized = false)
            val opt = AzoraIrDumper.dump(project, file, source, optimized = true)
            ApplicationManager.getApplication().invokeLater {
                if (source != rendered) return@invokeLater
                show(generated, ir)
                show(optimized, opt)
            }
        }
    }

    /** Refills [editor], keeping the reader where they were in it. */
    private fun show(editor: EditorEx, text: String) {
        val caret = editor.caretModel.offset
        ApplicationManager.getApplication().runWriteAction {
            editor.document.setText(text)
        }
        editor.caretModel.moveToOffset(caret.coerceIn(0, editor.document.textLength))
    }

    override fun getComponent(): JComponent = tabs

    override fun getPreferredFocusedComponent(): JComponent = generated.contentComponent

    override fun getName(): String = "Azora IR"

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    /**
     * The two viewers, which the platform does not own.
     *
     * The alarm and the document listener were registered against this editor,
     * so the platform disposes them with it. An editor made by [EditorFactory]
     * is released by whoever made it, and leaking one leaks its document and
     * every listener on it.
     */
    override fun dispose() {
        val factory = EditorFactory.getInstance()
        factory.releaseEditor(generated)
        factory.releaseEditor(optimized)
    }

    private companion object {
        const val REFRESH_DELAY_MS = 700
    }
}

/**
 * Runs the compiler for its IR.
 *
 * The text is compiled as it stands in the editor, saved or not, which is what
 * makes the pane a view of the file rather than of its last save. An unsaved
 * buffer is written beside the real file so that whatever the file imports
 * resolves the way it does for the real one.
 */
object AzoraIrDumper {

    fun dump(project: Project, file: VirtualFile, source: String, optimized: Boolean): String {
        val binary = compilerBinary() ?: return NO_SDK
        val saved = FileDocumentManager.getInstance().getDocument(file)?.let {
            !FileDocumentManager.getInstance().isDocumentUnsaved(it)
        } ?: true

        var scratch: File? = null
        val target = if (saved) {
            File(file.path)
        } else {
            val directory = File(file.path).parentFile
            File(directory, ".azora-preview-${file.nameWithoutExtension}.az")
                .also { it.writeText(source); scratch = it }
        }
        return try {
            run(binary, target, optimized)
        } finally {
            scratch?.delete()
        }
    }

    private fun run(binary: File, target: File, optimized: Boolean): String {
        val command = mutableListOf(binary.path, "compile", "ir", "--file-only")
        if (!optimized) command += "--debug"
        command += target.path
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text.ifBlank { "(nothing - this file declares no runtime items)" }
        } catch (failure: Exception) {
            "Could not run the compiler:\n${failure.message}"
        }
    }

    /** The installed `azora`, or null when the SDK has no binary to run. */
    private fun compilerBinary(): File? {
        val sdk = runCatching {
            org.azora.lang.idea.project.AzoraSdkSettings.getInstance().sdkPath()
        }.getOrNull() ?: return null
        return File(sdk, "bin/azora").takeIf { it.canExecute() }
    }

    private const val NO_SDK =
        "The Azora SDK has no compiler to ask.\n\n" +
            "`bin/azora` is missing from the configured SDK, so there is no IR to show.\n" +
            "Build and install one with ./install.sh, or point the SDK at a tree that has it."
}

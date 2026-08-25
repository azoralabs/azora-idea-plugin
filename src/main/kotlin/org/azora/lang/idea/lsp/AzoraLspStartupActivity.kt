/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.lsp

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

/** Starts the project-scoped AZLS process after the project is ready. */
class AzoraLspStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.service<AzoraLspService>()
        project.messageBus.connect(service).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file.extension.equals("az", true)) service.close(file)
                }
            },
        )
    }
}

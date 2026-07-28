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

package org.azora.lang.idea.build

import org.azora.lang.idea.AzoraIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.*
import java.util.function.Function
import javax.swing.JComponent

/**
 * [EditorNotificationProvider] that shows a banner when an Azora manifest
 * (`workspace.azon`, `package.azon`, or the legacy `azora.toml`) has been
 * edited and the project model is out of date.
 *
 * The banner appears at the top of `.az` files and manifests when
 * [AzoraProjectConfigService.needsSync] is `true`, offering a sync action that
 * re-reads the manifests and regenerates run configurations.
 */
class AzoraManifestSyncNotification : EditorNotificationProvider {

    /**
     * Collects notification data for the given [file] in [project].
     *
     * @return a factory producing the banner when a sync is pending, or one
     *   that yields `null` when nothing needs syncing.
     */
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?> {
        return Function { _ ->
            val service = AzoraProjectConfigService.getInstance(project)
            if (!service.needsSync) return@Function null

            val relevant = file.extension == "az" || service.isManifest(file.name)
            if (!relevant) return@Function null

            val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            panel.icon(AzoraIcons.AZORA)
            panel.text = "Azora manifest changed. Run configurations and indexed dependencies may be stale."
            panel.createActionLabel("Sync now") {
                service.sync()
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
            panel.createActionLabel("Dismiss") {
                service.sync()
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
            panel
        }
    }
}

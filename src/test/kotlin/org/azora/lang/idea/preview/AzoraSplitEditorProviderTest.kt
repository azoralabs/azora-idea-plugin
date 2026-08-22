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

import com.intellij.openapi.fileEditor.FileEditorPolicy
import org.azora.lang.idea.AzoraFileType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The preview is offered for Azora files and replaces the plain editor. */
class AzoraSplitEditorProviderTest {

    private val provider = AzoraSplitEditorProvider()

    @Test
    fun `the file type is the instance, not the companion`() {
        // `file.fileType == AzoraFileType` compiles and is never true - the
        // name without `.INSTANCE` is the companion object, so the preview
        // would have been offered for nothing.
        assertEquals("az", AzoraFileType.INSTANCE.defaultExtension)
    }

    @Test
    fun `the plain editor is hidden so a file opens once`() {
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
        assertEquals("azora-source-with-ir", provider.editorTypeId)
    }
}

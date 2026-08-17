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

package org.azora.lang.idea.azon

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AzonFormattingTest : BasePlatformTestCase() {

    fun testEnterIndentsInsideObject() {
        myFixture.configureByText(AzonFileType.INSTANCE, "package: {<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)

        myFixture.checkResult("package: {\n    <caret>")
    }

    fun testReformatIndentsNestedObjectsAndArrays() {
        val file = myFixture.configureByText(
            AzonFileType.INSTANCE,
            """
            package: {
            name: "app"
            targets: [
            "interpret"
            "native"
            ]
            }
            """.trimIndent(),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }

        assertEquals(
            """
            package: {
                name: "app"
                targets: [
                    "interpret"
                    "native"
                ]
            }
            """.trimIndent(),
            file.text,
        )
    }
}

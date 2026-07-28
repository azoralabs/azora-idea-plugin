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

package org.azora.lang.idea

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

/**
 * PSI file implementation for Azora source files.
 *
 * Each open `.az` file in the editor is represented by an instance of this
 * class, serving as the root of the PSI tree built by [AzoraParserDefinition].
 *
 * @param viewProvider the [FileViewProvider] supplying the file's content and virtual file reference.
 */
class AzoraFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AzoraLanguage) {

    /** Returns the [AzoraFileType] singleton so IntelliJ can identify this file's type. */
    override fun getFileType() = AzoraFileType.INSTANCE
}

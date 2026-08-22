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

package org.azora.lang.idea.symbol

import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext

/**
 * Asks the project's word index which names are written somewhere other than
 * the file that declares them.
 *
 * A member whose only caller is a test is used, and a file that can only see
 * itself would call it dead - both when dimming it and when warning about it.
 * The index answers without opening a file, so both passes can afford to ask.
 *
 * The check is deliberately one-directional: two unrelated declarations that
 * share a name will vouch for each other. Being wrong in the direction of
 * *saying nothing* is the only safe way to be wrong about dead code.
 */
object AzoraProjectWords {

    /**
     * Which of [names] appear in project code outside [file].
     *
     * Returns every name when the index cannot be reached, so a failure here
     * silences the passes that depend on it rather than misleading them.
     */
    fun writtenOutside(file: PsiFile, names: Collection<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val project = file.project
        val cache = runCatching { CacheManager.getInstance(project) }.getOrNull()
            ?: return names.toSet()
        val scope = GlobalSearchScope.projectScope(project)
        val here = file.virtualFile
        return names.filterTo(linkedSetOf()) { name ->
            runCatching {
                cache.getFilesWithWord(name, UsageSearchContext.IN_CODE, scope, true)
                    .any { it.virtualFile != here }
            }.getOrDefault(true)
        }
    }
}

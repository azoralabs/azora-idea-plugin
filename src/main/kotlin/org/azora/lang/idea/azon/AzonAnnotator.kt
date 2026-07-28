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

import org.azora.lang.idea.build.AzoraProjectConfigService
import org.azora.lang.idea.build.VALID_TARGETS
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.io.File

/**
 * Reports problems in `.azon` documents: syntax the reader could not make sense
 * of, and — in a manifest specifically — targets the toolchain does not know
 * and dependency paths that do not exist on disk.
 *
 * Checking dependency paths matters because a wrong `path:` is silent
 * otherwise: the package simply never gets indexed, and its types and macros
 * quietly stop resolving.
 */
class AzonAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile as? AzonFile ?: return
        // The whole document is checked once, anchored on the file element.
        if (element !== file) return

        val text = file.text
        val document = AzonParser.parse(text)

        for (problem in document.problems) {
            val start = problem.offset.coerceIn(0, text.length)
            val end = (start + 1).coerceAtMost(text.length)
            if (start >= end) continue
            holder.newAnnotation(HighlightSeverity.ERROR, problem.message)
                .range(TextRange(start, end))
                .textAttributes(AzoraSyntaxHighlighter.ERROR)
                .create()
        }

        val isManifest = runCatching {
            AzoraProjectConfigService.getInstance(file.project).isManifest(file.name)
        }.getOrDefault(file.name.endsWith(".azon"))
        if (isManifest) annotateManifest(file, text, document, holder)
    }

    /** Checks the parts of a manifest whose values have a fixed meaning. */
    private fun annotateManifest(
        file: AzonFile,
        text: String,
        document: AzonDocument,
        holder: AnnotationHolder,
    ) {
        val pkg = document["package"] ?: document["workspace"]

        val targets = (document["targets"] ?: pkg?.get("targets"))?.asStringList.orEmpty()
        for (target in targets) {
            if (target in VALID_TARGETS) continue
            val range = findQuoted(text, target) ?: continue
            holder.newAnnotation(
                HighlightSeverity.WARNING,
                "'$target' is not a target the Azora toolchain supports. " +
                    "Supported targets: ${VALID_TARGETS.sorted().joinToString(", ")}."
            ).range(range).textAttributes(AzoraSyntaxHighlighter.WARNING).create()
        }

        val manifestDir = file.virtualFile?.parent?.path?.let(::File) ?: return
        val dependencies = (document["dependencies"] ?: pkg?.get("dependencies"))?.members.orEmpty()
        for ((name, value) in dependencies) {
            val path = value.asString ?: value["path"]?.asString ?: continue
            if (File(manifestDir, path).isDirectory || File(path).isDirectory) continue
            val range = findQuoted(text, path) ?: continue
            holder.newAnnotation(
                HighlightSeverity.WARNING,
                "Dependency '$name' points at '$path', which does not exist. " +
                    "Its sources will not be indexed."
            ).range(range).textAttributes(AzoraSyntaxHighlighter.WARNING).create()
        }

        val members = (document["members"] ?: pkg?.get("members"))?.asStringList.orEmpty()
        for (member in members) {
            if (File(manifestDir, member).isDirectory) continue
            val range = findQuoted(text, member) ?: continue
            holder.newAnnotation(HighlightSeverity.WARNING, "Workspace member '$member' does not exist")
                .range(range)
                .textAttributes(AzoraSyntaxHighlighter.WARNING)
                .create()
        }
    }

    /** The range of the first occurrence of [value] as a quoted string. */
    private fun findQuoted(text: String, value: String): TextRange? {
        val index = text.indexOf("\"$value\"")
        if (index < 0) return null
        return TextRange(index, index + value.length + 2)
    }
}

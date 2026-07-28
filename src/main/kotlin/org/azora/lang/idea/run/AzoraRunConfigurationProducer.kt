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

package org.azora.lang.idea.run

import org.azora.lang.idea.build.AzoraProjectConfigService
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Matches `func main(…)` / `task main(…)`, with or without leading modifiers. */
internal val AZORA_ENTRY_POINT = Regex("""(?m)^\s*(?:\w+\s+)*(?:func|task)\s+main\s*\(""")

/** Matches a `test "…" { … }` block. */
internal val AZORA_TEST_BLOCK = Regex("""(?m)^\s*test\b""")

/** Shared plumbing for the Azora run-configuration producers. */
abstract class AzoraRunConfigurationProducerBase : LazyRunConfigurationProducer<AzoraRunConfiguration>() {

    final override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(AzoraConfigurationType::class.java)
            .configurationFactories[0]

    /** The `.az` file the context points at, or `null`. */
    protected fun contextFile(context: ConfigurationContext): Pair<PsiFile, VirtualFile>? {
        val psiFile = context.location?.psiElement?.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        if (virtualFile.extension != "az") return null
        return psiFile to virtualFile
    }
}

/**
 * Produces a "run tests" configuration for a `.az` file containing at least one
 * `test` block, so the gutter icon on a `test` keyword runs the tests.
 */
class AzoraRunTestProducer : AzoraRunConfigurationProducerBase() {

    override fun isConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val (_, virtualFile) = contextFile(context) ?: return false
        return configuration.isTest && configuration.filePath == virtualFile.path
    }

    override fun setupConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val (psiFile, virtualFile) = contextFile(context) ?: return false
        if (!AZORA_TEST_BLOCK.containsMatchIn(psiFile.text)) return false

        configuration.filePath = virtualFile.path
        configuration.mode = MODE_TEST
        configuration.target = "interpret"
        configuration.name = "Test ${virtualFile.nameWithoutExtension}"
        return true
    }
}

/**
 * Produces an "interpret" run configuration for a `.az` file with a
 * `func main` or `task main` entry point.
 */
class AzoraRunInterpretProducer : AzoraRunConfigurationProducerBase() {

    override fun isConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val (_, virtualFile) = contextFile(context) ?: return false
        return !configuration.isTest &&
            configuration.filePath == virtualFile.path &&
            configuration.target == "interpret"
    }

    override fun setupConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val (psiFile, virtualFile) = contextFile(context) ?: return false
        if (!AZORA_ENTRY_POINT.containsMatchIn(psiFile.text)) return false

        configuration.filePath = virtualFile.path
        configuration.mode = MODE_RUN
        configuration.target = "interpret"
        configuration.name = "Run ${virtualFile.nameWithoutExtension} [Interpret]"
        return true
    }
}

/**
 * Produces a "native" run configuration for a `.az` file with an entry point,
 * when the project's manifest declares the `native` target.
 */
class AzoraRunNativeProducer : AzoraRunConfigurationProducerBase() {

    override fun isConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val (_, virtualFile) = contextFile(context) ?: return false
        return !configuration.isTest &&
            configuration.filePath == virtualFile.path &&
            configuration.target == "native"
    }

    override fun setupConfigurationFromContext(
        configuration: AzoraRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val (psiFile, virtualFile) = contextFile(context) ?: return false
        if (!AZORA_ENTRY_POINT.containsMatchIn(psiFile.text)) return false

        val targets = runCatching {
            AzoraProjectConfigService.getInstance(context.project).declaredTargets()
        }.getOrDefault(listOf("interpret"))
        if ("native" !in targets) return false

        configuration.filePath = virtualFile.path
        configuration.mode = MODE_RUN
        configuration.target = "native"
        configuration.name = "Run ${virtualFile.nameWithoutExtension} [Native]"
        return true
    }
}

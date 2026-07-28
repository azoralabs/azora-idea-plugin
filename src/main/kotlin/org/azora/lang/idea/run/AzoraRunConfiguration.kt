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

import org.azora.lang.idea.build.AzoraManifestReader
import org.azora.lang.idea.project.AzoraSdkSettings
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import java.io.File

/** Execution targets backed by the compiler's codegen backends, plus the interpreter. */
val SUPPORTED_RUN_TARGETS = setOf("interpret", "web-js", "web-wasm", "native")

/** Run this configuration as a program. */
const val MODE_RUN = "run"

/** Run this configuration as the file's `test` blocks. */
const val MODE_TEST = "test"

/** Persistent options for [AzoraRunConfiguration]. */
class AzoraRunConfigurationOptions : RunConfigurationOptions() {

    /** The absolute path to the `.az` source file to run. */
    var filePath by string("")

    /** The execution target, defaults to `"interpret"`. */
    var target by string("interpret")

    /** Either [MODE_RUN] or [MODE_TEST]. */
    var mode by string(MODE_RUN)
}

/**
 * Run configuration for Azora programs and test blocks.
 *
 * Three shapes of command are produced:
 *
 * * **test** — `azora test <file>` runs every `test` block in the file.
 * * **interpret** — `azora run <file>` runs the file in the interpreter.
 * * **compiled targets** — `azora-build` builds and runs, using project mode
 *   when the project has a manifest and single-file mode otherwise.
 */
class AzoraRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<AzoraRunConfigurationOptions>(project, factory, name) {

    /** The absolute path to the `.az` source file to run. */
    var filePath: String
        get() = options.filePath ?: ""
        set(value) { options.filePath = value }

    /** The execution target (e.g. `"interpret"`, `"native"`). */
    var target: String
        get() = options.target ?: "interpret"
        set(value) { options.target = value }

    /** Whether this configuration runs the program or its tests. */
    var mode: String
        get() = options.mode ?: MODE_RUN
        set(value) { options.mode = value }

    /** True when this configuration runs `test` blocks rather than the program. */
    val isTest: Boolean get() = mode == MODE_TEST

    override fun getOptions(): AzoraRunConfigurationOptions =
        super.getOptions() as AzoraRunConfigurationOptions

    override fun getConfigurationEditor() = AzoraRunConfigurationEditor(project)

    /**
     * Validates the configuration before execution.
     *
     * `azora-build` is only required for the compiled targets, so an SDK that
     * ships just the `azora` binary can still run and test files.
     */
    override fun checkConfiguration() {
        if (filePath.isEmpty()) {
            throw RuntimeConfigurationError("Azora file is not specified.")
        }
        if (!File(filePath).exists()) {
            throw RuntimeConfigurationError("Azora file does not exist: $filePath")
        }
        if (!isTest && target !in SUPPORTED_RUN_TARGETS) {
            throw RuntimeConfigurationError(
                "Target '$target' is not supported by the current compiler. " +
                    "Use one of: ${SUPPORTED_RUN_TARGETS.joinToString()}."
            )
        }
        val sdkPath = AzoraSdkSettings.getInstance().sdkPath()
        if (!File(sdkPath, "bin/azora").exists()) {
            throw RuntimeConfigurationError("Azora SDK not found at '$sdkPath'. bin/azora is missing.")
        }
        if (needsBuildTool() && !File(sdkPath, "bin/azora-build").exists()) {
            throw RuntimeConfigurationError(
                "Building for '$target' needs bin/azora-build, which is missing from '$sdkPath' — " +
                    "run the SDK installer (./install.sh)."
            )
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(environment) {
            override fun startProcess(): OSProcessHandler {
                val handler = ProcessHandlerFactory.getInstance()
                    .createColoredProcessHandler(buildCommandLine())
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
    }

    /** Whether the selected target has to go through `azora-build`. */
    private fun needsBuildTool(): Boolean = !isTest && target != "interpret"

    /** The command that runs this configuration. */
    internal fun buildCommandLine(): GeneralCommandLine {
        val sdkPath = AzoraSdkSettings.getInstance().sdkPath()
        val azora = File(sdkPath, "bin/azora").absolutePath
        val buildTool = File(sdkPath, "bin/azora-build").absolutePath

        val file = File(filePath)
        val fileDir = file.absoluteFile.parentFile?.absolutePath ?: project.basePath.orEmpty()
        val basePath = project.basePath

        return when {
            isTest ->
                GeneralCommandLine(azora, "test", filePath).withWorkDirectory(fileDir)

            target == "interpret" ->
                GeneralCommandLine(azora, "run", filePath).withWorkDirectory(fileDir)

            // With a manifest present the build tool owns the whole project.
            basePath != null && AzoraManifestReader.findManifest(File(basePath)) != null ->
                GeneralCommandLine(
                    "sh", "-c",
                    "'$buildTool' build --target $target && '$buildTool' run --target $target"
                ).withWorkDirectory(basePath)

            else ->
                GeneralCommandLine(buildTool, "build", "--target", target, filePath).withWorkDirectory(fileDir)
        }.withCharset(Charsets.UTF_8)
    }
}

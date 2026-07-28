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

import org.azora.lang.idea.azon.AzonParser
import org.azora.lang.idea.azon.AzonValue
import org.azora.lang.idea.run.AzoraConfigurationType
import org.azora.lang.idea.run.AzoraRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.EditorNotifications
import java.io.File

/** Every compilation/execution target the Azora toolchain understands. */
val VALID_TARGETS = setOf(
    "interpret",
    "native",
    "web-js",
    "web-wasm",
    "kotlin-jvm",
    "kmp",
    "csharp",
    "python",
)

/** Manifest file names recognized at a project or package root, in priority order. */
val MANIFEST_NAMES = listOf("workspace.azon", "package.azon", "project.azon", "azora.azon")

/** A dependency edge declared by a manifest. */
data class AzoraDependency(
    val name: String,
    /** The dependency root, resolved against the declaring manifest's directory. */
    val path: File?,
    val version: String? = null,
) {
    /**
     * The directory to index for this dependency's Azora sources: its `src/`
     * when it has one, otherwise the dependency root itself.
     */
    val sourceRoot: File?
        get() = path?.let { root ->
            val src = File(root, "src")
            if (src.isDirectory) src else root.takeIf { it.isDirectory }
        }
}

/**
 * A parsed Azora manifest.
 *
 * One shape covers all three manifest flavours: a workspace (which lists
 * [members]), a library package, and an executable package.
 */
data class AzoraManifest(
    val name: String = "",
    val version: String = "",
    /** `"exe"`, `"lib"` or `"workspace"`. */
    val kind: String = "exe",
    /** The module path the package's sources declare, e.g. `app.core`. */
    val module: String = "",
    val entry: String = "main.az",
    val src: String = "src",
    val targets: List<String> = listOf("interpret"),
    val dependencies: List<AzoraDependency> = emptyList(),
    /** Workspace member directories, relative to the manifest. */
    val members: List<String> = emptyList(),
    /** The manifest file this was read from, or `null` when defaulted. */
    val file: File? = null,
) {
    /** Whether this manifest describes a multi-package workspace. */
    val isWorkspace: Boolean get() = members.isNotEmpty()

    /** Whether this package produces a runnable binary. */
    val isExecutable: Boolean get() = kind != "lib" && kind != "workspace"

    /** The manifest's directory, or `null` when defaulted. */
    val root: File? get() = file?.parentFile

    /** The absolute entry-point file, when one can be determined. */
    val entryFile: File?
        get() = root?.let { base ->
            val direct = File(base, entry)
            if (direct.isFile) direct else File(File(base, src), entry).takeIf { it.isFile }
        }

    /** Targets that the toolchain actually supports, never empty. */
    val validTargets: List<String> get() = targets.filter { it in VALID_TARGETS }.ifEmpty { listOf("interpret") }

    companion object {
        val EMPTY = AzoraManifest()
    }
}

/** Reads Azora manifests, in either the `.azon` or the legacy `azora.toml` form. */
object AzoraManifestReader {

    /** Finds the manifest file at [directory], or `null` when there is none. */
    fun findManifest(directory: File): File? {
        for (name in MANIFEST_NAMES) {
            val candidate = File(directory, name)
            if (candidate.isFile) return candidate
        }
        return File(directory, "azora.toml").takeIf { it.isFile }
    }

    /** Reads the manifest at [directory], or [AzoraManifest.EMPTY] when absent. */
    fun read(directory: File): AzoraManifest {
        val manifest = findManifest(directory) ?: return AzoraManifest.EMPTY
        val text = runCatching { manifest.readText() }.getOrNull() ?: return AzoraManifest.EMPTY
        return if (manifest.extension == "azon") parseAzon(text, manifest) else parseToml(text, manifest)
    }

    /**
     * Parses an AZON manifest.
     *
     * Both the workspace form (`workspace: { … }` plus `members: [ … ]`) and
     * the package form (`package: { … }` plus `dependencies: { … }`) are
     * accepted, and bare top-level keys work as a shorthand so a minimal
     * project needs no nesting.
     */
    fun parseAzon(text: String, file: File? = null): AzoraManifest {
        val doc = AzonParser.parse(text)
        val pkg = doc["package"] ?: doc["workspace"] ?: AzonValue.Obj(emptyMap())

        fun str(key: String): String? = pkg[key]?.asString ?: doc[key]?.asString

        val targets = (doc["targets"] ?: pkg["targets"])?.asStringList.orEmpty()
            .filter { it in VALID_TARGETS }

        val dependencies = (doc["dependencies"] ?: pkg["dependencies"])?.members.orEmpty()
            .map { (name, value) ->
                val declaredPath = value.asString ?: value["path"]?.asString
                AzoraDependency(
                    name = name,
                    path = declaredPath?.let { resolve(file, it) },
                    version = value["version"]?.asString,
                )
            }

        val members = (doc["members"] ?: pkg["members"])?.asStringList.orEmpty()

        return AzoraManifest(
            name = str("name").orEmpty(),
            version = str("version").orEmpty(),
            kind = str("kind") ?: str("type") ?: if (members.isNotEmpty()) "workspace" else "exe",
            module = str("module").orEmpty(),
            entry = str("entry") ?: "main.az",
            src = str("src") ?: "src",
            targets = targets.ifEmpty { listOf("interpret") },
            dependencies = dependencies,
            members = members,
            file = file,
        )
    }

    /** Parses the legacy `azora.toml` `[project]` table. */
    fun parseToml(text: String, file: File? = null): AzoraManifest {
        var name = ""
        var version = ""
        var targets = listOf<String>()
        var entry = "Main.az"
        var src = "src"
        var kind = "exe"
        var inProject = false

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inProject = trimmed == "[project]"
                continue
            }
            if (!inProject || !trimmed.contains("=")) continue

            val key = trimmed.substringBefore("=").trim()
            val value = trimmed.substringAfter("=").trim()
            when (key) {
                "name" -> name = value.removeSurrounding("\"")
                "version" -> version = value.removeSurrounding("\"")
                "entry" -> entry = value.removeSurrounding("\"")
                "src" -> src = value.removeSurrounding("\"")
                "type" -> kind = value.removeSurrounding("\"")
                "target" -> targets = listOf(value.removeSurrounding("\""))
                "targets" -> targets = value.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
            }
        }

        return AzoraManifest(
            name = name,
            version = version,
            kind = kind,
            entry = entry,
            src = src,
            targets = targets.ifEmpty { listOf("interpret") },
            file = file,
        )
    }

    private fun resolve(manifest: File?, path: String): File {
        val base = manifest?.parentFile
        val asFile = File(path)
        return when {
            asFile.isAbsolute -> asFile
            base != null -> runCatching { File(base, path).canonicalFile }.getOrDefault(File(base, path))
            else -> asFile
        }
    }
}

/**
 * Project service holding the project's Azora manifests.
 *
 * It reads the root manifest, expands workspace members into their own
 * manifests, exposes the dependency source roots that indexing needs, and
 * creates one run configuration per declared target.
 */
@Service(Service.Level.PROJECT)
class AzoraProjectConfigService(private val project: Project) {

    /** The root manifest, re-read on [sync]. */
    var manifest: AzoraManifest = AzoraManifest.EMPTY
        private set

    /** One manifest per workspace member; empty for a single-package project. */
    var memberManifests: List<AzoraManifest> = emptyList()
        private set

    /** Whether a manifest has been edited since the last [sync]. */
    var needsSync: Boolean = false
        private set

    /** Alias kept for call sites that read the project's manifest as `config`. */
    val config: AzoraManifest get() = manifest

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (isManifest(file.name)) {
                    needsSync = true
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }, project)
    }

    /** True when [fileName] is a manifest this service tracks. */
    fun isManifest(fileName: String): Boolean = fileName in MANIFEST_NAMES || fileName == "azora.toml"

    /** The manifest file the project is configured by, or `null`. */
    fun manifestFile(): File? = project.basePath?.let { AzoraManifestReader.findManifest(File(it)) }

    /**
     * Every source root outside the project's own tree that should be indexed:
     * the dependency paths declared by the root manifest and by each workspace
     * member. Macro and symbol indexing read these so a dependency that
     * declares macros or types is understood.
     */
    fun dependencySourceRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        for (m in listOf(manifest) + memberManifests) {
            m.dependencies.mapNotNull { it.sourceRoot }.forEach(roots::add)
        }
        return roots.toList()
    }

    /** All targets declared anywhere in the project, deduplicated. */
    fun declaredTargets(): List<String> {
        val targets = LinkedHashSet<String>()
        targets.addAll(manifest.validTargets)
        memberManifests.forEach { targets.addAll(it.validTargets) }
        return targets.toList().ifEmpty { listOf("interpret") }
    }

    /** Re-reads the manifests and rebuilds the generated run configurations. */
    fun sync() {
        val basePath = project.basePath ?: return
        val root = File(basePath)
        manifest = AzoraManifestReader.read(root)
        memberManifests = manifest.members
            .map { AzoraManifestReader.read(File(root, it)) }
            .filter { it != AzoraManifest.EMPTY }
        needsSync = false

        if (manifest != AzoraManifest.EMPTY) createRunConfigurations()
    }

    /**
     * Recreates one run configuration per declared target for every runnable
     * package: the root package, plus each executable workspace member.
     */
    private fun createRunConfigurations() {
        val runnable = buildList {
            if (manifest.isExecutable) add(manifest)
            addAll(memberManifests.filter { it.isExecutable })
        }
        if (runnable.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WriteAction.run<RuntimeException> {
                val runManager = RunManager.getInstance(project)
                val type = ConfigurationTypeUtil.findConfigurationType(AzoraConfigurationType::class.java)
                val factory = type.configurationFactories[0]

                runManager.allSettings
                    .filter { it.type == type && it.name.startsWith(GENERATED_PREFIX) }
                    .forEach { runManager.removeConfiguration(it) }

                var first = true
                for (pkg in runnable) {
                    val entry = pkg.entryFile ?: continue
                    for (target in pkg.validTargets) {
                        val label = target.replaceFirstChar { it.uppercase() }
                        val rc = factory.createTemplateConfiguration(project) as AzoraRunConfiguration
                        rc.filePath = entry.absolutePath
                        rc.target = target
                        rc.name = "$GENERATED_PREFIX${pkg.name.ifEmpty { entry.nameWithoutExtension }} [$label]"

                        val settings = runManager.createConfiguration(rc, factory)
                        runManager.addConfiguration(settings)
                        if (first) {
                            runManager.selectedConfiguration = settings
                            first = false
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Marks configurations this service owns, so hand-made ones survive a sync. */
        private const val GENERATED_PREFIX = "Run "

        fun getInstance(project: Project): AzoraProjectConfigService =
            project.getService(AzoraProjectConfigService::class.java)

        /** Parses an AZON manifest; used by tests and the new-project wizard. */
        fun parseAzon(content: String): AzoraManifest = AzoraManifestReader.parseAzon(content)

        /** Parses a legacy `azora.toml`; used by tests. */
        fun parseToml(content: String): AzoraManifest = AzoraManifestReader.parseToml(content)
    }
}

/** Reads the project's manifests once the project has opened. */
class AzoraProjectStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AzoraProjectConfigService.getInstance(project).sync()
    }
}

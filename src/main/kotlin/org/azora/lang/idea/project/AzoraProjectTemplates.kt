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

package org.azora.lang.idea.project

import org.azora.lang.idea.AzoraLanguageFacts
import java.io.File

/** The shapes of project the wizard can create. */
enum class AzoraProjectKind(val label: String, val description: String) {

    /** A single package that builds a runnable binary. */
    EXECUTABLE("Executable", "One package with a `func main` entry point"),

    /** A single package meant to be depended on. */
    LIBRARY("Library", "One package that exposes an API for other packages"),

    /** A workspace of several packages: a core library and an app that uses it. */
    WORKSPACE("Multi-module workspace", "A workspace of packages: a core library and an app that depends on it"),
}

/**
 * Creates the files for a new Azora project.
 *
 * Every generated project is manifest-driven: an executable or library gets a
 * `package.azon`, a workspace gets a `workspace.azon` listing its members and a
 * `package.azon` per member. The manifests are what the plugin reads to find
 * entry points, targets and dependency sources, so a scaffolded project has
 * working run configurations and cross-package resolution immediately.
 */
object AzoraProjectScaffolder {

    /**
     * Writes [kind]'s files under [root].
     *
     * Existing files are left alone, so scaffolding into a directory that
     * already holds work never overwrites it.
     *
     * @param root the project directory; created if missing.
     * @param name the project name, used for the manifest and module paths.
     * @param kind which shape of project to write.
     */
    fun scaffold(root: File, name: String, kind: AzoraProjectKind) {
        root.mkdirs()
        val moduleName = name.toSnakeCase().ifEmpty { "app" }

        when (kind) {
            AzoraProjectKind.EXECUTABLE -> scaffoldExecutable(root, name, moduleName)
            AzoraProjectKind.LIBRARY -> scaffoldLibrary(root, name, moduleName)
            AzoraProjectKind.WORKSPACE -> scaffoldWorkspace(root, name, moduleName)
        }

        write(File(root, ".gitignore"), GITIGNORE)
        write(File(root, "README.md"), readme(name, kind))
    }

    // ── Single-package projects ────────────────────────────────────────

    private fun scaffoldExecutable(root: File, name: String, module: String) {
        write(File(root, "package.azon"), executableManifest(name, module))
        write(
            File(root, "src/main.az"),
            """
            module $module

            import std.io

            /** Prints a greeting. */
            func greet(who: String): String {
                return "Hello, ${'$'}who!"
            }

            func main() {
                println(greet("Azora"))
            }

            test "greet names the caller" {
                assert greet("world") == "Hello, world!"
            }
            """.trimIndent() + "\n"
        )
    }

    private fun scaffoldLibrary(root: File, name: String, module: String) {
        write(File(root, "package.azon"), libraryManifest(name, module))
        write(
            File(root, "src/$module.az"),
            """
            module $module

            /** A point in two dimensions. */
            expose pack Point {
                var x: Real = 0.0
                var y: Real = 0.0
            }

            impl Point {
                /** The distance from this point to [other]. */
                expose func distanceTo(other: Point): Real {
                    fin dx = other.x - self.x
                    fin dy = other.y - self.y
                    return dx * dx + dy * dy
                }
            }

            test "a point is zero distance from itself" {
                fin origin = Point(x: 0.0, y: 0.0)
                assert origin.distanceTo(origin) == 0.0
            }
            """.trimIndent() + "\n"
        )
    }

    // ── Workspaces ─────────────────────────────────────────────────────

    private fun scaffoldWorkspace(root: File, name: String, module: String) {
        val coreName = "$name-core"
        val appName = "$name-app"

        write(File(root, "workspace.azon"), workspaceManifest(name, listOf(coreName, appName)))

        // The core package: a library the app depends on.
        val core = File(root, "packages/$coreName")
        write(File(core, "package.azon"), libraryManifest(coreName, "$module.core"))
        write(
            File(core, "src/core.az"),
            """
            module $module.core

            /** The greeting the application prints. */
            expose func greeting(who: String): String {
                return "Hello, ${'$'}who!"
            }

            test "greeting names the caller" {
                assert greeting("world") == "Hello, world!"
            }
            """.trimIndent() + "\n"
        )

        // The app package: an executable depending on core by relative path.
        val app = File(root, "packages/$appName")
        write(
            File(app, "package.azon"),
            """
            // The runnable package. `dependencies` is what tells the IDE where to
            // find the core package's sources, so its types, functions and macros
            // resolve across the workspace.
            package: {
                name: "$appName"
                version: "0.1.0"
                kind: "exe"
                module: "$module.app"
                entry: "src/main.az"
                src: "src"
            }
            targets: [ "interpret" "native" ]
            dependencies: {
                $coreName: { path: "../$coreName" }
            }
            """.trimIndent() + "\n"
        )
        write(
            File(app, "src/main.az"),
            """
            module $module.app

            import std.io
            import $module.core

            func main() {
                println(greeting("Azora"))
            }
            """.trimIndent() + "\n"
        )
    }

    // ── Manifests ──────────────────────────────────────────────────────

    private fun executableManifest(name: String, module: String) = """
        // Azora package manifest (AZON). Members are `key: value` pairs, one per
        // line — no commas needed.
        package: {
            name: "$name"
            version: "0.1.0"
            kind: "exe"
            module: "$module"
            entry: "src/main.az"
            src: "src"
        }

        // One run configuration is generated per target listed here.
        targets: [ "interpret" "native" ]

        // Path dependencies are indexed for completion and navigation:
        // dependencies: { other: { path: "../other" } }
        dependencies: { }
    """.trimIndent() + "\n"

    private fun libraryManifest(name: String, module: String) = """
        // Azora package manifest (AZON).
        package: {
            name: "$name"
            version: "0.1.0"
            kind: "lib"
            module: "$module"
            src: "src"
        }

        targets: [ "interpret" ]

        dependencies: { }
    """.trimIndent() + "\n"

    private fun workspaceManifest(name: String, members: List<String>) = """
        // Azora workspace manifest (AZON). Each member is a package directory
        // with its own package.azon.
        workspace: {
            name: "$name"
            version: "0.1.0"
            edition: "azora-${AzoraLanguageFacts.LANGUAGE_VERSION}"
        }

        members: [
        ${members.joinToString("\n") { "    \"packages/$it\"" }}
        ]

        // Metadata inherited by members that omit it.
        package: {
            authors: [ ]
            license: "Apache-2.0"
        }
    """.trimIndent() + "\n"

    private fun readme(name: String, kind: AzoraProjectKind) = """
        # $name

        ${kind.description}.

        ## Build and run

        ```bash
        azora run src/main.az      # run in the interpreter
        azora test src             # run every test block
        azora-build build --target native
        ```

        The project is described by its AZON manifest; edit it to add targets or
        path dependencies, then use **Sync now** in the editor banner.
    """.trimIndent() + "\n"

    private val GITIGNORE = """
        build/
        dist/
        *.ll
        *.wasm
        .DS_Store
    """.trimIndent() + "\n"

    /** Writes [content] to [file] unless it already exists. */
    private fun write(file: File, content: String) {
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}

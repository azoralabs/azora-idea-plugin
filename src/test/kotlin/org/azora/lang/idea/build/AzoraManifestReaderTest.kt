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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** Tests for reading `.azon` package and workspace manifests. */
class AzoraManifestReaderTest {

    @Test
    fun `reads an executable package manifest`() {
        val manifest = AzoraManifestReader.parseAzon(
            """
            package: {
                name: "my-app"
                version: "0.1.0"
                kind: "exe"
                module: "my_app"
                entry: "src/main.az"
                src: "src"
            }
            targets: [ "interpret" "native" ]
            """.trimIndent()
        )

        assertEquals("my-app", manifest.name)
        assertEquals("0.1.0", manifest.version)
        assertEquals("exe", manifest.kind)
        assertEquals("my_app", manifest.module)
        assertEquals(listOf("interpret", "native"), manifest.targets)
        assertTrue(manifest.isExecutable)
        assertFalse(manifest.isWorkspace)
    }

    @Test
    fun `a library package is not runnable`() {
        val manifest = AzoraManifestReader.parseAzon(
            """
            package: { name: "core" kind: "lib" }
            """.trimIndent()
        )

        assertFalse(manifest.isExecutable)
    }

    @Test
    fun `reads a workspace manifest and its members`() {
        val manifest = AzoraManifestReader.parseAzon(
            """
            workspace: {
                name: "azora-engine"
                version: "0.1.0"
                edition: "azora-0.0.4"
            }

            members: [
                "packages/azora-core"
                "packages/azora-ecs"
            ]
            """.trimIndent()
        )

        assertEquals("azora-engine", manifest.name)
        assertTrue(manifest.isWorkspace)
        assertEquals(listOf("packages/azora-core", "packages/azora-ecs"), manifest.members)
        // A workspace root is not itself something to run.
        assertFalse(manifest.isExecutable)
    }

    @Test
    fun `resolves dependency paths against the manifest directory`(@TempDir dir: Path) {
        val root = dir.toFile()
        val core = File(root, "packages/core/src").apply { mkdirs() }
        val appDir = File(root, "packages/app").apply { mkdirs() }
        val appManifest = File(appDir, "package.azon")
        appManifest.writeText(
            """
            package: { name: "app" kind: "exe" }
            dependencies: {
                core: { path: "../core" }
            }
            """.trimIndent()
        )

        val manifest = AzoraManifestReader.read(appDir)
        val dependency = manifest.dependencies.single()

        assertEquals("core", dependency.name)
        // The path is relative to the manifest, and `src/` is what gets indexed.
        assertEquals(core.canonicalFile, dependency.sourceRoot?.canonicalFile)
    }

    @Test
    fun `a dependency without a src directory indexes its root`(@TempDir dir: Path) {
        val root = dir.toFile()
        File(root, "lib").mkdirs()
        val pkg = File(root, "app").apply { mkdirs() }
        File(pkg, "package.azon").writeText(
            """
            package: { name: "app" }
            dependencies: { lib: { path: "../lib" } }
            """.trimIndent()
        )

        val dependency = AzoraManifestReader.read(pkg).dependencies.single()
        assertEquals(File(root, "lib").canonicalFile, dependency.sourceRoot?.canonicalFile)
    }

    @Test
    fun `unknown targets are dropped rather than offered as run configurations`() {
        val manifest = AzoraManifestReader.parseAzon(
            """
            package: { name: "app" }
            targets: [ "interpret" "quantum" ]
            """.trimIndent()
        )

        assertEquals(listOf("interpret"), manifest.targets)
    }

    @Test
    fun `finds the entry point through the src directory`(@TempDir dir: Path) {
        val root = dir.toFile()
        File(root, "src").mkdirs()
        File(root, "src/main.az").writeText("func main() {}\n")
        File(root, "package.azon").writeText("""package: { name: "app" entry: "src/main.az" }""")

        val manifest = AzoraManifestReader.read(root)
        assertEquals(File(root, "src/main.az").canonicalFile, manifest.entryFile?.canonicalFile)
    }

    @Test
    fun `prefers a workspace manifest over a package manifest at the same root`(@TempDir dir: Path) {
        val root = dir.toFile()
        File(root, "package.azon").writeText("""package: { name: "inner" }""")
        File(root, "workspace.azon").writeText("""workspace: { name: "outer" }""")

        assertEquals("outer", AzoraManifestReader.read(root).name)
    }

    @Test
    fun `still reads a legacy azora toml project`(@TempDir dir: Path) {
        val root = dir.toFile()
        File(root, "azora.toml").writeText(
            """
            [project]
            name = "legacy"
            targets = ["interpret"]
            """.trimIndent()
        )

        val manifest = AzoraManifestReader.read(root)
        assertEquals("legacy", manifest.name)
        assertEquals(listOf("interpret"), manifest.targets)
    }
}

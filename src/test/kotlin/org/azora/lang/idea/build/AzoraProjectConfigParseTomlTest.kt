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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AzoraProjectConfigService.parseToml].
 */
class AzoraProjectConfigParseTomlTest {

    @Test
    fun `parses complete valid TOML`() {
        val toml = """
            [project]
            name = "my_app"
            version = "1.0.0"
            targets = ["interpret", "native", "web-js"]
            entry = "App.az"
            src = "source"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals("my_app", config.name)
        assertEquals("1.0.0", config.version)
        assertEquals(listOf("interpret", "native", "web-js"), config.targets)
        assertEquals("App.az", config.entry)
        assertEquals("source", config.src)
    }

    @Test
    fun `uses defaults for missing fields`() {
        val toml = """
            [project]
            name = "test"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals("test", config.name)
        assertEquals("", config.version)
        assertEquals(listOf("interpret"), config.targets)
        assertEquals("Main.az", config.entry)
        assertEquals("src", config.src)
    }

    @Test
    fun `parses single target`() {
        val toml = """
            [project]
            target = "native"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals(listOf("native"), config.targets)
    }

    @Test
    fun `parses multiple targets`() {
        val toml = """
            [project]
            targets = ["interpret", "native", "web-js"]
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals(3, config.targets.size)
        assertTrue(config.targets.contains("interpret"))
        assertTrue(config.targets.contains("native"))
        assertTrue(config.targets.contains("web-js"))
    }

    @Test
    fun `empty content returns defaults`() {
        val config = AzoraProjectConfigService.parseToml("")
        assertEquals("", config.name)
        assertEquals(listOf("interpret"), config.targets)
        assertEquals("Main.az", config.entry)
    }

    @Test
    fun `ignores non-project sections`() {
        val toml = """
            [dependencies]
            name = "should_be_ignored"

            [project]
            name = "actual_name"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals("actual_name", config.name)
    }

    @Test
    fun `handles whitespace around equals sign`() {
        val toml = """
            [project]
            name   =   "spaced_out"
            version  =  "2.0.0"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals("spaced_out", config.name)
        assertEquals("2.0.0", config.version)
    }

    @Test
    fun `handles blank lines in TOML`() {
        val toml = """
            [project]

            name = "with_blanks"

            version = "1.0.0"
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals("with_blanks", config.name)
        assertEquals("1.0.0", config.version)
    }

    @Test
    fun `empty targets list defaults to interpret`() {
        val toml = """
            [project]
            targets = []
        """.trimIndent()

        val config = AzoraProjectConfigService.parseToml(toml)
        assertEquals(listOf("interpret"), config.targets)
    }
}

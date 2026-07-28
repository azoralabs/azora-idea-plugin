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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests for the AZON reader. */
class AzonParserTest {

    @Test
    fun `reads the comma-free object form`() {
        val document = AzonParser.parse(
            """
            config: {                 // objects use braces
                worldName: "USA"      // String
                worldSize: 1000       // Int
                birthRate: 75.4       // Real
                enabled: true
                missing: null
            }
            """.trimIndent()
        )

        assertTrue(document.isValid)
        val config = document["config"]!!
        assertEquals("USA", config["worldName"]?.asString)
        assertEquals(1000, config["worldSize"]?.asInt)
        assertEquals(true, config["enabled"]?.asBoolean)
        assertEquals(AzonValue.Null, config["missing"])
    }

    @Test
    fun `reads arrays without separators`() {
        val document = AzonParser.parse(
            """
            members: [
                "packages/core"
                "packages/app"
            ]
            """.trimIndent()
        )

        assertTrue(document.isValid)
        assertEquals(listOf("packages/core", "packages/app"), document["members"]?.asStringList)
    }

    @Test
    fun `tolerates the commas a JSON paste brings along`() {
        val document = AzonParser.parse("""targets: [ "interpret", "native" ]""")
        assertEquals(listOf("interpret", "native"), document["targets"]?.asStringList)
    }

    @Test
    fun `reads nested objects inside arrays`() {
        val document = AzonParser.parse(
            """
            objects: [
                { id: "a" position: { x: 4.0 y: 3.2 } }
                { id: "b" position: { x: 7.0 y: 9.2 } }
            ]
            """.trimIndent()
        )

        val objects = document["objects"]?.items.orEmpty()
        assertEquals(2, objects.size)
        assertEquals("a", objects[0]["id"]?.asString)
        assertEquals(3.2, (objects[0]["position"]?.get("y") as AzonValue.Num).value)
    }

    @Test
    fun `reports a missing colon and keeps reading the rest`() {
        val document = AzonParser.parse(
            """
            name "azora"
            version: "0.1.0"
            """.trimIndent()
        )

        assertFalse(document.isValid)
        assertTrue(document.problems.any { it.message.contains("Expected ':'") })
        // Recovery matters: the following member must still be readable.
        assertEquals("0.1.0", document["version"]?.asString)
    }

    @Test
    fun `reports an unterminated string`() {
        val document = AzonParser.parse("name: \"azora\nversion: \"0.1.0\"")
        assertTrue(document.problems.any { it.message.contains("Unterminated string") })
    }

    @Test
    fun `reports a duplicate member`() {
        val document = AzonParser.parse("name: \"a\"\nname: \"b\"")
        assertTrue(document.problems.any { it.message.contains("Duplicate member") })
    }

    @Test
    fun `an empty document is valid and empty`() {
        val document = AzonParser.parse("// only a comment\n")
        assertTrue(document.isValid)
        assertNull(document["anything"])
    }
}

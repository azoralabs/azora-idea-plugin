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

package org.azora.lang.idea.completion

import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which completions have to write an import with themselves.
 *
 * A file that reaches one name out of a module can *see* the module, so
 * completion offers the rest of its names - and the second one is only useful
 * if accepting it writes the clause that makes it compile. `import std.io::print`
 * beside a `println` that was completed and never imported is the bug this
 * answers.
 */
class ImportReachTest {

    private fun symbol(
        name: String,
        module: String? = "std.io",
        filePath: String? = "/std/io.az",
        autoImported: Boolean = false,
    ) = SymbolInfo(
        name = name,
        kind = SymbolKind.FUNC,
        filePath = filePath,
        modulePath = module,
        isAutoImported = autoImported,
    )

    private fun reach(source: String, ownModule: String? = "app.main") =
        ImportReach(source, ownModule, "/app/main.az")

    @Test
    fun `a sibling of a selected name needs its own import`() {
        val reach = reach("import std.io::println\n\nfunc main() {}\n")

        assertTrue(reach.needsImport(symbol("print")))
    }

    @Test
    fun `a name the clause already selects needs nothing`() {
        val reach = reach("import std.io::println\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("println")))
    }

    @Test
    fun `a name in a group the clause selects needs nothing`() {
        val reach = reach("import std.io::[println, print]\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("print")))
    }

    @Test
    fun `a wildcard covers every name in its module`() {
        val reach = reach("import std.io::*\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("print")))
    }

    @Test
    fun `a module taken whole covers every name in it`() {
        val reach = reach("import std.io\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("print")))
    }

    @Test
    fun `an unimported module needs an import`() {
        val reach = reach("import std.math\n\nfunc main() {}\n")

        assertTrue(reach.needsImport(symbol("print")))
    }

    @Test
    fun `this file's own declarations need nothing`() {
        val reach = reach("module app.main\n\nfunc helper() {}\n")

        assertFalse(reach.needsImport(symbol("helper", module = "app.main", filePath = "/app/main.az")))
    }

    @Test
    fun `a name from this file's own module needs nothing`() {
        val reach = reach("module app.main\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("helper", module = "app.main", filePath = "/app/other.az")))
    }

    @Test
    fun `an exposed module's names need nothing`() {
        val reach = reach("func main() {}\n")

        assertFalse(reach.needsImport(symbol("Int", module = "std.primitives", autoImported = true)))
    }

    @Test
    fun `a symbol with no module of its own is left alone`() {
        val reach = reach("func main() {}\n")

        assertFalse(reach.needsImport(symbol("x", module = null)))
    }

    @Test
    fun `a dotted clause naming a symbol covers that symbol`() {
        // `import std.math.abs` names either a module or a function in one, and
        // only the module graph knows which; both readings are already reached.
        val reach = reach("import std.math.abs\n\nfunc main() {}\n")

        assertFalse(reach.needsImport(symbol("abs", module = "std.math")))
        assertTrue(reach.needsImport(symbol("sqrt", module = "std.math")))
    }
}
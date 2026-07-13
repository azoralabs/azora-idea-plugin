/*
 * Copyright 2026 AzoraTech
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

package com.azora.lang.idea.formatting

import com.azora.lang.idea.AzoraLanguage
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

class AzoraCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = AzoraLanguage

    override fun getCodeSample(settingsType: SettingsType): String = """
        use std.{math, container}

        pack Point {
            fin x: Real
            fin y: Real
        }

        func clamp(x: Int, lo: Int, hi: Int): Int
        in {
            assert lo <= hi { "lo must be <= hi" }
        } out { r ->
            assert r >= lo { "result must be >= lo" }
        } zone {
            if x < lo { return lo }
            if x > hi { return hi }
            return x
        }

        task main() {
            fin value = await loadValue()
            println(value)
        }
    """.trimIndent()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.USE_TAB_CHARACTER = false
        commonSettings.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = true
        commonSettings.KEEP_CONTROL_STATEMENT_IN_ONE_LINE = true
    }
}

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

/**
 * A parsed AZON value.
 *
 * AZON — Azora Object Notation — is a comma-free data language: the document is
 * an implicit object whose `key: value` members are separated by newlines,
 * values are strings, numbers, booleans, `null`, objects or arrays, and `//`
 * line comments are allowed anywhere.
 */
sealed interface AzonValue {

    data class Str(val value: String) : AzonValue

    data class Num(val value: Double, val isInteger: Boolean) : AzonValue

    data class Bool(val value: Boolean) : AzonValue

    data object Null : AzonValue

    data class Obj(val entries: Map<String, AzonValue>) : AzonValue

    data class Arr(val elements: List<AzonValue>) : AzonValue

    /** This value read as a string, or `null` if it is not one. */
    val asString: String? get() = (this as? Str)?.value

    /** This value read as an int, accepting a whole number or a numeric string. */
    val asInt: Int?
        get() = when (this) {
            is Num -> value.toInt()
            is Str -> value.toIntOrNull()
            else -> null
        }

    /** This value read as a boolean. */
    val asBoolean: Boolean? get() = (this as? Bool)?.value

    /** This value's members if it is an object, otherwise empty. */
    val members: Map<String, AzonValue> get() = (this as? Obj)?.entries.orEmpty()

    /** This value's items if it is an array, otherwise empty. */
    val items: List<AzonValue> get() = (this as? Arr)?.elements.orEmpty()

    /** The named member of this object, or `null`. */
    operator fun get(key: String): AzonValue? = members[key]

    /** The string items of this array, ignoring anything that is not a string. */
    val asStringList: List<String> get() = items.mapNotNull { it.asString }
}

/** A problem found while reading an AZON document, with its source location. */
data class AzonProblem(val offset: Int, val line: Int, val message: String)

/** The outcome of parsing an AZON document: what was read, and what was wrong. */
data class AzonDocument(val root: AzonValue.Obj, val problems: List<AzonProblem>) {

    /** The named top-level member, or `null`. */
    operator fun get(key: String): AzonValue? = root[key]

    /** Whether the document parsed without complaint. */
    val isValid: Boolean get() = problems.isEmpty()

    companion object {
        val EMPTY = AzonDocument(AzonValue.Obj(emptyMap()), emptyList())
    }
}

/**
 * A hand-written recursive-descent reader for AZON.
 *
 * It is deliberately forgiving: a malformed member is recorded as a problem and
 * skipped rather than aborting, so the IDE can still offer completion and
 * navigation in a manifest that is mid-edit.
 */
class AzonParser(private val source: String) {

    private var pos = 0

    /** Problems collected while reading; never throws. */
    private val problems = mutableListOf<AzonProblem>()

    /**
     * Reads the whole document.
     *
     * @return the implicit top-level object plus any problems encountered.
     */
    fun parse(): AzonDocument {
        val members = parseMembers(topLevel = true)
        skipTrivia()
        if (pos < source.length) {
            problem("Unexpected '${source[pos]}' after the end of the document")
        }
        return AzonDocument(AzonValue.Obj(members), problems.toList())
    }

    /** Reads `key: value` members until `}` (or end of input at top level). */
    private fun parseMembers(topLevel: Boolean): Map<String, AzonValue> {
        val members = LinkedHashMap<String, AzonValue>()
        while (true) {
            skipTrivia()
            if (pos >= source.length) {
                if (!topLevel) problem("Missing '}' to close the object")
                return members
            }
            if (source[pos] == '}') {
                if (topLevel) problem("Unexpected '}' at the top level")
                return members
            }

            val keyStart = pos
            val key = parseKey()
            if (key == null) {
                problem("Expected a member name", keyStart)
                skipToNextMember()
                continue
            }

            skipTrivia()
            if (pos >= source.length || source[pos] != ':') {
                problem("Expected ':' after '$key'")
                skipToNextMember()
                continue
            }
            pos++ // ':'

            skipTrivia()
            val value = parseValue()
            if (value == null) {
                problem("Expected a value for '$key'")
                skipToNextMember()
                continue
            }
            if (members.put(key, value) != null) {
                problem("Duplicate member '$key'", keyStart)
            }
        }
    }

    /** Reads a member name: a bareword, or a quoted string for exotic keys. */
    private fun parseKey(): String? {
        if (pos < source.length && source[pos] == '"') return parseString()
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] in "_-.")) pos++
        return if (pos > start) source.substring(start, pos) else null
    }

    /** Reads any AZON value, or `null` if the text at [pos] is not one. */
    private fun parseValue(): AzonValue? {
        if (pos >= source.length) return null
        return when (val c = source[pos]) {
            '"' -> parseString()?.let { AzonValue.Str(it) }
            '{' -> parseObject()
            '[' -> parseArray()
            else -> when {
                c.isDigit() || c == '-' || c == '+' -> parseNumber()
                else -> parseBareword()
            }
        }
    }

    private fun parseObject(): AzonValue.Obj {
        pos++ // '{'
        val members = parseMembers(topLevel = false)
        skipTrivia()
        if (pos < source.length && source[pos] == '}') pos++
        return AzonValue.Obj(members)
    }

    private fun parseArray(): AzonValue.Arr {
        pos++ // '['
        val items = mutableListOf<AzonValue>()
        while (true) {
            skipTrivia()
            if (pos >= source.length) {
                problem("Missing ']' to close the array")
                break
            }
            if (source[pos] == ']') {
                pos++
                break
            }
            // Commas are optional in AZON; tolerate them so JSON pastes work.
            if (source[pos] == ',') {
                pos++
                continue
            }
            val before = pos
            val value = parseValue()
            if (value == null) {
                problem("Expected an array element")
                if (pos == before) pos++
                continue
            }
            items.add(value)
        }
        return AzonValue.Arr(items)
    }

    private fun parseString(): String? {
        val quote = pos
        pos++ // opening quote
        val sb = StringBuilder()
        while (pos < source.length) {
            when (val c = source[pos]) {
                '"' -> {
                    pos++
                    return sb.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= source.length) break
                    sb.append(
                        when (val escaped = source[pos]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'b' -> '\b'
                            '0' -> ' '
                            else -> escaped
                        }
                    )
                    pos++
                }
                '\n' -> {
                    problem("Unterminated string literal", quote)
                    return sb.toString()
                }
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        problem("Unterminated string literal", quote)
        return sb.toString()
    }

    private fun parseNumber(): AzonValue? {
        val start = pos
        if (source[pos] == '-' || source[pos] == '+') pos++
        var isInteger = true
        while (pos < source.length && source[pos].isDigit()) pos++
        if (pos < source.length && source[pos] == '.' && pos + 1 < source.length && source[pos + 1].isDigit()) {
            isInteger = false
            pos++
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            isInteger = false
            pos++
            if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        val text = source.substring(start, pos)
        val value = text.toDoubleOrNull()
        if (value == null) {
            problem("'$text' is not a number", start)
            return null
        }
        return AzonValue.Num(value, isInteger)
    }

    private fun parseBareword(): AzonValue? {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        return when (val word = source.substring(start, pos)) {
            "true" -> AzonValue.Bool(true)
            "false" -> AzonValue.Bool(false)
            "null" -> AzonValue.Null
            "" -> null
            else -> {
                problem("'$word' is not a value; strings must be quoted", start)
                AzonValue.Str(word)
            }
        }
    }

    /** Skips whitespace, newlines, optional separator commas and `//` comments. */
    private fun skipTrivia() {
        while (pos < source.length) {
            val c = source[pos]
            when {
                c.isWhitespace() || c == ',' -> pos++
                c == '/' && pos + 1 < source.length && source[pos + 1] == '/' -> {
                    while (pos < source.length && source[pos] != '\n') pos++
                }
                c == '/' && pos + 1 < source.length && source[pos + 1] == '*' -> {
                    pos += 2
                    while (pos + 1 < source.length && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                    pos = (pos + 2).coerceAtMost(source.length)
                }
                else -> return
            }
        }
    }

    /** Recovers from a malformed member by resuming at the next line. */
    private fun skipToNextMember() {
        while (pos < source.length && source[pos] != '\n' && source[pos] != '}') pos++
        if (pos < source.length && source[pos] == '\n') pos++
    }

    private fun problem(message: String, offset: Int = pos) {
        problems.add(AzonProblem(offset, lineAt(offset), message))
    }

    private fun lineAt(offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(source.length)) {
            if (source[i] == '\n') line++
        }
        return line
    }

    companion object {
        /** Parses [text] as an AZON document. */
        fun parse(text: String): AzonDocument = AzonParser(text).parse()
    }
}

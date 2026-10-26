package com.oakamil.olive

class JsonObject(initialMap: Map<String, Any?> = emptyMap()) {
    private val map: Map<String, Any?> = initialMap

    constructor(json: String) : this(SimpleJsonParser(json).parseObject())

    fun has(key: String): Boolean = map.containsKey(key)

    fun isNull(key: String): Boolean = map[key] == null

    fun optString(key: String, fallback: String = ""): String {
        val v = map[key] ?: return fallback
        return v.toString()
    }

    fun getString(key: String): String {
        val v = map[key] ?: throw NoSuchElementException("Key $key not found")
        return v.toString()
    }

    fun optDouble(key: String, fallback: Double = 0.0): Double {
        val v = map[key] ?: return fallback
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    fun getDouble(key: String): Double {
        val v = map[key] ?: throw NoSuchElementException("Key $key not found")
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
                ?: throw NumberFormatException("Cannot parse $v as Double")

            else -> throw NumberFormatException("Cannot parse $v as Double")
        }
    }

    fun optInt(key: String, fallback: Int = 0): Int {
        val v = map[key] ?: return fallback
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    fun optBoolean(key: String, fallback: Boolean = false): Boolean {
        val v = map[key] ?: return fallback
        return when (v) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: fallback
            else -> fallback
        }
    }

    fun getBoolean(key: String): Boolean {
        val v = map[key] ?: throw NoSuchElementException("Key $key not found")
        return when (v) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("Cannot parse $v as Boolean")

            else -> throw IllegalArgumentException("Cannot parse $v as Boolean")
        }
    }
}

class JsonArray(initialList: List<Any?> = emptyList()) {
    private val list: List<Any?> = initialList

    constructor(json: String) : this(SimpleJsonParser(json).parseArray())

    fun length(): Int = list.size

    fun getJSONObject(index: Int): JsonObject {
        val item = list[index]
        if (item is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return JsonObject(item as Map<String, Any?>)
        }
        if (item is JsonObject) return item
        throw IllegalStateException("Item at $index is not an object: $item")
    }
}

typealias JSONObject = JsonObject
typealias JSONArray = JsonArray

internal class SimpleJsonParser(private val src: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '{') return emptyMap()
        pos++ // skip '{'
        val result = mutableMapOf<String, Any?>()
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return result
        }

        while (pos < src.length) {
            skipWhitespace()
            if (pos >= src.length || src[pos] != '"') break
            val key = parseString()
            skipWhitespace()
            if (pos < src.length && src[pos] == ':') {
                pos++
            }
            skipWhitespace()
            val value = parseValue()
            result[key] = value
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == '}') {
                pos++
                break
            } else {
                break
            }
        }
        return result
    }

    fun parseArray(): List<Any?> {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '[') return emptyList()
        pos++ // skip '['
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return result
        }

        while (pos < src.length) {
            skipWhitespace()
            val value = parseValue()
            result.add(value)
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == ']') {
                pos++
                break
            } else {
                break
            }
        }
        return result
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) return null
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (c == '-' || c in '0'..'9') parseNumber() else null
        }
    }

    private fun parseString(): String {
        pos++ // skip opening quote
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\' && pos < src.length) {
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 <= src.length) {
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                    }

                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val start = pos
        if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
        var isFloating = false
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) {
            if (src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E') isFloating = true
            pos++
        }
        val raw = src.substring(start, pos)
        return if (isFloating) {
            raw.toDoubleOrNull() ?: 0.0
        } else {
            raw.toLongOrNull()?.let {
                if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it
            } ?: (raw.toDoubleOrNull() ?: 0.0)
        }
    }

    private fun parseBoolean(): Boolean {
        return if (src.startsWith("true", pos)) {
            pos += 4
            true
        } else if (src.startsWith("false", pos)) {
            pos += 5
            false
        } else {
            pos++
            false
        }
    }

    private fun parseNull(): Any? {
        if (src.startsWith("null", pos)) {
            pos += 4
        } else {
            pos++
        }
        return null
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) {
            pos++
        }
    }
}

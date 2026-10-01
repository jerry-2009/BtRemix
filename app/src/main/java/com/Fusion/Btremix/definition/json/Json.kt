package com.Fusion.Btremix.definition.json

/** Small dependency-free JSON tree and parser used by Definition loading. */
sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object JsonParser {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value()
        parser.whitespace()
        if (!parser.atEnd()) parser.fail("Trailing characters")
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0
        fun atEnd() = index >= text.length
        fun whitespace() { while (!atEnd() && text[index].isWhitespace()) index++ }
        fun fail(message: String): Nothing = throw JsonParseException("$message at character $index")

        fun value(): JsonValue {
            whitespace()
            if (atEnd()) fail("Expected a JSON value")
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonValue.StringValue(string())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.NullValue)
                '-', in '0'..'9' -> number()
                else -> fail("Unexpected character '${text[index]}'")
            }
        }

        private fun objectValue(): JsonValue.Object {
            index++
            whitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (!atEnd() && text[index] == '}') { index++; return JsonValue.Object(values) }
            while (true) {
                whitespace()
                if (atEnd() || text[index] != '"') fail("Expected object key")
                val key = string()
                whitespace()
                if (atEnd() || text[index] != ':') fail("Expected ':' after object key")
                index++
                if (values.put(key, value()) != null) fail("Duplicate object key '$key'")
                whitespace()
                when {
                    atEnd() -> fail("Unclosed object")
                    text[index] == '}' -> { index++; return JsonValue.Object(values) }
                    text[index] == ',' -> index++
                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun arrayValue(): JsonValue.Array {
            index++
            whitespace()
            val values = mutableListOf<JsonValue>()
            if (!atEnd() && text[index] == ']') { index++; return JsonValue.Array(values) }
            while (true) {
                values += value()
                whitespace()
                when {
                    atEnd() -> fail("Unclosed array")
                    text[index] == ']' -> { index++; return JsonValue.Array(values) }
                    text[index] == ',' -> index++
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun string(): String {
            if (atEnd() || text[index] != '"') fail("Expected string")
            index++
            val output = StringBuilder()
            while (!atEnd()) {
                val char = text[index++]
                when (char) {
                    '"' -> return output.toString()
                    '\\' -> {
                        if (atEnd()) fail("Unclosed escape")
                        when (val escaped = text[index++]) {
                            '"' -> output.append('"'); '\\' -> output.append('\\'); '/' -> output.append('/')
                            'b' -> output.append('\b'); 'f' -> output.append('\u000c'); 'n' -> output.append('\n')
                            'r' -> output.append('\r'); 't' -> output.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Invalid unicode escape")
                                val hex = text.substring(index, index + 4)
                                output.append(hex.toIntOrNull(16)?.toChar() ?: fail("Invalid unicode escape"))
                                index += 4
                            }
                            else -> fail("Invalid escape '$escaped'")
                        }
                    }
                    else -> if (char.code < 0x20) fail("Control character in string") else output.append(char)
                }
            }
            fail("Unclosed string")
        }

        private fun number(): JsonValue.NumberValue {
            val start = index
            if (text[index] == '-') index++
            if (atEnd()) fail("Invalid number")
            if (text[index] == '0') index++ else {
                if (text[index] !in '1'..'9') fail("Invalid number")
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && text[index] == '.') {
                index++
                if (atEnd() || !text[index].isDigit()) fail("Invalid number fraction")
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                if (atEnd() || !text[index].isDigit()) fail("Invalid number exponent")
                while (!atEnd() && text[index].isDigit()) index++
            }
            return JsonValue.NumberValue(text.substring(start, index))
        }

        private fun <T : JsonValue> literal(literal: String, result: T): T {
            if (!text.startsWith(literal, index)) fail("Expected '$literal'")
            index += literal.length
            return result
        }
    }
}

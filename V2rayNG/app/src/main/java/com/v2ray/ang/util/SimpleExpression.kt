package com.v2ray.ang.util

/**
 * Just enough arithmetic to make the decoy calculator behave like a real one: the four operators,
 * decimals, and correct precedence. Anything a normal calculator keypad can produce, this can
 * evaluate; anything else returns null rather than throwing.
 */
object SimpleExpression {

    /**
     * Keypad glyphs mapped to the ASCII operators the parser works in. Group separators are
     * dropped too, so a value pasted back from the display still parses — including the
     * non-breaking space some locales group with, which [Char.isWhitespace] does not cover.
     */
    fun normalize(raw: String): String = raw
        .replace('×', '*')
        .replace('÷', '/')
        .replace('−', '-')
        .replace('–', '-')
        .replace(',', '.')
        .filterNot { it.isWhitespace() || it == ' ' || it == ' ' || it == '\'' }

    /** True when [raw] contains only characters the calculator keypad can actually enter. */
    fun isKeypadTypable(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return false
        return normalized.all { it.isDigit() || it in "+-*/.^√%()" }
    }

    /**
     * Inserts [separator] between thousands in every integer run of [raw], leaving operators,
     * brackets and the fractional part alone. Display-only: the expression the parser sees keeps
     * its bare digits, so grouping can never change a result — or the unlock comparison.
     */
    fun withGroupSeparators(raw: String, separator: Char = ' '): String {
        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            if (!raw[index].isDigit()) {
                out.append(raw[index])
                index++
                continue
            }
            val start = index
            while (index < raw.length && raw[index].isDigit()) index++
            val digits = raw.substring(start, index)
            // Digits straight after a decimal point are the fraction — never grouped.
            if (start > 0 && raw[start - 1] == '.') {
                out.append(digits)
            } else {
                out.append(groupInteger(digits, separator))
            }
        }
        return out.toString()
    }

    /** "3936256" → "3 936 256"; anything up to three digits is returned untouched. */
    private fun groupInteger(digits: String, separator: Char): String {
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        val lead = digits.length % 3
        if (lead > 0) out.append(digits, 0, lead)
        var i = lead
        while (i < digits.length) {
            if (out.isNotEmpty()) out.append(separator)
            out.append(digits, i, i + 3)
            i += 3
        }
        return out.toString()
    }

    /**
     * @return the value of [raw], or null if it is malformed (unbalanced, trailing operator,
     * division by zero, …).
     */
    fun evaluate(raw: String): Double? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        return try {
            val parser = Parser(text)
            val value = parser.parseSum()
            if (!parser.atEnd()) null else value.takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        }
    }

    /** Recursive descent: sum → product → power → unary → atom (number, √, trailing %). */
    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun parseSum(): Double {
            var value = parseProduct()
            while (!atEnd()) {
                when (text[pos]) {
                    '+' -> { pos++; value += parseProduct() }
                    '-' -> { pos++; value -= parseProduct() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseProduct(): Double {
            var value = parsePower()
            while (!atEnd()) {
                when (text[pos]) {
                    '*' -> { pos++; value *= parsePower() }
                    '/' -> {
                        pos++
                        val divisor = parsePower()
                        if (divisor == 0.0) throw ArithmeticException("division by zero")
                        value /= divisor
                    }

                    else -> return value
                }
            }
            return value
        }

        /** Right-associative, so 2^3^2 is 2^(3^2) as it is in mathematics. */
        private fun parsePower(): Double {
            val base = parseUnary()
            if (!atEnd() && text[pos] == '^') {
                pos++
                return Math.pow(base, parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            if (!atEnd() && text[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (!atEnd() && text[pos] == '√') {
                pos++
                val operand = parseUnary()
                if (operand < 0) throw ArithmeticException("root of a negative number")
                return Math.sqrt(operand)
            }
            return parsePercent()
        }

        /** Trailing % scales the value it follows, the way a pocket calculator treats it. */
        private fun parsePercent(): Double {
            var value = parseAtom()
            while (!atEnd() && text[pos] == '%') {
                pos++
                value /= 100.0
            }
            return value
        }

        private fun parseAtom(): Double {
            if (!atEnd() && text[pos] == '(') {
                pos++
                val value = parseSum()
                if (atEnd() || text[pos] != ')') throw IllegalArgumentException("unbalanced bracket")
                pos++
                return value
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            val start = pos
            while (!atEnd() && (text[pos].isDigit() || text[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("expected a number at $pos")
            return text.substring(start, pos).toDouble()
        }
    }
}

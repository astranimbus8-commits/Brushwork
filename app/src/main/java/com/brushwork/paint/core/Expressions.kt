package com.brushwork.paint.core

import com.brushwork.paint.ui.common.ExpressionLabels

/**
 * v1.7 (item 15, design §3.15, I13): arithmetic in typed number fields. Pure, thread-safe, frozen.
 *
 * Grammar (a trailing unit word, `°` or `%` is stripped first; spaces between tokens are allowed):
 * ```
 * input   := [relop] expr
 * relop   := * / × ÷ x X        (only a LEADING one is relative: the value applies to the current one)
 * expr    := term {(+ | - | −) term}
 * term    := factor {(* | / | × | ÷ | x | X) factor}
 * factor  := [+ | - | −] primary   (ONE sign: "--5" is an error)
 * primary := number | '(' expr ')'
 * number  := (digits [(. | ,) [digits]] | (. | ,) digits) [(e | E) [+ | -] digits]
 * ```
 * A leading `+` or `-` is a sign, never relative. NaN or infinite results, a division by 0 and text
 * longer than [MAX_LENGTH] are errors ([ExpressionLabels.DIV_ZERO], [ExpressionLabels.INVALID]).
 * A number is read as [isPlainNumber] reads one, so ".5" and "5." work inside an expression too
 * (".5*2" is 1, "/.5" at 120 is 240; gate 2 review: the design's `digits [sep digits]` alone made
 * them errors, whose lenient fallback then read "120/.5" as 120.5).
 *
 * The three parse sites ([Units.parse], `NumberSliderMath.parseTyped`, `SliderMath.parseValue`)
 * run their v1.6 code unchanged for a [isPlainNumber] text; otherwise they [evaluate] it, and when
 * that is an error they run their v1.6 code after all (the lenient fallback: "1 000" -> 1000), so
 * no text that gave a number in v1.6 stops giving one. Fields resolve relative text against their
 * current value first ([resolveRelative]).
 */
object Expressions {
    /** Longest text evaluated (after trimming); longer text is an error. */
    const val MAX_LENGTH = 64

    sealed interface Result {
        data class Value(val value: Double) : Result
        /** [ExpressionLabels.DIV_ZERO] or [ExpressionLabels.INVALID]. */
        data class Error(val message: String) : Result
    }

    private val PLAIN = Regex("""[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?\s*(\p{L}+|%|°)?""")

    private const val RELOPS = "*/×÷xX"

    /**
     * An optional single sign (`+` or `-`), digits with an optional '.' or ',' decimal and
     * exponent, then an optional unit word, % or ° (surrounding spaces ignored). Everything v1.6
     * read as a number that a user types (".5", "5.", "1e3", "12,5 px") is plain; such text takes
     * the v1.6 path at every parse site.
     */
    fun isPlainNumber(text: String): Boolean = PLAIN.matches(text.trim())

    /** A leading × ÷ * / x X (after trimming): the value applies to the current one. */
    fun isRelative(text: String): Boolean = text.trim().firstOrNull()?.let { it in RELOPS } ?: false

    /**
     * [text] evaluated by the grammar above. Relative text ([isRelative]) applies its value to
     * [current] ("*2" at 120 is 240; "/0" is "Can't divide by 0"); without a finite [current] it
     * is an error. NaN, infinities and text over [MAX_LENGTH] are errors.
     */
    fun evaluate(text: String, current: Double? = null): Result {
        val trimmed = text.trim()
        if (trimmed.length > MAX_LENGTH) return INVALID
        val s = stripUnit(trimmed)
        if (s.isEmpty()) return INVALID
        val rel = s[0].takeIf { it in RELOPS }
        if (rel != null && (current == null || !current.isFinite())) return INVALID
        val p = Parser(if (rel != null) s.substring(1) else s)
        val v = try {
            val e = p.expr()
            p.skipSpaces()
            if (!p.atEnd) throw ParseError(false)
            e
        } catch (e: ParseError) {
            return if (e.divZero) DIV_ZERO_RESULT else INVALID
        }
        val out = when (rel) {
            null -> v
            '/', '÷' -> if (v == 0.0) return DIV_ZERO_RESULT else current!! / v
            else -> current!! * v
        }
        return if (out.isFinite()) Result.Value(out) else INVALID
    }

    /**
     * For fields: "/2" at 120 -> "120/2" (the current value formatted with '.' and full
     * precision, then the operator and the rest; a rest that is more than a number is put in
     * parentheses, "*2+1" -> "120*(2+1)", so the text means what [evaluate] makes of it). Null when
     * [text] has no leading relative operator, when [current] is not finite, or when the resolved
     * text is not a valid expression ("/0", "*2+", too long): the caller then parses [text] as it
     * is, so a parse site's v1.6 fallback reads what the user typed, never the current value's
     * digits joined to it ("/2+" at 120 never becomes the 1202 that "120/(2+)" filters to). A
     * readout takes its message from [evaluate] with the current value ("/0" at 120 is
     * [ExpressionLabels.DIV_ZERO]), not from this result.
     */
    fun resolveRelative(text: String, current: Float): String? =
        if (current.isFinite()) resolve(text, shortest(current.toString())) else null

    /** [resolveRelative] against a double [current] (fields that hold doubles keep their precision). */
    fun resolveRelative(text: String, current: Double): String? =
        if (current.isFinite()) resolve(text, shortest(current.toString())) else null

    // ------------------------------------------------------------------ internals

    private val INVALID = Result.Error(ExpressionLabels.INVALID)
    private val DIV_ZERO_RESULT = Result.Error(ExpressionLabels.DIV_ZERO)

    private fun resolve(text: String, cur: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t[0] !in RELOPS) return null
        val op = t[0]
        val rest = t.substring(1).trim()
        val out = if (rest.isEmpty() || isPlainNumber(rest)) {
            "$cur$op$rest"
        } else {
            val core = stripUnit(rest)
            val unit = rest.substring(core.length).trim()
            "$cur$op($core)$unit"
        }
        return out.takeIf { evaluate(it) is Result.Value }
    }

    /** Float/Double.toString without a trailing ".0" ("120.0" -> "120"; "1.0E-5" stays: the grammar reads it). */
    private fun shortest(s: String): String = if (s.endsWith(".0")) s.dropLast(2) else s

    /** [s] (trimmed) without one trailing unit word (letters), % or °, trimmed again. */
    private fun stripUnit(s: String): String {
        if (s.isEmpty()) return s
        val last = s.last()
        if (last == '%' || last == '°') return s.dropLast(1).trimEnd()
        if (!last.isLetter()) return s
        var i = s.length
        while (i > 0 && s[i - 1].isLetter()) i--
        return s.substring(0, i).trimEnd()
    }

    private class ParseError(val divZero: Boolean) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Recursive descent over [s] (depth is bounded by [MAX_LENGTH]). */
    private class Parser(private val s: String) {
        private var i = 0
        val atEnd: Boolean get() = i >= s.length

        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun peek(): Char? { skipSpaces(); return if (i < s.length) s[i] else null }

        fun expr(): Double {
            var v = term()
            while (true) {
                val c = peek() ?: return v
                if (c != '+' && c != '-' && c != '−') return v
                i++
                val r = term()
                v = if (c == '+') v + r else v - r
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                val c = peek() ?: return v
                if (c !in RELOPS) return v
                i++
                val r = factor()
                v = if (c == '/' || c == '÷') {
                    if (r == 0.0) throw ParseError(true)
                    v / r
                } else v * r
            }
        }

        private fun factor(): Double {
            val c = peek() ?: throw ParseError(false)
            return when (c) {
                '+' -> { i++; primary() }
                '-', '−' -> { i++; -primary() }
                else -> primary()
            }
        }

        private fun primary(): Double {
            val c = peek() ?: throw ParseError(false)
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw ParseError(false)
                i++
                return v
            }
            // A digit, or a separator followed by one (".5").
            val separatorFirst = (c == '.' || c == ',') && i + 1 < s.length && s[i + 1].isAsciiDigit()
            if (!c.isAsciiDigit() && !separatorFirst) throw ParseError(false)
            return number()
        }

        private fun number(): Double {
            val sb = StringBuilder()
            fun digits(): Boolean {
                val start = i
                while (i < s.length && s[i].isAsciiDigit()) sb.append(s[i++])
                return i > start
            }
            val whole = digits()
            if (i < s.length && (s[i] == '.' || s[i] == ',')) {
                i++
                sb.append('.')
                // "5." and ".5" are numbers (as isPlainNumber reads them); a lone separator is not.
                if (!digits() && !whole) throw ParseError(false)
            } else if (!whole) {
                throw ParseError(false)
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                sb.append('e')
                if (i < s.length && (s[i] == '+' || s[i] == '-')) sb.append(s[i++])
                if (!digits()) throw ParseError(false)
            }
            return sb.toString().toDouble()
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    }
}

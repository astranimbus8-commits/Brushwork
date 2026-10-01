package com.brushwork.paint.exchange.svg

/**
 * The CSS subset of SVG files (v1.5 §4.11b): `<style>` sheets with element, `.class`, `#id`,
 * `tag.class` / compound and `*` selectors in selector lists (Illustrator's `.st0{…}`), and
 * `style=""` declarations. Selectors with combinators (descendant, `>`, `+`, `~`), attribute
 * selectors and pseudo-classes are skipped and counted; at-rules are ignored. Pure Kotlin.
 */
object SvgCss {
    /** One simple compound selector: a tag (or null for any), ids and classes. */
    data class Selector(val tag: String?, val ids: List<String>, val classes: List<String>) {
        /** (ids, classes, tags) as one comparable number. */
        val specificity: Int get() = ids.size * 10_000 + classes.size * 100 + (if (tag != null) 1 else 0)

        fun matches(tag: String, id: String?, classes: Set<String>): Boolean {
            if (this.tag != null && this.tag != tag) return false
            for (i in ids) if (i != id) return false
            for (c in this.classes) if (c !in classes) return false
            return true
        }
    }

    /** A rule: [selector] with its [declarations]; [order] is its position in the document's sheets. */
    data class Rule(val selector: Selector, val declarations: Map<String, String>, val important: Set<String>, val order: Int)

    /** A parsed style sheet and how many selectors were skipped as unsupported. */
    class Sheet(val rules: List<Rule>, val skippedSelectors: Int)

    /** Parses [css] (several sheets can be concatenated); rule order starts at [firstOrder]. */
    fun parse(css: String, firstOrder: Int = 0): Sheet {
        val text = stripComments(css)
        val rules = ArrayList<Rule>()
        var skipped = 0
        var order = firstOrder
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            val prelude = text.substring(i, open).trim()
            val close = matchingBrace(text, open)
            val body = text.substring(open + 1, if (close < 0) text.length else close)
            i = if (close < 0) text.length else close + 1
            if (prelude.startsWith("@")) continue // @media, @font-face, @import...: ignored
            val (decls, important) = declarations(body)
            if (decls.isEmpty()) continue
            for (part in prelude.split(',')) {
                val sel = selector(part.trim())
                if (sel == null) {
                    if (part.isNotBlank()) skipped++
                    continue
                }
                rules += Rule(sel, decls, important, order++)
            }
        }
        return Sheet(rules, skipped)
    }

    /** `prop: value; ...` (property names lowercased; the last of a property wins). */
    fun declarations(body: String): Pair<Map<String, String>, Set<String>> {
        val out = LinkedHashMap<String, String>()
        val important = HashSet<String>()
        for (decl in splitDeclarations(stripComments(body))) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val name = decl.substring(0, colon).trim().lowercase()
            var value = decl.substring(colon + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            val bang = value.lowercase().lastIndexOf("!important")
            if (bang >= 0) {
                value = value.substring(0, bang).trim()
                important += name
            } else {
                important -= name
            }
            out[name] = value
        }
        return out to important
    }

    /** Splits on `;` outside parentheses and quotes (`url(data:...;base64,...)`). */
    private fun splitDeclarations(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = 0
        for (k in s.indices) {
            val c = s[k]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> depth = maxOf(0, depth - 1)
                c == ';' && depth == 0 -> { out += s.substring(start, k); start = k + 1 }
            }
        }
        out += s.substring(start)
        return out
    }

    /** A compound selector, or null when it uses anything unsupported. */
    fun selector(s: String): Selector? {
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() || it == '>' || it == '+' || it == '~' || it == '[' || it == ':' }) return null
        var i = 0
        var tag: String? = null
        val ids = ArrayList<String>()
        val classes = ArrayList<String>()
        fun ident(): String {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_' || s[i].code >= 0x80)) i++
            return s.substring(st, i)
        }
        if (s[0] == '*') {
            i = 1
        } else if (s[0] != '.' && s[0] != '#') {
            tag = ident()
            if (tag.isEmpty()) return null
        }
        while (i < s.length) {
            val c = s[i++]
            val name = ident()
            if (name.isEmpty()) return null
            when (c) {
                '.' -> classes += name
                '#' -> ids += name
                else -> return null
            }
        }
        return Selector(tag, ids, classes)
    }

    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        for (k in open until s.length) {
            when (s[k]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return k }
            }
        }
        return -1
    }

    private fun stripComments(s: String): String {
        if (!s.contains("/*")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s.startsWith("/*", i)) {
                val end = s.indexOf("*/", i + 2)
                if (end < 0) break
                i = end + 2
            } else {
                sb.append(s[i++])
            }
        }
        return sb.toString()
    }
}

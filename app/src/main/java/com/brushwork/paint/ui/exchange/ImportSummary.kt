package com.brushwork.paint.ui.exchange

import com.brushwork.paint.exchange.ImportOutcome

/**
 * The toast after an SVG / PDF import (v1.5 §4.11a): what came in and what was left out, e.g.
 * "Imported 342 shapes, 2 pictures · skipped: 3 clip paths, 1 filter". Pure Kotlin.
 */
object ImportSummary {
    fun format(o: ImportOutcome): String {
        val parts = ArrayList<String>()
        fun count(n: Int, one: String, many: String) { if (n > 0) parts += "$n ${if (n == 1) one else many}" }
        count(o.shapes, "shape", "shapes")
        count(o.pictures, "picture", "pictures")
        count(o.texts, "text", "texts")
        count(o.pages, "page", "pages")
        val head = when {
            parts.isNotEmpty() -> "Imported " + parts.joinToString(", ")
            o.layers > 0 -> "Imported ${o.layers} ${if (o.layers == 1) "layer" else "layers"}"
            else -> "Nothing was imported"
        }
        val sb = StringBuilder(head)
        if (o.skipped.isNotEmpty()) sb.append(" · skipped: ").append(o.skipped.entries.joinToString(", ") { (k, v) -> "$v ${singular(k, v)}" })
        if (o.dropped.isNotEmpty()) sb.append(" · left out: ").append(o.dropped.entries.joinToString(", ") { (k, v) -> "$v ${singular(k, v)}" })
        return sb.toString()
    }

    /**
     * "1 clip paths" reads badly: a lone item drops the plural s of the noun ending the phrase
     * before any explanation in parentheses ("dashed lines (drawn solid)" -> "dashed line (...)").
     */
    fun singular(what: String, n: Int): String {
        if (n != 1) return what
        val paren = what.indexOf(" (")
        val noun = if (paren >= 0) what.substring(0, paren) else what
        val tail = if (paren >= 0) what.substring(paren) else ""
        val cut = noun.lastIndexOf(' ') + 1
        val word = noun.substring(cut)
        val one = when {
            word.endsWith("ies") -> word.dropLast(3) + "y"
            word.endsWith("sses") -> word.dropLast(2)
            word.endsWith("s") && !word.endsWith("ss") && word.length > 1 && word.any { it.isLowerCase() } -> word.dropLast(1)
            else -> word
        }
        return noun.substring(0, cut) + one + tail
    }
}

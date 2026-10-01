package com.brushwork.paint.exchange.svg

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import java.io.IOException

/** One element of a parsed SVG file: its (normalized) name, attributes and children in order. */
class SvgElement(
    /** Local name for SVG elements ("path"), `bw:` + local name for Brushwork data, else the qualified name. */
    val name: String,
    private val attrs: Map<String, XmlAttr>,
    val parent: SvgElement?,
    val depth: Int,
) {
    val children = ArrayList<SvgElement>()

    /** Text content of a `#text` node. */
    var text: String? = null
    var textSlice: ByteSlice? = null

    val isText: Boolean get() = name == TEXT_NODE

    fun attr(name: String): String? = attrs[name]?.text

    fun attrSlice(name: String): ByteSlice? = attrs[name]?.slice

    fun has(name: String): Boolean = attrs.containsKey(name)

    val id: String? get() = attr("id")?.trim()?.takeIf { it.isNotEmpty() }

    val classes: Set<String> get() = attr("class")?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** `href` or `xlink:href`. */
    val href: String? get() = attr("href") ?: attr("xlink:href")

    val hrefSlice: ByteSlice? get() = attrSlice("href") ?: attrSlice("xlink:href")

    /** All text below this element (text nodes concatenated in order). */
    fun textContent(): String {
        val sb = StringBuilder()
        fun walk(e: SvgElement) {
            if (e.isText) sb.append(e.text ?: e.textSlice?.toString() ?: "")
            for (c in e.children) walk(c)
        }
        walk(this)
        return sb.toString()
    }

    override fun toString(): String = "<$name${id?.let { " id=$it" } ?: ""}>"

    companion object {
        const val TEXT_NODE = "#text"
    }
}

/**
 * A parsed SVG file (v1.5 §4.11b): the element tree, ids, style sheets and what was left out.
 * Limits: at most [MAX_ELEMENTS] elements (more: [truncated], the rest is not read) and
 * [MAX_DEPTH] levels (deeper subtrees are skipped and counted).
 */
class SvgDocument internal constructor(
    val root: SvgElement,
    val ids: Map<String, SvgElement>,
    val css: SvgCss.Sheet,
    /** Element limit reached: only the first [MAX_ELEMENTS] elements were read. */
    val truncated: Boolean,
    /** Unsupported features found while parsing (name -> count). */
    val skipped: Map<String, Int>,
    val elementCount: Int,
) {
    /** The Brushwork payload's base64 text, if the file carries one. */
    val payloadText: String? by lazy { findPayload(root)?.textContent()?.takeIf { it.isNotBlank() } }

    /** True when the file was written by Brushwork with its data. */
    val hasPayload: Boolean get() = payloadText != null

    /** The decoded payload (null without one); throws [IOException] when it is damaged. */
    fun payload(): BrushworkPayload? = payloadText?.let { Payload.fromBase64(it) }

    /**
     * The PNG / JPEG bytes of the picture with [id]: a `<bw:image>` (payload pictures) or an
     * `<image>` with a base64 data URI; null otherwise.
     */
    fun imageData(id: String): ByteArray? {
        val e = ids[id] ?: return null
        if (e.name == "bw:image") {
            val slice = e.children.firstOrNull { it.isText && it.textSlice != null }?.textSlice
            return try {
                slice?.decodeBase64() ?: java.util.Base64.getMimeDecoder().decode(e.textContent().filterNot { it.isWhitespace() })
            } catch (ex: Exception) {
                null
            }
        }
        if (e.name == "image") return DataUri.decode(e.href, e.hrefSlice)?.second
        return null
    }

    private fun findPayload(e: SvgElement): SvgElement? {
        if (e.name == "bw:payload") return e
        for (c in e.children) findPayload(c)?.let { return it }
        return null
    }

    companion object {
        const val MAX_ELEMENTS = 50_000
        const val MAX_DEPTH = 64

        /** Largest SVG file read (bytes); Brushwork files with their data may be larger ([MAX_PAYLOAD_BYTES]). */
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 200 * 1024 * 1024
    }
}

/** `data:` URIs of pictures. */
object DataUri {
    /** (mime type, bytes) of a base64 (or percent-encoded) data URI; null for anything else (external files are never read). */
    fun decode(text: String?, slice: ByteSlice?): Pair<String, ByteArray>? {
        if (slice != null) {
            if (!slice.startsWith("data:")) return null
            val comma = slice.indexOf(',')
            if (comma < 0) return null
            val head = slice.toString().let { it.substring(0, minOf(it.length, comma)) }.lowercase()
            if (!head.contains(";base64")) return null
            return try {
                mime(head) to slice.decodeBase64(comma + 1)
            } catch (e: IOException) {
                null
            }
        }
        val t = text?.trim() ?: return null
        if (!t.startsWith("data:", true)) return null
        val comma = t.indexOf(',')
        if (comma < 0) return null
        val head = t.substring(0, comma).lowercase()
        val body = t.substring(comma + 1)
        return try {
            if (head.contains(";base64")) mime(head) to java.util.Base64.getMimeDecoder().decode(body.filterNot { it.isWhitespace() || it == '%' })
            else mime(head) to java.net.URLDecoder.decode(body, "UTF-8").toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun mime(head: String): String = head.removePrefix("data:").substringBefore(';').trim()
}

/**
 * Builds an [SvgDocument] from bytes with the own [XmlTokenizer] (v1.5 §4.11b). Namespaces are
 * resolved: SVG elements (default or prefixed namespace) get their local name, the Brushwork data
 * elements `bw:` + local name, attributes of the xlink / Inkscape namespaces the canonical
 * `xlink:` / `inkscape:` prefixes. Pure Kotlin.
 */
object SvgParser {
    const val SVG_NS = "http://www.w3.org/2000/svg"
    private const val XLINK_NS = "http://www.w3.org/1999/xlink"
    private const val INKSCAPE_NS = "http://www.inkscape.org/namespaces/inkscape"

    /** Elements whose text content is kept. */
    private val TEXT_HOLDERS = setOf("text", "tspan", "textPath", "style", "title", "bw:payload", "bw:image")

    /** Elements counted as skipped wherever they appear (their content is not drawn). */
    private val SKIPPED_ELEMENTS = mapOf(
        "foreignObject" to "embedded HTML", "script" to "scripts", "animate" to "animations", "animateTransform" to "animations",
        "animateMotion" to "animations", "animateColor" to "animations", "set" to "animations",
    )

    fun parse(bytes: ByteArray): SvgDocument {
        val tok = XmlTokenizer(bytes)
        val skipped = LinkedHashMap<String, Int>()
        fun skip(what: String, n: Int = 1) { skipped[what] = (skipped[what] ?: 0) + n }
        var root: SvgElement? = null
        val stack = ArrayList<SvgElement>()
        val nsStack = ArrayList<Map<String, String>>()
        val ids = HashMap<String, SvgElement>()
        var count = 0
        var truncated = false
        // Depth of a subtree being skipped (too deep, or outside any element).
        var skipDepth = 0
        val styles = StringBuilder()
        loop@ while (true) {
            val t = tok.next() ?: break
            when (t) {
                is XmlToken.Start -> {
                    if (skipDepth > 0) {
                        if (!t.selfClosing) skipDepth++
                        continue@loop
                    }
                    val parent = stack.lastOrNull()
                    if (parent == null && root != null) {
                        // A second root element: not well-formed; what came first is kept.
                        break@loop
                    }
                    val depth = stack.size
                    if (depth >= SvgDocument.MAX_DEPTH) {
                        skip("deeply nested elements")
                        if (!t.selfClosing) skipDepth = 1
                        continue@loop
                    }
                    if (count >= SvgDocument.MAX_ELEMENTS) {
                        truncated = true
                        break@loop
                    }
                    count++
                    // Namespace declarations of this element.
                    val ns = HashMap(nsStack.lastOrNull() ?: emptyMap())
                    for (a in t.attrs) {
                        when {
                            a.name == "xmlns" -> ns[""] = a.text
                            a.name.startsWith("xmlns:") -> ns[a.name.substring(6)] = a.text
                        }
                    }
                    val name = elementName(t.name, ns)
                    val attrs = LinkedHashMap<String, XmlAttr>()
                    for (a in t.attrs) {
                        if (a.name == "xmlns" || a.name.startsWith("xmlns:")) continue
                        val an = attrName(a.name, ns)
                        attrs[an] = XmlAttr(an, a.value, a.slice)
                    }
                    val e = SvgElement(name, attrs, parent, depth)
                    if (parent == null) root = e else parent.children += e
                    e.id?.let { if (it !in ids) ids[it] = e }
                    SKIPPED_ELEMENTS[name]?.let { skip(it) }
                    if (!t.selfClosing) {
                        stack += e
                        nsStack += ns
                    }
                }
                is XmlToken.End -> {
                    if (skipDepth > 0) { skipDepth--; continue@loop }
                    // Mismatched end tags: close up to the matching open element (lenient).
                    val ns = nsStack.lastOrNull() ?: emptyMap()
                    val name = elementName(t.name, ns)
                    val at = stack.indexOfLast { it.name == name }
                    if (at < 0) continue@loop
                    while (stack.size > at) {
                        val closed = stack.removeAt(stack.lastIndex)
                        nsStack.removeAt(nsStack.lastIndex)
                        if (closed.name == "style") styles.append(closed.textContent()).append('\n')
                    }
                    if (stack.isEmpty()) break@loop
                }
                is XmlToken.Text -> {
                    if (skipDepth > 0) continue@loop
                    val parent = stack.lastOrNull() ?: continue@loop
                    if (parent.name !in TEXT_HOLDERS) continue@loop
                    val node = SvgElement(SvgElement.TEXT_NODE, emptyMap(), parent, parent.depth + 1)
                    node.text = t.text
                    node.textSlice = t.slice
                    parent.children += node
                }
            }
        }
        // Unclosed elements at the end of a truncated or malformed file: their styles still count.
        for (e in stack.asReversed()) if (e.name == "style") styles.append(e.textContent()).append('\n')
        val r = root ?: throw SvgFormatException("This file has no SVG content")
        if (r.name != "svg") throw SvgFormatException("This file is not an SVG picture")
        val sheet = SvgCss.parse(styles.toString())
        if (sheet.skippedSelectors > 0) skip("CSS selectors", sheet.skippedSelectors)
        return SvgDocument(r, ids, sheet, truncated, skipped, count)
    }

    private fun elementName(q: String, ns: Map<String, String>): String {
        val colon = q.indexOf(':')
        val prefix = if (colon >= 0) q.substring(0, colon) else ""
        val local = if (colon >= 0) q.substring(colon + 1) else q
        val uri = ns[prefix]
        return when {
            uri == Payload.SVG_NAMESPACE -> "bw:$local"
            uri == SVG_NS -> local
            // No namespace at all (hand-written files): treated as SVG.
            prefix.isEmpty() && uri.isNullOrEmpty() -> local
            // Another vocabulary's element (XHTML inside foreignObject...): never drawn.
            prefix.isEmpty() -> "{$uri}$local"
            else -> q
        }
    }

    private fun attrName(q: String, ns: Map<String, String>): String {
        val colon = q.indexOf(':')
        if (colon < 0) return q
        val prefix = q.substring(0, colon)
        val local = q.substring(colon + 1)
        return when (ns[prefix]) {
            XLINK_NS -> "xlink:$local"
            INKSCAPE_NS -> "inkscape:$local"
            SVG_NS -> local
            Payload.SVG_NAMESPACE -> "bw:$local"
            else -> if (prefix == "xml") "xml:$local" else q
        }
    }
}

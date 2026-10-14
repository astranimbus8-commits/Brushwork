package com.brushwork.paint.diagnostics

import com.brushwork.paint.ui.common.StabilityLabels19
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

/**
 * v1.9 (R9, design 3.2 A7a step 1; owner A7a): the text of one crash report and the privacy rules
 * every text that goes into one passes through. Pure (no Android), so the format, the size cap and
 * the privacy rules are checked on the JVM.
 *
 * A report reads:
 * ```
 * Brushwork crash report
 * Kind: CRASH
 * Fatal: yes
 * Time: 2026-10-13 14:03:07 UTC
 * Where: uncaught on thread main
 * App: 1.9 (19) release
 * Device: ZTE 8050, Android 14 (API 34), arm64-v8a
 * Memory: Java heap 120 of 512 MB, native heap 340 MB
 * Canvas: 2480x3508, 5 layers, tool MAGNETIC_LASSO
 * Summary: Crash: IllegalStateException
 *
 * Breadcrumbs, oldest first:
 * tool:MAGNETIC_LASSO
 *
 * Stack:
 * java.lang.IllegalStateException: <text>
 * 	at com.brushwork.paint.tools.select.magnetic.LiveWire.path(SourceFile:120)
 * [cut: 40 more lines]
 * ```
 * The fields come first and the last section (the stack, an exit's trace) is cut to fit
 * [CrashLog.MAX_BYTES]. Privacy ([scrub]): no artwork names, no text content, no paths, no
 * links, no e-mail addresses; only class names, line numbers, sizes, counts and ids of the app.
 */
internal object ReportText {
    /** The report's first line ([StabilityLabels19.REPORT_TITLE]). */
    const val TITLE = StabilityLabels19.REPORT_TITLE

    /** The line the cut section ends with: "[cut: N more lines]". */
    const val CUT = "[cut: %d more lines]"

    /** A scrubbed message is at most this long (chars). */
    const val MAX_MESSAGE = 300

    /** At most this many frames of one throwable, and this many throwables (causes, suppressed) in all. */
    internal const val MAX_FRAMES = 120
    private const val MAX_THROWABLES = 10

    /** How the Diagnostics list names a report of [kind] ([fatal]: the app stopped); the texts are [StabilityLabels19]'s. */
    fun kindTitle(kind: CrashReport.Kind, fatal: Boolean = true): String = when (kind) {
        CrashReport.Kind.CRASH -> if (fatal) StabilityLabels19.KIND_CRASH else StabilityLabels19.KIND_HANDLED
        CrashReport.Kind.OUT_OF_MEMORY -> StabilityLabels19.KIND_OUT_OF_MEMORY
        CrashReport.Kind.FREEZE -> StabilityLabels19.KIND_FREEZE
        CrashReport.Kind.NATIVE -> StabilityLabels19.KIND_NATIVE
        CrashReport.Kind.ANR -> StabilityLabels19.KIND_ANR
        CrashReport.Kind.LOW_MEMORY_KILL -> StabilityLabels19.KIND_LOW_MEMORY_KILL
    }

    /** True when [t] or one of its causes is an [OutOfMemoryError]. */
    fun isOutOfMemory(t: Throwable): Boolean {
        var e: Throwable? = t
        var n = 0
        while (e != null && n++ < MAX_THROWABLES) {
            if (e is OutOfMemoryError) return true
            e = e.cause
        }
        return false
    }

    /** The innermost cause of [t] (the one that says what went wrong; outer ones mostly wrap it). */
    fun rootCause(t: Throwable): Throwable {
        var e = t
        var n = 0
        while (n++ < MAX_THROWABLES) e = e.cause?.takeIf { it !== e } ?: break
        return e
    }

    /** The one line the Diagnostics list shows: the kind and the root cause's class, and [where] for a handled error. */
    fun summary(kind: CrashReport.Kind, fatal: Boolean, t: Throwable?, where: String? = null): String {
        val title = kindTitle(kind, fatal)
        val cause = t?.let { rootCause(it).javaClass.simpleName.ifEmpty { rootCause(it).javaClass.name } }
        val base = if (cause == null) title else "$title: $cause"
        return if (!fatal && !where.isNullOrBlank()) "$base in ${scrub(where)}" else base
    }

    /** UTC "yyyy-MM-dd HH:mm:ss UTC" of [millis]. */
    fun utc(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis)) + " UTC"
    }

    private val uri = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S*""")
    private val email = Regex("""[^\s@"'<>()]+@[^\s@"'<>()]+\.[a-zA-Z]{2,}""")
    private val quoted = Regex(""""[^"]*"|'[^'\n]*'|“[^”]*”|‘[^’]*’|«[^»]*»""")
    private val path = Regex("""[^\s"'()\[\]{}<>,;=]*[/\\][^\s"'()\[\]{}<>,;=]*""")

    /**
     * [text] without personal data: links become `<uri>`, e-mail addresses `<email>`, quoted text
     * (a typed text, a name, a number format's input) `<text>`, anything with a slash or a
     * backslash (a file path, a content id) `<path>`, and each of [names] (the open artwork's
     * name) `<name>`; then at most [MAX_MESSAGE] chars on one line. Null and blank are "".
     */
    fun scrub(text: String?, names: Collection<String> = emptyList()): String {
        if (text.isNullOrBlank()) return ""
        var s: String = text
        for (n in names) if (n.length >= 2) s = s.replace(n, "<name>")
        s = uri.replace(s, "<uri>")
        s = email.replace(s, "<email>")
        s = quoted.replace(s, "<text>")
        s = path.replace(s) { m -> if (m.value.any { it.isLetterOrDigit() }) "<path>" else m.value }
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return if (s.length > MAX_MESSAGE) s.substring(0, MAX_MESSAGE) + "…" else s
    }

    /**
     * [t]'s stack as `printStackTrace` writes it (the causes as "Caused by:", the suppressed as
     * "Suppressed:", frames in common with the enclosing trace as "... n more"), each message
     * [scrub]bed with [names]. At most [MAX_FRAMES] frames per throwable and [MAX_THROWABLES]
     * throwables; a cycle of causes ends the walk.
     */
    fun stackLines(t: Throwable, names: Collection<String> = emptyList()): List<String> {
        val out = ArrayList<String>(64)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun header(e: Throwable): String {
            val m = scrub(e.message, names)
            return if (m.isEmpty()) e.javaClass.name else "${e.javaClass.name}: $m"
        }
        fun walk(e: Throwable, prefix: String, indent: String, enclosing: Array<StackTraceElement>) {
            if (!seen.add(e) || seen.size > MAX_THROWABLES) return
            out += indent + prefix + header(e)
            val frames = runCatching { e.stackTrace }.getOrDefault(emptyArray())
            var m = frames.size - 1
            var n = enclosing.size - 1
            while (m >= 0 && n >= 0 && frames[m] == enclosing[n]) { m--; n-- }
            val common = frames.size - 1 - m
            val shown = minOf(m + 1, MAX_FRAMES)
            for (i in 0 until shown) out += "$indent\tat ${frames[i]}"
            if (m + 1 > shown) out += "$indent\t... ${m + 1 - shown} frames left out"
            if (common > 0) out += "$indent\t... $common more"
            for (s in runCatching { e.suppressed }.getOrDefault(emptyArray())) walk(s, "Suppressed: ", "$indent\t", frames)
            e.cause?.let { walk(it, "Caused by: ", indent, frames) }
        }
        walk(t, "", "", emptyArray())
        return out
    }

    /**
     * The report: [TITLE], the [fields] ("Name: value", in order), then each of [sections]
     * ("Title:" and its lines, after a blank line). At most [maxBytes] bytes in UTF-8: the last
     * section is cut, ending with [CUT]; when even the fields do not fit, they are cut too.
     */
    fun compose(fields: List<Pair<String, String>>, sections: List<Pair<String, List<String>>>, maxBytes: Int): String {
        val sb = StringBuilder(4096)
        var bytes = 0
        // Room kept for the cut marker.
        val budget = maxBytes - 48
        fun add(line: String): Boolean {
            val b = utf8Length(line) + 1
            if (bytes + b > budget) return false
            sb.append(line).append('\n')
            bytes += b
            return true
        }
        fun cut(left: Int) {
            val marker = CUT.format(left)
            sb.append(marker).append('\n')
        }
        add(TITLE)
        for ((i, f) in fields.withIndex()) {
            if (!add("${f.first}: ${f.second}")) { cut(fields.size - i); return sb.toString() }
        }
        for ((title, lines) in sections) {
            if (!add("") || !add("$title:")) { cut(lines.size); return sb.toString() }
            for ((i, line) in lines.withIndex()) {
                if (!add(line)) { cut(lines.size - i); return sb.toString() }
            }
        }
        return sb.toString()
    }

    /** The UTF-8 length of [s] in bytes, without encoding it. */
    fun utf8Length(s: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

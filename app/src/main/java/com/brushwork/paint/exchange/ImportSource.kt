package com.brushwork.paint.exchange

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.svg.SvgDocument
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream

/** An import that can't go on: [message] is shown to the user. */
class ImportException(message: String) : IOException(message)

/** What kind of file an import is. */
enum class ImportKind { SVG, PDF }

/**
 * A picked file ready to import: SVG files in memory ([bytes]), PDF files copied to a temporary
 * [file] (the platform renderer needs a seekable file). [close] deletes the copy.
 */
class ImportFile(val kind: ImportKind, val name: String, val bytes: ByteArray?, val file: File?) : Closeable {
    override fun close() {
        file?.delete()
    }
}

/**
 * Reads a picked Uri for import (v1.5 §4.11a): the content decides the kind (`%PDF-`, `<svg` /
 * `<?xml`, gzip-compressed SVG), whatever the provider claims. SVG files are limited to 20 MB
 * (Brushwork's own files with their data to 200 MB). Blocking: call on IO.
 */
object ImportSource {
    private const val SNIFF = 64 * 1024

    fun open(context: Context, uri: Uri): ImportFile {
        val resolver = context.contentResolver
        val name = displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported"
        val head = resolver.openInputStream(uri)?.use { readUpTo(it, SNIFF) } ?: throw ImportException("The file could not be opened")
        return when (sniff(head)) {
            ImportKind.PDF -> {
                val dir = File(context.cacheDir, "imports").apply { mkdirs() }
                prune(dir)
                val f = File(dir, "import-${UUID.randomUUID()}.pdf")
                try {
                    resolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                        ?: throw ImportException("The file could not be opened")
                } catch (e: Throwable) {
                    f.delete()
                    throw e
                }
                ImportFile(ImportKind.PDF, name, null, f)
            }
            ImportKind.SVG -> {
                val gz = head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
                val bytes = resolver.openInputStream(uri)?.use { raw ->
                    val input: InputStream = if (gz) GZIPInputStream(raw) else raw
                    readSvg(input)
                } ?: throw ImportException("The file could not be opened")
                ImportFile(ImportKind.SVG, name, bytes, null)
            }
            null -> throw ImportException("This file is neither an SVG nor a PDF")
        }
    }

    /** The kind of a file starting with [head], or null. */
    fun sniff(head: ByteArray): ImportKind? {
        if (head.size >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return ImportKind.PDF
        // %PDF- may follow some junk bytes (allowed within the first 1 KB).
        val start = String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1)
        if (start.contains("%PDF-")) return ImportKind.PDF
        if (head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return ImportKind.SVG
        val text = String(head, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (text.startsWith("<svg") || text.contains("<svg") && (text.startsWith("<?xml") || text.startsWith("<!--") || text.startsWith("<!DOCTYPE"))) {
            return ImportKind.SVG
        }
        if (text.startsWith("<?xml") || text.startsWith("<!DOCTYPE svg")) return ImportKind.SVG
        return null
    }

    /** All of [input] within the SVG limits (larger for files carrying Brushwork data). */
    fun readSvg(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        var limit = SvgDocument.MAX_BYTES.toLong()
        var checked = false
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (!checked && out.size() >= SNIFF) {
                checked = true
                if (String(out.toByteArray(), 0, SNIFF, Charsets.UTF_8).contains(Payload.SVG_NAMESPACE)) limit = SvgDocument.MAX_PAYLOAD_BYTES.toLong()
            }
            if (out.size() > limit) {
                throw ImportException(if (limit == SvgDocument.MAX_BYTES.toLong()) "This SVG is too large to import (over 20 MB)" else "This file is too large to import")
            }
        }
        return out.toByteArray()
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val k = input.read(buf, n, max - n)
            if (k < 0) break
            n += k
        }
        return buf.copyOf(n)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    /** Copies older than a day (a crash mid-import) are deleted. */
    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }
}

package com.brushwork.paint.storage.backup

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** v1.9 A8: one document of a [SafTree] as its folder's children query listed it. */
internal class SafDoc(
    val uri: Uri,
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val flags: Int,
) {
    val supportsRename: Boolean get() = flags and Document.FLAG_SUPPORTS_RENAME != 0
    val supportsMove: Boolean get() = flags and Document.FLAG_SUPPORTS_MOVE != 0
    override fun toString(): String = "SafDoc($name)"
}

/**
 * v1.9 A8 (R16 (b)): a picked folder (a Storage Access Framework tree) as the backup uses it.
 * Every folder's children are read with ONE children query per [SafTree] (one mirror run), then
 * kept up to date locally as files are made, renamed, moved and deleted (never
 * `DocumentFile.findFile`, which queries again for every name). Writes use "wt", since some
 * providers do not truncate on "w"; a provider that refuses "wt" gets a fresh file written with "w".
 *
 * Blocking; for the backup's worker thread and the restore's IO coroutine.
 */
internal class SafTree(private val resolver: ContentResolver, val tree: Uri) {
    private val listings = HashMap<String, LinkedHashMap<String, SafDoc>>()

    /** The picked folder itself (throws [FileNotFoundException] when it is gone or the grant is lost). */
    val picked: SafDoc by lazy {
        val id = DocumentsContract.getTreeDocumentId(tree)
        query(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: throw FileNotFoundException("The backup folder is gone")
    }

    /** One document by Uri (a single query), or null when it does not exist. */
    fun query(uri: Uri): SafDoc? = try {
        resolver.query(uri, COLUMNS, null as Bundle?, null)?.use { c -> if (c.moveToFirst()) docOf(c) else null }
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun docOf(c: android.database.Cursor): SafDoc {
        val id = c.getString(0)
        val mime = c.getString(2)
        return SafDoc(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
            id = id,
            name = c.getString(1) ?: "",
            isDir = mime == Document.MIME_TYPE_DIR,
            size = if (c.isNull(3)) 0L else c.getLong(3),
            flags = if (c.isNull(4)) 0 else c.getInt(4),
        )
    }

    /** [dir]'s children by name (one children query, then cached). */
    fun list(dir: SafDoc): Map<String, SafDoc> = listing(dir)

    private fun listing(dir: SafDoc): LinkedHashMap<String, SafDoc> = listings.getOrPut(dir.id) {
        val out = LinkedHashMap<String, SafDoc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id)
        resolver.query(uri, COLUMNS, null as Bundle?, null)?.use { c ->
            while (c.moveToNext()) {
                val d = docOf(c)
                // Two documents with one name (a provider's " (1)" leftovers aside): keep the first.
                out.putIfAbsent(d.name, d)
            }
        } ?: throw IOException("Could not list ${dir.name}")
        out
    }

    fun child(dir: SafDoc, name: String): SafDoc? = listing(dir)[name]

    /** The folder [name] in [parent], made when missing and [create] is true. */
    fun dir(parent: SafDoc, name: String, create: Boolean): SafDoc? {
        listing(parent)[name]?.let { return it.takeIf { d -> d.isDir } }
        if (!create) return null
        val uri = DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Could not create the folder $name")
        val made = SafDoc(uri, DocumentsContract.getDocumentId(uri), name, isDir = true, size = 0L, flags = 0)
        listing(parent)[name] = made
        listings[made.id] = LinkedHashMap() // a new folder is empty: no query needed
        return made
    }

    /** Creates the empty file [name] in [parent] (its name assumed free: the listing says so). */
    fun createFile(parent: SafDoc, name: String, mime: String): SafDoc {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name)
            ?: throw IOException("Could not create $name")
        val made = SafDoc(uri, DocumentsContract.getDocumentId(uri), name, isDir = false, size = 0L, flags = parent.flags)
        listing(parent)[name] = made
        return made
    }

    /**
     * Writes [block]'s bytes to [name] in [parent] (made when missing, rewritten with "wt" when
     * present) and returns the file. A provider that refuses "wt" gets the old file deleted and a
     * new one written with "w", so no old bytes can survive past the new end.
     */
    fun write(parent: SafDoc, name: String, mime: String, block: (OutputStream) -> Unit): SafDoc {
        var doc = listing(parent)[name] ?: createFile(parent, name, mime)
        val out = try {
            resolver.openOutputStream(doc.uri, "wt")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } ?: run {
            if (doc.size > 0L) {
                delete(parent, doc)
                doc = createFile(parent, name, mime)
            }
            resolver.openOutputStream(doc.uri, "w") ?: throw IOException("Could not write $name")
        }
        var written = 0L
        val counting = object : OutputStream() {
            override fun write(b: Int) { out.write(b); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            override fun flush() = out.flush()
        }
        out.use { block(counting); counting.flush() }
        val updated = SafDoc(doc.uri, doc.id, doc.name, isDir = false, size = written, flags = doc.flags)
        listing(parent)[name] = updated
        return updated
    }

    /** Writes [bytes] as [name] in [parent] ("wt"). */
    fun writeBytes(parent: SafDoc, name: String, mime: String, bytes: ByteArray): SafDoc =
        write(parent, name, mime) { it.write(bytes) }

    /** Copies [input] into [name] in [parent] with the 256 KB buffer ("wt"); closes [input]. */
    fun copyIn(parent: SafDoc, name: String, mime: String, input: InputStream): SafDoc =
        input.use { src -> write(parent, name, mime) { out -> src.copyTo(out, BackupLayout.BUFFER) } }

    fun open(doc: SafDoc): InputStream = resolver.openInputStream(doc.uri) ?: throw FileNotFoundException("Could not read ${doc.name}")

    fun readBytes(doc: SafDoc): ByteArray = open(doc).use { it.readBytes() }

    /** Deletes [doc] (a folder with everything in it) from [parent]. */
    fun delete(parent: SafDoc, doc: SafDoc) {
        if (!DocumentsContract.deleteDocument(resolver, doc.uri)) throw IOException("Could not delete ${doc.name}")
        val l = listing(parent)
        if (l[doc.name]?.id == doc.id) l.remove(doc.name)
        listings.remove(doc.id)
    }

    /**
     * Renames [doc] in [parent] to [name] and returns it, or throws when the provider gave it
     * another name (a taken name gets " (1)" on some providers: the caller deletes the old file
     * first, so a suffix means something else went wrong).
     */
    fun rename(parent: SafDoc, doc: SafDoc, name: String): SafDoc {
        val uri = DocumentsContract.renameDocument(resolver, doc.uri, name) ?: doc.uri
        val renamed = query(uri) ?: throw IOException("The renamed ${doc.name} is gone")
        val l = listing(parent)
        if (l[doc.name]?.id == doc.id) l.remove(doc.name)
        l[renamed.name] = renamed
        if (renamed.name != name) throw IOException("${doc.name} became ${renamed.name}, not $name")
        return renamed
    }

    /**
     * Moves [doc] from [from] into [to] (the provider's move when it supports it; else a copy of
     * its files, then a delete). Returns the moved folder or file.
     */
    fun move(doc: SafDoc, from: SafDoc, to: SafDoc): SafDoc {
        if (doc.supportsMove) {
            val uri = DocumentsContract.moveDocument(resolver, doc.uri, from.uri, to.uri)
            if (uri != null) {
                val moved = query(uri) ?: throw IOException("The moved ${doc.name} is gone")
                val l = listing(from)
                if (l[doc.name]?.id == doc.id) l.remove(doc.name)
                listing(to)[moved.name] = moved
                listings.remove(doc.id)
                return moved
            }
        }
        val copy = copyTree(doc, to)
        delete(from, doc)
        return copy
    }

    private fun copyTree(doc: SafDoc, to: SafDoc): SafDoc {
        if (!doc.isDir) return copyIn(to, doc.name, BackupLayout.MIME_DATA, open(doc))
        val target = dir(to, doc.name, create = true)!!
        for (child in list(doc).values) copyTree(child, target)
        return target
    }

    /** Forgets the cached listing of [dir] (the next [list] queries again). */
    fun forget(dir: SafDoc) {
        listings.remove(dir.id)
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}

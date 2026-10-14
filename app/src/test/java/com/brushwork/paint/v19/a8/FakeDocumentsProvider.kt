package com.brushwork.paint.v19.a8

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.9 A8 (R16, design decision 15): a [DocumentsProvider] over a plain folder, standing in for
 * the phone's storage provider (Robolectric cannot drive ExternalStorageProvider), with the quirks
 * the backup's writes guard against, each switchable:
 * - [truncatesOnW] false: "w" keeps the old bytes past the end of what is written (only "wt" truncates);
 * - [renameClash]: a rename onto an existing name suffixes it (" (1)") or fails, never replaces;
 * - [supportsRename] false: no `FLAG_SUPPORTS_RENAME`, and a rename throws;
 * - [failNextRename]: the next rename throws, like the app being killed between delete and rename;
 * - [supportsMove] false: no `FLAG_SUPPORTS_MOVE`;
 * - [availableBytes]: the free space its root reports.
 *
 * It counts the children queries per folder ([childQueries]), logs every change ([log]) as
 * "create|write|delete|rename|move <path>" and keeps the names of the threads that wrote
 * ([writers]). Document ids are "root:<path relative to the folder>".
 */
class FakeDocumentsProvider : DocumentsProvider() {
    enum class RenameClash { SUFFIX, FAIL }

    override fun onCreate(): Boolean = true

    private fun fileOf(docId: String): File {
        require(docId.startsWith(ROOT_DOC)) { "not a document of this provider: $docId" }
        val rel = docId.removePrefix(ROOT_DOC)
        return if (rel.isEmpty()) root else File(root, rel)
    }

    private fun idOf(file: File): String =
        if (file == root) ROOT_DOC else ROOT_DOC + file.relativeTo(root).invariantSeparatorsPath

    private fun pathOf(file: File): String = if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath

    private fun flagsOf(file: File): Int {
        var f = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
        if (supportsRename) f = f or Document.FLAG_SUPPORTS_RENAME
        if (supportsMove) f = f or Document.FLAG_SUPPORTS_MOVE
        if (file.isDirectory) f = f or Document.FLAG_DIR_SUPPORTS_CREATE
        return f
    }

    private fun addRow(c: MatrixCursor, file: File) {
        val row = c.newRow()
        for (col in c.columnNames) {
            when (col) {
                Document.COLUMN_DOCUMENT_ID -> row.add(col, idOf(file))
                Document.COLUMN_DISPLAY_NAME -> row.add(col, if (file == root) root.name else file.name)
                Document.COLUMN_MIME_TYPE -> row.add(col, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                Document.COLUMN_SIZE -> row.add(col, if (file.isDirectory) null else file.length())
                Document.COLUMN_LAST_MODIFIED -> row.add(col, file.lastModified())
                Document.COLUMN_FLAGS -> row.add(col, flagsOf(file))
            }
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        val row = c.newRow()
        for (col in c.columnNames) {
            when (col) {
                Root.COLUMN_ROOT_ID -> row.add(col, "root")
                Root.COLUMN_DOCUMENT_ID -> row.add(col, ROOT_DOC)
                Root.COLUMN_TITLE -> row.add(col, "Fake storage")
                Root.COLUMN_FLAGS -> row.add(col, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                Root.COLUMN_AVAILABLE_BYTES -> row.add(col, availableBytes)
            }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException("no document $documentId")
        return MatrixCursor(projection ?: DOC_COLUMNS).also { addRow(it, file) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("no folder $parentDocumentId")
        childQueries.getOrPut(pathOf(dir)) { AtomicInteger() }.incrementAndGet()
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        dir.listFiles()?.sortedBy { it.name }?.forEach { addRow(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = documentId.removePrefix(ROOT_DOC)
        val p = parentDocumentId.removePrefix(ROOT_DOC)
        return documentId.startsWith(ROOT_DOC) && (p.isEmpty() || parent == p || parent.startsWith("$p/"))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a folder")
        if (mode.contains('w')) {
            if (!file.exists()) throw FileNotFoundException("no document $documentId")
            val truncate = mode.contains('t') || truncatesOnW
            log += "write ${pathOf(file)} $mode"
            writers += Thread.currentThread().name
            if (truncate) {
                RandomAccessFile(file, "rw").use { it.setLength(0) }
            }
            // ParcelFileDescriptor.parseMode("w") truncates; open without it so "w" keeps the tail.
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        if (!file.isFile) throw FileNotFoundException("no document $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** "name (1).ext" for a taken "name.ext" (the storage provider's way). */
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (f.exists()) f = File(dir, name.substring(0, dot) + " (${n++})" + name.substring(dot))
        return f
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("no folder $parentDocumentId")
        val file = unique(dir, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!file.mkdir()) throw IllegalStateException("could not create ${pathOf(file)}")
        } else if (!file.createNewFile()) {
            throw IllegalStateException("could not create ${pathOf(file)}")
        }
        log += "create ${pathOf(file)}"
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException("no document $documentId")
        log += "delete ${pathOf(file)}"
        if (!file.deleteRecursively()) throw IllegalStateException("could not delete ${pathOf(file)}")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        if (!supportsRename) throw UnsupportedOperationException("Rename not supported")
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException("no document $documentId")
        if (failNextRename) {
            failNextRename = false
            throw IllegalStateException("killed before the rename of ${pathOf(file)}")
        }
        var target = File(file.parentFile, displayName)
        if (target.exists() && target != file) {
            if (renameClash == RenameClash.FAIL) throw IllegalStateException("${pathOf(target)} exists")
            target = unique(file.parentFile!!, displayName)
        }
        if (!file.renameTo(target)) throw IllegalStateException("could not rename ${pathOf(file)}")
        log += "rename ${pathOf(file)} ${target.name}"
        return idOf(target)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        if (!supportsMove) throw UnsupportedOperationException("Move not supported")
        val file = fileOf(sourceDocumentId)
        val target = unique(fileOf(targetParentDocumentId), file.name)
        if (!file.renameTo(target)) throw IllegalStateException("could not move ${pathOf(file)}")
        log += "move ${pathOf(file)} ${pathOf(target)}"
        return idOf(target)
    }

    companion object {
        const val AUTHORITY = "com.brushwork.paint.test.documents"
        const val ROOT_DOC = "root:"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        @Volatile lateinit var root: File
        @Volatile var truncatesOnW = false
        @Volatile var renameClash = RenameClash.SUFFIX
        @Volatile var supportsRename = true
        @Volatile var failNextRename = false
        @Volatile var supportsMove = true
        @Volatile var availableBytes = 64L shl 30
        val childQueries = ConcurrentHashMap<String, AtomicInteger>()
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** The threads that opened a document for writing. */
        val writers: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Registers the provider over [folder] with every quirk on (the hardest provider) and returns the tree of [path]. */
        fun install(folder: File, path: String = ""): Uri {
            folder.mkdirs()
            root = folder
            truncatesOnW = false
            renameClash = RenameClash.SUFFIX
            supportsRename = true
            failNextRename = false
            supportsMove = true
            availableBytes = 64L shl 30
            childQueries.clear()
            log.clear()
            writers.clear()
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(info)
            if (path.isNotEmpty()) File(folder, path).mkdirs()
            return tree(path)
        }

        /** The tree Uri of the folder at [path] (as the folder picker returns it). */
        fun tree(path: String = ""): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC + path)

        /** The children queries of the folder at [path] since [install] or [resetCounts]. */
        fun queriesOf(path: String): Int = childQueries[path]?.get() ?: 0

        fun resetCounts() {
            childQueries.clear()
            log.clear()
            writers.clear()
        }
    }
}

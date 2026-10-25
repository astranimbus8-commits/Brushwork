package com.brushwork.paint.engine.budget

import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * v1.10 (F1 stub, design 3.3; body A10, design 2.8 and 2.10 M4): the phone's [StorageProbe].
 *
 * [freeBytes]: `StatFs.availableBytes` of the volume holding [freeBytes]'s directory (its nearest
 * existing parent while it is not made yet), so the swap cap of a session about to make its
 * directory reads the right volume. [writeRead]: M4, the bytes written in [CHUNK] (4 MiB) chunks
 * to one file under the directory, synced (`FileChannel.force(true)`), read back and deleted;
 * the speeds in MB/s (10^6 bytes per second). The read follows the write, so the page cache
 * serves it: shown in the check's details, never a decision input (design 2.10). Blocking; never
 * on main. A probe that throws reaches the caller (the check reads it as slow and full).
 */
object AndroidStorageProbe : StorageProbe {
    /** M4's chunk: 4 MiB. */
    const val CHUNK = 4 shl 20

    /** The probe file's name under the directory [writeRead] is given. */
    const val PROBE_FILE = "probe.bin"

    override fun freeBytes(dir: File): Long {
        var d: File? = dir.absoluteFile
        while (d != null && !d.exists()) d = d.parentFile
        return StatFs((d ?: dir).path).availableBytes
    }

    override fun writeRead(dir: File, bytes: Long): Pair<Float, Float> {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("probe directory not made: $dir")
        val file = File(dir, PROBE_FILE)
        val chunk = ByteArray(CHUNK) { (it * 31 + (it ushr 12)).toByte() }
        val chunks = ((bytes + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        val total = chunks.toLong() * CHUNK
        try {
            val w0 = SystemClock.elapsedRealtimeNanos()
            FileOutputStream(file).use { out ->
                repeat(chunks) { out.write(chunk) }
                out.channel.force(true)
            }
            val w1 = SystemClock.elapsedRealtimeNanos()
            var read = 0L
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    read += n
                }
            }
            val r1 = SystemClock.elapsedRealtimeNanos()
            if (read != total) throw IOException("probe read $read of $total bytes")
            return mbs(total, w1 - w0) to mbs(total, r1 - w1)
        } finally {
            file.delete()
        }
    }

    /** [bytes] in [nanos] as MB/s; an immeasurably short time counts as 1 µs. */
    private fun mbs(bytes: Long, nanos: Long): Float = (bytes / 1e6 / (nanos.coerceAtLeast(1_000L) / 1e9)).toFloat()
}

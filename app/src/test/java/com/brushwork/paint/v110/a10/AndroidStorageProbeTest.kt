package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.budget.AndroidStorageProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException

/**
 * v1.10 A10 (design 2.8 and 2.10 M4, step 4): the phone's storage probe. The free bytes are the
 * volume's available bytes, read for a directory not made yet (the swap directory of a session
 * about to start) without making it. M4 writes whole chunks into its directory (made when
 * missing), reads them back, gives two positive speeds and leaves no probe file; a directory it
 * cannot make throws.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val work get() = File(app.cacheDir, "a10-probe")

    @After
    fun cleanUp() {
        work.deleteRecursively()
        ShadowStatFs.reset()
    }

    @Test
    fun theFreeBytesAreTheVolumesAvailableBytes() {
        ShadowStatFs.registerStats(app.noBackupFilesDir, 4_000, 3_000, 2_500)
        val notMade = File(app.noBackupFilesDir, "swap/session-to-come")
        assertFalse(notMade.exists())
        assertEquals(2_500L * ShadowStatFs.BLOCK_SIZE, AndroidStorageProbe.freeBytes(notMade))
        assertFalse("reading never makes it", notMade.exists())
        assertEquals(2_500L * ShadowStatFs.BLOCK_SIZE, AndroidStorageProbe.freeBytes(app.noBackupFilesDir))
    }

    @Test
    fun m4WritesReadsBackAndLeavesNothing() {
        val dir = File(work, "check")
        val (write, read) = AndroidStorageProbe.writeRead(dir, AndroidStorageProbe.CHUNK + 1L)
        assertTrue("write $write MB/s", write > 0f && write.isFinite())
        assertTrue("read $read MB/s", read > 0f && read.isFinite())
        assertTrue("its directory made", dir.isDirectory)
        assertFalse("no probe file left", File(dir, AndroidStorageProbe.PROBE_FILE).exists())
        assertEquals("nothing else left", 0, dir.listFiles()!!.size)
    }

    @Test
    fun aDirectoryItCannotMakeThrows() {
        val blocker = File(work, "a-file").apply { parentFile!!.mkdirs(); writeText("not a directory") }
        assertThrows(IOException::class.java) { AndroidStorageProbe.writeRead(File(blocker, "check"), 1L) }
    }
}

package com.brushwork.paint.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.brushwork.paint.core.PixelBuffer
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * Subject segmentation with Google ML Kit (Play services module "subject_segment").
 *
 * The module is installed on demand through [ModuleInstallClient] (and prefetched when the
 * service is created). A call waits up to [INSTALL_WAIT_MS] for a download while it makes
 * progress; a download that has shown no progress for [STALL_MS] (offline, queued) is not waited
 * for, and the install continues in the background (a listener flips [moduleReady]). Failures (no
 * Play services, no network, errors) make the backend return null — the pipeline then falls back
 * to the scene model and saliency — and are retried after [RETRY_AFTER_MS].
 */
internal class MlKitSubjectBackend private constructor(private val appContext: Context) : SubjectBackend {
    private val lock = ReentrantLock()
    private var segmenter: SubjectSegmenter? = null

    /** A module install request whose listener has not reported completion yet. */
    private class PendingInstall(val startedAt: Long) {
        val done = CountDownLatch(1)
        @Volatile var lastActivityAt: Long = startedAt
    }

    @Volatile private var moduleReady = false
    @Volatile private var retryAtMs = 0L
    @Volatile private var pendingInstall: PendingInstall? = null

    override fun subjectMask(image: PixelBuffer): FloatArray? {
        if (Looper.getMainLooper().isCurrentThread) {
            Log.w(TAG, "ML Kit skipped: segmentation must run off the main thread")
            return null
        }
        if (!usable()) return null
        try {
            // Interruptible: another thread may hold the lock while it waits for the download.
            lock.lockInterruptibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        try {
            val seg = segmenterLocked()
            return if (!ensureModuleLocked(seg, INSTALL_WAIT_MS)) null else processLocked(seg, image)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit subject segmentation unavailable", e)
            backOff()
            return null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Starts the module download without waiting for it (call from a background thread). Does
     * nothing if a segmentation is running (it requests the module itself).
     */
    fun requestModule() {
        if (moduleReady || Looper.getMainLooper().isCurrentThread || !usable()) return
        if (!lock.tryLock()) return
        try {
            ensureModuleLocked(segmenterLocked(), waitMs = 0L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit module request failed", e)
            backOff()
        } finally {
            lock.unlock()
        }
    }

    private fun usable(): Boolean = SystemClock.elapsedRealtime() >= retryAtMs && playServicesAvailable()

    private fun playServicesAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        false
    }

    private fun backOff() {
        retryAtMs = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
    }

    private fun segmenterLocked(): SubjectSegmenter =
        segmenter ?: SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        ).also { segmenter = it }

    /** True once the Play services module is installed; may wait up to [waitMs] for a download. */
    private fun ensureModuleLocked(seg: SubjectSegmenter, waitMs: Long): Boolean {
        if (moduleReady) return true
        val client = ModuleInstall.getClient(appContext)
        pendingInstall?.let { pending ->
            // E.g. started by the prefetch: wait while it progresses, not at all once it stalled.
            if (waitMs > 0 && awaitInstall(pending, waitMs)) return true
            if (moduleReady) return true
            // The status listener can be lost (e.g. Play services restarted): ask directly.
            if (modulesAvailable(client, seg)) {
                moduleReady = true
                if (pendingInstall === pending) pendingInstall = null
                return true
            }
            if (SystemClock.elapsedRealtime() - pending.startedAt < PENDING_EXPIRY_MS) return false
            Log.i(TAG, "ML Kit module install made no progress; requesting it again")
            if (pendingInstall === pending) pendingInstall = null
        }
        if (modulesAvailable(client, seg)) {
            moduleReady = true
            return true
        }
        val pending = PendingInstall(SystemClock.elapsedRealtime())
        val listener = object : InstallStatusListener {
            private var lastBytes = -1L

            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finish(true)
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        Log.w(TAG, "ML Kit module install ended in state ${update.installState} (error ${update.errorCode})")
                        finish(false)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING,
                    ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING -> {
                        val bytes = update.progressInfo?.bytesDownloaded ?: -1L
                        if (update.installState == ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING || bytes != lastBytes) {
                            lastBytes = bytes
                            pending.lastActivityAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }

            fun finish(ok: Boolean) {
                if (ok) moduleReady = true else backOff()
                if (pendingInstall === pending) pendingInstall = null
                client.unregisterListener(this)
                pending.done.countDown()
            }
        }
        pendingInstall = pending
        val request = ModuleInstallRequest.newBuilder()
            .addApi(seg)
            .setListener(listener, DIRECT)
            .build()
        try {
            val response = Tasks.await(client.installModules(request), REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            if (response.areModulesAlreadyInstalled()) {
                listener.finish(true)
                return true
            }
        } catch (e: Exception) {
            if (pendingInstall === pending) pendingInstall = null
            client.unregisterListener(listener)
            throw e
        }
        if (waitMs > 0 && !awaitInstall(pending, waitMs)) {
            Log.i(TAG, "ML Kit module still downloading; using the fallback for now")
        }
        return moduleReady
    }

    private fun modulesAvailable(client: ModuleInstallClient, seg: SubjectSegmenter): Boolean =
        Tasks.await(client.areModulesAvailable(seg), REQUEST_TIMEOUT_S, TimeUnit.SECONDS).areModulesAvailable()

    /**
     * Waits for [pending] to finish for at most [maxMs], but gives up as soon as the download has
     * shown no progress for [STALL_MS]. Returns true if the module is ready.
     */
    private fun awaitInstall(pending: PendingInstall, maxMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + maxMs
        while (!moduleReady) {
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline || now - pending.lastActivityAt > STALL_MS) return false
            if (pending.done.await(min(POLL_MS, deadline - now), TimeUnit.MILLISECONDS)) break
        }
        return moduleReady
    }

    private fun processLocked(seg: SubjectSegmenter, image: PixelBuffer): FloatArray? {
        // Not recycled: after a timeout ML Kit may still be reading it (the GC frees it).
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        val result = try {
            Tasks.await(seg.process(InputImage.fromBitmap(bitmap, 0)), PROCESS_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "ML Kit subject segmentation timed out")
            return null
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) {
                // The module went away (e.g. Play services update): check again next time.
                moduleReady = false
                Log.w(TAG, "ML Kit module not available", cause)
            } else {
                Log.w(TAG, "ML Kit subject segmentation failed", cause ?: e)
                backOff()
            }
            return null
        }
        val buffer = result.foregroundConfidenceMask ?: return null
        buffer.rewind()
        if (buffer.remaining() != image.size) {
            Log.w(TAG, "ML Kit mask has ${buffer.remaining()} values for ${image.width}x${image.height}")
            return null
        }
        return FloatArray(image.size).also { buffer.get(it) }
    }

    companion object {
        private const val TAG = "Segmentation"
        private const val REQUEST_TIMEOUT_S = 20L
        private const val PROCESS_TIMEOUT_S = 30L
        /** Longest wait for a module download that keeps making progress. */
        private const val INSTALL_WAIT_MS = 45_000L
        /** A download without any progress for this long is not waited for (offline, queued). */
        private const val STALL_MS = 10_000L
        /** A request whose listener never reported back is issued again after this long. */
        private const val PENDING_EXPIRY_MS = 5 * 60_000L
        private const val POLL_MS = 250L
        private const val RETRY_AFTER_MS = 3 * 60_000L
        private val DIRECT = Executor { it.run() }

        @Volatile private var instance: MlKitSubjectBackend? = null

        fun get(context: Context): MlKitSubjectBackend =
            instance ?: synchronized(this) {
                instance ?: MlKitSubjectBackend(context.applicationContext ?: context).also { instance = it }
            }
    }
}

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
import kotlin.math.min

/**
 * Subject segmentation with Google ML Kit (Play services module "subject_segment").
 *
 * The module is installed on demand through [ModuleInstallClient]. The first call waits up to
 * [INSTALL_WAIT_MS] for the download; if it takes longer the install continues in the background
 * (a listener flips [moduleReady]) and later calls only wait briefly. Failures (no Play services,
 * no network, errors) make the backend return null — the pipeline then falls back to the scene
 * model and saliency — and are retried after [RETRY_AFTER_MS].
 */
internal class MlKitSubjectBackend private constructor(private val appContext: Context) : SubjectBackend {
    private val lock = Any()
    private var segmenter: SubjectSegmenter? = null

    @Volatile private var moduleReady = false
    @Volatile private var retryAtMs = 0L
    @Volatile private var pendingInstall: CountDownLatch? = null

    override fun subjectMask(image: PixelBuffer): FloatArray? {
        if (Looper.getMainLooper().isCurrentThread) {
            Log.w(TAG, "ML Kit skipped: segmentation must run off the main thread")
            return null
        }
        if (!usable()) return null
        synchronized(lock) {
            return try {
                val seg = segmenterLocked()
                if (!ensureModuleLocked(seg, INSTALL_WAIT_MS)) null else processLocked(seg, image)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit subject segmentation unavailable", e)
                backOff()
                null
            }
        }
    }

    /** Starts the module download without waiting for it (call from a background thread). */
    fun requestModule() {
        if (moduleReady || Looper.getMainLooper().isCurrentThread || !usable()) return
        synchronized(lock) {
            try {
                ensureModuleLocked(segmenterLocked(), waitMs = 0L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit module request failed", e)
                backOff()
            }
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
        pendingInstall?.let { latch ->
            if (waitMs > 0) latch.await(min(waitMs, PENDING_WAIT_MS), TimeUnit.MILLISECONDS)
            return moduleReady
        }
        val client = ModuleInstall.getClient(appContext)
        if (Tasks.await(client.areModulesAvailable(seg), REQUEST_TIMEOUT_S, TimeUnit.SECONDS).areModulesAvailable()) {
            moduleReady = true
            return true
        }
        val latch = CountDownLatch(1)
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finish(true)
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        Log.w(TAG, "ML Kit module install ended in state ${update.installState} (error ${update.errorCode})")
                        finish(false)
                    }
                }
            }

            fun finish(ok: Boolean) {
                if (ok) moduleReady = true else backOff()
                pendingInstall = null
                client.unregisterListener(this)
                latch.countDown()
            }
        }
        pendingInstall = latch
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
            pendingInstall = null
            client.unregisterListener(listener)
            throw e
        }
        if (waitMs > 0 && !latch.await(waitMs, TimeUnit.MILLISECONDS)) {
            Log.i(TAG, "ML Kit module still downloading; using the fallback for now")
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
        /** First wait for the module download. */
        private const val INSTALL_WAIT_MS = 45_000L
        /** Wait when a download started by an earlier call is still running. */
        private const val PENDING_WAIT_MS = 4_000L
        private const val RETRY_AFTER_MS = 3 * 60_000L
        private val DIRECT = Executor { it.run() }

        @Volatile private var instance: MlKitSubjectBackend? = null

        fun get(context: Context): MlKitSubjectBackend =
            instance ?: synchronized(this) {
                instance ?: MlKitSubjectBackend(context.applicationContext ?: context).also { instance = it }
            }
    }
}

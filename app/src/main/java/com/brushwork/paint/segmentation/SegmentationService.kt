package com.brushwork.paint.segmentation

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterServices
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Lightroom-style semantic targets. */
enum class SmartTarget(val label: String) {
    SUBJECT("Subject"),
    BACKGROUND("Background"),
    SKY("Sky"),
    NATURE("Nature (plants, trees, grass)"),
    BUILDINGS("Buildings"),
    PEOPLE("People"),
    WATER("Water"),
}

/**
 * On-device segmentation. All methods are BLOCKING and must be called off the main thread.
 * Results are per-pixel confidences 0..1 with the same size as the input.
 *
 * - SUBJECT uses ML Kit Subject Segmentation (Play services module, installed on demand);
 *   BACKGROUND is its complement. Without ML Kit: scene-model people ∪ a saliency heuristic.
 * - SKY / NATURE / BUILDINGS / PEOPLE / WATER use the bundled Autoseg-EdgeTPU scene parser
 *   (LiteRT). Without it: color/texture heuristics ([SceneHeuristics]).
 * - Masks are computed at <= 1280 px and brought to full size with a fast guided filter that
 *   uses the full-resolution image as guide ([SegmentationPipeline]).
 *
 * Safe to call repeatedly and concurrently from background threads; results for several
 * targets on the same image share one analysis.
 */
class SegmentationService(private val context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val sceneParser = LiteRtSceneParser.get(appContext)
    private val subjectBackend = MlKitSubjectBackend.get(appContext)
    private val pipeline = SegmentationPipeline(sceneParser, subjectBackend, log = { msg, t -> Log.w(TAG, msg, t) })
    private val prepared = AtomicBoolean(false)

    /**
     * Confidence 0..1 per pixel of [image] (same size) that it belongs to [target], or null if
     * segmentation failed (e.g. out of memory). An all-zero result means "nothing found".
     */
    fun segment(image: PixelBuffer, target: SmartTarget): FloatArray? {
        if (Looper.getMainLooper().isCurrentThread) Log.w(TAG, "segment($target) called on the main thread")
        val start = SystemClock.elapsedRealtime()
        return try {
            pipeline.segment(image, target).also {
                Log.d(TAG, "$target ${image.width}x${image.height} in ${SystemClock.elapsedRealtime() - start} ms")
            }
        } catch (e: OutOfMemoryError) {
            pipeline.clearCache()
            Log.e(TAG, "segment($target) ran out of memory on ${image.width}x${image.height}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "segment($target) failed", e)
            null
        }
    }

    /** Adapter used by filters such as Background Removal. */
    fun asFilterServices(): FilterServices = object : FilterServices {
        override fun subjectMask(image: PixelBuffer): FloatArray? = segment(image, SmartTarget.SUBJECT)
    }

    /**
     * Non-blocking warm-up: loads the scene model and starts the ML Kit module download on a
     * background thread, so the first [segment] call is fast. Safe to call any number of times.
     */
    fun prepare() {
        if (!prepared.compareAndSet(false, true)) return
        thread(name = "bw-seg-prepare", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                sceneParser.warmUp()
                subjectBackend.requestModule()
            } catch (t: Throwable) {
                Log.w(TAG, "segmentation warm-up failed", t)
            }
        }
    }

    /** Frees cached analyses and the model interpreter (they are rebuilt on demand). */
    fun releaseMemory() {
        pipeline.clearCache()
        sceneParser.release()
    }

    private fun registerMemoryCallbacks() {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = releaseMemory()
        })
    }

    companion object {
        private const val TAG = "Segmentation"

        @Volatile private var instance: SegmentationService? = null
        fun get(context: Context): SegmentationService =
            instance ?: synchronized(this) {
                instance ?: SegmentationService(context.applicationContext).also {
                    instance = it
                    it.registerMemoryCallbacks()
                }
            }
    }
}

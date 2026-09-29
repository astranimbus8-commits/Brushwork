package com.brushwork.paint.segmentation

import android.content.Context
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterServices

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

// STUB — replaced by the selection module.
/**
 * On-device segmentation. All methods are BLOCKING and must be called off the main thread.
 * Results are per-pixel confidences 0..1 with the same size as the input.
 */
class SegmentationService(private val context: Context) {
    fun segment(image: PixelBuffer, target: SmartTarget): FloatArray? = null

    /** Adapter used by filters such as Background Removal. */
    fun asFilterServices(): FilterServices = object : FilterServices {
        override fun subjectMask(image: PixelBuffer): FloatArray? = segment(image, SmartTarget.SUBJECT)
    }

    companion object {
        @Volatile private var instance: SegmentationService? = null
        fun get(context: Context): SegmentationService =
            instance ?: synchronized(this) { instance ?: SegmentationService(context.applicationContext).also { instance = it } }
    }
}

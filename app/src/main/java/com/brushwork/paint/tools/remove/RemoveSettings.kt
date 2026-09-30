package com.brushwork.paint.tools.remove

import androidx.compose.runtime.mutableStateOf
import com.brushwork.paint.AppSettings
import com.brushwork.paint.inpaint.InpaintParams
import com.brushwork.paint.inpaint.SamplingArea
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Where a content-aware fill puts its result. */
@Serializable
enum class CafOutput(val label: String) {
    NEW_LAYER("New layer"),
    CURRENT_LAYER("Current layer"),
}

/** Which pixels a content-aware fill copies from. */
@Serializable
enum class CafSource(val label: String) {
    LAYER("Current layer"),
    ALL_LAYERS("All layers"),
}

/** Options of the selection's "Content-aware fill" (persisted). */
@Serializable
data class CafOptions(
    val sampling: SamplingArea = SamplingArea.AUTO,
    /** Grow the selection by 0..[InpaintParams.MAX_EXPAND] px before filling. */
    val expand: Int = InpaintParams.DEFAULT_EXPAND,
    val colorAdaptation: Boolean = true,
    val output: CafOutput = CafOutput.NEW_LAYER,
    val source: CafSource = CafSource.LAYER,
)

/** Remove tool options (persisted). */
@Serializable
data class RemoveSettings(
    /** Brush diameter in document pixels. */
    val size: Float = DEFAULT_SIZE,
    val source: CafSource = CafSource.LAYER,
    val colorAdaptation: Boolean = true,
) {
    companion object {
        const val MIN_SIZE = 2f
        const val MAX_SIZE = 600f
        const val DEFAULT_SIZE = 48f
        /** The painted area is grown by this much before filling (anti-aliased edges, halos). */
        const val EXPAND = 3
    }
}

/**
 * A value stored in the app settings as JSON and observable by Compose (reads come from a
 * snapshot state; writes save right away).
 */
internal class StoredSetting<T>(
    private val store: AppSettings,
    private val key: String,
    private val serializer: KSerializer<T>,
    default: T,
) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(runCatching { store.getObject(key, serializer) }.getOrNull() ?: default)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (value == state.value) return
        state.value = value
        runCatching { store.putObject(key, serializer, value) }
    }
}

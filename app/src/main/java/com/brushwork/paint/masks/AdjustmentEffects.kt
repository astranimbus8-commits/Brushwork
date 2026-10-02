package com.brushwork.paint.masks

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.PixelMapper

/**
 * The effects adjustment layers can have (v1.5 §4.3; owned by A5): every pointwise filter (a
 * non-null [Filter.pixelMapper]), with its values stored as JSON in [AdjustmentSpec.values].
 */
object AdjustmentEffects {
    /** The default effect of new adjustment layers. */
    const val DEFAULT_ID = "adjust.tone"

    /**
     * Filters known to be EXACTLY the identity at their default values: an adjustment layer with
     * such an effect at its defaults is skipped by the compositor (nothing to map, and no
     * premultiplication round trip either). Tone's defaults are the identity by contract (§4.8).
     */
    private val IDENTITY_AT_DEFAULTS = setOf(DEFAULT_ID)

    /** Filters that can be an adjustment layer's effect, in menu order. */
    val filters: List<Filter> get() = FilterRegistry.all.filter { it.isAdjustmentCapable }

    /** The filter of [spec], or null when it is unknown here (an effect from a newer version). */
    fun filterOf(spec: AdjustmentSpec): Filter? = FilterRegistry.byId(spec.filterId)

    /** The values of [spec] for [filter] (defaults for what is missing or unreadable). */
    fun valuesOf(spec: AdjustmentSpec, filter: Filter): FilterValues = FilterValuesCodec.fromJson(filter, spec.values)

    /** The spec of [filter] with [values]. */
    fun spec(filter: Filter, values: FilterValues): AdjustmentSpec =
        AdjustmentSpec(filterId = filter.id, values = FilterValuesCodec.toJson(filter, values))

    /**
     * Default values of [filter] for a new effect: parameters that follow the drawing color
     * start at [drawingColor] (as in a filter session).
     */
    fun defaultValues(filter: Filter, drawingColor: Int): FilterValues {
        val v = filter.defaultValues()
        for (p in filter.params) if (p is FilterParam.Color && p.useDrawingColor) v.set(p.key, drawingColor)
        return v
    }

    /** A new default spec of [filter] (Tone when null). */
    fun defaultSpec(filter: Filter? = FilterRegistry.byId(DEFAULT_ID), drawingColor: Int = 0xFF000000.toInt()): AdjustmentSpec =
        if (filter == null) AdjustmentSpec() else spec(filter, defaultValues(filter, drawingColor))

    /** True when [spec] is a known effect whose mapping changes nothing (it is skipped). */
    fun isIdentity(spec: AdjustmentSpec): Boolean {
        if (spec.filterId !in IDENTITY_AT_DEFAULTS) return false
        val f = filterOf(spec) ?: return false
        return FilterValuesCodec.toJson(f, valuesOf(spec, f)) == FilterValuesCodec.toJson(f, f.defaultValues())
    }

    /**
     * The pixel mapper of [spec], or null when the layer draws as pass-through: an unknown effect,
     * a filter without a mapper, or the exact identity.
     */
    fun mapperOf(spec: AdjustmentSpec): PixelMapper? {
        val f = filterOf(spec) ?: return null
        if (f.generatesContent) return null
        if (isIdentity(spec)) return null
        return try {
            f.pixelMapper(valuesOf(spec, f))
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * True when [filter] at [values] can be applied live by an adjustment layer: pointwise at
     * these settings. Some settings of adjustment-capable filters look at neighbours or at the
     * whole picture (Levels' "Auto levels", Black & White's smoothing and anti-aliasing): an
     * adjustment layer with them would show nothing, so they are refused where they are set.
     */
    fun isLive(filter: Filter, values: FilterValues): Boolean {
        if (filter.generatesContent) return false
        return try {
            filter.pixelMapper(values) != null
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Why [filter]'s current settings can't be an adjustment layer's live effect. */
    fun notLiveMessage(filter: Filter, setting: String? = null): String =
        if (setting != null) "\"$setting\" can't be live in an adjustment layer (it looks at the whole picture): use Filters → ${filter.name} for it"
        else "${filter.name} can't be live in an adjustment layer with these settings (they look at the whole picture)"

    /** "Tone", or "Unknown effect" for an effect this version doesn't have. */
    fun displayName(spec: AdjustmentSpec): String = filterOf(spec)?.name ?: "Unknown effect"
}

package com.brushwork.paint.snap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.IncrementSettings

/**
 * The increments service (v1.6 §3.4; real, frozen): `EditorController.increments`, one per
 * controller (no process-global state: nothing leaks between editors or tests), exposed to the
 * shared controls through `ui/common/LocalIncrements`.
 *
 * [state] is Compose state, persisted in [AppSettings.increments] (app-wide, not per document,
 * never an undo step). While the master switch is off every helper returns its input unchanged
 * and [step] / [customStep] are null (I8: bit-identical to v1.5). Gestures snap in this order,
 * per axis: object snapping (a magenta guide that engages), then grid snapping, then the
 * increment; typed values are never quantized. View gestures (zoom, canvas rotation) are never
 * stepped.
 */
class Increments(private val settings: AppSettings) {
    /** The current steps and switch (Compose state); change them with [update]. */
    var state: IncrementSettings by mutableStateOf(settings.increments)
        private set

    /** Applies [t] to the steps, sanitizes and persists them (nothing when unchanged). */
    fun update(t: (IncrementSettings) -> IncrementSettings) {
        val next = t(state).sanitized()
        if (next == state) return
        state = next
        settings.increments = next
    }

    /** The master switch (Compose state). */
    val enabled: Boolean get() = state.enabled

    /** The step of [kind] in its unit (px, %, °), or null while increments are off. */
    fun step(kind: IncrementKind): Float? = if (state.enabled) state.step(kind) else null

    /** The custom step of control [key] (in that control's shown unit), or null while off or unset. */
    fun customStep(key: String): Float? = if (state.enabled) state.custom[key] else null

    /** A move from the gesture start (document px), each axis on a multiple of the Length step. */
    fun lengthDelta(d: Vec2): Vec2 {
        val s = step(IncrementKind.LENGTH) ?: return d
        return Vec2(IncrementMath.snapDelta(d.x, s), IncrementMath.snapDelta(d.y, s))
    }

    /** An absolute length or coordinate (document px) on a multiple of the Length step. */
    fun lengthAbs(v: Float): Float {
        val s = step(IncrementKind.LENGTH) ?: return v
        return IncrementMath.snap(v, s)
    }

    /** A size (brush size, font size, stroke width; px) on a multiple of the Size step (the caller clamps it). */
    fun size(v: Float): Float {
        val s = step(IncrementKind.SIZE) ?: return v
        return IncrementMath.snap(v, s)
    }

    /**
     * A scale factor relative to the gesture start (1 = unchanged) on the Scale step's multiples,
     * at least one step and always positive (a mirrored scale: pass `|k|`, keep the sign).
     */
    fun factor(k: Float): Float {
        val s = step(IncrementKind.SCALE) ?: return k
        return IncrementMath.snapFactor(k, s)
    }

    /** A percentage of the original size (Transform: 100, 110, 120 …) on the Scale step's multiples. */
    fun scalePercent(p: Float): Float {
        val s = step(IncrementKind.SCALE) ?: return p
        return IncrementMath.snapPercentOfOriginal(p, s)
    }

    /** An absolute angle (degrees) on the Angle step's multiples, in (-180, 180]. */
    fun angle(deg: Float): Float {
        val s = step(IncrementKind.ANGLE) ?: return deg
        return IncrementMath.snapAngle(deg, s)
    }

    /** A 0..1 fraction (opacity, flow...) on multiples of the Percent step; 0 and 1 stay reachable. */
    fun percent01(f: Float): Float {
        val s = step(IncrementKind.PERCENT) ?: return f
        if (!f.isFinite()) return f
        return (IncrementMath.snapInRange(f * 100.0, s.toDouble(), 0.0, 100.0) / 100.0).toFloat()
    }

    /**
     * What the gesture being quantized shows ("+30 px", "120 %", "45°"; the InfoChip slot), or
     * null. Set by the tool while its gesture is stepped, cleared when it ends.
     */
    var readout: String? by mutableStateOf(null)
}

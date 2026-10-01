package com.brushwork.paint.masks

import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A filter's values as JSON (the effect of an adjustment layer, [AdjustmentSpec.values]; v1.5,
 * owned by A5 after F1). Per parameter type: Slider -> number, Toggle -> bool, Choice / Seed /
 * Color -> int, Point -> [x, y], Curve -> [[x, y]…], Gradient -> [[pos, color]…], Text -> string.
 * Unknown keys are ignored; missing or unreadable values take the parameter's default.
 */
object FilterValuesCodec {
    fun toJson(f: Filter, v: FilterValues): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (p in f.params) {
            val raw = v.raw(p.key) ?: p.defaultValue()
            out[p.key] = when (p) {
                is FilterParam.Slider -> JsonPrimitive((raw as? Number)?.toFloat() ?: p.default)
                is FilterParam.Toggle -> JsonPrimitive(raw as? Boolean ?: p.default)
                is FilterParam.Choice -> JsonPrimitive((raw as? Number)?.toInt() ?: p.default)
                is FilterParam.Seed -> JsonPrimitive((raw as? Number)?.toInt() ?: p.default)
                is FilterParam.Color -> JsonPrimitive((raw as? Number)?.toInt() ?: p.default)
                is FilterParam.Point -> {
                    val a = raw as? FloatArray ?: floatArrayOf(p.defaultX, p.defaultY)
                    JsonArray(listOf(JsonPrimitive(a.getOrElse(0) { p.defaultX }), JsonPrimitive(a.getOrElse(1) { p.defaultY })))
                }
                is FilterParam.Curve -> {
                    @Suppress("UNCHECKED_CAST")
                    val pts = (raw as? List<*>)?.filterIsInstance<CurvePoint>() ?: p.default
                    JsonArray(pts.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) })
                }
                is FilterParam.Gradient -> {
                    val stops = (raw as? List<*>)?.filterIsInstance<GradientStop>() ?: p.default
                    JsonArray(stops.map { JsonArray(listOf(JsonPrimitive(it.position), JsonPrimitive(it.color))) })
                }
                is FilterParam.Text -> JsonPrimitive(raw as? String ?: p.default)
            }
        }
        return JsonObject(out)
    }

    fun fromJson(f: Filter, j: JsonObject): FilterValues {
        val values = f.defaultValues()
        for (p in f.params) {
            val e = j[p.key] ?: continue
            val v: Any? = when (p) {
                is FilterParam.Slider -> e.number()?.toFloat()?.takeIf { it.isFinite() }
                is FilterParam.Toggle -> (e as? JsonPrimitive)?.booleanOrNull
                is FilterParam.Choice -> e.int()
                is FilterParam.Seed -> e.int()
                is FilterParam.Color -> e.int()
                is FilterParam.Point -> (e as? JsonArray)?.let { a ->
                    val x = a.getOrNull(0)?.number()?.toFloat()
                    val y = a.getOrNull(1)?.number()?.toFloat()
                    if (x != null && y != null) floatArrayOf(x, y) else null
                }
                is FilterParam.Curve -> (e as? JsonArray)?.let { a ->
                    a.mapNotNull { item ->
                        val pair = item as? JsonArray ?: return@mapNotNull null
                        val x = pair.getOrNull(0)?.number()?.toFloat() ?: return@mapNotNull null
                        val y = pair.getOrNull(1)?.number()?.toFloat() ?: return@mapNotNull null
                        CurvePoint(x, y)
                    }.takeIf { it.size >= 2 }
                }
                is FilterParam.Gradient -> (e as? JsonArray)?.let { a ->
                    a.mapNotNull { item ->
                        val pair = item as? JsonArray ?: return@mapNotNull null
                        val pos = pair.getOrNull(0)?.number()?.toFloat() ?: return@mapNotNull null
                        val color = pair.getOrNull(1)?.int() ?: return@mapNotNull null
                        GradientStop(pos, color)
                    }.takeIf { it.isNotEmpty() }
                }
                is FilterParam.Text -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            if (v != null) values.set(p.key, v)
        }
        return values
    }

    private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** An int, also from a number written with a fraction or out of Int range (colors as unsigned). */
    private fun JsonElement.int(): Int? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        p.intOrNull?.let { return it }
        p.longOrNull?.let { return it.toInt() }
        return p.doubleOrNull?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
    }
}

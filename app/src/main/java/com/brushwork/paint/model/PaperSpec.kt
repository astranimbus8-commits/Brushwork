package com.brushwork.paint.model

import kotlinx.serialization.Serializable

/**
 * R15: a sheet under the artwork, never pixels (I21): no layer bitmap, merge result, layer
 * export, clipboard payload or undo snapshot contains it. 1.9 offers white only. Stored in
 * `project.json` (`ProjectFileDto.paper`) and in the Brushwork SVG / PDF payload
 * (`BrushworkPayload.paper`), both never written when there is no paper (I13).
 */
@Serializable
data class PaperSpec(val color: Int = 0xFFFFFFFF.toInt())

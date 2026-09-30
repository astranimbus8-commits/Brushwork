package com.brushwork.paint.tools.select

import kotlinx.serialization.Serializable

/** Object select options (persisted). */
@Serializable
data class ObjectSelectSettings(
    /** Analyse the whole canvas (what you see) or only the active layer. */
    val source: SampleSource = SampleSource.CANVAS,
    /** Snap the outline to color edges and keep fine detail (hair, fur, leaves). */
    val refineEdges: Boolean = true,
)

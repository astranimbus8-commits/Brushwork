package com.brushwork.paint.tools.transform

import com.brushwork.paint.AppSettings
import kotlinx.serialization.Serializable

/**
 * v1.7 (item 16, design §3.16 b): the Free deform preference ("Mesh columns", "Mesh rows",
 * "Smooth mesh"), kept under [KEY] through `AppSettings.getObject`/`putObject`. The mesh itself
 * is session state; these are what the next Free deform starts with.
 */
@Serializable
data class MeshSettings(
    val columns: Int = DEFAULT_CELLS,
    val rows: Int = DEFAULT_CELLS,
    /** Catmull-Rom patches through the vertices (C1 across cells); off: bilinear cells. */
    val smooth: Boolean = true,
) {
    /** Columns and rows held to 1..[MeshDeform.MAX_CELLS]. */
    fun sanitized(): MeshSettings = copy(
        columns = columns.coerceIn(1, MeshDeform.MAX_CELLS),
        rows = rows.coerceIn(1, MeshDeform.MAX_CELLS),
    )

    companion object {
        const val KEY = "transform.mesh"

        /** 3 x 3 cells (4 x 4 vertices). */
        const val DEFAULT_CELLS = 3

        /** The stored preference, or the defaults when there is none (or it can't be read). */
        fun load(settings: AppSettings): MeshSettings =
            runCatching { settings.getObject(KEY, serializer()) }.getOrNull()?.sanitized() ?: MeshSettings()

        fun save(settings: AppSettings, value: MeshSettings) {
            runCatching { settings.putObject(KEY, serializer(), value.sanitized()) }
        }
    }
}

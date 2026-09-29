package com.brushwork.paint.filters

import android.content.Context
import androidx.core.content.edit

/** The most recently used filter ids (newest first), persisted in SharedPreferences. */
object FilterRecents {
    const val PREFS_NAME = "brushwork_filters"
    const val MAX = 8
    private const val KEY = "recent"

    /** Moves [id] to the front of [list], dropping duplicates and anything beyond [max]. */
    fun push(list: List<String>, id: String, max: Int = MAX): List<String> =
        (listOf(id) + list.filter { it != id }).take(max)

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.take(MAX)
            ?: emptyList()

    fun record(context: Context, id: String) {
        val updated = push(load(context), id)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY, updated.joinToString("\n")) }
    }
}

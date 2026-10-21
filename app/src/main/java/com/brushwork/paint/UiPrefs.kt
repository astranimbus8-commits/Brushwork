package com.brushwork.paint

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * v1.9 (I26, R3): UI memory that history taps never roll back. Not journaled (a plain
 * `SharedPreferences` file, [FILE], not [AppSettings]' journal); not in the backup's settings.json.
 * Written only by an explicit tap on the More toggle or the object bar's extra-row toggle;
 * closeAll, mutual exclusion and covers only hide a row and never write here.
 */
class UiPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Tool ids (`ToolId.name`) whose options-strip More row is open; empty by default. */
    var stripOpenRows: Set<String>
        // A copy: the set SharedPreferences returns must not be changed or kept.
        get() = prefs.getStringSet(STRIP_OPEN_ROWS, null)?.toSet().orEmpty()
        set(v) = prefs.edit { putStringSet(STRIP_OPEN_ROWS, v.toHashSet()) }

    /** The vector object bar's extra row is open; closed by default. */
    var objectBarMoreOpen: Boolean
        get() = prefs.getBoolean(OBJECT_BAR_MORE_OPEN, false)
        set(v) = prefs.edit { putBoolean(OBJECT_BAR_MORE_OPEN, v) }

    /**
     * v1.10 (R4, F0): the array-behind hint (`ArrayLabels110.BEHIND_HINT`) was shown on this
     * install; false by default (missing = not shown yet). A3 reads and sets it.
     */
    var hintArrayBehind: Boolean
        get() = prefs.getBoolean(HINT_ARRAY_BEHIND, false)
        set(v) = prefs.edit { putBoolean(HINT_ARRAY_BEHIND, v) }

    /**
     * v1.10 (R1, F0): how many times one mirror side was picked on this phone, across sessions; 0
     * by default. `MirrorLabels110.SIDE_HINT` shows while it is below 3. A1 counts it.
     */
    var hintSidePicks: Int
        get() = prefs.getInt(HINT_SIDE_PICKS, 0)
        set(v) = prefs.edit { putInt(HINT_SIDE_PICKS, v) }

    companion object {
        /** The preferences file (never `brushwork_settings`, which is journaled). */
        const val FILE = "brushwork_ui"
        const val STRIP_OPEN_ROWS = "strip.openRows"
        const val OBJECT_BAR_MORE_OPEN = "objectBar.moreOpen"
        const val HINT_ARRAY_BEHIND = "hint.arrayBehind"
        const val HINT_SIDE_PICKS = "hint.sidePicks"
    }
}

package com.brushwork.paint.engine.check

import android.content.Context
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult

/**
 * v1.10 (A10, design 2.11): which check results the gallery announces, once each.
 *
 * A result is announced ([EngineNotice.CheckChose], drawn by `EngineNoticeCard`) when the layer
 * storage is Automatic and the check settled below FULL (CLASSIC or TILES, not provisional) with
 * another level or reason than the phone's previous final result. Nothing is announced for FULL,
 * a provisional result, an interrupted run recorded as CLASSIC, the same level again (an update's
 * re-run), a chosen Full or Classic, or while the low-memory closings' notice waits (it already
 * says Classic). "Shown once" is `UiPrefs.engineNoticeShownAt`, the
 * `ranAtMillis` of the result last announced: a result with nothing to announce sets it at once;
 * one with a notice sets it when the card shows ([markShown]). A notice raised but never shown
 * (the process ended first) is raised again at the next start ([atStart]).
 */
object CheckNotices {
    /** The `ranAtMillis` of the result whose notice is waiting in `EngineNotices.current` (0: none). */
    @Volatile
    var pendingRanAt: Long = 0L
        private set

    /** The notice for [result], whatever came before: CLASSIC or TILES and final; null otherwise. */
    fun noticeOf(result: EngineCheckResult): EngineNotice? {
        if (result.provisional || result.level == EngineLevel.FULL) return null
        val reason = result.reason.ifEmpty { if (result.level == EngineLevel.TILES) CheckReasons.SLOW_STORAGE else CheckReasons.LOW_MEMORY }
        return EngineNotice.CheckChose(result.level, reason)
    }

    /**
     * After the check stored [result] ([previous]: this phone's result before it, or null): raises
     * its notice when it has one to announce ([announce] false for an interrupted run), else marks
     * it as announced and withdraws a check notice still waiting. Any thread.
     */
    fun afterCheck(context: Context, result: EngineCheckResult, previous: EngineCheckResult?, mode: LayerStorageMode, announce: Boolean) {
        val changed = previous == null || previous.provisional || previous.level != result.level || previous.reason != result.reason
        val notice = if (announce && mode == LayerStorageMode.AUTOMATIC && changed) noticeOf(result) else null
        // The low-memory closings' notice already says Classic: it is not followed by a second one.
        if (notice != null && EngineNotices.current != EngineNotice.LowMemoryClosings) {
            raise(notice, result.ranAtMillis)
            return
        }
        UiPrefs(context).engineNoticeShownAt = result.ranAtMillis
        if (EngineNotices.current is EngineNotice.CheckChose) {
            EngineNotices.current = null
            pendingRanAt = 0L
        }
    }

    /** At the app's start: raises the notice of [stored] if it was raised before and never shown. */
    fun atStart(context: Context, stored: EngineCheckResult?, mode: LayerStorageMode) {
        if (stored == null || mode != LayerStorageMode.AUTOMATIC || EngineNotices.current != null) return
        if (stored.ranAtMillis <= UiPrefs(context).engineNoticeShownAt) return
        noticeOf(stored)?.let { raise(it, stored.ranAtMillis) }
    }

    /**
     * The user chose [mode] in the settings: a chosen Full or Classic settles a check notice still
     * waiting (its text would describe the automatic choice the user just replaced).
     */
    fun onModeChosen(context: Context, mode: LayerStorageMode) {
        val waiting = EngineNotices.current
        if (mode == LayerStorageMode.AUTOMATIC || waiting !is EngineNotice.CheckChose) return
        markShown(context, waiting)
        EngineNotices.current = null
        pendingRanAt = 0L
    }

    /** `EngineNoticeCard` shows [notice]: it counts as announced. */
    fun markShown(context: Context, notice: EngineNotice, nowMillis: Long = System.currentTimeMillis()) {
        val prefs = UiPrefs(context)
        when (notice) {
            is EngineNotice.CheckChose -> if (pendingRanAt > prefs.engineNoticeShownAt) prefs.engineNoticeShownAt = pendingRanAt
            EngineNotice.LowMemoryClosings -> prefs.engineLmkNoticeShownAt = nowMillis
        }
    }

    private fun raise(notice: EngineNotice, ranAt: Long) {
        pendingRanAt = ranAt
        EngineNotices.current = notice
    }
}

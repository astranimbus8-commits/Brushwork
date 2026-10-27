package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineStat
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.StatSummary
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.CheckDetails
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineState
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.engine.noticeText
import com.brushwork.paint.ui.engine.subtitleOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 A10 (design 2.11, step 2): the texts of the Layer storage row and its notices, without a
 * screen: the row's subtitle for each mode and result, one notice text per reason, and the lines
 * [CheckDetails] copies (with and without `EngineStats`).
 */
class LayerStorageTextsTest {
    private val auto = LayerStorageMode.AUTOMATIC

    @Test
    fun theSubtitleIsTheEffectiveChoice() {
        assertEquals(EngineLabels110.SUB_NO_CHECK, subtitleOf(auto, null, pending = false))
        assertEquals(EngineLabels110.SUB_FULL, subtitleOf(auto, A10Kit.result(EngineLevel.FULL), pending = false))
        assertEquals(EngineLabels110.SUB_TILES, subtitleOf(auto, A10Kit.result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE), pending = false))
        assertEquals(
            "Automatic: Classic (low memory)",
            subtitleOf(auto, A10Kit.result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY), pending = false),
        )
        assertEquals(
            String.format(EngineLabels110.SUB_CLASSIC, CheckReasons.SLOW_COMPOSITING),
            subtitleOf(auto, A10Kit.result(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING), pending = false),
        )
        assertEquals(
            "a Classic result without a reason reads as memory",
            String.format(EngineLabels110.SUB_CLASSIC, CheckReasons.LOW_MEMORY),
            subtitleOf(auto, A10Kit.result(EngineLevel.CLASSIC), pending = false),
        )
        // A provisional Classic waits for the check; a provisional FULL is what the sessions run with.
        val provisional = A10Kit.result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = true, inconclusiveRuns = 1)
        assertEquals(EngineLabels110.SUB_NO_CHECK, subtitleOf(auto, provisional, pending = false))
        assertEquals(EngineLabels110.SUB_FULL, subtitleOf(auto, A10Kit.result(EngineLevel.FULL, provisional = true), pending = false))
        // A choice is shown as chosen, whatever the check found.
        for (check in listOf(null, A10Kit.result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY), A10Kit.result(EngineLevel.FULL))) {
            assertEquals(EngineLabels110.SUB_FULL_CHOSEN, subtitleOf(LayerStorageMode.FULL, check, pending = false))
            assertEquals(EngineLabels110.SUB_CLASSIC_CHOSEN, subtitleOf(LayerStorageMode.CLASSIC, check, pending = false))
        }
        // After Check again, until the check runs.
        for (mode in LayerStorageMode.entries) {
            assertEquals(EngineLabels110.CHECK_PENDING, subtitleOf(mode, A10Kit.result(EngineLevel.FULL), pending = true))
        }
    }

    @Test
    fun oneNoticeTextPerReason() {
        assertEquals(EngineLabels110.CHECK_CLASSIC_MEMORY, noticeText(EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY)))
        assertEquals(EngineLabels110.CHECK_CLASSIC_SLOW, noticeText(EngineNotice.CheckChose(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING)))
        assertEquals(EngineLabels110.CHECK_TILES, noticeText(EngineNotice.CheckChose(EngineLevel.TILES, CheckReasons.SLOW_STORAGE)))
        assertEquals(EngineLabels110.LMK_NOTICE, noticeText(EngineNotice.LowMemoryClosings))
        for (text in listOf(EngineLabels110.CHECK_CLASSIC_MEMORY, EngineLabels110.CHECK_CLASSIC_SLOW, EngineLabels110.CHECK_TILES, EngineLabels110.LMK_NOTICE)) {
            assertTrue("each names the one path: $text", text.endsWith("To change it, ${EngineLabels110.SETTINGS_PATH}."))
        }
    }

    @Test
    fun theCopiedDetailsHoldTheCheckNumbersAndTheStats() {
        val mib = 1L shl 20
        val budgets = Budgets(javaMax = 512 * mib, nativeBudget = 1024 * mib, known = true, stepDown = 1)
        val check = A10Kit.result(EngineLevel.TILES, CheckReasons.SLOW_STORAGE, stepDown = 1, lmkDeathsAt = listOf(1_700_000_000_000L))
        val none = CheckDetails.lines(auto, EngineLevel.TILES, budgets, check, EngineState(), emptyList())
        val text = none.joinToString("\n")
        for (part in listOf(
            "Setting: automatic", "Running now: TILES, R 1024 MiB, J 512 MiB, memory known, step-down 1",
            "Check: TILES (slow or full storage)", "in 1000 ms, version ${A10Kit.VERSION}, ${A10Kit.key(3788L shl 20)}",
            "total 3788 MiB, available 1843 MiB, threshold 216 MiB", "Native probe: 256 MiB (REACHED)",
            "Deflate: 80 MB/s, inflate 250 MB/s, ratio 3.00", "Storage: write 120 MB/s, read 250 MB/s, free 8.0 GiB",
            "Composite of 64 layers: 20.0 ms", "Step-down: 1; low-memory closings: 20", "Check pending: no; slow swap reads: no",
        )) assertTrue("the details hold \"$part\":\n$text", text.contains(part))
        assertFalse("no timings while EngineStats keeps none:\n$text", text.contains("p50"))
        assertEquals(CheckDetails.text(auto, EngineLevel.TILES, budgets, check, EngineState(), emptyList()), text)

        val stats = listOf(StatSummary(EngineStat.SWITCH, 12, 31.5f, 48.25f), StatSummary(EngineStat.LEDGER_PEAK, 3, 300f * mib, 400f * mib))
        val with = CheckDetails.lines(auto, EngineLevel.TILES, budgets, check, EngineState(engineCheckPending = true), stats)
        assertTrue(with.contains("  SWITCH: 31.5 ms / 48.3 ms (12)") || with.contains("  SWITCH: 31.5 ms / 48.2 ms (12)"))
        assertTrue("the ledger peak in MiB: $with", with.contains("  LEDGER_PEAK: 300 MiB / 400 MiB (3)"))
        assertTrue(with.contains("Check pending: yes; slow swap reads: no"))

        val first = CheckDetails.lines(LayerStorageMode.FULL, EngineLevel.CLASSIC, budgets, null, EngineState(), emptyList())
        assertTrue(first.contains("Check: not run yet"))
        assertTrue(first.contains("Setting: full"))
    }
}

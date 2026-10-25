package com.brushwork.paint.v110.a8

import android.os.Looper
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.TileWorker
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * Helpers of the A8 tests (v1.10, design 2.0, 2.6, 2.7): draining the `tile-swapper` and the main
 * looper it posts to, and the TILES ledger.
 */
internal object A8Kit {
    /**
     * Runs the main looper until every [TileWorker] job ran its main part (the worker posts each
     * result there), failing after [timeoutMs] of wall time.
     */
    fun drain(timeoutMs: Long = 10_000L) {
        val looper = shadowOf(Looper.getMainLooper())
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            looper.idle()
            if (TileWorker.isIdle) return
            if (System.currentTimeMillis() > until) fail("the tile-swapper still has ${TileWorker.pendingJobs} jobs after $timeoutMs ms")
            Thread.sleep(1)
        }
    }

    /** The bytes the ledger counts under TILES now. */
    fun tiles(): Long = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
}

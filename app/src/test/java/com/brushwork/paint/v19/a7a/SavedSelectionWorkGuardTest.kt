package com.brushwork.paint.v19.a7a

import android.content.Context
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StabilityLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.9 A7a step 6 (design 3.2 A7a, R9): a saved selection whose compression throws never throws
 * at `await` (nor from the undo that lands it). The save is dropped (no row, no step), the error
 * is kept as a quiet report and [StabilityLabels19.ERROR_TOAST] names the step ("Save selection",
 * "Update saved selection"); nothing reaches the scope's handler, and the next save works.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionWorkGuardTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
    }

    @After
    fun drain() {
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    private fun square(): Selection {
        val p = Path().apply { addRect(20f, 20f, 120f, 100f, Path.Direction.CW) }
        return Selection.fromPath(p, 200, 150, antiAlias = false)
    }

    private fun controller(): EditorController {
        val c = Smoke.controller(app, Smoke.document(200, 150, layers = 1, whiteBottom = true))
        c.setSelection(square(), recordUndo = false)
        return c
    }

    @Test
    fun aThrowingSaveIsKeptSaidAndDroppedNeverThrownOn() {
        val c = controller()
        val steps = c.undoManager.undoCount
        c.beforeSavedSelectionPack = { throw IllegalStateException("pack test") }
        c.message = null
        assertTrue(c.saveSelection())
        assertTrue(Smoke.pumpUntil { c.pendingSavedSelections.isEmpty() && c.message != null })
        assertEquals(StabilityLabels19.ERROR_TOAST.format(SavedSelectionLabels.SAVE), c.message)
        assertTrue("no row", c.doc.savedSelections.isEmpty())
        assertEquals("no step", steps, c.undoManager.undoCount)
        CrashLog.drain()
        val r = CrashLog.reports(app).single()
        assertTrue(r.id, r.id.endsWith("-crash-quiet"))
        assertEquals("Handled error: IllegalStateException in saved selection", r.summary)

        // Landed by an undo before its work ends: nothing thrown there either.
        c.beforeSavedSelectionPack = { kotlinx.coroutines.delay(150); throw IllegalStateException("pack test, later") }
        assertTrue(c.saveSelection())
        c.undo()
        assertTrue(Smoke.pumpUntil { c.pendingSavedSelections.isEmpty() })
        Smoke.pump(300)
        assertTrue(c.doc.savedSelections.isEmpty())

        // The next save works.
        c.beforeSavedSelectionPack = null
        assertTrue(c.saveSelection())
        assertTrue(Smoke.pumpUntil { c.pendingSavedSelections.isEmpty() && c.doc.savedSelections.size == 1 })
        assertEquals(steps + 1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "a throwing saved selection")
    }
}

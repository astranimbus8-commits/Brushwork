package com.brushwork.paint.qa3

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Final QA (v1.5 §4.6, §7 checklist 5; v1.6 §3.7.8 pill) of the X / Y pill's own controls with
 * real fingers at the user's 392 dp, on a raster picture in the Transform tool: the screen
 * reader's "Increase X" / "Decrease Y" (the v1.5 ‹ › arrows: 1 px each), a drag of the number
 * that turns fine when the finger goes far above or below the cell, the "#" cell switching
 * increments on (a drag then lands on multiples of 10 px, "Increase X" goes to the next
 * multiple), a value typed in cm at the document DPI; all of it part of the pending transform
 * (✓ is one step). The fold is remembered by the next editor, and folding / unfolding never moves
 * the canvas.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.stripcontrolssandbox"])
class Qa3StripControlsUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            closeEditors()
        }
    }

    private fun closeEditors() {
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        activities.clear()
        runCatching { settle() }
    }

    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        controller.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun matrixOf(c: EditorController): FloatArray = FloatArray(9).also { c.viewTransform.matrix.getValues(it) }


    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    /** Runs the screen reader's custom action [label] of the cell [cellLabel] ("X slider"). */
    private fun action(cellLabel: String, label: String) {
        val cell = slider(cellLabel)
        cell.node.config[SemanticsActions.CustomActions].single { it.label == label }.action.invoke()
        settle(4)
    }

    @Test
    fun stripControls() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("transform: arrows, fine drag, typed cm, one step on ✓") { transform() }
        section("fold remembered by the next editor, no canvas jump") { fold() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun transform() {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2)) }
        c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawRect(160f, 110f, 240f, 190f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        val view0 = matrixOf(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool.transformState != null })
        settle()
        assertArrayEquals("the canvas doesn't move when the strip appears", view0, matrixOf(c), 0f)
        val steps = c.undoManager.undoCount
        assertEquals(200f, tool.anchorPosition!!.x, 0.01f)

        // The screen reader's increase / decrease (the v1.5 arrows): 1 px each.
        repeat(3) { action("X slider", "Increase X") }
        assertEquals("three increases", 203f, tool.anchorPosition!!.x, 0.01f)
        action("Y slider", "Decrease Y")
        assertEquals(149f, tool.anchorPosition!!.y, 0.01f)

        // A drag of the number that goes far from the cell (fine mode): the second half moves a tenth as far.
        val e = slider("X slider")
        val x0 = e.bounds.left + e.bounds.width * 0.5f
        val y0 = e.bounds.center.y
        val dp = activity.resources.displayMetrics.density
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            e.window.dispatchTouchEvent(ev)
            ev.recycle()
            idle(8)
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        val atDown = tool.anchorPosition!!.x
        for (i in 1..6) send(MotionEvent.ACTION_MOVE, x0 + 10f * i, y0)
        val coarse = tool.anchorPosition!!.x - atDown
        assertTrue("the drag moved it: $coarse", coarse > 10f)
        // The finger goes 60 dp down (more than 48 dp from the cell) and on 60 px to the right.
        send(MotionEvent.ACTION_MOVE, x0 + 60f, y0 + 60f * dp)
        settle(2)
        assertTrue("\"Fine\" shows while the finger is away from the track", SmokeUi.has("Fine", exact = true))
        val beforeFine = tool.anchorPosition!!.x
        for (i in 1..6) send(MotionEvent.ACTION_MOVE, x0 + 60f + 10f * i, y0 + 60f * dp)
        val fine = tool.anchorPosition!!.x - beforeFine
        assertEquals("fine mode moves a tenth as far ($coarse / $fine)", coarse * 0.1f, fine, coarse * 0.02f + 0.11f)
        send(MotionEvent.ACTION_UP, x0 + 120f, y0 + 60f * dp)
        settle(4)
        assertFalse("\"Fine\" goes with the finger", SmokeUi.has("Fine", exact = true))

        // "#": increments on (Length 10 px). A drag lands the value on a multiple of 10 px, and
        // "Increase X" goes to the next multiple.
        assertFalse(c.increments.enabled)
        SmokeUi.click("Increments", exact = true)
        assertTrue("the # cell switches increments on", c.increments.enabled)
        val cell = slider("X slider")
        RobolectricUi.drag(cell.window, cell.bounds.center.x to cell.bounds.center.y, cell.bounds.center.x + 107f to cell.bounds.center.y)
        val stepped = tool.anchorPosition!!.x
        assertEquals("on a multiple of 10 px: $stepped", Math.round(stepped / 10f) * 10f, stepped, 1e-3f)
        tool.setAnchorPosition(x = 203.0)
        tool.endNumericEdit()
        settle()
        action("X slider", "Increase X")
        assertEquals("the next multiple", 210f, tool.anchorPosition!!.x, 1e-3f)
        SmokeUi.click("Increments", exact = true)
        assertFalse("off again", c.increments.enabled)

        // Typed in cm at the document DPI.
        tool.unit = LengthUnit.CM
        settle()
        assertTrue("the strip shows cm", SmokeUi.has(" cm"))
        SmokeUi.click("Type Y")
        SmokeUi.typeAndDone("Y", "1.5")
        val y = (1.5 / 2.54 * c.doc.dpi).toFloat()
        assertEquals(y, tool.anchorPosition!!.y, 0.1f)
        assertEquals("all of it is the pending transform", steps, c.undoManager.undoCount)
        assertArrayEquals("the canvas never moved", view0, matrixOf(c), 0f)
        val x = tool.anchorPosition!!.x
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        // The picture is where the strip put it.
        val bmp = c.activeLayer.bitmap
        assertEquals("centre painted", 0xFF2266CC.toInt(), bmp.getPixel(Math.round(x), Math.round(y)))
        assertEquals("old place cleared", 0, bmp.getPixel(162, 112) ushr 24)
        c.undo()
        assertEquals("undo puts it back", 0xFF2266CC.toInt(), bmp.getPixel(200, 150))
    }

    private fun fold() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        AppSettings(context).coordinateStripFolded = false
        val doc = Smoke.document(400, 300, layers = 2)
        doc.layers[1].bitmap.eraseColor(0)
        Canvas(doc.layers[1].bitmap).drawRect(160f, 110f, 240f, 190f, Paint().apply { color = 0xFF2266CC.toInt() })
        val (_, c) = editor { Smoke.controller(it, doc) }
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool.transformState != null })
        settle()
        val view0 = matrixOf(c)
        SmokeUi.click("Fold the X / Y strip")
        assertTrue("folded: a lone ✥", SmokeUi.has("Unfold the X / Y strip"))
        assertFalse(SmokeUi.has("X slider"))
        assertArrayEquals("folding doesn't move the canvas", view0, matrixOf(c), 0f)
        tool.commit()
        settle()
        closeEditors()

        // The next editor (a new session, the same app settings) starts folded.
        assertTrue(AppSettings(context).coordinateStripFolded)
        val doc2 = Smoke.document(400, 300, layers = 2)
        Canvas(doc2.layers[1].bitmap).drawRect(100f, 100f, 140f, 140f, Paint().apply { color = 0xFF2266CC.toInt() })
        val (_, c2) = editor { EditorController(it.applicationContext, doc2, Smoke.newScope(), AppSettings(it)) }
        c2.selectTool(ToolId.TRANSFORM)
        val tool2 = c2.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil { tool2.transformState != null })
        settle()
        assertTrue("still folded", SmokeUi.has("Unfold the X / Y strip"))
        val view1 = matrixOf(c2)
        SmokeUi.click("Unfold the X / Y strip")
        assertTrue(SmokeUi.has("X slider"))
        assertArrayEquals("unfolding doesn't move the canvas", view1, matrixOf(c2), 0f)
        assertFalse("unfolded is remembered", AppSettings(context).coordinateStripFolded)
    }
}

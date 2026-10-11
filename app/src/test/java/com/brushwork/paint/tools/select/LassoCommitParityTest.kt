package com.brushwork.paint.tools.select

import android.content.Context
import android.graphics.Path
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 (C, design §4.7): the lasso's commit moved to [LassoCommit] without changing a byte. Twelve
 * lassos (freehand, polygon and curve; anti-aliased or not; the four modes over a prior
 * selection) are drawn through [LassoTool] as a finger would, and the selection each leaves is
 * compared with the SHA-256 recorded from the v1.7 tool at the branch's base (762b3cb7), before
 * the extraction. The same outlines committed straight through [LassoCommit.apply] give the same
 * masks, under the label they are given.
 */
@RunWith(RobolectricTestRunner::class)
class LassoCommitParityTest {

    private enum class Kind { FREEHAND, POLYGON, CURVE }

    private class Case(val kind: Kind, val points: List<Pair<Float, Float>>, val antiAlias: Boolean, val mode: SelectionMode, val withBase: Boolean)

    private fun circle(cx: Float, cy: Float, rx: Float, ry: Float, n: Int, turn: Float = 0f) = (0 until n).map { i ->
        val a = 2 * PI * i / n + turn
        (cx + rx * cos(a)).toFloat() to (cy + ry * sin(a)).toFloat()
    }

    private fun star(cx: Float, cy: Float, r1: Float, r2: Float, n: Int) = (0 until 2 * n).map { i ->
        val a = PI * i / n - PI / 2
        val r = if (i % 2 == 0) r1 else r2
        (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat()
    }

    /** A figure eight: the outline crosses itself (even-odd versus winding shows here). */
    private fun eight(cx: Float, cy: Float, r: Float, n: Int) = (0 until n).map { i ->
        val t = 2 * PI * i / n
        (cx + r * sin(t)).toFloat() to (cy + r * 0.6f * sin(2 * t)).toFloat()
    }

    private val cases = listOf(
        Case(Kind.FREEHAND, circle(150f, 110f, 80f, 60f, 48), antiAlias = true, SelectionMode.REPLACE, withBase = false),
        Case(Kind.FREEHAND, circle(140f, 120f, 70.5f, 90.25f, 40, 0.3f), antiAlias = false, SelectionMode.REPLACE, withBase = false),
        Case(Kind.POLYGON, listOf(40f to 30f, 260f to 50f, 230f to 200f, 60f to 180f), antiAlias = true, SelectionMode.REPLACE, withBase = false),
        Case(Kind.POLYGON, listOf(30.5f to 20.25f, 200.75f to 40f, 120f to 220.5f), antiAlias = false, SelectionMode.ADD, withBase = true),
        Case(Kind.CURVE, circle(160f, 120f, 90f, 70f, 5), antiAlias = true, SelectionMode.REPLACE, withBase = false),
        Case(Kind.CURVE, circle(150f, 110f, 100f, 80f, 6, 0.4f), antiAlias = false, SelectionMode.SUBTRACT, withBase = true),
        Case(Kind.FREEHAND, circle(170f, 130f, 110f, 70f, 64), antiAlias = true, SelectionMode.INTERSECT, withBase = true),
        Case(Kind.POLYGON, star(160f, 120f, 100f, 45f, 5), antiAlias = true, SelectionMode.SUBTRACT, withBase = true),
        Case(Kind.CURVE, circle(200f, 90f, 70f, 60f, 4, 0.2f), antiAlias = true, SelectionMode.ADD, withBase = true),
        Case(Kind.FREEHAND, eight(160f, 120f, 110f, 72), antiAlias = true, SelectionMode.REPLACE, withBase = false),
        Case(Kind.POLYGON, star(150f, 115f, 110f, 30f, 7), antiAlias = false, SelectionMode.INTERSECT, withBase = true),
        // Partly off the canvas: the mask is clipped to the document.
        Case(Kind.FREEHAND, circle(290f, 30f, 90f, 80f, 56), antiAlias = true, SelectionMode.ADD, withBase = true),
    )

    /** SHA-256 (first 16 hex digits) of each case's `selection.toBytes()`, recorded from v1.7's LassoTool at 762b3cb7. */
    private val recorded: List<String> = listOf(
        "ea855897fb73f18b", "cde0989c95a3f14d", "22330a8d34dfcea2", "fde9c403d42d1597",
        "4cdb3c784dade859", "36d67a9da762e4ce", "a902802236e68fdd", "ca78c523c48c571d",
        "8c07d7b8b463e63f", "77d3a7e9634daadd", "937aa67469f8da8a", "c77376e4127b0ff5",
    )

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", W, H)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(W, H))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
            .also { it.snapping.enabled = false }
    }

    /** The prior selection the ADD / SUBTRACT / INTERSECT cases combine with: an anti-aliased ellipse. */
    private fun base(): Selection = Selection.fromPath(Path().apply { addOval(60f, 40f, 250f, 190f, Path.Direction.CW) }, W, H, true)

    private fun await(c: EditorController, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue("the selection was applied", done())
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y)); pointerUp(ToolPoint(x, y))
    }

    /** Draws [case] through the lasso tool and returns the selection's bytes. */
    private fun throughTheTool(case: Case): ByteArray {
        val c = controller()
        c.selectTool(ToolId.LASSO)
        val tool = c.tools.getValue(ToolId.LASSO) as LassoTool
        tool.setKind(
            when (case.kind) {
                Kind.FREEHAND -> LassoKind.FREEHAND
                Kind.POLYGON -> LassoKind.POLYGON
                Kind.CURVE -> LassoKind.CURVE
            },
        )
        tool.settings = tool.settings.copy(antiAlias = case.antiAlias)
        tool.mode = case.mode
        val before = if (case.withBase) base().also { c.setSelection(it, recordUndo = false) } else null
        val steps = c.undoManager.undoCount
        when (case.kind) {
            Kind.FREEHAND -> {
                val p = case.points
                c.pointerDown(ToolPoint(p[0].first, p[0].second))
                for (i in 1 until p.size) c.pointerMove(ToolPoint(p[i].first, p[i].second))
                c.pointerUp(ToolPoint(p.last().first, p.last().second))
            }
            Kind.POLYGON, Kind.CURVE -> {
                for ((x, y) in case.points) c.tap(x, y)
                tool.commit()
            }
        }
        await(c) { c.selection !== before && !tool.busy }
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        return c.selection?.toBytes() ?: ByteArray(W * H)
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    @Test
    fun theToolsMasksAreTheRecordedV17Ones() {
        val got = cases.map { hash(throughTheTool(it)) }
        if (recorded.size != cases.size) {
            System.err.println("=== LassoCommitParityTest recorded hashes:")
            got.forEachIndexed { i, h -> System.err.println("RECORD $i \"$h\"") }
        }
        assertEquals(cases.size, recorded.size)
        for (i in cases.indices) assertEquals("lasso $i (${cases[i].kind}, aa ${cases[i].antiAlias}, ${cases[i].mode})", recorded[i], got[i])
        assertFalse("the cases differ from one another", got.toSet().size < cases.size)
    }

    @Test
    fun lassoCommitStraightGivesTheSameMasksUnderItsLabel() {
        // The polygon cases: the tool's outline is the polygon through the corners (snapping off).
        val polygons = cases.indices.filter { cases[it].kind == Kind.POLYGON }
        assertEquals(4, polygons.size)
        for (i in polygons) {
            val case = cases[i]
            val c = controller()
            val before = if (case.withBase) base().also { c.setSelection(it, recordUndo = false) } else null
            val path = LassoTool.polygonPath(case.points.flatMap { listOf(it.first, it.second) }.toFloatArray())
            var finished = 0
            LassoCommit.apply(c, path, case.mode, case.antiAlias, ToolId.MAGNETIC_LASSO.label, onFinished = { finished++ })
            path.reset() // the job has its own copy
            await(c) { c.selection !== before && finished == 1 }
            assertEquals("lasso $i straight", recorded[i], hash(c.selection!!.toBytes()))
            assertEquals("one step under the given label", ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
            // rasterize is Selection.fromPath, byte for byte.
            val again = LassoTool.polygonPath(case.points.flatMap { listOf(it.first, it.second) }.toFloatArray())
            assertTrue(LassoCommit.rasterize(again, W, H, case.antiAlias).toBytes().contentEquals(Selection.fromPath(again, W, H, case.antiAlias).toBytes()))
        }
    }

    private companion object {
        const val W = 320
        const val H = 240
    }
}

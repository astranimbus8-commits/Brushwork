package com.brushwork.paint.qa18

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.8 final QA (items 6 and 7, design §3.7 and §3.8): what the user SEES of each generated curve
 * and of cogs, drawn by the vector layer's renderer on a white 512 px page:
 * - every Curves entry (Archimedean, Golden, Logarithmic, Fermat spiral, Sine wave, Rose) on the
 *   Curve and the Path tool is drawn and stored with its generator (the stored curve matches it);
 * - a rose shows its petals (n when n·d is odd, else 2n: the design's r = R·cos(n/d·θ)), counted
 *   as ink crossings of a circle at 0.7 R (two per petal);
 * - a cog shows its teeth (6, 12, 64), counted round a circle half way down the teeth; a hole is
 *   a hole (the page shows through), round corners keep the teeth.
 * The renders go to the QA shots folder for the lead (only where it exists, [Qa18CurvesShots]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.qa18.curvesrendersandbox"])
class Qa18CurvesRenderTest {
    private val n = 512
    private val scopes = ArrayList<CoroutineScope>()

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("r", "r", n, n)
        d.layers += Layer(d.newLayerId(), "Background", BitmapUtils.createLayerBitmap(n, n))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(n, n)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.color = 0xFF203080.toInt()
            it.brush = BrushLibrary.defaultBrush.copy(size = 4f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    /** The active vector layer drawn on white, saved as [name] for the lead. */
    private fun render(c: EditorController, name: String): Bitmap {
        val b = BitmapUtils.createLayerBitmap(n, n)
        val cv = Canvas(b)
        cv.drawColor(Color.WHITE)
        VectorLayerRenderer.render(cv, c.activeLayer.vector!!, Rect(0, 0, n, n), tips = TipCache(), document = Rect(0, 0, n, n))
        Qa18CurvesShots.save(b, name)
        return b
    }

    private fun inked(b: Bitmap, x: Int, y: Int): Boolean {
        val p = b.getPixel(x.coerceIn(0, n - 1), y.coerceIn(0, n - 1))
        return (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 200
    }

    private fun ink(b: Bitmap): Int {
        var k = 0
        for (y in 0 until n) for (x in 0 until n) if (inked(b, x, y)) k++
        return k
    }

    /** Inked runs round the circle of radius [r] about ([cx], [cy]). */
    private fun runs(b: Bitmap, cx: Float, cy: Float, r: Float, samples: Int = 4096): Int {
        val on = BooleanArray(samples) { i ->
            val a = 2.0 * PI * i / samples
            inked(b, (cx + r * cos(a)).roundToInt(), (cy + r * sin(a)).roundToInt())
        }
        return (0 until samples).count { i -> on[i] && !on[(i + samples - 1) % samples] }
    }

    /** A generated [kind] on the [id] tool (its settings changed by [change]), committed and drawn. */
    private fun curve(id: ToolId, kind: CurveGenKind, name: String, change: (CurveGenerator) -> CurveGenerator = { it }): Pair<CurveGenerator, Bitmap> {
        val c = controller()
        c.selectTool(id)
        val t = c.tools.getValue(id) as CurveTool
        t.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 3f) }
        assertTrue("$name: placed", t.startGenerator(kind, null))
        t.changeGenerator(change(t.generator!!))
        val g = t.generator!!
        t.commit()
        val stored = c.activeLayer.vector!!.objects.single() as VPath
        assertEquals("$name: stored with its settings", g, stored.generator)
        assertTrue("$name: the stored curve is its generator's (I16)", CurveGenerators.matches(stored))
        val b = render(c, name)
        assertTrue("$name: drawn (${ink(b)} px)", ink(b) > 400)
        return g to b
    }

    /** Petals of the rose r = R·cos(n/d·θ) (design §3.7): n when n·d is odd, else 2n. */
    private fun petals(nn: Int, d: Int) = if ((nn * d) % 2 == 1) nn else 2 * nn

    private fun rose(nn: Int, d: Int = 1) {
        val (g, b) = curve(ToolId.CURVE, CurveGenKind.ROSE, if (d == 1) "curves-rose-petals-$nn.png" else "curves-rose-${nn}over$d.png") {
            it.copy(petalsN = nn, petalsD = d)
        }
        if (d == 1) assertEquals("Petals $nn: ${petals(nn, d)} petals seen", 2 * petals(nn, d), runs(b, g.cx, g.cy, 0.7f * g.size))
    }

    /** A cog dragged corner to corner of a 400 px box centred on the page, committed and drawn. */
    private fun cog(name: String, teeth: Int, hole: Float = 0f, corner: CornerStyle = CornerStyle.SHARP, radius: Float = 0f): Bitmap {
        val c = controller()
        c.selectTool(ToolId.SHAPE)
        val t = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        t.update {
            it.copy(
                type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40C0E0.toInt(),
                corner = corner, cornerRadius = radius, keepProportions = false, fromCenter = false, snapAngle = false,
                cogTeeth = teeth, cogHole = hole,
            )
        }
        c.pointerDown(ToolPoint(56f, 56f)); c.pointerMove(ToolPoint(250f, 250f)); c.pointerMove(ToolPoint(456f, 456f)); c.pointerUp(ToolPoint(456f, 456f))
        val depth = t.settings.cogDepth
        t.commit()
        val b = render(c, name)
        // Half way down the teeth (tips at 200 px, the root at 200·(1 - depth)).
        assertEquals("$name: $teeth teeth seen", teeth, runs(b, 256f, 256f, 200f * (1f - depth / 2f)))
        val holeR = hole * 200f * (1f - depth)
        assertEquals("$name: the centre is ${if (hole > 0f) "the page (a hole)" else "the cog"}", hole > 0f, !inked(b, 256, 256))
        if (hole > 0f) assertTrue("$name: the cog round the hole", inked(b, 256, (256 + holeR + 20f).roundToInt()))
        return b
    }

    @Test
    fun eachGeneratorAndCogIsDrawnAsDesigned() {
        try {
            for (k in CurveGenKind.entries) {
                curve(ToolId.CURVE, k, "curves-curve-${k.name.lowercase()}.png")
                curve(ToolId.PATH, k, "curves-path-${k.name.lowercase()}.png")
            }
            rose(5)
            for (p in listOf(2, 3, 4, 6)) rose(p)
            rose(7, 2)
            curve(ToolId.CURVE, CurveGenKind.ARCHIMEDEAN, "curves-archimedean-10turns-inner30.png") { it.copy(turns = 10f, inner = 0.3f) }
            curve(ToolId.CURVE, CurveGenKind.SINE, "curves-sine-8waves.png") { it.copy(waves = 8f) }
            cog("curves-cog-6.png", 6)
            cog("curves-cog-12.png", 12)
            cog("curves-cog-64.png", 64)
            cog("curves-cog-12-hole.png", 12, hole = 0.4f)
            cog("curves-cog-12-round.png", 12, corner = CornerStyle.ROUND, radius = 8f)
        } finally {
            scopes.forEach { it.cancel() }
        }
    }
}

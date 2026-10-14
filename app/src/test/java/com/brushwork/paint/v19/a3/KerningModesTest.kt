package com.brushwork.paint.v19.a3

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.KerningModes
import com.brushwork.paint.tools.text.ScriptRuns
import com.brushwork.paint.tools.text.TextBoxPreset
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.ui.common.KernLabels19
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.placement.PAIR_KERNING_TAGS
import com.brushwork.paint.ui.placement.pairKerningChosen
import com.brushwork.paint.ui.placement.pairKerningIndex
import com.brushwork.paint.ui.placement.pairKerningNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.Character.UnicodeScript
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.9 A3 step 3 (R5): the "Pair kerning" modes Off, Auto and Metrics (Optical: step 4).
 *
 * - Auto (and Metrics on Latin text, which has nothing to take back) draws the v1.8 pixels: the
 *   goldens of `UnscaledParityGoldenTest` (Windows hashes) and the StaticLayout route.
 * - Metrics keeps the font's pairs inside Latin, Greek and Cyrillic runs ("AV" kerned) and sets
 *   every other pair by its plain advances ("テキ" untouched by the font's kerning), with manual
 *   kerns on top; a mode that moves a letter exports as outlines.
 * - "Off" is the "Font kerning" switch: it keeps the stored mode, which the switch brings back.
 * - Straight vertical text keeps the font's pairs; text on a path takes its mode.
 */
@RunWith(RobolectricTestRunner::class)
class KerningModesTest {

    private val w = 500
    private val h = 400

    private fun crc(item: TextItem): Long {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    private fun TextItem.mode(m: TextKerningMode?) = copy(spec = spec.copy(kerningMode = m))

    /** `UnscaledParityGoldenTest`'s "plain" case. */
    private val plain = TextItem("Hello, World! gjpqy", TextSpec(sizePx = 40f), 250f, 200f)

    /** `UnscaledParityGoldenTest`'s "boxed" case. */
    private val boxed = TextItem(
        "Boxed words that wrap\nin a centred caption",
        TextSpec(
            sizePx = 28f, align = TextAlign.CENTER, lineSpacing = 1.3f, letterSpacing = 0.05f,
            box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(width = 300f), 28f),
        ),
        250f, 200f, 12f,
    )

    @Test
    fun autoAndMetricsOnLatinTextDrawTheV18Pixels() {
        for (item in listOf(plain, boxed)) {
            val metrics = item.mode(TextKerningMode.METRICS_LATIN)
            assertNull("Latin text: Metrics adds nothing", TextRenderer.kernPx(metrics.spec, metrics.text, emptyList(), 0, null))
            assertNotNull("Metrics on Latin text keeps the StaticLayout route", TextRenderer.prepare(metrics).block?.staticLayout)
            assertEquals("Metrics on Latin text draws Auto's pixels", crc(item), crc(metrics))
            assertFalse(TextRenderer.modeMovesLetters(metrics))
            assertEquals("Latin text exports as runs under Metrics", TextExport.lines(item), TextExport.lines(metrics)?.map { it.copy(paintSpec = it.paintSpec.copy(spec = item.spec)) })
        }
        assumeTrue("the goldens are Windows hashes", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assertEquals("Auto is v1.8's (v1.5's) pixels", 2439784897L, crc(plain))
        assertEquals(843261284L, crc(boxed))
        assertEquals(2439784897L, crc(plain.mode(TextKerningMode.METRICS_LATIN)))
    }

    @Test
    fun offKeepsTheModeAndDrawsUnkerned() {
        val av = TextItem("AVATAR", TextSpec(sizePx = 80f), 250f, 200f)
        val off = av.copy(spec = av.spec.copy(fontKerning = false))
        assertNotEquals("Off sets AV by its plain advances", crc(av), crc(off))
        // Off with a stored mode draws as Off; the switch turned on brings the mode back.
        val offMetrics = off.mode(TextKerningMode.METRICS_LATIN)
        assertEquals(crc(off), crc(offMetrics))
        assertNull(KerningModes.modeOf(offMetrics.spec))
        assertEquals(TextKerningMode.METRICS_LATIN, KerningModes.modeOf(offMetrics.spec.copy(fontKerning = true)))
    }

    @Test
    fun metricsKeepsLatinPairsAndSetsOtherScriptsByTheirPlainAdvances() {
        val text = "AVATAR テキスト ВАТА ΑΥΓΟ\nこんにちは Wave"
        val spec = TextSpec(sizePx = 60f, kerningMode = TextKerningMode.METRICS_LATIN)
        val item = TextItem(text, spec, 250f, 200f)
        val block = requireNotNull(TextRenderer.prepare(item).block)
        val on = FloatArray(text.length)
        val plainAdv = FloatArray(text.length)
        val paint = Paint(block.textPaint)
        val unkerned = Paint(paint).apply { fontFeatureSettings = KerningModes.withoutKerning(paint.fontFeatureSettings) }
        // Per paragraph, as the renderer measures.
        var ps = 0
        while (ps < text.length) {
            val pe = text.indexOf('\n', ps).let { if (it < 0) text.length else it }
            val a = FloatArray(pe - ps)
            val b = FloatArray(pe - ps)
            paint.getTextWidths(text, ps, pe, a)
            unkerned.getTextWidths(text, ps, pe, b)
            a.copyInto(on, ps)
            b.copyInto(plainAdv, ps)
            ps = pe + 1
        }
        val px = TextRenderer.kernPx(spec, text, emptyList(), 0, null) ?: FloatArray(text.length)
        val scripts = ScriptRuns.scripts(text)
        for (i in 0 until text.length - 1) {
            if (text[i] == '\n' || text[i + 1] == '\n') continue
            val got = on[i] + px[i]
            if (ScriptRuns.keepsKerning(scripts, i)) {
                assertEquals("pair ${text[i]}${text[i + 1]} keeps the font's kerning", on[i], got, 1e-3f)
            } else {
                assertEquals("pair ${text[i]}${text[i + 1]} is set by its plain advance", plainAdv[i], got, 1e-3f)
            }
        }
        // "AV" is kerned by the font (narrower than its plain advance) and stays so.
        assertTrue("the font kerns AV: ${on[0]} < ${plainAdv[0]}", on[0] < plainAdv[0])
        assertEquals(0f, px[0], 0f)
        // Manual kerns add on top of the mode: a kern after テ adds its 1/1000 em to what Metrics gives.
        val te = text.indexOf('テ')
        val kerned = TextRenderer.kernPx(spec, text, listOf(TextKern(te, 200)), 0, null)!!
        assertEquals(px[te] + 0.2f * spec.sizePx, kerned[te], 1e-3f)
        // Auto adds nothing to the same text.
        assertNull(TextRenderer.kernPx(spec.copy(kerningMode = null), text, emptyList(), 0, null))
    }

    @Test
    fun scriptRunsKeepLatinGreekAndCyrillicTogether() {
        val text = "(AV) テキ。ВА ΑΥ 1.1"
        val s = ScriptRuns.scripts(text)
        assertEquals("a leading bracket joins the first letter's run", UnicodeScript.LATIN.ordinal, s[0])
        assertEquals("the closing bracket joins the run before it", UnicodeScript.LATIN.ordinal, s[3])
        assertTrue(ScriptRuns.keepsKerning(s, 1))
        assertFalse("Latin space then Katakana", ScriptRuns.keepsKerning(s, 4))
        assertFalse("Katakana pairs", ScriptRuns.keepsKerning(s, text.indexOf('テ')))
        assertEquals("。 joins the Katakana run", UnicodeScript.KATAKANA.ordinal, s[text.indexOf('。')])
        assertTrue("Cyrillic pairs", ScriptRuns.keepsKerning(s, text.indexOf('В')))
        assertTrue("Greek pairs", ScriptRuns.keepsKerning(s, text.indexOf('Α')))
        assertFalse("Cyrillic then Greek across a space", ScriptRuns.keepsKerning(s, text.indexOf('А') + 1))
        assertTrue("digits after Greek join it", ScriptRuns.keepsKerning(s, text.indexOf("1.1")))
        assertEquals(UnicodeScript.LATIN.ordinal, ScriptRuns.scripts("1.1")[1])
        assertTrue(ScriptRuns.allRoman("Hello, World! 1.1"))
        assertTrue("a space between scripts is no pair of letters", ScriptRuns.allRoman("Hello, World! Вася Γειά"))
        assertFalse(ScriptRuns.allRoman("Hello テキ"))
        assertFalse("Latin touching Cyrillic", ScriptRuns.allRoman("AВ"))
        assertTrue("a line break between scripts is no pair", ScriptRuns.allRoman("Hello\nВася"))
    }

    @Test
    fun verticalTextKeepsTheFontsPairsAndTextOnAPathTakesItsMode() {
        val metrics = TextSpec(sizePx = 40f, kerningMode = TextKerningMode.METRICS_LATIN)
        assertEquals(TextKerningMode.METRICS_LATIN, KerningModes.modeOf(metrics))
        assertNull(KerningModes.modeOf(metrics.copy(vertical = true)))
        assertEquals(TextKerningMode.METRICS_LATIN, KerningModes.modeOf(metrics.copy(vertical = true), onPath = true))
        assertNull("Off", KerningModes.modeOf(metrics.copy(fontKerning = false), onPath = true))
        assertNull("Auto", KerningModes.modeOf(TextSpec()))
        // Vertical text draws the same under every mode.
        val v = TextItem("縦書き テキスト ABC", TextSpec(sizePx = 30f, vertical = true), 250f, 200f)
        assertEquals(crc(v), crc(v.mode(TextKerningMode.METRICS_LATIN)))
        // Text on a path lays out (and draws) with its mode; Latin text there is unchanged.
        val path = TextPathSpec(type = TextPathType.LINE, x1 = 50f, y1 = 250f, x2 = 450f, y2 = 250f)
        val onPath = TextItem("AVATAR on a line", TextSpec(sizePx = 40f), path = path)
        assertEquals(crc(onPath), crc(onPath.mode(TextKerningMode.METRICS_LATIN)))
    }

    @Test
    fun aModeThatMovesALetterExportsAsOutlines() {
        val mixed = TextItem("AVATAR テキスト こんにちは", TextSpec(sizePx = 60f, kerningMode = TextKerningMode.METRICS_LATIN), 250f, 200f)
        val moves = TextRenderer.modeMovesLetters(mixed)
        assertEquals(moves, KerningModes.moves(TextRenderer.kernPx(mixed.spec, mixed.text, emptyList(), 0, null)))
        if (moves) assertNull("a moved letter: outlines", TextExport.lines(mixed)) else assertNotNull(TextExport.lines(mixed))
        assertNotNull("outlines are always there", TextExport.outlineParts(mixed))
        // Auto and Latin-only Metrics export as runs.
        assertNotNull(TextExport.lines(mixed.mode(null)))
        assertNotNull(TextExport.lines(TextItem("AVATAR", mixed.spec, 250f, 200f)))
    }

    @Test
    fun offIsTheFontKerningSwitch() {
        val auto = TextSpec()
        assertEquals("Auto at first", 1, pairKerningIndex(auto))
        val metrics = pairKerningChosen(auto, 3)
        assertEquals(TextKerningMode.METRICS_LATIN, metrics.kerningMode)
        assertTrue(metrics.fontKerning)
        assertEquals(3, pairKerningIndex(metrics))
        // Off turns the switch off and keeps the mode...
        val off = pairKerningChosen(metrics, 0)
        assertFalse(off.fontKerning)
        assertEquals(TextKerningMode.METRICS_LATIN, off.kerningMode)
        assertEquals(0, pairKerningIndex(off))
        // ...which the switch brings back.
        assertEquals(3, pairKerningIndex(off.copy(fontKerning = true)))
        val optical = pairKerningChosen(off, 2)
        assertTrue(optical.fontKerning)
        assertEquals(TextKerningMode.OPTICAL, optical.kerningMode)
        assertEquals(2, pairKerningIndex(optical))
        assertEquals(null, pairKerningChosen(optical, 1).kerningMode)
        // The notes, and the tags.
        assertNull(pairKerningNote(0, vertical = false))
        assertEquals(KernLabels19.AUTO_NOTE, pairKerningNote(1, vertical = false))
        assertEquals(KernLabels19.OPTICAL_NOTE, pairKerningNote(2, vertical = false))
        assertEquals(KernLabels19.METRICS_NOTE, pairKerningNote(3, vertical = false))
        assertEquals(KerningLabels.VERTICAL_REFUSAL, pairKerningNote(3, vertical = true))
        assertEquals(KernLabels19.AUTO_NOTE, pairKerningNote(1, vertical = true))
        assertEquals(listOf("OFF", "AUTO", "OPTICAL", "METRICS_LATIN").map { V19Tags.KERN_PREFIX + it }, PAIR_KERNING_TAGS)
        assertEquals(TextKerningMode.METRICS_LATIN.name, PAIR_KERNING_TAGS[3].removePrefix(V19Tags.KERN_PREFIX))
    }
}

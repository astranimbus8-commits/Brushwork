package com.brushwork.paint.qa16

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Inflater

/**
 * v1.6 final QA, I8 (found by `V15PlainProjectCompatTest`): v1.6 added `VPath.spline` (null for
 * every path that isn't a Path-tool curve). The payload and vector codecs write defaults, so v1.6
 * wrote `"spline":null` into every plain path: an SVG / PDF export of an untouched v1.5 document
 * (its embedded Brushwork data) and every re-saved `.vec` file differed from v1.5's bytes. A path
 * without a spline must encode exactly as in v1.5 (no `spline` key); a Path-tool curve keeps it.
 */
class SplineAbsentEncodingTest {

    private val plain = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(10f, 20f), VAnchor(60f, 80f, outX = 5f, outY = 0f), VAnchor(120f, 30f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 3f),
    )
    private val spline = VSpline(listOf(VSplinePoint(10f, 20f), VSplinePoint(60f, 80f, weight = 2f), VSplinePoint(120f, 30f)), order = 3)
    private val withSpline = plain.copy(id = 2, spline = spline)

    private fun content(vararg paths: VPath): VectorContent = VectorContent.EMPTY.plus(paths.toList()).first

    private fun inflate(b: ByteArray): String {
        val inf = Inflater()
        inf.setInput(b)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toString(Charsets.UTF_8.name())
    }

    private fun payload(c: VectorContent) = BrushworkPayload(
        width = 200, height = 100,
        layers = listOf(PayloadLayer(1, LayerProps("Vector 1", 1f, LayerBlendMode.NORMAL, true, false, false, false, true), kind = PayloadKind.VECTOR, vector = c)),
    )

    @Test
    fun aPlainPathWritesNoSplineKey() {
        val vec = inflate(VectorCodec.encode(content(plain)))
        assertFalse(".vec of a plain path: $vec", vec.contains("spline"))
        val json = String(Payload.toJson(payload(content(plain))), Charsets.UTF_8)
        assertFalse("payload of a plain path: $json", json.contains("spline"))
    }

    @Test
    fun aPathToolCurveKeepsItsSplineThroughBothCodecs() {
        val c = content(plain, withSpline)
        val vec = inflate(VectorCodec.encode(c))
        assertEquals("one spline key in .vec", 1, Regex("\"spline\"").findAll(vec).count())
        val back = VectorCodec.decode(VectorCodec.encode(c))
        assertEquals(listOf(null, spline), back.objects.map { (it as VPath).spline })
        val p = Payload.fromJson(Payload.toJson(payload(c)))
        val objects = p.layers.single().vector!!.objects
        assertEquals(listOf(null, spline), objects.map { (it as VPath).spline })
        assertTrue(String(Payload.toJson(payload(c)), Charsets.UTF_8).contains("\"spline\":{"))
    }
}

package com.brushwork.paint.v110.a1

import com.brushwork.paint.compat18.VectorCodecV18
import com.brushwork.paint.v110.a1.A1Kit.kinds
import com.brushwork.paint.v110.a1.A1Kit.mul
import com.brushwork.paint.v110.a1.A1Kit.translate
import com.brushwork.paint.v110.a1.A1Kit.turnAbout
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (b)): no field and no codec change: a moved side is another matrix in the stored map
 * list, a deleted side is fewer maps. An older app reads the v1.10 file as it is: the frozen
 * decoder keeps every map bit for bit, and what it writes back decodes and renders exactly as
 * v1.10 drew it, for every map kind, on a path and a shape.
 *
 * The frozen `compat19` codec is not on `main` at gate-v110-0, so the newest frozen decoder, the
 * v1.8 one (`compat18.VectorCodecV18`), stands in; copy maps are stored the same way since v1.8.
 */
@RunWith(RobolectricTestRunner::class)
class OneSideCompat19Test {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun aMovedOrDeletedSideReadsAndRendersTheSameInTheFrozenCodec() {
        for ((name, base) in listOf("path" to kit.box(60f, 100f, 120f, 140f), "shape" to kit.rectangle(90f, 120f, 60f, 40f)))
            for ((kind, maps) in kinds()) {
                val what = "$name, $kind"
                val o = withMaps(base, maps)
                val k = maps.lastIndex
                val box = CopyPick.boundsOf(o, k)
                val moved = CopyEdit.offset(o.withId(1), k, mul(translate(13f, -7f), turnAbout(box.centerX(), box.centerY(), 30.0)))!!
                val flipped = CopyEdit.offset(o.withId(2), 0, floatArrayOf(-1f, 0f, 2f * box.centerX(), 0f, 1f, 0f, 0f, 0f, 1f))!!
                val deleted = CopyEdit.dropCopy(o.withId(3), 0)!!
                val content = VectorContent(objects = listOf(moved, flipped, deleted))
                val bytes = VectorCodec.encode(content)
                val app = VectorCodec.decode(bytes)

                val frozen = VectorCodecV18.decode(bytes)
                val back = VectorCodec.decode(VectorCodecV18.encode(frozen))
                assertEquals("$what: every object", app.objects.size, back.objects.size)
                for ((a, b) in app.objects.zip(back.objects)) {
                    val am = ObjectCopies.of(a).maps
                    val bm = ObjectCopies.of(b).maps
                    assertEquals("$what: object ${a.id}, maps", am.size, bm.size)
                    for (j in am.indices) assertArrayEquals("$what: object ${a.id}, map $j bit for bit", am[j], bm[j], 0f)
                }
                val drawn = kit.render(app)
                val again = kit.render(back)
                var differ = 0
                for (i in drawn.indices) if (drawn[i] != again[i]) differ++
                assertTrue("$what: rendered the same ($differ pixels differ)", differ == 0)
            }
    }
}

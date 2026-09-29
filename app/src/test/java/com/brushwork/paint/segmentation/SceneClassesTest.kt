package com.brushwork.paint.segmentation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassesTest {

    @Test
    fun classSetsPerTarget() {
        assertArrayEquals(intArrayOf(3), SceneClasses.classesFor(SmartTarget.SKY))
        assertArrayEquals(intArrayOf(5, 10, 18, 30), SceneClasses.classesFor(SmartTarget.NATURE))
        assertArrayEquals(intArrayOf(2, 26), SceneClasses.classesFor(SmartTarget.BUILDINGS))
        assertArrayEquals(intArrayOf(9, 15), SceneClasses.attachedClassesFor(SmartTarget.BUILDINGS))
        assertArrayEquals(intArrayOf(13), SceneClasses.classesFor(SmartTarget.PEOPLE))
        assertArrayEquals(intArrayOf(22, 27), SceneClasses.classesFor(SmartTarget.WATER))
        assertFalse(SceneClasses.isSceneTarget(SmartTarget.SUBJECT))
        assertFalse(SceneClasses.isSceneTarget(SmartTarget.BACKGROUND))
        assertEquals(SceneClasses.COUNT, SceneClasses.LABELS.size)
        assertEquals("sky", SceneClasses.LABELS[SceneClasses.SKY])
        assertEquals("person", SceneClasses.LABELS[SceneClasses.PERSON])
        assertEquals("sea", SceneClasses.LABELS[SceneClasses.SEA])
        assertEquals("field", SceneClasses.LABELS[SceneClasses.FIELD])
    }

    private fun map(vararg rows: String): Pair<ByteArray, Int> {
        // One char per pixel: S sky, T tree, G grass, P person, B building, H house, W window,
        // D door, w wall, A water, E sea, . other
        val code = mapOf('S' to 3, 'T' to 5, 'G' to 10, 'P' to 13, 'B' to 2, 'H' to 26, 'W' to 9, 'D' to 15, 'w' to 1, 'A' to 22, 'E' to 27, '.' to 0)
        val w = rows[0].length
        val out = ByteArray(w * rows.size)
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> out[y * w + x] = code.getValue(c).toByte() } }
        return out to w
    }

    private fun maskString(m: FloatArray, w: Int): List<String> =
        m.toList().chunked(w).map { row -> row.joinToString("") { if (it == 1f) "#" else "." } }

    @Test
    fun targetMaskSelectsClassSets() {
        val (m, w) = map(
            "SSSSSSSS",
            "TTGG.PAE",
            "BBHH.PAE",
        )
        val h = 3
        assertEquals(listOf("########", "........", "........"), maskString(SceneClasses.targetMask(m, w, h, SmartTarget.SKY), w))
        assertEquals(listOf("........", "####....", "........"), maskString(SceneClasses.targetMask(m, w, h, SmartTarget.NATURE), w))
        assertEquals(listOf("........", "........", "####...."), maskString(SceneClasses.targetMask(m, w, h, SmartTarget.BUILDINGS), w))
        assertEquals(listOf("........", ".....#..", ".....#.."), maskString(SceneClasses.targetMask(m, w, h, SmartTarget.PEOPLE), w))
        assertEquals(listOf("........", "......##", "......##"), maskString(SceneClasses.targetMask(m, w, h, SmartTarget.WATER), w))
        assertTrue(SceneClasses.targetMask(m, w, h, SmartTarget.SUBJECT).all { it == 0f })
    }

    @Test
    fun windowsAndDoorsCountOnlyOnFacades() {
        val (m, w) = map(
            "SSSSSSSSSSSS",
            "BBBWWB..wwww",
            "BBBWWB..wWWw",
            "BBBBBBD.wwww",
            "......D.wDww",
        )
        val out = maskString(SceneClasses.targetMask(m, w, 5, SmartTarget.BUILDINGS), w)
        assertEquals(
            listOf(
                "............",
                "######......", // facade window included
                "######......", // indoor window (surrounded by wall) excluded
                "#######.....", // door touching the building included with its whole component
                "......#.....", // indoor door excluded
            ),
            out,
        )
    }

    @Test
    fun outOfRangeIdsAreIgnored() {
        val m = byteArrayOf(3, 40, -1, 3)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), SceneClasses.targetMask(m, 2, 2, SmartTarget.SKY), 0f)
    }
}

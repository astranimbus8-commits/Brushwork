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
}

package com.brushwork.paint.snap

import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.IncrementSettings
import com.brushwork.paint.smoke.Smoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** v1.6 foundation (§4.9): the increments service — persistence, and off = identity (I8). */
@RunWith(RobolectricTestRunner::class)
class IncrementsServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun freshSettings(): AppSettings = AppSettings(app).also { it.prefs.edit().clear().commit() }

    @Test
    fun offByDefaultAndEveryHelperIsTheIdentity() {
        val inc = Increments(freshSettings())
        assertFalse(inc.enabled)
        assertEquals(IncrementSettings(), inc.state)
        for (k in IncrementKind.entries) assertNull(inc.step(k))
        assertNull(inc.customStep("Exposure|EV"))
        val d = Vec2(27.3f, -4.1f)
        assertEquals(d, inc.lengthDelta(d))
        assertEquals(13.7f, inc.lengthAbs(13.7f), 0f)
        assertEquals(7.3f, inc.size(7.3f), 0f)
        assertEquals(1.234f, inc.factor(1.234f), 0f)
        assertEquals(113f, inc.scalePercent(113f), 0f)
        assertEquals(37f, inc.angle(37f), 0f)
        assertEquals(0.333f, inc.percent01(0.333f), 0f)
        assertNull(inc.readout)
    }

    @Test
    fun stepsApplyWhileOn() {
        val inc = Increments(freshSettings())
        inc.update { it.copy(enabled = true) }
        assertEquals(10f, inc.step(IncrementKind.LENGTH))
        val moved = inc.lengthDelta(Vec2(27f, -4f))
        assertEquals(30f, moved.x, 0f)
        assertEquals(0f, moved.y, 0f)
        assertEquals(20f, inc.lengthAbs(17f), 0f)
        assertEquals(7f, inc.size(7.3f), 0f)
        assertEquals(1.1f, inc.factor(1.13f), 1e-5f)
        assertEquals(120f, inc.scalePercent(118f), 1e-4f)
        assertEquals(45f, inc.angle(40f), 0f)
        assertEquals(0.35f, inc.percent01(0.33f), 1e-6f)
        assertEquals(1f, inc.percent01(0.99f), 0f)
        // Custom steps: only while on, only when set.
        assertNull(inc.customStep("Exposure|EV"))
        inc.update { it.withCustom("Exposure|EV", 0.25f) }
        assertEquals(0.25f, inc.customStep("Exposure|EV"))
        inc.update { it.copy(enabled = false) }
        assertNull(inc.customStep("Exposure|EV"))
        assertEquals(17f, inc.lengthAbs(17f), 0f)
    }

    @Test
    fun persistsAcrossServicesAndSanitizes() {
        val settings = freshSettings()
        val a = Increments(settings)
        a.update { it.copy(enabled = true).with(IncrementKind.ANGLE, 22.5f).with(IncrementKind.LENGTH, -3f).withCustom("Count|", 2f) }
        assertEquals("a negative step takes the default", 10f, a.state.lengthPx, 0f)
        val b = Increments(AppSettings(app))
        assertTrue(b.enabled)
        assertEquals(22.5f, b.state.angleDeg, 0f)
        assertEquals(mapOf("Count|" to 2f), b.state.custom)
        // An unchanged update writes nothing and keeps the instance.
        val before = b.state
        b.update { it }
        assertSame(before, b.state)
        // Damaged stored JSON reads as the defaults.
        settings.prefs.edit().putString("increments", "{not json").commit()
        assertEquals(IncrementSettings(), AppSettings(app).increments)
    }

    @Test
    fun sanitizedKeepsUsableValues() {
        val s = IncrementSettings(
            enabled = true, lengthPx = Float.NaN, sizePx = 0f, scalePercent = 5000f, angleDeg = 15f, percent = Float.POSITIVE_INFINITY,
            custom = (0 until 80).associate { "k$it" to it.toFloat() } + mapOf("" to 1f, "nan" to Float.NaN),
        ).sanitized()
        assertEquals(10f, s.lengthPx, 0f)
        assertEquals(1f, s.sizePx, 0f)
        assertEquals(10f, s.scalePercent, 0f)
        assertEquals(15f, s.angleDeg, 0f)
        assertEquals(5f, s.percent, 0f)
        assertEquals(IncrementSettings.MAX_CUSTOM, s.custom.size)
        assertTrue(s.custom.values.all { it > 0f && it.isFinite() })
        assertFalse("" in s.custom)
        val ok = IncrementSettings(enabled = true, angleDeg = 5f)
        assertSame(ok, ok.sanitized())
    }

    @Test
    fun eachControllerHasItsOwnService() {
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val c1 = Smoke.controller(activity)
        val c2 = Smoke.controller(activity)
        assertTrue(c1.increments !== c2.increments)
        assertFalse(c1.increments.enabled)
    }
}

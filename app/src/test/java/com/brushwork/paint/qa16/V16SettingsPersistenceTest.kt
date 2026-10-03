package com.brushwork.paint.qa16

import android.content.Context
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.IncrementSettings
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 final QA, the app-wide v1.6 settings (increments, transparency display, fast adjustment
 * preview, curve handle size): the preferences v1.5 left give v1.5's behaviour (I8; the fast
 * preview is on by design, v1.6 §3.1) and reading them writes nothing; set values survive a restart
 * (a new [AppSettings] and controller over the same preferences); v1.5's own keys are kept; damaged
 * or future values fall back to usable ones.
 */
@RunWith(RobolectricTestRunner::class)
class V16SettingsPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun prefs() = app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE)

    /** A controller as the editor makes one after a restart: over the stored preferences (not cleared). */
    private fun restarted(): EditorController = EditorController(app, Smoke.document(), Smoke.newScope(), AppSettings(app))

    @Before
    fun clear() {
        prefs().edit().clear().commit()
    }

    @Test
    fun v15PreferencesGiveV15Behaviour() {
        // What v1.5.0 left: only its own keys.
        AppSettings(app).apply {
            twoFingerUndo = false
            autosaveSeconds = 30
        }
        val v15 = prefs().all.toMap()
        assertEquals(setOf("twoFingerUndo", "autosaveSeconds"), v15.keys)
        val c = restarted()
        val s = c.settings
        assertEquals("increments off with their default steps", IncrementSettings(), s.increments)
        assertFalse(c.increments.enabled)
        for (k in IncrementKind.entries) assertNull("no $k step while off", c.increments.step(k))
        assertEquals(TransparencyDisplay.LIGHT_CHECKER, s.transparencyDisplay)
        assertTrue("fast adjustment preview on by default (v1.6 §3.1; Safe compositing stays the kill switch)", s.fastAdjustPreview)
        assertTrue(c.liveAdjust.fastPreview)
        assertEquals(1f, s.curveHandleScale)
        assertFalse(s.twoFingerUndo)
        assertEquals(30, s.autosaveSeconds)
        assertEquals("reading the v1.6 defaults writes nothing", v15, prefs().all.toMap())
        // Setting the v1.6 values keeps v1.5's.
        c.increments.update { it.copy(enabled = true) }
        s.transparencyDisplay = TransparencyDisplay.WHITE
        s.fastAdjustPreview = false
        for ((k, v) in v15) assertEquals("v1.5 key $k kept", v, prefs().all[k])
    }

    @Test
    fun v16SettingsSurviveARestart() {
        val c1 = restarted()
        c1.increments.update { it.copy(enabled = true, angleDeg = 30f, lengthPx = 5f).withCustom("Exposure|EV", 0.25f) }
        c1.settings.transparencyDisplay = TransparencyDisplay.DARK_CHECKER
        c1.settings.fastAdjustPreview = false
        c1.settings.curveHandleScale = 1.5f
        val c2 = restarted()
        assertEquals(c1.increments.state, c2.increments.state)
        assertEquals(30f, c2.increments.step(IncrementKind.ANGLE))
        assertEquals(5f, c2.increments.step(IncrementKind.LENGTH))
        assertEquals(0.25f, c2.increments.customStep("Exposure|EV"))
        assertEquals(TransparencyDisplay.DARK_CHECKER, c2.settings.transparencyDisplay)
        assertFalse(c2.settings.fastAdjustPreview)
        assertFalse("a new editor starts with the stored switch", c2.liveAdjust.fastPreview)
        assertEquals(1.5f, c2.settings.curveHandleScale)
        // Off again: the steps are kept for next time, none applies.
        c2.increments.update { it.copy(enabled = false) }
        val c3 = restarted()
        assertFalse(c3.increments.enabled)
        assertNull(c3.increments.step(IncrementKind.ANGLE))
        assertEquals(30f, c3.increments.state.angleDeg)
        for (t in TransparencyDisplay.entries) {
            c3.settings.transparencyDisplay = t
            assertEquals(t, AppSettings(app).transparencyDisplay)
        }
    }

    @Test
    fun damagedOrFutureValuesFallBack() {
        prefs().edit()
            .putString("transparencyDisplay", "SEPIA")
            .putString("increments", "{not json")
            .putFloat("curveHandleScale", Float.NaN)
            .commit()
        val s = AppSettings(app)
        assertEquals(TransparencyDisplay.LIGHT_CHECKER, s.transparencyDisplay)
        assertEquals(IncrementSettings(), s.increments)
        assertEquals(1f, s.curveHandleScale)
        // A later version's extra key is ignored; an unusable step takes its default.
        prefs().edit().putString("increments", """{"enabled":true,"angleDeg":-3,"lengthPx":4,"future":{"x":1}}""").commit()
        val inc = AppSettings(app).increments
        assertTrue(inc.enabled)
        assertEquals(IncrementSettings().angleDeg, inc.angleDeg)
        assertEquals(4f, inc.lengthPx)
        assertEquals(inc, restarted().increments.state)
    }
}

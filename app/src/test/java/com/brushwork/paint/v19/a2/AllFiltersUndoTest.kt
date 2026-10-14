package com.brushwork.paint.v19.a2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.fxsmoke.Fx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

/**
 * v1.9 (R8, A2 accept): every registered filter's every parameter takes part in the session's
 * undo. Per filter: each parameter is changed once and finished (what its control does at the end
 * of a change) and is one step named after it; Undo through the controller (the hotbar's, the
 * two-finger tap's) walks them all back, and the values are the session's defaults again
 * ([FilterSession.sessionDefault]: a drawing-colour parameter's is the drawing colour); Redo
 * brings every change back; one more Undo at the start cancels the panel, as in v1.8.
 *
 * The changed value comes from an exhaustive `when` over [FilterParam] (a new subclass does not
 * compile until it is given one), and the test fails when a subclass a registered filter uses is
 * not exercised (its change equal to the default, or not one step). No UI; previews are not run
 * (the debounce is never reached).
 */
@RunWith(RobolectricTestRunner::class)
class AllFiltersUndoTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() = scope.cancel()

    /** A valid value of [p] other than [current] (the session's default at the start). */
    private fun changed(p: FilterParam, current: Any): Any = when (p) {
        is FilterParam.Slider -> Fx.nonDefault(p)!!.let { v -> if (FilterSessionMath.sameValue(v, current)) (if (p.max != current) p.max else p.min) else v }
        is FilterParam.Toggle -> !(current as Boolean)
        is FilterParam.Choice -> ((current as Number).toInt() + 1) % p.options.size
        is FilterParam.Color -> if (current != Fx.ALT_COLOR) Fx.ALT_COLOR else Fx.ALT_COLOR_2
        is FilterParam.Point -> floatArrayOf(if (p.defaultX < 0.5f) 0.8f else 0.2f, if (p.defaultY < 0.5f) 0.75f else 0.25f)
        is FilterParam.Curve -> Fx.nonDefault(p)!!
        is FilterParam.Gradient -> Fx.nonDefault(p)!!
        is FilterParam.Text -> "$current 42"
        is FilterParam.Seed -> (current as Number).toInt() + 17
        is FilterParam.Fringes -> Fx.nonDefault(p)!!
    }

    @Test
    fun everyParameterOfEveryFilterIsUndoneToItsDefault() {
        val all = FilterRegistry.all
        assertTrue("the registry holds the filter set: ${all.size}", all.size >= 80)
        val used = all.flatMap { f -> f.params.map { it::class } }.toSet()
        val exercised = mutableSetOf<KClass<out FilterParam>>()
        val problems = mutableListOf<String>()
        for (f in all) {
            try {
                walk(f, exercised)
            } catch (e: Throwable) {
                problems += "${f.id}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} filter(s):\n" + problems.joinToString("\n"))
        val missing = used - exercised
        assertTrue("FilterParam subclasses a filter uses but no change exercised: ${missing.map { it.simpleName }}", missing.isEmpty())
        // The registered filters use every kind the panel has (a kind no filter uses would be unexercised here).
        for (k in listOf(FilterParam.Slider::class, FilterParam.Choice::class, FilterParam.Point::class, FilterParam.Fringes::class)) {
            assertTrue("${k.simpleName} is used", k in used)
        }
    }

    private fun walk(f: Filter, exercised: MutableSet<KClass<out FilterParam>>) {
        val d = Fx.newDoc(context, scope, 24, 16, withLineArt = false)
        val c = d.controller
        c.startFilter(f)
        val s = c.filterSession ?: error("the session did not start: ${c.message}")
        s.debounceMs = 600_000L
        val defaults = f.params.associate { it.key to s.sessionDefault(it) }
        for (p in f.params) assertTrue("${p.key} starts at its default", FilterSessionMath.sameValue(s.values.raw(p.key) ?: defaults[p.key], defaults[p.key]))

        // Each parameter changed and finished: one step, named after it.
        for ((i, p) in f.params.withIndex()) {
            val v = changed(p, defaults.getValue(p.key))
            assertFalse("${p.key}: the change differs from the default", FilterSessionMath.sameValue(v, defaults[p.key]))
            s.update(p.key, v)
            s.commitStep(p.key)
            assertEquals("${p.key}: one step", i + 1, s.history.depth)
            assertEquals("${p.key}: the step's name", p.label, s.undoLabel)
            exercised += p::class
        }
        val changedValues = s.values.copy()

        // Undo walks every step back to the defaults; the panel stays open.
        repeat(f.params.size) {
            assertTrue(s.canUndo)
            c.undo()
            assertSame("Undo keeps the panel open while it has steps", s, c.filterSession)
        }
        assertFalse("at the start", s.canUndo)
        for (p in f.params) {
            val now = s.values.raw(p.key) ?: defaults[p.key]
            assertTrue("${p.key}: undone to its default (${describe(now)} vs ${describe(defaults[p.key])})", FilterSessionMath.sameValue(now, defaults[p.key]))
        }

        // Redo brings every change back.
        repeat(f.params.size) { c.redo() }
        assertTrue("redone to the changed values", FilterSessionMath.sameValues(s.values, changedValues))

        // Back to the start, then one more Undo cancels the panel (v1.8).
        repeat(f.params.size) { c.undo() }
        c.undo()
        assertNull("the last Undo cancels the panel", c.filterSession)
        assertTrue(s.isClosed)
    }

    private fun describe(v: Any?): String = when (v) {
        is FloatArray -> v.toList().toString()
        is IntArray -> v.toList().toString()
        else -> v.toString()
    }
}

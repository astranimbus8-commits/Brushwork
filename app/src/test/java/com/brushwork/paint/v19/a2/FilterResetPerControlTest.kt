package com.brushwork.paint.v19.a2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.filters.paramHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R8, A2 accept): each control's reset icon. The panel shows it exactly while its parameter
 * differs from the session's default ([com.brushwork.paint.ui.filters.ParamHost.isDefault], which
 * the controls' `ResetSlot` and the slider's `onReset` read); a reset puts that parameter alone
 * back and is one step "Reset <label>", which Undo takes back. For every parameter of every
 * registered filter, through the panel's own host. The icon's place in the composed row is
 * `Qa19FilterUndoUiTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class FilterResetPerControlTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() = scope.cancel()

    /** A valid value of [p] other than its default [d]. */
    private fun other(p: FilterParam, d: Any): Any = when (p) {
        is FilterParam.Point -> floatArrayOf(if (p.defaultX < 0.5f) 0.8f else 0.2f, 0.3f)
        is FilterParam.Text -> "$d!"
        is FilterParam.Seed -> (d as Number).toInt() + 5
        is FilterParam.Color -> if (d != Fx.ALT_COLOR) Fx.ALT_COLOR else Fx.ALT_COLOR_2
        is FilterParam.Choice -> ((d as Number).toInt() + 1) % p.options.size
        is FilterParam.Toggle -> !(d as Boolean)
        is FilterParam.Slider, is FilterParam.Curve, is FilterParam.Gradient, is FilterParam.Fringes -> Fx.nonDefault(p)!!
    }

    @Test
    fun theIconShowsOnlyOffTheDefaultAndItsResetIsOneStep() {
        val problems = mutableListOf<String>()
        var checked = 0
        for (f in FilterRegistry.all) {
            try {
                checked += check(f)
            } catch (e: Throwable) {
                problems += "${f.id}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} filter(s):\n" + problems.joinToString("\n"))
        assertTrue("every filter's parameters checked: $checked", checked > 200)
    }

    private fun check(f: Filter): Int {
        val d = Fx.newDoc(context, scope, 24, 16, withLineArt = false)
        val c = d.controller
        c.startFilter(f)
        val s = c.filterSession ?: error("the session did not start: ${c.message}")
        s.debounceMs = 600_000L
        val host = s.paramHost()
        for (p in f.params) assertTrue("${p.key}: no icon at the start", host.isDefault(p))
        for (p in f.params) {
            val def = s.sessionDefault(p)
            val depth = s.history.depth
            s.update(p.key, other(p, def))
            s.commitStep(p.key)
            assertFalse("${p.key}: the icon shows off the default", host.isDefault(p))
            for (q in f.params) if (q !== p) assertTrue("${q.key}: still no icon while ${p.key} changes", host.isDefault(q))

            host.resetParam(p.key)
            assertTrue("${p.key}: the reset hides the icon", host.isDefault(p))
            assertTrue("${p.key}: back at its default", FilterSessionMath.sameValue(s.values.raw(p.key), def))
            assertEquals("${p.key}: the change and the reset, a step each", depth + 2, s.history.depth)
            assertEquals(String.format(FilterLabels19.RESET_ONE, p.label), s.undoLabel)

            c.undo()
            assertFalse("${p.key}: Undo brings the change and the icon back", host.isDefault(p))
            c.redo()
            assertTrue(host.isDefault(p))
        }
        // A value moved back to the default by hand hides the icon too.
        f.params.firstOrNull { it is FilterParam.Slider }?.let { p ->
            val def = s.sessionDefault(p)
            s.update(p.key, other(p, def)); assertFalse(host.isDefault(p))
            s.update(p.key, def); assertTrue("${p.key}: at the default again, no icon", host.isDefault(p))
        }
        s.cancel()
        return f.params.size
    }
}

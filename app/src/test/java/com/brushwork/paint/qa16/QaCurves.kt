package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The v1.6 final QA's curve / increment flows (Path tool, Bézier handle scaling, increments
 * everywhere) share these: the ibisPaint tool menu as a finger reaches it, the options strip's
 * sliders, touches given in document px, the increments switched on with custom steps through
 * More › Increments…, and PNG renders of what the user sees (kept out of the repository).
 */
internal object QaCurves {

    /** Where the QA renders go (outside the repository; absent on other machines: no render). */
    private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v16-qa-shots")

    // ------------------------------------------------------------------ the tool menu

    /** Picks [label] in the ibisPaint tool menu (bottom bar slot 2), scrolling it as a finger would. */
    fun tool(s: ChromeScreen, label: String) {
        click("Tools (current:")
        assertNotNull("the tool menu is open", s.tagged(ChromeTags.TOOL_MENU))
        scrollMenuTo(s, label)
        click(label, exact = true)
        assertNull("a pick closes the menu", s.tagged(ChromeTags.TOOL_MENU))
        settle()
    }

    private fun cell(label: String): SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    private fun scrollMenuTo(s: ChromeScreen, label: String) {
        fun inside() = cell(label)?.let { n -> n.boundsInWindow.height >= n.size.height - 1f && n.boundsInWindow.width >= n.size.width - 1f } == true
        if (inside()) return
        val node = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("\"$label\" is not in the tool menu")
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
        val scroll = requireNotNull(n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
        repeat(30) {
            if (inside()) return
            scroll.invoke(0f, 60f * s.density)
            settle(2)
        }
        throw AssertionError("\"$label\" never scrolled into the tool menu")
    }

    // ------------------------------------------------------------------ strip controls

    /** The (last placed) node described [description] (content description containing it). */
    fun node(description: String): SemanticsNode = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(description) } == true
    }.node

    /** Scrolls the (horizontally scrolling) options strip until [description] shows whole. */
    fun scrollStripTo(s: ChromeScreen, description: String) {
        fun whole() = node(description).let { it.boundsInWindow.width >= it.size.width - 1f && it.boundsInWindow.height >= it.size.height - 1f }
        if (whole()) return
        var p: SemanticsNode? = node(description)
        while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
        val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$description\" is clipped and does not scroll" }
        // Towards it: from the strip's left half rightwards, else back.
        val dir = if (node(description).boundsInWindow.left > p!!.boundsInWindow.center.x) 1f else -1f
        repeat(40) {
            if (whole()) return
            scroll.invoke(dir * 60f * s.density, 0f)
            settle(4)
        }
        throw AssertionError("\"$description\" never scrolled into view")
    }

    /** The slider described [name] (its SetProgress action). */
    fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(name) } == true
    }

    /** Moves slider [name] to [v] (its own value space) as one drag would end there. */
    fun setSlider(name: String, v: Float) {
        requireNotNull(slider(name).node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    /** The current value of slider [name]. */
    fun sliderValue(name: String): Float = slider(name).node.config[SemanticsProperties.ProgressBarRangeInfo].current

    // ------------------------------------------------------------------ touches (document px)

    fun tap(s: ChromeScreen, x: Float, y: Float) {
        s.touch.idle(400)
        val (sx, sy) = s.screen(x, y)
        s.touch.tap(sx, sy)
        settle(4)
    }

    fun tap(s: ChromeScreen, p: Vec2) = tap(s, p.x, p.y)

    /**
     * One finger from document point [from] by [by] (document px, measured where the finger went
     * down), [held] checked while it is still down, then lifted.
     */
    fun drag(s: ChromeScreen, from: Vec2, by: Vec2, steps: Int = 10, held: () -> Unit = {}) {
        s.touch.idle(400)
        val a = s.screen(from.x, from.y)
        val b = s.screen(from.x + by.x, from.y + by.y)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (i in 1..steps) {
            s.touch.idle(16)
            s.touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + (b.first - a.first) * i / steps, a.second + (b.second - a.second) * i / steps))
        }
        s.touch.idle(16)
        settle(2)
        held()
        s.touch.send(MotionEvent.ACTION_UP, P(0, b.first, b.second))
        s.touch.idle(50)
        settle()
    }

    // ------------------------------------------------------------------ increments

    /**
     * More › Increments… (the sheet "Increment steps"): "Use increments" on and the given steps
     * typed (null leaves a kind's step as it is); Close.
     */
    fun incrementsOn(length: String? = "25", size: String? = "3", scale: String? = "25", angle: String? = "30", percent: String? = "10") {
        click("More options")
        click("Increments…", exact = true)
        SmokeUi.assertIncrementsPanelShown()
        if (!switchOn("Use increments")) click("Use increments", exact = true)
        assertTrue("\"Use increments\" is on", switchOn("Use increments"))
        length?.let { SmokeUi.typeAndDone("Length step", it) }
        size?.let { SmokeUi.typeAndDone("Size step", it) }
        scale?.let { SmokeUi.typeAndDone("Scale step", it) }
        angle?.let { SmokeUi.typeAndDone("Angle step", it) }
        percent?.let { SmokeUi.typeAndDone("Percent step", it) }
        click("Close", exact = true)
        settle()
    }

    /** More › Increments…: "Use increments" off; Close. */
    fun incrementsOff() {
        click("More options")
        click("Increments…", exact = true)
        SmokeUi.assertIncrementsPanelShown()
        if (switchOn("Use increments")) click("Use increments", exact = true)
        assertTrue("\"Use increments\" is off", !switchOn("Use increments"))
        click("Close", exact = true)
        settle()
    }

    /** Whether the switch row [label] is on (its toggleable state). */
    private fun switchOn(label: String): Boolean {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { return it == androidx.compose.ui.state.ToggleableState.On }
            n = n.parent
        }
        return false
    }

    /** The options strip's "Snap to objects" chip ("Snap" when compact) switched off. */
    fun snapOff(c: com.brushwork.paint.EditorController) {
        if (!c.snapping.enabled) return
        if (SmokeUi.has("Snap to objects", exact = true)) click("Snap to objects", exact = true) else click("Snap", exact = true)
        assertTrue("snapping is off", !c.snapping.enabled)
    }

    // ------------------------------------------------------------------ geometry

    /** Distance from [q] to the outline of a stadium [height] tall, 3 × as wide, centred on [c]. */
    fun stadiumDistance(q: Vec2, c: Vec2, height: Float): Float {
        val r = height / 2f
        val half = height
        val x = abs(q.x - c.x)
        val y = q.y - c.y
        return if (x <= half) abs(abs(y) - r) else abs(hypot(x - half, y) - r)
    }

    /** True when [v] is a whole multiple of [step] (within [eps]). */
    fun onStep(v: Float, step: Float, eps: Float = 1e-3f): Boolean = abs(v / step - Math.round(v / step)) * step < eps

    // ------------------------------------------------------------------ renders

    /**
     * Renders the editor window and the flattened document of [s] to `<name>-screen.png` and
     * `<name>-doc.png` in the QA shots folder; returns the paths written (none on other machines).
     */
    fun shot(s: ChromeScreen, name: String): List<String> {
        if (!SHOTS.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        runCatching {
            val v = s.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            out += write(bmp, "$name-screen.png")
        }
        runCatching {
            val d = s.c.doc
            val bmp = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(-1)
            val cv = Canvas(bmp)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            for (l in d.layers) if (l.visible) { p.alpha = (l.opacity * 255).toInt().coerceIn(0, 255); cv.drawBitmap(l.bitmap, 0f, 0f, p) }
            out += write(bmp, "$name-doc.png")
        }
        return out
    }

    private fun write(bmp: Bitmap, file: String): String {
        val f = File(SHOTS, file)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    /** Lets Robolectric's looper run [ms] and settles the UI. */
    fun idle(s: ChromeScreen, ms: Long = 300) {
        s.touch.idle(ms)
        Smoke.pump(50)
        settle()
    }
}

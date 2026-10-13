package com.brushwork.paint.qa19

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog

/**
 * v1.9 F0 (design 2.2, I27): the shared Robolectric helpers of the v1.9 UI tests. FROZEN after
 * F0 (5.4 `$FrozenTests`): areas build against it and never edit it, nor the kits it wraps
 * ([SmokeUi], [StripTestKit], [ChromeHarness]).
 *
 * A v1.9 UI test class holds ONE test method (the v1.6 habit, see [ChromeHarness]) that runs its
 * cases as sections, at each width ([Q392], [Q360]) in its own class and at each font scale of
 * I27 ([FONT_SCALES]) as a section:
 *
 * ```
 * @RunWith(RobolectricTestRunner::class)
 * @Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.thickness360sandbox"])
 * class Qa19ThicknessNarrowUiTest {
 *     @Test
 *     fun thickness() = Qa19Ui.uiTest { h ->
 *         for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
 *             val s = h.editor(fontScale = scale)
 *             ...
 *             Qa19Ui.assertNoTextOverflow("at font scale $scale")
 *         }
 *     }
 * }
 * ```
 *
 * Font scale: [withFontScale] gives one composable a [LocalDensity] with that `fontScale`
 * (linear). A popup, menu, dialog or sheet window takes its density from its own window, not from
 * the [LocalDensity] of the content that opened it, so [Qa19Harness.editor] and [Qa19Harness.show]
 * set the device's font scale instead (Robolectric's configuration, as Settings does on a phone:
 * every window, with Android 14's non-linear scaling of large sizes) before the activity is made,
 * and each [Qa19Harness.section] puts it back to 1.
 */
internal object Qa19Ui {
    /** The 392 dp phone of the v1.9 tests (the qualifiers of an `@Config`). */
    const val Q392 = "w392dp-h873dp-xxhdpi"

    /** The 360 dp phone of the v1.9 tests. */
    const val Q360 = "w360dp-h760dp-xxhdpi"

    /** The font scales every new v1.9 control is checked at (I27), besides 1.0. */
    val FONT_SCALES: List<Float> = listOf(1.3f, 2.0f)

    /**
     * Runs a v1.9 UI test: Robolectric's log off, the test recomposer ([SmokeUi.installTestRecomposer]),
     * a watchdog, then [block] with a fresh [Qa19Harness]; afterwards it throws the first failed
     * section (the others suppressed).
     */
    fun uiTest(block: (Qa19Harness) -> Unit) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = Qa19Harness()
        try {
            block(h)
        } finally {
            dog.interrupt()
            deviceFontScale(1f)
        }
        h.finish()
    }

    /** [content] laid out with a [LocalDensity] of the same density and [scale] as its font scale (I27). */
    @Composable
    fun withFontScale(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, scale), content = content)
    }

    /**
     * Sets the device's font scale (every window made from now on, popups and dialogs included).
     * [Qa19Harness] calls it; a test calls it only to show something without the harness.
     */
    fun deviceFontScale(scale: Float) {
        if (RuntimeEnvironment.getFontScale() != scale) RuntimeEnvironment.setFontScale(scale)
    }

    /** How [n]'s text is laid out now, or null when it is not a text. */
    fun textLayout(n: SemanticsNode): TextLayoutResult? {
        val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val out = mutableListOf<TextLayoutResult>()
        return if (get(out) == true) out.firstOrNull() else null
    }

    /**
     * The texts on screen (the windows shown since [SmokeUi.markBaseline], placed nodes only)
     * whose layout has a visual overflow: wider or taller than their box, or cut by `maxLines`
     * (clipped or ellipsized), as `"text" in <bounds> (why)`. [include] picks the nodes to check.
     *
     * v1.9 F1b: "too wide" is a line wider than the text's box, not `didOverflowWidth`. The
     * semantics action lays the text out again at the max width of its last constraints
     * (`TextStringSimpleNode`'s slow path), so `multiParagraph.width` is that max width and
     * `didOverflowWidth` was true for every text narrower than the room it was given (a short
     * label in a row). An ellipsized line counts as cut.
     */
    fun textOverflows(include: (RobolectricUi.Element) -> Boolean = { true }): List<String> {
        val windows = RobolectricUi.windowRoots()
        val out = mutableListOf<String>()
        for (e in RobolectricUi.elements()) {
            if (windows.indexOf(e.window) < SmokeUi.baseline || !e.node.layoutInfo.isPlaced || !include(e)) continue
            val r = textLayout(e.node) ?: continue
            val wide = (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 0.5f }
            val tall = r.didOverflowHeight && !r.multiParagraph.didExceedMaxLines
            val cut = r.multiParagraph.didExceedMaxLines || (0 until r.lineCount).any { r.isLineEllipsized(it) }
            if (!wide && !tall && !cut) continue
            val why = listOfNotNull(
                "too wide".takeIf { wide },
                "too tall".takeIf { tall },
                "past its max lines".takeIf { cut },
            ).joinToString(", ")
            out += "\"${r.layoutInput.text.text}\" in ${e.bounds} at font scale ${r.layoutInput.density.fontScale} ($why)"
        }
        return out
    }

    /**
     * I27: fails when a text on screen has `TextLayoutResult.hasVisualOverflow` (read through
     * `SemanticsActions.GetTextLayoutResult`), naming every such text. [where] goes into the
     * message; [except] skips texts by their content (a layer name that is ellipsized on purpose).
     */
    fun assertNoTextOverflow(where: String = "", except: (String) -> Boolean = { false }) {
        val bad = textOverflows { e ->
            val t = e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                ?: e.node.config.getOrNull(SemanticsProperties.EditableText)?.text
            t == null || !except(t)
        }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box${if (where.isEmpty()) "" else " $where"}:\n" + bad.joinToString("\n"))
    }

    // Thin wrappers over the frozen kits, so a v1.9 test needs one import.

    /** [SmokeUi.settle]. */
    fun settle(steps: Int = 12, stepMs: Long = 50) = SmokeUi.settle(steps, stepMs)

    /** [SmokeUi.find] (opens the strip's More row when the label is not on row 1). */
    fun find(label: String, exact: Boolean = false): RobolectricUi.Element? = SmokeUi.find(label, exact)

    /** [SmokeUi.has] (never opens the More row). */
    fun has(label: String, exact: Boolean = false): Boolean = SmokeUi.has(label, exact)

    /** [SmokeUi.click]. */
    fun click(label: String, exact: Boolean = false, settleAfter: Boolean = true) = SmokeUi.click(label, exact, settleAfter)

    /** [SmokeUi.shown]. */
    fun shown(): List<String> = SmokeUi.shown()

    /** [StripTestKit.tagged]. */
    fun tagged(tag: String): SemanticsNode? = StripTestKit.tagged(tag)

    /** [StripTestKit.openMoreRow]. */
    fun openMoreRow(): Boolean = StripTestKit.openMoreRow()

    /** [StripTestKit.closeMoreRow]. */
    fun closeMoreRow(): Boolean = StripTestKit.closeMoreRow()

    /** [StripTestKit.openObjectRow]. */
    fun openObjectRow(): Boolean = StripTestKit.openObjectRow()

    /** [StripTestKit.closeObjectRow]. */
    fun closeObjectRow(): Boolean = StripTestKit.closeObjectRow()
}

/**
 * [ChromeHarness] with font scales: sections, the editor and any composable in a fresh activity.
 * Made by [Qa19Ui.uiTest].
 */
internal class Qa19Harness {
    private val chrome = ChromeHarness()
    private val activities = mutableListOf<ActivityController<*>>()

    /** [ChromeHarness.section]; afterwards this harness's activities are closed and the font scale is 1 again. */
    fun section(name: String, block: () -> Unit) = chrome.section(name) {
        try {
            block()
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            Qa19Ui.deviceFontScale(1f)
        }
    }

    /**
     * The editor on screen ([ChromeHarness.editor]) at the device's [fontScale]. At 1 it is
     * [ChromeHarness.editor] itself.
     */
    fun editor(
        doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true),
        fontScale: Float = 1f,
        setup: (EditorController) -> Unit = {},
    ): ChromeScreen {
        Qa19Ui.deviceFontScale(fontScale)
        if (fontScale == 1f) return chrome.editor(doc, setup)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val c = Smoke.controller(activity, doc)
        setup(c)
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        SmokeUi.settle()
        c.tools
        SmokeUi.settle()
        return ChromeScreen(activity, c)
    }

    /** [content] (in [BrushworkTheme] when [theme]) in a fresh activity at the device's [fontScale]. */
    fun show(fontScale: Float = 1f, theme: Boolean = true, content: @Composable () -> Unit): ComponentActivity {
        Qa19Ui.deviceFontScale(fontScale)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        activity.setContent { if (theme) BrushworkTheme { content() } else content() }
        SmokeUi.settle()
        return activity
    }

    /** [ChromeHarness.finish]: throws the first failed section. */
    fun finish() = chrome.finish()
}

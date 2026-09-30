package com.brushwork.paint.smoke

import android.view.View
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.BwSheetPillKey
import com.brushwork.paint.ui.common.BwSheetTitleKey
import org.junit.Assert.assertTrue

/** Semantics lookups on top of [RobolectricUi] (click labels, texts, windows). */
internal object SmokeUi {

    fun settle(steps: Int = 12, stepMs: Long = 50) = RobolectricUi.settle(steps, stepMs)

    /**
     * Parks infinite animations instead of running them, like compose-ui-test does. Without it a
     * `Popup` (every DropdownMenu) hangs Robolectric: it polls its anchor position in an endless
     * `withInfiniteAnimationFrameNanos` loop that the paused looper keeps running at the same
     * instant. Indeterminate progress indicators simply stay still.
     */
    private object ParkInfiniteAnimations : InfiniteAnimationPolicy {
        override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R = awaitCancellation()
    }

    /**
     * Makes every window recomposer created from now on use [ParkInfiniteAnimations], and makes
     * frames arrive only as the test advances the clock (one per frame interval, like vsync). By
     * default Robolectric's Choreographer advances the clock itself when a frame is requested and
     * delivers it at once, so a coroutine that awaits every frame (the layer list's auto-scroll
     * while a row is dragged) keeps the looper busy forever.
     */
    @OptIn(InternalComposeUiApi::class)
    fun installTestRecomposer() {
        WindowRecomposerPolicy.setFactory { root -> root.createLifecycleAwareWindowRecomposer(ParkInfiniteAnimations) }
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    }

    /** Windows that existed before the current screen was shown (other activities). */
    var baseline = 0

    /** Root views of the windows shown since [baseline] was taken (activity first). */
    fun windows(): List<View> = RobolectricUi.windowRoots().drop(baseline)

    /** Call before creating the activity of a section: later window counts start from here. */
    fun markBaseline() { baseline = RobolectricUi.windowRoots().size }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun SemanticsNode.descriptions(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun SemanticsNode.clickLabel(): String? = config.getOrNull(SemanticsActions.OnClick)?.label

    /** Everything a person could read or hear for this node. */
    private fun SemanticsNode.labels(): List<String> = texts() + descriptions() + listOfNotNull(clickLabel())

    private fun matches(n: SemanticsNode, label: String, exact: Boolean) = n.labels().any { if (exact) it == label else it.contains(label) }

    /**
     * Elements of the current screen's windows that are on screen. Nodes that are composed but
     * not placed (a minimized or covered menu panel keeps its content that way) are not shown to
     * anyone, so they are left out, like accessibility services leave them out.
     */
    private fun elements() = RobolectricUi.elements().filter { e ->
        RobolectricUi.windowRoots().indexOf(e.window) >= baseline && e.node.layoutInfo.isPlaced
    }

    fun find(label: String, exact: Boolean = false): RobolectricUi.Element? = elements().lastOrNull { matches(it.node, label, exact) }

    fun has(label: String, exact: Boolean = false): Boolean = find(label, exact) != null

    /** Taps the (last) element labelled [label] at its center with real touch events. */
    fun tap(label: String, exact: Boolean = false) {
        val e = find(label, exact) ?: throw AssertionError("nothing labelled \"$label\" on screen; shown: ${shown().take(100)}")
        e.tap()
    }

    /**
     * Invokes the click action of the element labelled [label] (text, content description or
     * click label), or of its nearest clickable ancestor (an icon's description sits on a child
     * of its button). Works for sheets that have not finished animating in.
     */
    fun click(label: String, exact: Boolean = false, settleAfter: Boolean = true) {
        Smoke.step("click \"$label\"")
        val candidates = elements().filter { matches(it.node, label, exact) }
        for (e in candidates.asReversed()) {
            var n: SemanticsNode? = e.node
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            val action = n?.config?.getOrNull(SemanticsActions.OnClick)?.action ?: continue
            action.invoke()
            if (settleAfter) settle()
            return
        }
        throw AssertionError("nothing clickable labelled \"$label\"; shown: ${shown().take(100)}")
    }

    fun shown(): List<String> = elements().flatMap { it.node.labels() }.distinct()

    /** Whether the clickable element labelled [label] (or its clickable ancestor) is enabled. */
    fun isEnabled(label: String, exact: Boolean = true): Boolean {
        val e = find(label, exact) ?: throw AssertionError("nothing labelled \"$label\"")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n) { "\"$label\" is not clickable" }
        return !n.config.contains(SemanticsProperties.Disabled)
    }

    /** Clicks the tab (semantic role Tab) labelled [label]. */
    fun clickTab(label: String) {
        Smoke.step("tab \"$label\"")
        val tab = elements().lastOrNull { e ->
            e.node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab &&
                e.node.subtreeLabels().contains(label)
        } ?: throw AssertionError("no tab \"$label\"; shown: ${shown().take(100)}")
        requireNotNull(tab.node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle()
    }

    /** Clicks [label] inside the window that shows [windowText] (e.g. a dialog's confirm button). */
    fun clickIn(windowText: String, label: String) {
        Smoke.step("click \"$label\" in \"$windowText\"")
        val window = find(windowText, exact = true)?.window ?: throw AssertionError("no window shows \"$windowText\"; shown: ${shown().take(100)}")
        val e = elements().lastOrNull { it.window === window && matches(it.node, label, exact = true) }
            ?: throw AssertionError("no \"$label\" in the window of \"$windowText\"")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "\"$label\" is not clickable" }.invoke()
        settle()
    }

    private fun SemanticsNode.subtreeLabels(): List<String> = labels() + children.flatMap { it.subtreeLabels() }

    /** The (last) editable text field labelled [label]. */
    fun field(label: String): RobolectricUi.Element =
        elements().lastOrNull { e -> e.node.config.getOrNull(SemanticsActions.SetText) != null && label in e.node.subtreeLabels() }
            ?: throw AssertionError("no text field \"$label\"; shown: ${shown().take(100)}")

    /**
     * Types [text] into the field labelled [label] like a user: focus, replace the text, then
     * press the keyboard's Done key (the commit path) and settle.
     */
    fun typeAndDone(label: String, text: String) {
        Smoke.step("type \"$text\" into \"$label\"")
        field(label).focus()
        settle(2)
        field(label).type(text)
        settle(2)
        val ime = field(label).node.config.getOrNull(SemanticsActions.OnImeAction)?.action
            ?: throw AssertionError("field \"$label\" has no IME action")
        ime.invoke()
        settle(4)
    }

    /** Types [text] into [label], then moves focus away (the focus-loss commit path). */
    fun typeAndLeave(label: String, text: String) {
        Smoke.step("type \"$text\" into \"$label\" and leave")
        val f = field(label)
        f.focus()
        settle(2)
        field(label).type(text)
        settle(2)
        f.window.clearFocus()
        settle(4)
    }

    private fun appliedChanges(): Long = androidx.compose.runtime.Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    /**
     * Nothing recomposes by itself once the screen has settled: no state written during
     * composition, no size-feedback loop, no animation that never ends. Lets [settleMs] of time
     * pass (longer than a short snackbar, 4 s, whose dismissal recomposes), then requires ZERO
     * applied changes over another second without input.
     */
    fun assertIdle(where: String, settleMs: Long = 5_000) {
        Smoke.pump(settleMs, stepMs = 50)
        // Background work (brush previews rendered one by one, thumbnails, a file list) finishes
        // at real-time moments and recomposes once each; a loop never stops. So wait up to a few
        // seconds of REAL time for one quiet (virtual) second.
        val counts = mutableListOf<Long>()
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            val before = appliedChanges()
            Smoke.pump(1_000, stepMs = 50)
            val changes = appliedChanges() - before
            if (changes == 0L) return
            counts += changes
        }
        throw AssertionError("$where: the UI keeps recomposing with no input (changes per second: $counts)")
    }

    /** At least [min] windows are shown and every one has a size (a sheet that failed to measure would not). */
    fun assertWindowsLaidOut(min: Int = 1) {
        val roots = windows()
        assertTrue("expected at least $min windows, got ${roots.size}", roots.size >= min)
        assertTrue("a window has no size: ${roots.map { "${it.width}x${it.height}" }}", roots.all { it.width > 0 && it.height > 0 })
    }

    // ------------------------------------------------------------------ hosted (non-modal) sheets

    /** Titles of the sheet panels the editor's sheet host shows (expanded), oldest first. */
    fun sheetTitles(): List<String> = elements().mapNotNull { it.node.config.getOrNull(BwSheetTitleKey) }

    /** Titles in the pills of minimized sheets. */
    fun pillTitles(): List<String> = elements().mapNotNull { it.node.config.getOrNull(BwSheetPillKey) }

    /** The shown hosted panel (the node carrying its title), or null. */
    fun sheetPanel(): RobolectricUi.Element? = elements().lastOrNull { it.node.config.getOrNull(BwSheetTitleKey) != null }

    /** A menu is up: a modal sheet or dialog window, a hosted panel, or a minimized one's pill. */
    fun menuOpen(): Boolean = windows().size > 1 || sheetTitles().isNotEmpty() || pillTitles().isNotEmpty()

    /**
     * A panel (titled [title], if given) is shown by the editor's host: in the editor's own window
     * (no extra window), laid out with a size.
     */
    fun assertPanelShown(title: String? = null) {
        assertWindowsLaidOut(1)
        assertTrue("a hosted panel adds no window: ${windows().size}", windows().size == 1)
        val panel = sheetPanel() ?: throw AssertionError("no panel shown; shown: ${shown().take(80)}")
        val t = panel.node.config[BwSheetTitleKey]
        if (title != null) assertTrue("panel \"$t\" shown instead of \"$title\"", t == title)
        assertTrue("panel \"$t\" has no size: ${panel.bounds}", panel.bounds.width > 0f && panel.bounds.height > 0f)
    }
}

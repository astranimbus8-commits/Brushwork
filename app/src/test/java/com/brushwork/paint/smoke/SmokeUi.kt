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

    /** Makes every window recomposer created from now on use [ParkInfiniteAnimations]. */
    @OptIn(InternalComposeUiApi::class)
    fun installTestRecomposer() {
        WindowRecomposerPolicy.setFactory { root -> root.createLifecycleAwareWindowRecomposer(ParkInfiniteAnimations) }
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

    private fun elements() = RobolectricUi.elements().filter { e -> RobolectricUi.windowRoots().indexOf(e.window) >= baseline }

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

    /** At least [min] windows are shown and every one has a size (a sheet that failed to measure would not). */
    fun assertWindowsLaidOut(min: Int = 1) {
        val roots = windows()
        assertTrue("expected at least $min windows, got ${roots.size}", roots.size >= min)
        assertTrue("a window has no size: ${roots.map { "${it.width}x${it.height}" }}", roots.all { it.width > 0 && it.height > 0 })
    }
}

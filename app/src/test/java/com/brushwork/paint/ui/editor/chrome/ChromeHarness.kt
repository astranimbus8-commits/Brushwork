package com.brushwork.paint.ui.editor.chrome

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.robolectric.Robolectric

/**
 * Shared plumbing of the v1.6 chrome tests: sections (all UI work of a class runs in ONE test, a
 * habit from before [SmokeUi.installTestRecomposer] restarted Compose's main dispatcher for each
 * test; a section's failure doesn't hide the next section's), the editor in a fresh activity, and
 * measurements in dp relative to the editor's root.
 */
internal class ChromeHarness {
    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    fun editor(doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true), setup: (EditorController) -> Unit = {}): ChromeScreen {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val c = Smoke.controller(activity, doc)
        setup(c)
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        settle()
        return ChromeScreen(activity, c)
    }

    fun finish() {
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}

/** The editor on screen, measured in dp from the top-left of the editor's root. */
internal class ChromeScreen(val activity: ComponentActivity, val c: EditorController) {
    val density: Float = activity.resources.displayMetrics.density
    val touch = Smoke.Touch(activity.window.decorView)

    /** The editor's root (window px): the first node of the activity's window. */
    val root: Rect
        get() = RobolectricUi.elements().first { it.window === activity.window.decorView }.bounds

    val widthDp: Float get() = root.width / density
    val heightDp: Float get() = root.height / density

    /** System bar insets the editor sees (dp): status bar (top), navigation bar (bottom). */
    fun insetsDp(): Pair<Float, Float> {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.systemBars())
        return ((insets?.top ?: 0) / density) to ((insets?.bottom ?: 0) / density)
    }

    /** [r] (window px) in dp relative to the editor's root. */
    fun dp(r: Rect): Rect {
        val o = root
        return Rect((r.left - o.left) / density, (r.top - o.top) / density, (r.right - o.left) / density, (r.bottom - o.top) / density)
    }

    /** Bounds (dp) of the node tagged [tag], or null. */
    fun tagged(tag: String): Rect? = placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.let { dp(it.bounds) }

    /** Bounds (dp) of the clickable labelled [label] (its own label or a child's). */
    fun clickable(label: String, exact: Boolean = true): Rect? {
        val e = SmokeUi.find(label, exact) ?: return null
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.let { dp(it.boundsInWindow) }
    }

    /** Elements of the editor's window and later windows that are placed. */
    fun placed(): List<RobolectricUi.Element> = RobolectricUi.elements().filter { e ->
        RobolectricUi.windowRoots().indexOf(e.window) >= SmokeUi.baseline && e.node.layoutInfo.isPlaced
    }

    val canvas: CanvasView get() = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

    /** Window pixel of document point ([x], [y]). */
    fun screen(x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvas.getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    /** Document point under window pixel ([x], [y]). */
    fun doc(x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvas.getLocationInWindow(loc)
        val p = c.viewTransform.screenToDoc(x - loc[0], y - loc[1])
        return p.x to p.y
    }
}

/**
 * The clickable elements on screen with everything they are known by: their own text,
 * description and click label, plus those of descendants that are not clickables themselves
 * (what a screen reader announces for them, and what [SmokeUi.click] finds them by).
 */
internal object Clickables {
    /**
     * A clickable on screen: [labels] with its descendants' texts and descriptions, [own] its
     * name alone â€” its own text, description and click label plus its descendants' descriptions
     * (an icon button's name sits on its icon), not the texts it shows (a layer row's "100%" over
     * "Normal"); [bounds] are what shows (clipped by a scrolling list), [width] Ã— [height] its own
     * layout size in dp (the target a finger gets once it is scrolled into view).
     */
    class Item(
        val node: SemanticsNode,
        val labels: Set<String>,
        val bounds: Rect,
        val window: android.view.View,
        val width: Float,
        val height: Float,
        val own: Set<String> = labels,
    )

    private fun SemanticsNode.ownLabels(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)

    private fun SemanticsNode.isClickable(): Boolean = config.getOrNull(SemanticsActions.OnClick) != null

    private fun SemanticsNode.mergedLabels(): Set<String> {
        val out = linkedSetOf<String>()
        out += ownLabels()
        fun walk(n: SemanticsNode) {
            // What a `clearAndSetSemantics` node hides (its children, which the unmerged tree still lists) is not read.
            if (n.config.isClearingSemantics) return
            for (child in n.children) {
                if (child.isClickable()) continue
                out += child.ownLabels()
                walk(child)
            }
        }
        walk(this)
        return out.filter { it.isNotBlank() }.toSet()
    }

    /** Own labels plus the descriptions (not the texts) of descendants that aren't clickables. */
    private fun SemanticsNode.nameLabels(): Set<String> {
        val out = linkedSetOf<String>()
        out += ownLabels()
        fun walk(n: SemanticsNode) {
            if (n.config.isClearingSemantics) return
            for (child in n.children) {
                if (child.isClickable()) continue
                out += child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                walk(child)
            }
        }
        walk(this)
        return out.filter { it.isNotBlank() }.toSet()
    }

    fun onScreen(screen: ChromeScreen, inside: Rect? = null, outside: List<Rect> = emptyList()): List<Item> =
        screen.placed()
            .filter { it.node.isClickable() }
            .map {
                Item(
                    it.node, it.node.mergedLabels(), screen.dp(it.bounds), it.window,
                    it.node.size.width / screen.density, it.node.size.height / screen.density,
                    own = it.node.nameLabels(),
                )
            }
            .filter { it.bounds.width > 0f && it.bounds.height > 0f }
            .filter { inside == null || inside.contains(it.bounds.center) }
            .filter { outside.none { o -> o.contains(it.bounds.center) } }

    /** [items] known by their names ([Item.own]) inside [region] (list rows that show the same values), by all their labels elsewhere. */
    fun ownLabelsInside(items: List<Item>, region: Rect): List<Item> = items.map { item ->
        if (region.contains(item.bounds.center)) Item(item.node, item.own, item.bounds, item.window, item.width, item.height, item.own) else item
    }

    /** Labels shared by two or more different clickables, with their bounds. */
    fun duplicates(items: List<Item>): Map<String, List<Rect>> {
        val byLabel = linkedMapOf<String, MutableList<Item>>()
        for (item in items) for (l in item.labels) byLabel.getOrPut(l) { mutableListOf() } += item
        return byLabel.filterValues { it.size > 1 }.mapValues { (_, v) -> v.map { it.bounds } }
    }
}

package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditListener
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.LayerListListener
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextWrapReflow
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * Keeps linked text stories whole (v1.6, §3.6c; area D): `controller.textThreads`, created and
 * registered by the controller at init as an [EditListener] AND a [LayerListListener], so it works
 * before the Text frames tool exists.
 *
 * - A frame that leaves its story — its layer deleted or merged down, its pixels edited (which
 *   clears its text: brush, eraser, Transform, filters, flip...), merged into from above, or
 *   edited by v1.5 (which drops its thread) — makes the story's frames inconsistent (a gap
 *   between slices, no frame at 0, the last frame's `overset` wrong). On the next committed edit
 *   such a story re-flows into the frames left, INSIDE the triggering step
 *   ([EditorController.amendLastStep], I2): text is never lost while one frame remains.
 *   The state is checked, not remembered: undo and redo emit no events, so nothing here relies
 *   on having seen a layer before it changed. Stories are only looked at when one of their
 *   layers' stored data (or the set of their layers) changed since they were last seen whole;
 *   a story already damaged when the project was opened is left alone until it is touched.
 * - A DUPLICATED frame layer becomes an unlinked plain fixed-box text of its slice, drawn again
 *   (its own text layout), in the duplicate's step.
 * - Frames brought in again under the same story id (a Brushwork SVG / PDF imported into the
 *   artwork it came from) are taken apart into separate stories in the import's own step (the
 *   import reports the layers it adds), before anything re-flows: a frame's own edit right
 *   after it never sees the story twice.
 * - Its own writes carry [TextWrapReflow.REFLOW_LABEL] and are ignored; a re-flow whose frames
 *   come out as stored writes nothing. Delivery therefore converges within 2 of the controller's
 *   4 rounds.
 * - Locked frames keep their slice (the controller refuses to change them): a heal flows the
 *   other frames around them, from their end.
 * - Undo and redo never re-flow (the controller doesn't report them).
 */
class TextThreads(private val c: EditorController) : EditListener, LayerListListener {

    /** A frame of a story in the document: its layer and the item it stores. */
    class Frame(val layer: Layer, val item: TextItem) {
        val thread: TextThreadSpec get() = item.thread
    }

    /** A story as its frames hold it: the copy that wins (highest `rev`, then the lowest index). */
    class Story(val id: Long, val text: String, val spec: TextSpec, val rev: Long, val frames: List<Frame>)

    /** The measured tails of the stories (flowing then drawing a chain measures each once). */
    internal val measures = StoryMeasureCache()

    internal val writer = StoryWriter(c, measures) { _, data, item -> remember(data, item) }

    /** Heals and re-flows done since this controller started (tests and diagnostics). */
    var healCount: Int = 0
        private set

    // ------------------------------------------------------------------ the frame index

    /**
     * Decoded frames by stored data INSTANCE (undo restores the same instances; nothing to decode
     * again). Least recently used first: every look at the document touches the data its layers
     * hold now, so what goes first is data no layer holds any more (each can carry a copy of a
     * story of up to 50,000 characters): at most [STALE_DECODED] of those are kept.
     */
    private val decoded = object : LinkedHashMap<DataRef, TextItem?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DataRef, TextItem?>): Boolean =
            size > c.doc.layers.size + STALE_DECODED
    }

    /** A stored text data string compared by identity. */
    private class DataRef(val data: String) {
        override fun equals(other: Any?): Boolean = other is DataRef && other.data === data
        override fun hashCode(): Int = System.identityHashCode(data)
    }

    private fun remember(data: String, item: TextItem?) {
        decoded[DataRef(data)] = item?.takeIf { it.thread.isOn }
    }

    /** The frame item stored in [data], or null when it is no frame (or can't be read). */
    private fun itemOf(data: String): TextItem? {
        val key = DataRef(data)
        if (decoded.containsKey(key)) return decoded[key]
        val item = if (maybeThreaded(data)) TextCodec.decode(data)?.takeIf { it.thread.isOn } else null
        decoded[key] = item
        return item
    }

    /** The frame [layer] stores (decoded once per stored data), or null when it is no frame. */
    fun frameOf(layer: Layer): TextItem? = layer.textData?.let { itemOf(it) }

    /** Real: [layer]'s text data is a threaded item (a frame of a linked story). */
    fun isFrame(layer: Layer): Boolean = frameOf(layer) != null

    /** Every frame of the document, bottom layer first. */
    fun allFrames(): List<Frame> {
        val out = ArrayList<Frame>()
        for (l in c.doc.layers) frameOf(l)?.let { out += Frame(l, it) }
        return out
    }

    /** The frames of every story, each in chain order (index, then stack position). */
    fun stories(): Map<Long, List<Frame>> {
        val by = LinkedHashMap<Long, MutableList<Frame>>()
        for (f in allFrames()) by.getOrPut(f.thread.storyId) { ArrayList() } += f
        for (list in by.values) list.sortWith(CHAIN_ORDER)
        return by
    }

    /** The frames of story [storyId] in chain order (empty when it has none). */
    fun framesOf(storyId: Long): List<Frame> {
        if (storyId == 0L) return emptyList()
        return allFrames().filter { it.thread.storyId == storyId }.sortedWith(CHAIN_ORDER)
    }

    /** Story [storyId] as its frames hold it, or null when no frame holds it. */
    fun story(storyId: Long): Story? = storyOf(framesOf(storyId))

    /** The story [frames] hold (the copy with the highest `rev`, then the lowest index), or null when empty. */
    fun storyOf(frames: List<Frame>): Story? {
        if (frames.isEmpty()) return null
        val best = frames.minWith(compareByDescending<Frame> { it.thread.rev }.thenBy { it.thread.index })
        return Story(best.thread.storyId, best.thread.story, best.item.spec, frames.maxOf { it.thread.rev }, frames)
    }

    /** A new story id: random, positive, 63-bit, used by no frame of the document. */
    fun newStoryId(): Long {
        val used = allFrames().mapTo(HashSet()) { it.thread.storyId }
        while (true) {
            val id = Random.nextLong() and Long.MAX_VALUE
            if (id != 0L && id !in used) return id
        }
    }

    // ------------------------------------------------------------------ story health (heals)

    /** The stored data of each story's frames when the story was last seen whole: (layer, data) pairs. */
    private var seen: Map<Long, List<Pair<Layer, String>>> = signatures(stories())

    private fun signatures(stories: Map<Long, List<Frame>>): Map<Long, List<Pair<Layer, String>>> =
        stories.mapValues { (_, frames) -> frames.map { it.layer to (it.layer.textData ?: "") } }

    private fun sameSignature(a: List<Pair<Layer, String>>?, b: List<Pair<Layer, String>>): Boolean {
        if (a == null || a.size != b.size) return false
        for (i in a.indices) if (a[i].first !== b[i].first || a[i].second !== b[i].second) return false
        return true
    }

    /**
     * Looks at the stories whose frames changed since they were last seen whole and re-flows the
     * ones that are no longer whole (see the class notes), folded into the newest step.
     */
    private fun checkStories() {
        var now = stories()
        // The same story brought in twice (its frames imported again from a Brushwork SVG or PDF)
        // is two chains with one story id: each copy becomes a story of its own first.
        var split = false
        for ((id, frames) in now) {
            if (sameSignature(seen[id], signatureOf(frames))) continue
            if (splitCopies(frames)) split = true
        }
        if (split) now = stories()
        for ((id, frames) in now) {
            if (sameSignature(seen[id], signatureOf(frames))) continue
            if (!isWhole(frames)) heal(id)
        }
        seen = signatures(stories())
    }

    /**
     * [frames] (one story id) hold the same chain positions more than once — copies of the story
     * (the same frames imported again) or damaged data: they are taken apart into chains, layer
     * stack order first (each frame joins the first chain that doesn't have its position yet).
     * The first chain keeps the story; every other one becomes a story of its own (a new id;
     * the same slices, so only data changes), folded into the newest step. Locked frames keep
     * their id. False when there is nothing to take apart.
     */
    private fun splitCopies(frames: List<Frame>): Boolean {
        if (frames.size < 2 || frames.map { it.thread.index }.toSet().size == frames.size) return false
        val chains = ArrayList<MutableList<Frame>>()
        for (f in frames.sortedBy { c.doc.indexOf(it.layer) }) {
            val chain = chains.firstOrNull { ch -> ch.none { it.thread.index == f.thread.index } }
                ?: ArrayList<Frame>().also { chains += it }
            chain += f
        }
        if (chains.size < 2) return false
        val writes = ArrayList<FrameWrite>()
        val ids = HashSet<Long>()
        for (chain in chains.drop(1)) {
            var id = newStoryId()
            while (!ids.add(id)) id = newStoryId()
            for (f in chain) {
                if (c.doc.effectiveLocked(f.layer)) continue
                writes += FrameWrite(f.layer, f.item, f.item.copy(thread = f.thread.copy(storyId = id)))
            }
        }
        if (writes.isEmpty()) return false
        var done = false
        c.amendLastStep { done = writer.write(writes, TextWrapReflow.REFLOW_LABEL) != null }
        return done
    }

    private fun signatureOf(frames: List<Frame>) = frames.map { it.layer to (it.layer.textData ?: "") }

    /**
     * True when [frames] (one story, chain order) hold their story whole: the same story copy,
     * each unlocked frame starting where the one before ends (the first at 0), indices increasing,
     * and `overset` only on the last frame, exactly when the story goes on beyond it. Locked
     * frames keep whatever slice they have (they can't be changed).
     */
    internal fun isWhole(frames: List<Frame>): Boolean {
        val s = storyOf(frames) ?: return true
        for ((k, f) in frames.withIndex()) {
            val th = f.thread
            if (k > 0 && th.index <= frames[k - 1].thread.index) return false
            if (c.doc.effectiveLocked(f.layer)) continue
            if (th.story !== s.text && th.story != s.text) return false
            val expected = if (k == 0) 0 else frames[k - 1].thread.end
            if (th.start != expected) return false
            if (th.overset != (k == frames.lastIndex && th.end < s.text.length)) return false
        }
        return true
    }

    /**
     * Re-flows story [storyId] into the frames that hold it (locked ones pinned), folded into the
     * newest step; false when nothing changed.
     */
    private fun heal(storyId: Long): Boolean {
        val frames = framesOf(storyId)
        val s = storyOf(frames) ?: return false
        val chain = frames.map { FlowFrame(it.layer, it.item, pinned = c.doc.effectiveLocked(it.layer)) }
        return reflow(s, chain)
    }

    /**
     * Flows story [s] into [chain] and writes what changed, folded into the newest step (labels
     * [TextWrapReflow.REFLOW_LABEL]); `rev` is bumped only when something changes. False when the
     * frames already hold this flow.
     */
    private fun reflow(s: Story, chain: List<FlowFrame>): Boolean {
        val items = try {
            TextThreadFlow.flow(s.text, s.spec, chain, s.id, s.rev, measures)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to re-flow the linked text")
            return false
        }
        val stored = chain.map { f -> f.layer?.let { frameOf(it) } }
        val unchanged = items.indices.all { i -> stored[i] == items[i] }
        if (unchanged) return false
        val rev = s.rev + 1
        val writes = ArrayList<FrameWrite>()
        for ((i, f) in chain.withIndex()) {
            val layer = f.layer ?: continue
            if (f.pinned) continue
            writes += FrameWrite(layer, stored[i], withRev(items[i], rev))
        }
        var done = false
        c.amendLastStep {
            done = writer.write(writes, TextWrapReflow.REFLOW_LABEL) != null
        }
        if (done) healCount++
        return done
    }

    /** [item] (a frame) at story revision [rev]. */
    internal fun withRev(item: TextItem, rev: Long): TextItem =
        if (!item.thread.isOn || item.thread.rev == rev) item else item.copy(thread = item.thread.copy(rev = rev))

    override fun onEdited(e: EditEvent) {
        // Our own writes (and the Text tool's wrap re-flows) carry this label.
        if (e.label == TextWrapReflow.REFLOW_LABEL) return
        checkStories()
    }

    override fun onLayerList(e: LayerListEvent) {
        if (e.kind == LayerListKind.DUPLICATED) unthreadCopy(e.layer)
        checkStories()
    }

    /**
     * A duplicated frame becomes an unlinked plain fixed-box text of its slice (§3.6a), drawn
     * again with its own layout, in the duplicate's step. A locked copy is unlocked around the
     * change (the change and the lock are restored together by undo).
     */
    private fun unthreadCopy(copy: Layer) {
        val item = frameOf(copy) ?: return
        if (c.doc.indexOf(copy) < 0) return
        val plain = item.copy(thread = TextThreadSpec()).sanitized()
        c.amendLastStep {
            val locked = copy.locked
            if (locked) c.setLayerProps(copy, copy.props().copy(locked = false), TextWrapReflow.REFLOW_LABEL)
            writer.update(copy, item, plain)
            if (locked) c.setLayerProps(copy, copy.props().copy(locked = true), TextWrapReflow.REFLOW_LABEL)
        }
    }

    // ------------------------------------------------------------------ seams (frozen signatures)

    /**
     * Re-flows story [storyId] into its frames inside the current step ([EditorController.amendLastStep]);
     * false when nothing changed. Frames whose wrap outline is stale first get their picture's
     * current outline (`c.textWrap.contours`): `TextWrapReflow` hands every re-traced frame
     * here instead of re-rendering it alone — once per frame whose outline changed, so one edit
     * can call it several times for the same story (the later calls find nothing to do).
     * As for plain text, `TextWrapReflow` never calls it for a locked frame or for the layer open
     * in the Text tool.
     */
    fun reflowStory(storyId: Long): Boolean {
        val frames = framesOf(storyId)
        val s = storyOf(frames) ?: return false
        val chain = frames.map { f ->
            if (c.doc.effectiveLocked(f.layer)) FlowFrame(f.layer, f.item, pinned = true) else FlowFrame(f.layer, refreshedWrap(f.item))
        }
        val changed = reflow(s, chain)
        seen = signatures(stories())
        return changed
    }

    /** [item] wrapping around its picture's current outline (itself when that is what it has, or it can't be traced). */
    internal fun refreshedWrap(item: TextItem): TextItem {
        if (!item.wrapActive) return item
        val src = c.doc.layerById(item.wrap.sourceLayerId)?.takeIf { !it.isTextLayer && !it.isAdjustmentLayer } ?: return item
        val polys = c.textWrap.contours.polygons(src, item.wrap.contour) ?: return item
        return if (polys == item.wrap.polygons) item else item.copy(wrap = item.wrap.copy(polygons = polys))
    }

    /**
     * Switches to the Text frames tool with frame [layer] selected (its story editor open when
     * [openEditor]); false if [layer] is not a frame. The Text tool's `editLayer` and the layer
     * window's "Edit text" call it first.
     */
    fun openForEditing(layer: Layer, openEditor: Boolean): Boolean {
        if (c.doc.indexOf(layer) < 0 || !isFrame(layer)) return false
        if (c.activeToolId != ToolId.TEXT_FRAMES) c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools[ToolId.TEXT_FRAMES] as? TextFrameTool ?: return false
        return tool.openFrame(layer, openEditor)
    }

    // ------------------------------------------------------------------ writing stories (the tool)

    /**
     * Writes story [storyId] ([story] in [spec]'s look) flowed into [chain] as ONE step named
     * [label] (I2), plus [extra] writes of frames that leave the story (unlinked ones) in the
     * same step. New frames (`layer == null`) are created, named by [nameOf] (their chain
     * position). Refused with a message, writing nothing, when a frame that would change is
     * locked. Returns the layers of [chain] in order, or null when nothing was written.
     */
    internal fun writeStory(
        label: String,
        storyId: Long,
        story: String,
        spec: TextSpec,
        chain: List<FlowFrame>,
        extra: List<FrameWrite> = emptyList(),
        nameOf: (Int, TextItem) -> String = { k, it -> frameName(k, it) },
    ): List<Layer>? {
        val before = framesOf(storyId)
        val base = before.maxOfOrNull { it.thread.rev } ?: 0L
        var items = try {
            TextThreadFlow.flow(story, spec, chain, storyId, base, measures)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to flow the linked text")
            return null
        }
        val olds = chain.map { f -> f.layer?.let { frameOf(it) ?: TextCodec.decode(it.textData) } }
        // A re-flow (the story, its look, a slice or the chain changes) bumps rev for every frame;
        // a frame only moved (its lines the same) changes alone.
        val reflowed = extra.isNotEmpty() || before.size != chain.size || chain.indices.any { k ->
            val o = olds[k]
            o == null || !o.threaded || o.thread.copy(rev = base) != items[k].thread ||
                FrameGeometry.storyLook(o.spec) != FrameGeometry.storyLook(items[k].spec)
        }
        if (reflowed) items = items.map { withRev(it, base + 1) }
        val writes = ArrayList<FrameWrite>()
        for ((k, f) in chain.withIndex()) {
            if (f.pinned) continue
            val layer = f.layer
            val old = olds[k]
            if (old == items[k]) continue
            // A locked frame can't change (its rev included, I9): the edit is refused.
            if (layer != null && c.doc.effectiveLocked(layer)) {
                c.toast(lockedMessage(k))
                return null
            }
            writes += FrameWrite(layer, old, items[k], if (layer == null) nameOf(k, items[k]) else "")
        }
        if (writes.isEmpty() && extra.isEmpty()) return chain.map { it.layer ?: return null }
        for (x in extra) {
            val l = x.layer
            if (l != null && c.doc.effectiveLocked(l)) {
                c.toast(lockedMessage(before.indexOfFirst { it.layer === l }.coerceAtLeast(0)))
                return null
            }
        }
        val news = chain.count { it.layer == null }
        if (c.effectiveLayerCount + news > c.maxLayers) {
            c.toast(LAYER_LIMIT.format(c.maxLayers))
            return null
        }
        var result: List<Layer>? = null
        c.groupUndo(label) {
            val out = writer.write(writes, label, label) ?: return@groupUndo
            if (writer.write(extra, label, label) == null) return@groupUndo
            // The chain's layers in order: the existing ones, and the created ones in creation order.
            val created = ArrayDeque<Layer>()
            for (i in writes.indices) if (writes[i].layer == null) created.addLast(out[i])
            result = chain.map { f -> f.layer ?: created.removeFirst() }
        }
        seen = signatures(stories())
        return result
    }

    /** "Frame 2: Lorem ipsum…": the layer name of new frame [k] showing [item]'s slice. */
    fun frameName(k: Int, item: TextItem): String {
        val flat = item.text.trim().replace(Regex("\\s+"), " ")
        val cps = flat.codePoints().limit(12).toArray()
        val snippet = String(cps, 0, cps.size).trimEnd()
        return if (snippet.isEmpty()) "Frame ${k + 1}" else "Frame ${k + 1}: $snippet"
    }

    /** The message refusing to change locked frame [k] (0-based chain position) of a story. */
    fun lockedMessage(k: Int): String = "Unlock frame ${k + 1} to edit this story"

    /** Frees the measured stories (the editor closes, or memory is short). */
    fun release() {
        measures.clear()
        decoded.clear()
    }

    /** Lays out frame [item] for drawing, reusing the measured tails of the story. */
    internal fun prepare(item: TextItem, reuse: com.brushwork.paint.tools.text.PreparedText? = null) = measures.prepare(item, reuse)

    /** Where frame [item]'s slice ends in its story (through the measured tails). */
    internal fun frameEnd(item: TextItem): Int = if (item.thread.isOn) measures.frameLayout(item).end else TextRenderer.frameEnd(item)

    companion object {
        /** Chain order: by index, then by place in the layer stack (damaged data with equal indices). */
        private val CHAIN_ORDER = Comparator<Frame> { a, b -> a.thread.index.compareTo(b.thread.index) }

        /** Decoded data kept beyond what the layers hold now (undo and redo bring it back). */
        private const val STALE_DECODED = 32

        /** The Text tool's message at the layer limit. */
        const val LAYER_LIMIT = "Layer limit reached (%d) for this canvas size: delete or merge a layer to add text"

        /**
         * Cheap test of stored text [data] (JSON): false when it is certainly no frame (its
         * thread's `storyId` is 0, or it has no thread: v1.5 data), true when it may be one.
         * The thread is the item's last field; quotes inside strings are escaped, so the key
         * can't appear inside the text or the story.
         */
        internal fun maybeThreaded(data: String): Boolean {
            val key = "\"storyId\":"
            val i = data.lastIndexOf(key)
            if (i < 0) return false
            var j = i + key.length
            while (j < data.length && data[j] == ' ') j++
            if (j >= data.length) return true
            if (data[j] != '0') return true
            val next = if (j + 1 < data.length) data[j + 1] else '}'
            return next.isDigit() || next == '.'
        }
    }
}

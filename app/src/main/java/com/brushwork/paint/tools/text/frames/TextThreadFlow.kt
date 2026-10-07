package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec

/**
 * One frame of a chain to flow a story into (v1.6, §3.6c): its [layer] (null = a frame that is
 * not created yet) and its [template], which gives where the frame is and how big (`cx`, `cy`,
 * `spec.box.width` × `spec.box.minHeight`) and what it wraps around (`wrap`). A [pinned] frame (a
 * locked layer) keeps its stored slice ([template] is then its stored item): the frames after it
 * continue from its end.
 */
class FlowFrame(val layer: Layer?, val template: TextItem, val pinned: Boolean = false)

/**
 * The flow of a linked story through its frames (v1.6, §3.6c; area D): frame 0 starts at 0, and
 * each frame's slice ends where [TextRenderer.frameEnd] says the frame is full, which is where the
 * next frame starts. The last frame records `overset` when the story goes on beyond it; a frame
 * with no room for even one line takes nothing. Because the slice end and the frame's rendering
 * are the same layout (`TextRenderer.frameLayout`), every frame's pixels equal its slice of the
 * chain (I1), and every item returned satisfies I9 (`text == story[start, end)`; one story, story
 * id, rev and look for all, except each frame's box size).
 */
object TextThreadFlow {

    /**
     * The items of [frames] (in chain order) holding [story] in [spec]'s look (its box size is
     * each frame's own), as story [storyId] at [rev]. Indices follow the chain (0, 1, 2…; a pinned
     * frame keeps its stored index and the frames after it number on from there). [cache] (the
     * measured tails) makes flowing and then drawing a chain measure each tail once.
     * v1.7: [kerns] (the story's manual kerns, story indices) go into every frame, so each frame
     * measures the ones of its tail and a kern stays between the same letters whichever frame
     * they flow into.
     */
    fun flow(
        story: String,
        spec: TextSpec,
        frames: List<FlowFrame>,
        storyId: Long,
        rev: Long,
        cache: StoryMeasureCache? = null,
        kerns: List<TextKern> = emptyList(),
    ): List<TextItem> {
        val text = cap(story)
        val look = FrameGeometry.storyLook(spec)
        val out = ArrayList<TextItem>(frames.size)
        var start = 0
        var lastIndex = -1
        for ((k, f) in frames.withIndex()) {
            val last = k == frames.lastIndex
            if (f.pinned) {
                val kept = f.template
                out += kept
                lastIndex = maxOf(lastIndex, kept.thread.index)
                start = kept.thread.end.coerceIn(0, text.length)
                continue
            }
            val index = maxOf(k, lastIndex + 1)
            lastIndex = index
            val probe = frameItem(text, look, f.template, storyId, index, start, start, false, rev, kerns)
            val end = (cache?.frameLayout(probe) ?: TextRenderer.frameLayout(probe)).end.coerceIn(start, text.length)
            out += frameItem(text, look, f.template, storyId, index, start, end, last && end < text.length, rev, kerns)
            start = end
        }
        return out
    }

    /**
     * Frame [index] of story [storyId] showing `story[start, end)` in [look] with [template]'s
     * place, size and wrap, carrying the story's [kerns] (v1.7): sanitized, so it is exactly what
     * a frame layer stores and decodes to.
     */
    fun frameItem(
        story: String,
        look: TextSpec,
        template: TextItem,
        storyId: Long,
        index: Int,
        start: Int,
        end: Int,
        overset: Boolean,
        rev: Long,
        kerns: List<TextKern> = emptyList(),
    ): TextItem = TextItem(
        text = "",
        spec = FrameGeometry.withFrameBox(look, template.spec.box),
        cx = template.cx,
        cy = template.cy,
        rotationDeg = 0f,
        path = TextPathSpec(),
        wrap = template.wrap,
        thread = TextThreadSpec(storyId = storyId, index = index, story = story, start = start, end = end, overset = overset, rev = rev),
        kerns = kerns,
    ).sanitized()

    /** [story] cut to [TextThreadSpec.MAX_STORY] characters (never inside a surrogate pair), as frames store it. */
    fun cap(story: String): String {
        if (story.length <= TextThreadSpec.MAX_STORY) return story
        var cut = TextThreadSpec.MAX_STORY
        if (Character.isHighSurrogate(story[cut - 1])) cut--
        return story.substring(0, cut)
    }

    /** Characters (code points) of [item]'s story that no frame shows: the overset beyond the last frame [item]. */
    fun oversetCount(item: TextItem): Int {
        val th = item.thread
        if (!th.isOn || th.end >= th.story.length) return 0
        return th.story.codePointCount(th.end, th.story.length)
    }
}

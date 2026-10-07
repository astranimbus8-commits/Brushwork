package com.brushwork.paint.tools.text

/**
 * v1.7 §3.17d: the unkerned texts whose pixels must stay v1.6's (letter-scaled kinds; the v1.5
 * kinds are [UnscaledParityGoldenTest]'s), for [TextKernRenderRobolectricTest].
 */
internal object TextKernRenderCases {
    private const val STORY = "Linked frames flow a story from one box into the next, just like InDesign. " +
        "When the first frame is full, the words that do not fit continue in the second frame."

    /** The second frame of [STORY] with [spec] (laid out like the flow). */
    fun secondFrame(spec: TextSpec, kerns: List<TextKern> = emptyList()): TextItem {
        val first = TextItem(spec = spec, cx = 250f, cy = 120f, thread = TextThreadSpec(storyId = 7, story = STORY), kerns = kerns)
        val end0 = TextRenderer.frameEnd(first)
        val second = TextItem(spec = spec, cx = 250f, cy = 260f, thread = TextThreadSpec(storyId = 7, index = 1, story = STORY, start = end0, end = end0), kerns = kerns)
        val end1 = TextRenderer.frameEnd(second)
        return second.copy(thread = second.thread.copy(end = end1, overset = end1 < STORY.length)).sanitized()
    }

    fun cases(): Map<String, TextItem> {
        val on = LetterScaleSpec(smallestPercent = 40f)
        val square = WrapPolygon(listOf(180f, 300f, 300f, 180f), listOf(140f, 140f, 260f, 260f))
        return linkedMapOf(
            "scaledPlain" to TextItem("Hello, World! gjpqy", TextSpec(sizePx = 40f, letterScale = on), 250f, 200f),
            "scaledBoxed" to TextItem(
                "Boxed words that wrap\nin a centred caption",
                TextSpec(sizePx = 28f, align = TextAlign.CENTER, lineSpacing = 1.3f, letterSpacing = 0.05f, box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(width = 300f), 28f), letterScale = on),
                250f, 200f, 12f,
            ),
            "scaledWrapped" to TextItem(
                WrapFixtures.LOREM, TextSpec(sizePx = 16f, box = TextBoxSpec(width = 360f), letterScale = on), 250f, 200f,
                wrap = TextWrapSpec(sourceLayerId = 3, polygons = listOf(square), gapPx = 6f, sides = WrapSides.BOTH),
            ),
            "scaledFrame" to secondFrame(TextSpec(sizePx = 22f, box = TextBoxSpec(width = 240f, minHeight = 80f), letterScale = on)),
            "scaledPath" to TextItem("Around the circle", TextSpec(sizePx = 30f, letterScale = on), path = TextPathSpec(type = TextPathType.CIRCLE, cx = 250f, cy = 200f, radius = 120f)),
            "scaledVertical" to TextItem("縦書き ABC\n12!? テキスト", TextSpec(sizePx = 30f, vertical = true, letterScale = on), 250f, 200f),
        )
    }
}

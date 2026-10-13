package com.brushwork.paint.engine

/**
 * v1.9 (design 2.4.4; owner A6): named timings of the hot paths (a frame, a brush lift, the
 * vector eraser's preview rebuild, a mask drag frame, a filter preview, a layer write, opening a
 * document), kept as the last [SAMPLES] samples per name. What P0 measures (2.7) and what the
 * frame-times overlay shows (its switch: StabilityLabels19.SHOW_FRAME_TIMES). Off by default: then a
 * [span] costs one volatile read.
 * Thread-safe ([record] is called from the main thread and from IO and Default workers).
 */
object PerfTrace {
    /** AppSettings.showFrameTimes, or a test. When false every call costs one volatile read. */
    @Volatile var enabled: Boolean = false

    /** Runs [block] and, while [enabled], records how long it took under [name]. */
    inline fun <T> span(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - start)
        }
    }

    /** Records one sample of [nanos] under [name] (nothing while not [enabled]). */
    fun record(name: String, nanos: Long) {
        if (!enabled) return
        synchronized(rings) { rings.getOrPut(name) { Ring() }.add(nanos) }
    }

    /** The last [SAMPLES] samples of [name] (all zero when there are none). */
    fun stats(name: String): Stats {
        val samples = synchronized(rings) { rings[name]?.snapshot() } ?: return Stats(0, 0f, 0f, 0f)
        if (samples.isEmpty()) return Stats(0, 0f, 0f, 0f)
        samples.sort()
        fun ms(nanos: Long) = nanos / 1_000_000f
        fun at(q: Float) = ms(samples[((samples.size - 1) * q + 0.5f).toInt().coerceIn(0, samples.size - 1)])
        return Stats(samples.size, at(0.5f), at(0.9f), ms(samples.last()))
    }

    /**
     * v1.9 (F3): the newest sample of [name] in milliseconds, or null before the first: the
     * frame-times overlay's "last lift".
     */
    fun lastMs(name: String): Float? = synchronized(rings) { rings[name]?.last() }?.let { it / 1_000_000f }

    /** Forgets every sample (tests, and the overlay when it is switched on). */
    fun clear() {
        synchronized(rings) { rings.clear() }
    }

    /** [count] samples; the median, 90th percentile and longest, in milliseconds. */
    data class Stats(val count: Int, val p50Ms: Float, val p90Ms: Float, val maxMs: Float)

    /** How many samples are kept per name. */
    const val SAMPLES = 120

    const val FRAME = "frame"
    const val LIFT = "brush.lift"
    const val ERASE_REBUILD = "erase.rebuild"
    const val MASK_FRAME = "mask.frame"
    const val FILTER_PREVIEW = "filter.preview"
    const val CUT = "erase.cut"
    const val SAVE_LAYER = "save.layer"
    const val OPEN = "open"

    private val rings = HashMap<String, Ring>()

    /** The last [SAMPLES] samples of one name. */
    private class Ring {
        private val values = LongArray(SAMPLES)
        private var size = 0
        private var next = 0

        fun add(nanos: Long) {
            values[next] = nanos
            next = (next + 1) % SAMPLES
            if (size < SAMPLES) size++
        }

        fun snapshot(): LongArray = values.copyOf(size)

        fun last(): Long? = if (size == 0) null else values[(next - 1 + SAMPLES) % SAMPLES]
    }
}

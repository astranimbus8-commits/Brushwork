package com.brushwork.paint.segmentation

/** Connected-component utilities on binary masks (pure Kotlin, iterative, no recursion). */
object Regions {

    /** Result of [label]: per-pixel component id (0 = not in mask, 1..count) and sizes by id. */
    class Labels(val ids: IntArray, val count: Int, val sizes: IntArray)

    /** Labels the connected components of [mask] (4- or 8-connected). */
    fun label(mask: BooleanArray, w: Int, h: Int, eightConnected: Boolean = true): Labels {
        require(mask.size == w * h)
        val ids = IntArray(w * h)
        val queue = IntArray(w * h)
        val sizes = ArrayList<Int>()
        sizes += 0
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || ids[start] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = start
            ids[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!eightConnected && dx != 0 && dy != 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (mask[j] && ids[j] == 0) { ids[j] = next; queue[tail++] = j }
                    }
                }
            }
            sizes += tail
        }
        return Labels(ids, next, sizes.toIntArray())
    }

    /**
     * Pixels of [passable] reachable (4-connected) from the pixels where [seed] is true AND
     * passable. [canStep] (from, to) can veto individual steps, e.g. across strong color edges.
     * Returns a new mask.
     */
    fun floodFrom(
        passable: BooleanArray, w: Int, h: Int,
        canStep: ((from: Int, to: Int) -> Boolean)? = null,
        seed: (x: Int, y: Int) -> Boolean,
    ): BooleanArray {
        require(passable.size == w * h)
        val reached = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var tail = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (passable[i] && seed(x, y)) { reached[i] = true; queue[tail++] = i }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0) tail = visit(i, i - 1, passable, reached, queue, tail, canStep)
            if (x < w - 1) tail = visit(i, i + 1, passable, reached, queue, tail, canStep)
            if (y > 0) tail = visit(i, i - w, passable, reached, queue, tail, canStep)
            if (y < h - 1) tail = visit(i, i + w, passable, reached, queue, tail, canStep)
        }
        return reached
    }

    private fun visit(
        from: Int, j: Int, passable: BooleanArray, reached: BooleanArray, queue: IntArray, tail: Int,
        canStep: ((Int, Int) -> Boolean)?,
    ): Int {
        if (!passable[j] || reached[j]) return tail
        if (canStep != null && !canStep(from, j)) return tail
        reached[j] = true
        queue[tail] = j
        return tail + 1
    }

    /**
     * Fills holes of [mask] in place: components of the complement that do not touch the image
     * border and have at most [maxHoleSize] pixels become part of the mask.
     */
    fun fillHoles(mask: BooleanArray, w: Int, h: Int, maxHoleSize: Int) {
        val inv = BooleanArray(mask.size) { !mask[it] }
        val outside = floodFrom(inv, w, h) { x, y -> x == 0 || y == 0 || x == w - 1 || y == h - 1 }
        val holes = BooleanArray(mask.size) { inv[it] && !outside[it] }
        val lab = label(holes, w, h, eightConnected = false)
        for (i in mask.indices) {
            val id = lab.ids[i]
            if (id != 0 && lab.sizes[id] <= maxHoleSize) mask[i] = true
        }
    }
}

package com.brushwork.paint.diagnostics

/**
 * v1.9 (R9, design 2.4.13; owner A7a): the last [SIZE] things the app did, for a crash report.
 * Never personal data: tool ids, sizes, counts. F1b calls it at a tool switch (`tool:<id>`),
 * opening a canvas (`open:<w>x<h>/<layers>`), a filter's start and apply (`filter:<id>`), a save's
 * start and end, an export and onTrimMemory (`trim:<level>`).
 */
object Breadcrumbs {
    const val SIZE = 32

    private val ring = arrayOfNulls<String>(SIZE)
    private var next = 0
    private var count = 0

    /** Thread-safe ring. Never personal data: tool ids, sizes, counts. */
    fun add(event: String) {
        synchronized(ring) {
            ring[next] = event
            next = (next + 1) % SIZE
            if (count < SIZE) count++
        }
    }

    /** The events still in the ring, oldest first. */
    fun snapshot(): List<String> = synchronized(ring) {
        List(count) { i -> ring[(next - count + i + SIZE) % SIZE]!! }
    }
}

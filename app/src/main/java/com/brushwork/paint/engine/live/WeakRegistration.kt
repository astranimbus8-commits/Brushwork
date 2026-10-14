package com.brushwork.paint.engine.live

import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * v1.9 A6 (design 2.4.3): [MemoryGovernor] registrations that hold their owner weakly. The
 * governor's registry is global, so a plain entry would keep an editor (its document, its caches)
 * alive until someone closed it. Here an owner that is collected without closing its registration
 * reads as 0 bytes and frees nothing, and its entry goes at the next [register]. Owners still
 * close their registrations when their caches go.
 */
internal object WeakRegistration {
    private val gone = ReferenceQueue<Any>()

    private class Ref(owner: Any) : WeakReference<Any>(owner, gone) {
        @Volatile var registration: MemoryGovernor.Registration? = null
    }

    /**
     * Registers [owner]'s memory as [name] at [priority]: [bytes] is what it holds now, [trim] what
     * it frees under a pressure (returning the bytes freed). Both are handed the owner and must not
     * capture it themselves.
     */
    fun <T : Any> register(
        owner: T,
        name: String,
        priority: Int,
        bytes: (T) -> Long,
        trim: (T, MemoryPressure) -> Long,
    ): MemoryGovernor.Registration {
        while (true) (gone.poll() as Ref? ?: break).registration?.close()
        val ref = Ref(owner)
        @Suppress("UNCHECKED_CAST")
        val registration = MemoryGovernor.register(
            name,
            priority,
            bytes = { (ref.get() as T?)?.let(bytes) ?: 0L },
            trim = { pressure -> (ref.get() as T?)?.let { trim(it, pressure) } ?: 0L },
        )
        ref.registration = registration
        return registration
    }
}

package com.voztranslate.app.recording

import java.util.concurrent.atomic.AtomicBoolean

/** Serializes capture admission (including STARTING) with destructive session storage work. */
internal object SessionOperationGate {
    private enum class Owner { CAPTURE, STORAGE }

    private val lock = Any()
    private var owner: Owner? = null
    private var ownerToken = 0L

    fun tryAcquireCapture(): Lease? = acquire(Owner.CAPTURE)

    fun tryAcquireStorage(): Lease? = acquire(Owner.STORAGE)

    private fun acquire(requestedOwner: Owner): Lease? = synchronized(lock) {
        if (owner != null) return@synchronized null
        val token = ++ownerToken
        owner = requestedOwner
        Lease {
            synchronized(lock) {
                if (ownerToken == token && owner == requestedOwner) owner = null
            }
        }
    }

    class Lease internal constructor(private val releaseOwner: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) releaseOwner()
        }
    }
}

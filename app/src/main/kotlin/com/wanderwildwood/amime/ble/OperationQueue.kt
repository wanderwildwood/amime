package com.wanderwildwood.amime.ble

/**
 * Runs GATT operations one at a time.
 *
 * Android's BLE stack accepts exactly one outstanding operation per connection. A second
 * write issued before the first has called back does not queue and does not throw — it
 * returns false and is dropped, and the frame with it. Every write, descriptor write and MTU
 * request therefore goes through here.
 *
 * Kept free of Android types so the ordering can be tested without a radio: an operation is
 * any function that starts something and returns whether it started.
 */
class OperationQueue(
    /** Told when an operation could not even be started, so a caller can give up on it. */
    private val onStartFailed: (Operation) -> Unit = {},
) {

    /**
     * One GATT call. Returns what the Android API returned: false means the stack refused to
     * start it, and no callback is coming.
     */
    fun interface Operation {
        fun start(): Boolean
    }

    private val pending = ArrayDeque<Operation>()
    private var running: Operation? = null

    val isBusy: Boolean get() = running != null
    val depth: Int get() = pending.size + if (running == null) 0 else 1

    /** Add an operation, starting it if nothing else is in flight. */
    fun enqueue(operation: Operation) {
        pending.addLast(operation)
        if (running == null) startNext()
    }

    /**
     * Called from a GATT callback when the operation in flight has finished, however it
     * finished. A failure still completes: the alternative is a queue that never moves again
     * because one write was rejected by the far end.
     */
    fun completeCurrent() {
        running = null
        startNext()
    }

    /** Drop everything, on disconnect. Nothing queued survives a connection. */
    fun clear() {
        pending.clear()
        running = null
    }

    private fun startNext() {
        while (running == null) {
            val next = pending.removeFirstOrNull() ?: return
            running = next
            if (!next.start()) {
                // No callback is coming for an operation that never started, so completing it
                // here is the only thing that keeps the queue moving.
                running = null
                onStartFailed(next)
            }
        }
    }
}

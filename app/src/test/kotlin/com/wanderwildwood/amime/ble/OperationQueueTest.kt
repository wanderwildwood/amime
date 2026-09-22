package com.wanderwildwood.amime.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationQueueTest {

    @Test
    fun `a second operation waits for the first to call back`() {
        val started = mutableListOf<String>()
        val queue = OperationQueue()

        queue.enqueue { started += "a"; true }
        queue.enqueue { started += "b"; true }

        assertEquals("b must not start while a is in flight", listOf("a"), started)
        queue.completeCurrent()
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun `completing advances only one step`() {
        val started = mutableListOf<String>()
        val queue = OperationQueue()
        listOf("a", "b", "c").forEach { name -> queue.enqueue { started += name; true } }

        assertEquals(listOf("a"), started)
        queue.completeCurrent()
        assertEquals(listOf("a", "b"), started)
        queue.completeCurrent()
        assertEquals(listOf("a", "b", "c"), started)
    }

    /**
     * The failure that wedges a BLE connection forever: an operation the stack refuses never
     * calls back, so if the queue waits for a callback it waits for good.
     */
    @Test
    fun `an operation that never starts does not wedge the queue`() {
        val started = mutableListOf<String>()
        val queue = OperationQueue()

        queue.enqueue { started += "refused"; false }
        queue.enqueue { started += "next"; true }

        assertEquals(listOf("refused", "next"), started)
        assertTrue("the second operation should now be in flight", queue.isBusy)
    }

    @Test
    fun `a refusal is reported so the caller can give up on that frame`() {
        val refused = mutableListOf<OperationQueue.Operation>()
        val queue = OperationQueue(onStartFailed = { refused += it })

        queue.enqueue { false }
        assertEquals(1, refused.size)
        assertFalse(queue.isBusy)
    }

    /** A run of refusals should drain, not recurse or stall halfway. */
    @Test
    fun `consecutive refusals all drain`() {
        var attempts = 0
        val queue = OperationQueue()
        repeat(5) { queue.enqueue { attempts++; false } }

        assertEquals(5, attempts)
        assertFalse(queue.isBusy)
        assertEquals(0, queue.depth)
    }

    @Test
    fun `nothing queued survives a disconnect`() {
        val started = mutableListOf<String>()
        val queue = OperationQueue()
        queue.enqueue { started += "a"; true }
        queue.enqueue { started += "b"; true }

        queue.clear()
        queue.completeCurrent()

        assertEquals("b belonged to the old connection", listOf("a"), started)
        assertEquals(0, queue.depth)
    }

    @Test
    fun `depth counts the one in flight as well as those waiting`() {
        val queue = OperationQueue()
        assertEquals(0, queue.depth)
        queue.enqueue { true }
        assertEquals(1, queue.depth)
        queue.enqueue { true }
        assertEquals(2, queue.depth)
        queue.completeCurrent()
        assertEquals(1, queue.depth)
    }

    // ---- an operation that never calls back ----

    /**
     * Android's GATT stack does not always call back. A write to a link that is failing but
     * not yet declared dead can simply never complete, and everything here waits on the one
     * in flight — so without a way out the queue stops for good, while the app goes on
     * looking connected.
     */
    @Test
    fun `an operation that never answers can be given up on`() {
        val started = mutableListOf<Long>()
        val queue = OperationQueue(onStarted = { started += it })
        val ran = mutableListOf<String>()
        queue.enqueue { ran += "first"; true }
        queue.enqueue { ran += "second"; true }

        assertEquals(listOf("first"), ran)
        assertTrue(queue.isBusy)

        assertTrue(queue.abandonIfStuck(started.first()))

        assertEquals(listOf("first", "second"), ran)
    }

    /**
     * By the time a watchdog looks, the operation it was watching may have finished and
     * three more may have come and gone. Abandoning whatever happens to be running then
     * would throw away a perfectly healthy one.
     */
    @Test
    fun `a watchdog for an operation that already finished abandons nothing`() {
        val started = mutableListOf<Long>()
        val queue = OperationQueue(onStarted = { started += it })
        queue.enqueue { true }
        val first = started.first()
        queue.completeCurrent()
        queue.enqueue { true }

        assertFalse(queue.abandonIfStuck(first))

        // The one that is genuinely running is untouched.
        assertTrue(queue.isBusy)
    }

    @Test
    fun `an idle queue has nothing to give up on`() {
        val queue = OperationQueue()
        assertFalse(queue.abandonIfStuck(1))
    }

}

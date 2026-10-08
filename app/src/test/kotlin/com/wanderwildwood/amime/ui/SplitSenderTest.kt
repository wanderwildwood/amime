package com.wanderwildwood.amime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitSenderTest {

    @Test
    fun `the sender is everything before the first colon and space`() {
        assertEquals("Ada Whitlock" to "time: 9:30", splitSender("Ada Whitlock: time: 9:30"))
    }

    @Test
    fun `text without a sender is all words`() {
        assertEquals(null to "no name here", splitSender("no name here"))
        assertEquals(null to ": starts oddly", splitSender(": starts oddly"))
    }
}

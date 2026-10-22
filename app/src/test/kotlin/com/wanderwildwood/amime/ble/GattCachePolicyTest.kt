package com.wanderwildwood.amime.ble

import com.wanderwildwood.amime.ble.GattCachePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class GattCachePolicyTest {
    @Test fun invalidHandleRetriesOnce() {
        assertEquals(Verdict.RETRY, GattCachePolicy.decide(1, 0))
    }

    @Test fun invalidHandleTwiceGivesUpWithHint() {
        assertEquals(Verdict.GIVE_UP_WITH_HINT, GattCachePolicy.decide(1, 1))
    }

    @Test fun missingServiceCountsAsStale() {
        assertEquals(Verdict.RETRY, GattCachePolicy.decide(null, 0))
        assertEquals(Verdict.GIVE_UP_WITH_HINT, GattCachePolicy.decide(null, 1))
    }

    @Test fun otherErrorsAreUnchanged() {
        for (status in listOf(3, 5, 8, 133, 257)) {
            assertEquals(Verdict.GIVE_UP, GattCachePolicy.decide(status, 0))
            assertEquals(Verdict.GIVE_UP, GattCachePolicy.decide(status, 1))
        }
    }
}

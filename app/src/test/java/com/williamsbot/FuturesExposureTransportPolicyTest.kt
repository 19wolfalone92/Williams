package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesExposureTransportPolicyTest {
    @Test fun connectionOrWorkerLossRevokesEntryEvenWithRecentSignedSnapshot() {
        val now = 1_800_000_100_000L
        assertNull(futuresExposureTransportBlocker(true, false, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(false, false, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, true, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, false, false, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, true, now - 1L, now))
    }

    @Test fun completingASlowAuditCannotRenewTheAgeOfItsOldAccountSnapshot() {
        val started = 1_800_000_000_000L
        val completed = started + 95_000L
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, started, completed))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, 0L, completed))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, completed + 1L, completed))
        assertNull(futuresExposureTransportBlocker(true, false, true, false, started, started + 90_000L))
    }
}

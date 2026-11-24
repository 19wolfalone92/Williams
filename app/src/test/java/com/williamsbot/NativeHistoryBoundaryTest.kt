package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeHistoryBoundaryTest {
    private fun ms(s:String) = Instant.parse(s).toEpochMilli()
    @Test fun monthBoundaryUsesCalendarNotThirtyDays() {
        assertEquals(ms("2024-03-01T00:00:00Z")-1L,
            nativeHistoryCloseMs(ms("2024-02-01T00:00:00Z"),"1M"))
        assertEquals(ms("2026-03-01T00:00:00Z")-1L,
            nativeHistoryCloseMs(ms("2026-02-01T00:00:00Z"),"1M"))
        assertNull(nativeHistoryCloseMs(ms("2026-02-02T00:00:00Z"),"1M"))
    }
    @Test fun weeklyMondayAndFixedFrameAlignment() {
        assertEquals(ms("2026-11-30T00:00:00Z")-1L,
            nativeHistoryCloseMs(ms("2026-11-23T00:00:00Z"),"1w"))
        assertNull(nativeHistoryCloseMs(ms("2026-11-24T00:00:00Z"),"1w"))
        assertNull(nativeHistoryCloseMs(ms("2026-11-24T10:15:00Z"),"1h"))
        assertEquals(ms("2026-11-24T11:00:00Z")-1L,
            nativeHistoryCloseMs(ms("2026-11-24T10:00:00Z"),"1h"))
    }
}

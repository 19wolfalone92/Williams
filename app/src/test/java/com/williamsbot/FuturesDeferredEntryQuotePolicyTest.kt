package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesDeferredEntryQuotePolicyTest {
    @Test fun deferredStopArmingDoesNotDependOnCurrentBidAsk() {
        for (spread in listOf(0.01, 0.0043751709, 0.50, Double.NaN, Double.POSITIVE_INFINITY)) {
            // There is no executable market order at the instant of arming.
            assertNull(futuresEntryQuoteBlocker(
                NativeFuturesCampaignCoordinator.EntryKind.STOP, 100.0,
                100.0 * (1 + spread), 0.0015))
        }
        assertNull(futuresEntryQuoteBlocker(
            NativeFuturesCampaignCoordinator.EntryKind.STOP, null, null, 0.0015))
        assertNull(futuresEntryQuoteBlocker(
            NativeFuturesCampaignCoordinator.EntryKind.STOP, -1.0, 0.0, 0.0015))
    }

    @Test fun immediateMarketStillNeedsExecutableBidAskBound() {
        val market = NativeFuturesCampaignCoordinator.EntryKind.MARKET
        assertNull(futuresEntryQuoteBlocker(market, 100.0, 100.02, 0.0015))
        assertEquals("FUTURES_ENTRY_SPREAD_BLOCKED",
            futuresEntryQuoteBlocker(market, 100.0, 100.5, 0.0015))
        for ((bid, ask) in listOf(
            null to null, 100.0 to 99.0, 0.0 to 101.0,
            Double.NaN to 101.0, 100.0 to Double.POSITIVE_INFINITY)) {
            assertEquals("FUTURES_ENTRY_TOP_OF_BOOK_UNVERIFIED",
                futuresEntryQuoteBlocker(market, bid, ask, 0.0015))
        }
        assertEquals("FUTURES_ENTRY_TOP_OF_BOOK_UNVERIFIED",
            futuresEntryQuoteBlocker(market, 100.0, 100.02, Double.NaN))
    }

    @Test fun otherKindsNeverInheritTheStopArmingExemption() {
        for(other in listOf(
            NativeFuturesCampaignCoordinator.EntryKind.LIMIT,
            NativeFuturesCampaignCoordinator.EntryKind.TAKE_PROFIT)) {
            assertEquals("FUTURES_ENTRY_QUOTE_KIND_UNSUPPORTED",
                futuresEntryQuoteBlocker(other, 100.0, 100.02, 0.0015))
        }
    }
}

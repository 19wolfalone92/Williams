package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class FuturesEntryPriceParityTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test fun signedVwapAndPositionEntryCanDifferByConservativeExchangeRounding() {
        assertTrue(futuresEntryPriceMatchesSignedFill(bd("56.2100"), bd("56.21"), null))
        assertTrue(futuresEntryPriceMatchesSignedFill(bd("56.214"), bd("56.21"), bd("0.01")))
        assertTrue(futuresEntryPriceMatchesSignedFill(bd("56.206"), bd("56.21"), bd("0.01")))
        assertTrue(futuresEntryPriceMatchesSignedFill(bd("101"), bd("101.04"), bd("0.1")))
        assertTrue(futuresEntryPriceMatchesSignedFill(bd("99"), bd("98.96"), bd("0.1")))
    }

    @Test fun unknownPrecisionOrLargeDriftCannotAuthorizeCampaignManagement() {
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("56.214"), bd("56.21"), null))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("56.214"), bd("56.21"), bd("0")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("56.214"), bd("56.21"), bd("-0.01")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("56.21"), bd("56.24"), bd("0.01")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("101"), bd("101.08"), bd("0.1")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("101"), bd("99.0"), bd("0.1")))
        assertFalse(futuresEntryPriceMatchesSignedFill(null, bd("56.21"), bd("0.01")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("56.21"), null, bd("0.01")))
        assertFalse(futuresEntryPriceMatchesSignedFill(bd("0"), bd("0"), bd("0.01")))
    }
}

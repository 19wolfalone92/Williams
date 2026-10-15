package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionOwnershipTest {
    @Test
    fun onlyExplicitBinanceOrderNotFoundResponsesAreClassifiedAsAbsent() {
        assertTrue(isDefinitiveBinanceOrderNotFound(
            java.io.IOException("""Binance USD-M Futures HTTP 400: {"code":-2013,"msg":"Order does not exist."}""")
        ))
        assertTrue(isDefinitiveBinanceOrderNotFound(
            java.io.IOException("""Binance USD-M Futures HTTP 400: {"code":-2011,"msg":"Unknown order sent."}""")
        ))
        assertFalse(isDefinitiveBinanceOrderNotFound(
            java.io.IOException("timeout while querying order")
        ))
        assertFalse(isDefinitiveBinanceOrderNotFound(
            java.io.IOException("""Binance USD-M Futures HTTP 500: {"code":-1000,"msg":"Internal error"}""")
        ))
    }


    @Test
    fun emergencyCloseRequiresCurrentProtectedCampaignAndMatchingDirection() {
        assertTrue(isManagedOpenPositionIntent(
            "OPEN_PROTECTED", "LONG", "LONG", "PROTECTED", "W2PS_current"
        ))
        assertFalse(isManagedOpenPositionIntent(
            "CLOSED_OR_STOPPED", "LONG", "LONG", "PROTECTED", "W2PS_current"
        ))
        assertFalse(isManagedOpenPositionIntent(
            "OPEN_PROTECTED", "SHORT", "LONG", "PROTECTED", "W2PS_current"
        ))
        assertFalse(isManagedOpenPositionIntent(
            "OPEN_PROTECTED", "LONG", "LONG", "SUBMISSION_UNKNOWN", "W2PS_current"
        ))
        assertFalse(isManagedOpenPositionIntent(
            "OPEN_PROTECTED", "LONG", "LONG", "PROTECTED", ""
        ))
    }

    @Test
    fun onlyCurrentOrExplicitlyTrackedPredecessorProtectionIsBotOwned() {
        assertTrue(isOwnedProtectionClientId("W2PS_current", "W2PS_current", ""))
        assertTrue(isOwnedProtectionClientId("W2PS_previous", "W2PS_current", "W2PS_previous"))
        assertFalse(isOwnedProtectionClientId("manual-user-stop", "W2PS_current", "W2PS_previous"))
        assertFalse(isOwnedProtectionClientId("", "W2PS_current", "W2PS_previous"))
    }

    @Test
    fun filledExchangeOrderDoesNotMeanCampaignIsClosed() {
        val legacyFilled = JSONObject().put("exchange_status", "FILLED")
        assertEquals("", campaignLifecycleStatus(legacyFilled))
        assertTrue("Ambiguous legacy FILLED intent must reserve the campaign slot",
            isEntryCampaignReserved(legacyFilled))

        val confirmedEntry = JSONObject()
            .put("exchange_status", "FILLED")
            .put("order_status", "FILLED")
            .put("campaign_status", "FILL_CONFIRMATION_PENDING")
        assertEquals("FILL_CONFIRMATION_PENDING", campaignLifecycleStatus(confirmedEntry))
        assertTrue(isEntryCampaignReserved(confirmedEntry))

        val closed = JSONObject()
            .put("exchange_status", "FILLED")
            .put("order_status", "FILLED")
            .put("campaign_status", "CLOSED_OR_STOPPED")
        assertEquals("CLOSED_OR_STOPPED", campaignLifecycleStatus(closed))
        assertFalse(isEntryCampaignReserved(closed))

        val ambiguousExit = JSONObject()
            .put("intent_role", "EXIT")
            .put("exchange_status", "FILLED")
        assertTrue("An exit marked FILLED is not terminal until flatness is confirmed",
            isEntryCampaignReserved(ambiguousExit))
        ambiguousExit.put("exchange_status", "EXIT_CONFIRMED")
        assertFalse(isEntryCampaignReserved(ambiguousExit))

        val invalidLifecycle = JSONObject()
            .put("exchange_status", "FILLED")
            .put("campaign_status", "UNKNOWN_CORRUPT_STATE")
        assertTrue("Unknown campaign lifecycle must reserve the slot",
            isEntryCampaignReserved(invalidLifecycle))
    }

    @Test
    fun newestCampaignIsAuthoritativeEvenWhenOldCampaignWasProtected() {
        val intents = JSONArray()
            .put(JSONObject()
                .put("intent_role", "ENTRY")
                .put("symbol", "BTCUSDT")
                .put("direction", "LONG")
                .put("campaign_status", "OPEN_PROTECTED")
                .put("protection_status", "PROTECTED")
                .put("protection_client_id", "W2PS_old"))
            .put(JSONObject()
                .put("intent_role", "ENTRY")
                .put("symbol", "BTCUSDT")
                .put("direction", "LONG")
                .put("campaign_status", "CLOSED_OR_STOPPED")
                .put("protection_status", "PROTECTED")
                .put("protection_client_id", "W2PS_new"))
        assertEquals(1, latestEntryIntentIndex(intents, "BTCUSDT"))
        val latest = intents.getJSONObject(latestEntryIntentIndex(intents, "BTCUSDT")!!)
        assertFalse(isManagedOpenPositionIntent(
            campaignLifecycleStatus(latest),
            latest.optString("direction"),
            "LONG",
            latest.optString("protection_status"),
            latest.optString("protection_client_id")
        ))
        assertNull(latestEntryIntentIndex(intents, "ETHUSDT"))
    }

    @Test
    fun orderOwnershipIsScopedToTheSymbol() {
        val owners = mapOf("BTCUSDT" to setOf("same-client-id"), "ETHUSDT" to setOf("other-id"))
        assertTrue(isOwnedClientOrderForSymbol("BTCUSDT", "same-client-id", owners))
        assertFalse(isOwnedClientOrderForSymbol("ETHUSDT", "same-client-id", owners))
        assertFalse(isOwnedClientOrderForSymbol("BTCUSDT", "", owners))
    }

    @Test
    fun profitLockingTrailingStopsMayCrossEntryButMustRemainBeyondLiveMark() {
        // A long stop moved above entry is still protective while mark remains higher.
        assertTrue(isProtectiveStopOnCorrectSide("LONG", "110", "105"))
        assertFalse(isProtectiveStopOnCorrectSide("LONG", "104", "105"))

        // A short stop moved below entry is still protective while mark remains lower.
        assertTrue(isProtectiveStopOnCorrectSide("SHORT", "90", "95"))
        assertFalse(isProtectiveStopOnCorrectSide("SHORT", "96", "95"))

        assertFalse(isProtectiveStopOnCorrectSide("FLAT", "100", "95"))
        assertFalse(isProtectiveStopOnCorrectSide("LONG", "0", "95"))
    }

    @Test
    fun missingOrUnknownExchangeAlgoStatusNeverCountsAsConfirmedProtection() {
        assertTrue(isActiveFuturesProtectionStatus("NEW"))
        assertTrue(isActiveFuturesProtectionStatus("WORKING"))
        assertFalse(isActiveFuturesProtectionStatus(""))
        assertFalse(isActiveFuturesProtectionStatus("UNKNOWN"))
        assertFalse(isActiveFuturesProtectionStatus("TRIGGERED"))
        assertFalse(isActiveFuturesProtectionStatus("CANCELED"))
        assertFalse(isActiveFuturesProtectionStatus("EXPIRED"))
    }

    @Test
    fun exchangeProtectionRequiresExactIdDirectionTriggerClosePositionAndActiveStatus() {
        val validLong = JSONObject()
            .put("type", "STOP_MARKET")
            .put("side", "SELL")
            .put("closePosition", true)
            .put("algoStatus", "NEW")
            .put("clientAlgoId", "W2PS_BTC_current")
            .put("triggerPrice", "95.00")
        assertTrue(matchesConfirmedFuturesProtectiveStop(
            validLong, "LONG", "W2PS_BTC_current", "95"
        ))
        assertFalse(matchesConfirmedFuturesProtectiveStop(
            JSONObject(validLong.toString()).put("side", "BUY"),
            "LONG", "W2PS_BTC_current", "95"
        ))
        assertFalse(matchesConfirmedFuturesProtectiveStop(
            JSONObject(validLong.toString()).put("closePosition", false),
            "LONG", "W2PS_BTC_current", "95"
        ))
        assertFalse(matchesConfirmedFuturesProtectiveStop(
            JSONObject(validLong.toString()).put("triggerPrice", "94.99"),
            "LONG", "W2PS_BTC_current", "95"
        ))
        assertFalse(matchesConfirmedFuturesProtectiveStop(
            JSONObject(validLong.toString()).put("clientAlgoId", "manual-stop"),
            "LONG", "W2PS_BTC_current", "95"
        ))
        assertFalse(matchesConfirmedFuturesProtectiveStop(
            JSONObject(validLong.toString()).put("algoStatus", ""),
            "LONG", "W2PS_BTC_current", "95"
        ))
        val validShort = JSONObject()
            .put("type", "STOP_MARKET")
            .put("side", "BUY")
            .put("closePosition", true)
            .put("status", "WORKING")
            .put("clientAlgoId", "W2PS_ETH_current")
            .put("stopPrice", "105")
        assertTrue(matchesConfirmedFuturesProtectiveStop(
            validShort, "SHORT", "W2PS_ETH_current", "105.0"
        ))
    }

    @Test
    fun structuralStopMustBeOnTheCorrectSideForDirection() {
        assertTrue(hasValidStructuralStop("LONG", "100", "95"))
        assertFalse(hasValidStructuralStop("LONG", "100", "100"))
        assertFalse(hasValidStructuralStop("LONG", "100", "105"))
        assertTrue(hasValidStructuralStop("SHORT", "100", "105"))
        assertFalse(hasValidStructuralStop("SHORT", "100", "95"))
        assertFalse(hasValidStructuralStop("FLAT", "100", "95"))
    }
    @Test
    fun filledEntryRequiresAStableFlatWindowAndNoUnresolvedExitBeforeTerminalization() {
        assertFalse(shouldFinalizeFilledFlatCampaign(
            "FILL_CONFIRMATION_PENDING", true, false, 0L, 10_000L
        ))
        assertFalse(shouldFinalizeFilledFlatCampaign(
            "FILL_CONFIRMATION_PENDING", true, true, 1_000L, 10_000L
        ))
        assertFalse(shouldFinalizeFilledFlatCampaign(
            "FILL_CONFIRMATION_PENDING", false, false, 1_000L, 10_000L
        ))
        assertFalse(shouldFinalizeFilledFlatCampaign(
            "FILL_CONFIRMATION_PENDING", true, false, 10_000L, 9_000L
        ))
        assertFalse(shouldFinalizeFilledFlatCampaign(
            "POSITION_OPEN", true, false, 1_000L, 10_000L
        ))
        assertTrue(shouldFinalizeFilledFlatCampaign(
            "FILL_CONFIRMATION_PENDING", true, false, 1_000L, 4_000L
        ))
    }
}

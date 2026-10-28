package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesProtectionDisplaySnapshotTest {
    private class Fixture(val direction: String = "LONG") {
        var now = 1_000_000L
        var positionReads = 0
        var afterOrders: () -> Unit = {}
        val position = JSONObject().put("symbol", "BNBUSDT").put("positionSide", "BOTH")
            .put("positionAmt", if (direction == "LONG") "0.21" else "-0.21")
            .put("entryPrice", "749.24").put("markPrice", "754.38")
        val order = JSONObject().put("symbol", "BNBUSDT").put("positionSide", "BOTH")
            .put("algoId", "123").put("clientAlgoId", "W2PS_BNB_owner")
            .put("orderType", "STOP_MARKET").put("algoStatus", "NEW")
            .put("side", if (direction == "LONG") "SELL" else "BUY")
            .put("workingType", "MARK_PRICE").put("closePosition", true)
            .put("triggerPrice", if (direction == "LONG") "742.67" else "760.00")
        var orders = JSONArray().put(order)
        val proof = JSONObject().put("symbol", "BNBUSDT").put("direction", direction)
            .put("quantity", position.getString("positionAmt"))
            .put("stop", order.getString("triggerPrice"))
            .put("protection_client_id", order.getString("clientAlgoId"))
            .put("observed_at_ms", now - 128_000L).put("stop_proof", "OWNED_STOP_SEEN")
            .put("strategy_ownership_verified", true).put("automatic_trailing_authorized", true)
            .put("management_mode", "STRATEGY_OWNED")
        val owner = JSONObject().put("symbol", "BNBUSDT").put("direction", direction)
            .put("intent_role", "ENTRY").put("client_id", "original-owner")
            .put("campaign_status", "OPEN_PROTECTED").put("strategy_ownership_verified", true)
            .put("protection_client_id", order.getString("clientAlgoId"))
            .put("confirmed_position_qty", "0.21").put("actual_entry_price", "749.24")
        var contract = 754.38
        var contractReads = 0
        fun read(): FuturesProtectionDisplaySnapshot = readFuturesProtectionDisplaySnapshot(
            readPositions = { positionReads++; JSONArray().put(JSONObject(position.toString())) },
            readOpenAlgos = { JSONArray(orders.toString()).also { afterOrders() } },
            contractPrice = { contractReads++; contract },
            trustedObservations = JSONArray().put(proof), intents = JSONArray().put(owner), clock = { now }
        )
    }

    @Test fun archivedStaleProofIsReplacedByBracketedSignedReadsInBothDirections() {
        for (direction in listOf("LONG", "SHORT")) {
            val f = Fixture(direction)
            val old = f.proof.toString()
            val result = f.read()
            val proof = result.observations.getJSONObject(0)
            assertEquals(2, f.positionReads)
            assertEquals(f.now, proof.getLong("observed_at_ms"))
            assertEquals(f.now, result.observedAtMs)
            assertEquals("OWNED_STOP_SEEN", proof.getString("stop_proof"))
            assertTrue(proof.getBoolean("strategy_ownership_verified"))
            assertEquals(old, f.proof.toString()) // execution evidence is never republished
        }
    }

    @Test fun freshStopWithoutDurableOwnershipStaysExternal() {
        for (mutate in listOf<(Fixture) -> Unit>(
            { it.owner.put("strategy_ownership_verified", false) },
            { it.owner.put("actual_entry_price", "748") },
            { it.owner.put("confirmed_position_qty", "0.22") },
            { it.owner.put("protection_client_id", "another-stop") },
            { it.owner.put("native_stop_replacement", JSONObject()) },
            { it.proof.put("protection_client_id", "another-stop") },
            { it.proof.put("quantity", "0.22") },
            { it.proof.put("strategy_ownership_verified", false) }
        )) {
            val f = Fixture(); mutate(f)
            val proof = f.read().observations.getJSONObject(0)
            assertEquals(proof.toString(), "EXTERNAL_PROTECTED", proof.getString("stop_proof"))
            assertFalse(proof.getBoolean("strategy_ownership_verified"))
            assertFalse(proof.getBoolean("automatic_trailing_authorized"))
        }
    }

    @Test fun stopMissingCanceledWrongSideOrInsufficientQuantityCannotPass() {
        for (mutate in listOf<(Fixture) -> Unit>(
            { it.orders = JSONArray() },
            { it.order.put("algoStatus", "CANCELED") },
            { it.order.put("side", "BUY") },
            { it.order.put("triggerPrice", "760") },
            { it.order.put("closePosition", false).put("reduceOnly", true).put("quantity", "0.20") }
        )) {
            val f = Fixture(); mutate(f)
            val proof = f.read().observations.getJSONObject(0)
            assertEquals("STOP_UNCONFIRMED", proof.getString("stop_proof"))
            assertFalse(proof.getBoolean("automatic_trailing_authorized"))
        }
    }

    @Test fun duplicateFullStopsRemainUnresolved() {
        val f = Fixture()
        f.orders.put(JSONObject(f.order.toString()).put("algoId", "124").put("clientAlgoId", "another"))
        val proof = f.read().observations.getJSONObject(0)
        assertEquals("MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED", proof.getString("stop_proof"))
        assertFalse(proof.getBoolean("strategy_ownership_verified"))
    }

    @Test fun contractStopUsesContractPriceRatherThanMark() {
        val f = Fixture()
        f.order.put("workingType", "CONTRACT_PRICE")
        f.contract = 740.0 // MARK is above the STOP, CONTRACT is already below it
        assertEquals("STOP_UNCONFIRMED", f.read().observations.getJSONObject(0).getString("stop_proof"))
        assertEquals(1, f.contractReads)
    }

    @Test fun aFillReductionReversalOrChangedEntryDuringReadRejectsWholeGeneration() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("positionAmt", "0.22") }, { it.put("positionAmt", "0.20") },
            { it.put("positionAmt", "-0.21") }, { it.put("positionAmt", "0") },
            { it.put("entryPrice", "750") }
        )) {
            val f = Fixture(); f.afterOrders = { mutate(f.position) }
            try { f.read(); fail("Mixed exposure accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("exposure changed")) }
        }
    }

    @Test fun slowAndBackwardsClockCannotStampOldEvidenceAsNew() {
        for (elapsed in listOf(15_001L, -1L)) {
            val f = Fixture(); f.afterOrders = { f.now += elapsed }
            try { f.read(); fail("Invalid snapshot clock accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("freshness")) }
        }
    }

    @Test fun requestStartTimeIsRetainedAndPriceFluctuationDoesNotChangeExposure() {
        val f = Fixture(); val started = f.now
        f.afterOrders = { f.now += 3_000; f.position.put("markPrice", "755") }
        val result = f.read()
        assertEquals(started, result.observedAtMs)
        assertEquals(started, result.observations.getJSONObject(0).getLong("observed_at_ms"))
        assertEquals("755", result.positions.getJSONObject(0).getString("markPrice"))
    }

    @Test(expected = IllegalStateException::class) fun malformedAlgoRowsCannotLookLikeNoStops() {
        val f = Fixture(); f.orders = JSONArray().put("invalid"); f.read()
    }

    @Test(expected = IllegalStateException::class) fun hedgePositionCannotBeDisplayedAsOneWay() {
        val f = Fixture(); f.position.put("positionSide", "LONG"); f.read()
    }

    @Test fun signedReadFailureDoesNotChangeOriginalOwnershipProof() {
        val f = Fixture(); val before = f.proof.toString()
        f.afterOrders = { throw java.io.IOException("read failed") }
        try { f.read(); fail("Failed GET accepted") } catch (_: java.io.IOException) { }
        assertEquals(before, f.proof.toString())
    }
}

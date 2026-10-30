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
        var symbolScopedReads = 0
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
        var supervisions = JSONArray()
        var standardOrders = JSONArray()
        fun read(): FuturesProtectionDisplaySnapshot = readFuturesProtectionDisplaySnapshot(
            readPositions = { positionReads++; JSONArray().put(JSONObject(position.toString())) },
            readOpenAlgosForSymbol = { symbol ->
                assertEquals("BNBUSDT", symbol)
                symbolScopedReads++
                JSONArray(orders.toString()).also { afterOrders() }
            },
            contractPrice = { contractReads++; contract },
            trustedObservations = JSONArray().put(proof), intents = JSONArray().put(owner), clock = { now },
            supervisions = supervisions, readOpenOrders = { standardOrders }
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

    @Test fun onlyLiveSymbolStopsAreSignedAndFailureCauseIsExplicit() {
        val f=Fixture()
        val verified=f.read().observations.getJSONObject(0)
        assertEquals(1,f.symbolScopedReads)
        assertEquals(1,verified.getInt("signed_algo_orders_seen"))
        assertEquals(1,verified.getInt("signed_full_stops_seen"))
        assertTrue(verified.isNull("stop_proof_rejection"))

        f.orders=JSONArray()
        val missing=f.read().observations.getJSONObject(0)
        assertEquals("STOP_UNCONFIRMED",missing.getString("stop_proof"))
        assertEquals("NO_OPEN_ALGO_ORDERS_RETURNED_FOR_SYMBOL",
            missing.getString("stop_proof_rejection"))
        assertEquals(0,missing.getInt("signed_algo_orders_seen"))
        assertFalse(missing.getBoolean("automatic_trailing_authorized"))

        f.orders=JSONArray().put(JSONObject(f.order.toString()).put("algoStatus","CANCELED"))
        val canceled=f.read().observations.getJSONObject(0)
        assertEquals("SIGNED_ALGOS_PRESENT_BUT_NOT_FULL_POSITION_PROTECTIVE_STOP",
            canceled.getString("stop_proof_rejection"))
        assertEquals("STOP_UNCONFIRMED",canceled.getString("stop_proof"))
    }

    @Test fun malformedOrForeignSymbolSignedAlgoMustNeverVerifyStop() {
        val f=Fixture()
        f.orders=JSONArray().put(JSONObject(f.order.toString()).put("symbol","BTCUSDT"))
        try { f.read(); fail("Foreign symbol accepted") }
        catch (expected:IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("foreign/missing symbol"))
        }
    }

    @Test fun twoLiveSymbolsMustEachHaveAnIndependentSignedStopQuery() {
        val btc=JSONObject().put("symbol","BNBUSDT").put("positionSide","BOTH")
            .put("positionAmt","0.21").put("entryPrice","749.24").put("markPrice","754.38")
        val eth=JSONObject().put("symbol","ETHUSDT").put("positionSide","BOTH")
            .put("positionAmt","-0.020").put("entryPrice","2249.73").put("markPrice","2235.78")
        val bnbStop=JSONObject().put("symbol","BNBUSDT").put("positionSide","BOTH")
            .put("clientAlgoId","BNB_STOP").put("orderType","STOP_MARKET")
            .put("algoStatus","NEW").put("side","SELL").put("workingType","MARK_PRICE")
            .put("closePosition",true).put("triggerPrice","742.67")
        val ethStop=JSONObject().put("symbol","ETHUSDT").put("positionSide","BOTH")
            .put("clientAlgoId","ETH_STOP").put("orderType","STOP_MARKET")
            .put("algoStatus","NEW").put("side","BUY").put("workingType","MARK_PRICE")
            .put("closePosition",true).put("triggerPrice","2341.45")
        val requested=mutableListOf<String>()
        val stops=mapOf("BNBUSDT" to bnbStop,"ETHUSDT" to ethStop)
        fun snapshot(readStop:(String)->JSONArray):FuturesProtectionDisplaySnapshot =
            readFuturesProtectionDisplaySnapshot(
                readPositions={JSONArray().put(btc).put(eth)},
                readOpenAlgosForSymbol=readStop,contractPrice={error("MARK_PRICE only")},
                trustedObservations=JSONArray(),intents=JSONArray(),clock={1_000_000L}
            )
        val result=snapshot { symbol ->
            requested.add(symbol)
            JSONArray().put(requireNotNull(stops[symbol]))
        }
        assertEquals(listOf("BNBUSDT","ETHUSDT"),requested)
        assertEquals(2,result.observations.length())
        for(i in 0 until 2) {
            val proof=result.observations.getJSONObject(i)
            assertEquals("EXTERNAL_PROTECTED",proof.getString("stop_proof"))
            assertEquals(1,proof.getInt("signed_full_stops_seen"))
            assertFalse(proof.getBoolean("automatic_trailing_authorized"))
        }
        try {
            snapshot { symbol ->
                if(symbol=="ETHUSDT") throw java.io.IOException("signed GET ETH failed")
                JSONArray().put(bnbStop)
            }
            fail("Accepted one protected symbol after a second symbol failed signed GET")
        } catch(expected:java.io.IOException) {
            assertTrue(expected.message.orEmpty().contains("ETH failed"))
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

    @Test fun previouslyApprovedStopOnlySupervisionRetainsItsLimitedModeWithoutOwnership() {
        for (mode in listOf("OPERATOR_APPROVED_STOP_ONLY", "AUTO_VERIFIED_STOP_ONLY")) {
            val f = Fixture()
            f.owner.put("strategy_ownership_verified", false)
            f.proof.put("stop_proof", "EXTERNAL_PROTECTED").put("strategy_ownership_verified", false)
                .put("management_mode", mode)
            val supervisor = JSONObject().put("symbol", "BNBUSDT").put("direction", "LONG")
                .put("state", "ACTIVE").put("mode", mode).put("quantity", "0.21")
                .put("entry_price", "749.24").put("stop", "742.67")
                .put("protection_client_id", f.order.getString("clientAlgoId"))
                .put("strategy_ownership_verified", false).put("market_close_authorized", false)
                .put("add_on_authorized", false)
            f.supervisions.put(supervisor)
            val result = f.read().observations.getJSONObject(0)
            assertEquals("EXTERNAL_PROTECTED", result.getString("stop_proof"))
            assertEquals(mode, result.getString("management_mode"))
            assertTrue(result.getBoolean("automatic_trailing_authorized"))
            assertFalse(result.getBoolean("strategy_ownership_verified"))
            // A new resting entry on the same symbol invalidates this limited proof.
            f.standardOrders.put(JSONObject().put("symbol", "BNBUSDT"))
            assertFalse(f.read().observations.getJSONObject(0).getBoolean("automatic_trailing_authorized"))
            f.standardOrders = JSONArray()
            supervisor.put("entry_price", "750")
            assertFalse(f.read().observations.getJSONObject(0).getBoolean("automatic_trailing_authorized"))
        }
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

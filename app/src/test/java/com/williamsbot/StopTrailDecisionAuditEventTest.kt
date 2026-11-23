package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StopTrailDecisionAuditEventTest {
    /**
     * DIAG12 device-signed reproduction: 4.64.50 repeats
     * AUTONOMOUS_ORPHAN_ANALYSIS_BLOCKED ETHUSDT: Failed requirement.
     * The H1/H4 pre-adoption event uses this legitimate phase, which the
     * historical event contract rejected before autonomous STOP-only
     * supervision could ever reach its two signed snapshots.
     */
    @Test fun diag12SignedOrphanH1H4ReviewMustBeDurableBeforeSafeAdoption() {
        for (decision in listOf("DEFER","PROPOSE_TIGHTEN_ONLY")) {
            val facts=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
                .put("direction","LONG").put("decision",decision)
                .put("blocker",if(decision=="DEFER") "NO_NEW_CONFIRMED_H1_PROTECTION_STRUCTURE" else "")
                .put("autonomous_orphan_mode","AUTO_VERIFIED_STOP_ONLY")
                .put("strategy_ownership_verified",false)
                .put("no_add_close_reverse",true)
                .put("as_of_ms",1_795_405_332_535L)
            val event=stopTrailDecisionAuditEvent("ETHUSDT","LONG",
                "AUTO_RECOVERY:ETHUSDT","CLOSED_H1_H4_REVIEW_BEFORE_ADOPTION",facts,
                recordedAtMs=1_795_405_332_535L)
            assertEquals("CLOSED_H1_H4_REVIEW_BEFORE_ADOPTION",event.getString("phase"))
            assertTrue(event.isNull("exchange_outcome"))
            assertFalse(event.getJSONObject("decision_evidence")
                .getBoolean("strategy_ownership_verified"))
        }
    }

    @Test fun diag12OrphanH1H4AdmissionAuditCannotInventOwnershipOrExchangeMutation() {
        fun evidence()=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("direction","LONG").put("decision","DEFER")
            .put("blocker","NO_NEW_CONFIRMED_H1_PROTECTION_STRUCTURE")
            .put("autonomous_orphan_mode","AUTO_VERIFIED_STOP_ONLY")
            .put("strategy_ownership_verified",false)
            .put("no_add_close_reverse",true)
        val invalid=listOf(
            evidence().put("strategy_ownership_verified",true),
            evidence().put("no_add_close_reverse",false),
            evidence().put("autonomous_orphan_mode","TC2_FULL_OWNERSHIP"),
            evidence().put("direction","SHORT")
        )
        for (item in invalid) {
            assertFalse(runCatching {
                stopTrailDecisionAuditEvent("ETHUSDT","LONG","AUTO_RECOVERY:ETHUSDT",
                    "CLOSED_H1_H4_REVIEW_BEFORE_ADOPTION",item,
                    recordedAtMs=1_795_405_332_535L)
            }.isSuccess)
        }
        assertFalse(runCatching {
            stopTrailDecisionAuditEvent("ETHUSDT","LONG","AUTO_RECOVERY:ETHUSDT",
                "CLOSED_H1_H4_REVIEW_BEFORE_ADOPTION",evidence(),
                JSONObject().put("ok",true),1_795_405_332_535L)
        }.isSuccess)
    }
    @Test fun longEvidenceAndExchangeReceiptRemainByteForByteRecoverableAfterSerialization() {
        val candles = JSONArray((0 until 250).map { i ->
            JSONObject().put("open_time_ms", i * 3_600_000L)
                .put("high", 100.0 + i).put("low", 99.0 + i)
                .put("close", 99.5 + i)
        })
        val evidence = JSONObject().put("schema", "WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("direction", "LONG").put("decision", "PROPOSE_TIGHTEN_ONLY")
            .put("source_rule", "CONFIRMED_H1_FRACTAL")
            .put("h1_candles", candles).put("old_stop", 99.0)
            .put("candidate_stop", 101.0)
            .put("wave_hypotheses_can_authorize_orders", false)
            .put("proposed_binance_protection_client_id", "W2T_BTC_1234")
        assertTrue(evidence.toString().length > 1600)
        val exchange = JSONObject().put("ok", true).put("stop",
            JSONObject().put("clientAlgoId", "W2T_BTC_1234")
                .put("triggerPrice", "101.0").put("algoStatus", "NEW"))
        val event = stopTrailDecisionAuditEvent("BTCUSDT", "LONG", "campaign-123",
            "EXCHANGE_RESPONSE_OK", evidence, exchange, 42L)
        val roundTrip = JSONObject(event.toString())
        assertEquals("campaign-123", roundTrip.getString("campaign_id"))
        assertEquals("LONG", roundTrip.getString("direction"))
        assertEquals("EXCHANGE_RESPONSE_OK", roundTrip.getString("phase"))
        assertEquals(250, roundTrip.getJSONObject("decision_evidence")
            .getJSONArray("h1_candles").length())
        assertEquals(101.0, roundTrip.getJSONObject("decision_evidence")
            .getDouble("candidate_stop"), 1e-9)
        assertEquals("W2T_BTC_1234", roundTrip.getJSONObject("exchange_outcome")
            .getJSONObject("stop").getString("clientAlgoId"))
        assertEquals("W2T_BTC_1234", roundTrip.getJSONObject("decision_evidence")
            .getString("proposed_binance_protection_client_id"))
        assertTrue(roundTrip.getBoolean("evidence_untruncated"))
    }

    @Test fun rejectedAndDeferredPhasesCannotClaimBinanceConfirmed() {
        val evidence = JSONObject().put("schema", "WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("direction", "SHORT").put("decision", "DEFER").put("blocker", "H4_CORRECTION_CAUTION_RETAIN_EXCHANGE_STOP")
        val row = stopTrailDecisionAuditEvent("SOLUSDT", "SHORT", "campaign-456",
            "REVIEW_DEFERRED", evidence, recordedAtMs = 123L)
        assertEquals("REVIEW_DEFERRED", row.getString("phase"))
        assertTrue(row.isNull("exchange_outcome"))
        try {
            stopTrailDecisionAuditEvent("DOGEUSDT", "SHORT", "x", "SILENT_SUCCESS",
                evidence, recordedAtMs = 123L)
            fail("Unrecognized exchange state must not be logged as a confirmed success")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun successMustProveSameClientSamePriceAndTrueCoordinatorOutcome() {
        val facts=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("direction","LONG").put("decision","PROPOSE_TIGHTEN_ONLY")
            .put("candidate_stop",101.0)
            .put("proposed_binance_protection_client_id","W2T_BTC_EXPECTED")
        fun success(stop:JSONObject,ok:Boolean=true):Boolean = runCatching {
            stopTrailDecisionAuditEvent("BTCUSDT","LONG","campaign","EXCHANGE_RESPONSE_OK",
                facts,JSONObject().put("ok",ok).put("stop",stop),500L)
        }.isSuccess
        val legit=JSONObject().put("clientAlgoId","W2T_BTC_EXPECTED")
            .put("triggerPrice","101.0").put("algoStatus","NEW")
        assertTrue(success(legit))
        assertFalse(success(JSONObject(legit.toString()).put("clientAlgoId","W2T_WRONG")))
        assertFalse(success(JSONObject(legit.toString()).put("triggerPrice","100.5")))
        assertFalse(success(JSONObject(legit.toString()).put("algoStatus","CANCELED")))
        assertFalse(success(legit,false))
        assertFalse(runCatching {
            stopTrailDecisionAuditEvent("BTCUSDT","LONG","campaign",
                "EXCHANGE_NOT_CONFIRMED",facts,JSONObject().put("ok",true),500L)
        }.isSuccess)
        assertTrue(runCatching {
            stopTrailDecisionAuditEvent("BTCUSDT","LONG","campaign",
                "EXCHANGE_EXCEPTION_UNVERIFIED",facts,
                JSONObject().put("retry_authorized",false),500L)
        }.isSuccess)
    }

    @Test fun operatorApprovalReceiptRequiresCorrectStop() {
        val facts=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("decision","PROPOSE_TIGHTEN_ONLY").put("direction","SHORT")
            .put("candidate_stop",95.0)
            .put("proposed_binance_protection_client_id","W2T_SOL_OPERATOR")
        val ok=JSONObject().put("ok",true).put("stop","95.0")
            .put("protection_client_id","W2T_SOL_OPERATOR")
            .put("exchange_proof","ONE_SIGNED_ACTIVE_MATCHING_STOP_ID_PRICE_SIDE_QTY")
        assertTrue(runCatching { stopTrailDecisionAuditEvent(
            "SOLUSDT","SHORT","SUPERVISION:SOLUSDT:1",
            "EXCHANGE_RESPONSE_OK",facts,ok,22L) }.isSuccess)
        assertFalse(runCatching { stopTrailDecisionAuditEvent(
            "SOLUSDT","SHORT","SUPERVISION:SOLUSDT:1",
            "EXCHANGE_RESPONSE_OK",facts,JSONObject(ok.toString()).put("stop","95.1"),22L) }.isSuccess)
        assertFalse(runCatching { stopTrailDecisionAuditEvent(
            "SOLUSDT","SHORT","SUPERVISION:SOLUSDT:1",
            "EXCHANGE_RESPONSE_OK",facts,
            JSONObject(ok.toString()).put("protection_client_id","W2T_OTHER"),22L) }.isSuccess)
    }

    @Test fun repeatedDecisionFingerprintChangesWithClosedBarsAndBlockers() {
        fun audit(h1:Long, blocker:String):JSONObject {
            val series=JSONArray().put(JSONObject().put("open_time_ms",h1))
            return JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
                .put("direction","LONG").put("decision","DEFER")
                .put("as_of_ms",1230000L).put("blocker",blocker)
                .put("source_rule","TC2_LAST_5_CLOSED_BARS")
                .put("candidate_stop",98.0).put("old_stop",96.0)
                .put("h1_last_twelve_closed",series)
        }
        val same=stopTrailDeferredFingerprint("campaign",audit(3600000L,"H4_CORRECTION"))
        assertEquals(same,stopTrailDeferredFingerprint("campaign",audit(3600000L,"H4_CORRECTION")))
        assertNotEquals(same,stopTrailDeferredFingerprint("campaign",audit(7200000L,"H4_CORRECTION")))
        assertNotEquals(same,stopTrailDeferredFingerprint("campaign",audit(3600000L,"H1_NOISE")))
        assertNotEquals(same,stopTrailDeferredFingerprint("another-campaign",audit(3600000L,"H4_CORRECTION")))
        val unavailable=JSONObject().put("direction","LONG").put("blocker","CLOSED_H1_HISTORY_NOT_VERIFIED")
            .put("as_of_ms",3_600_000L)
        val later=JSONObject(unavailable.toString()).put("as_of_ms",7_200_000L)
        assertNotEquals(stopTrailDeferredFingerprint("campaign",unavailable),
            stopTrailDeferredFingerprint("campaign",later))
    }

    @Test fun signedStopIsMatchedAgainstActualSubmittedTickPriceNotFloatArtifacts() {
        val facts=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("direction","LONG").put("decision","PROPOSE_TIGHTEN_ONLY")
            .put("candidate_stop",0.07687000000000001)
            .put("submitted_stop_price","0.07687")
            .put("proposed_binance_protection_client_id","W2T_DOGE_REAL")
        val proof=JSONObject().put("ok",true).put("stop",
            JSONObject().put("clientAlgoId","W2T_DOGE_REAL")
                .put("triggerPrice","0.07687").put("algoStatus","NEW"))
        assertTrue(runCatching {
            stopTrailDecisionAuditEvent("DOGEUSDT","LONG","campaign-doge",
                "EXCHANGE_RESPONSE_OK",facts,proof,4444L)
        }.isSuccess)
        assertFalse(runCatching {
            stopTrailDecisionAuditEvent("DOGEUSDT","LONG","campaign-doge",
                "EXCHANGE_RESPONSE_OK",facts,
                JSONObject(proof.toString()).put("stop",
                    JSONObject(proof.getJSONObject("stop").toString()).put("triggerPrice","0.07686")),4444L)
        }.isSuccess)
    }
}

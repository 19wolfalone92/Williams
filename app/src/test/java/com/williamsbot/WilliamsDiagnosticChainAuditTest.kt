package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDiagnosticChainAuditTest {
    private val now=1_800_000_000_000L

    private fun audit(checks: JSONArray = JSONArray(), status: JSONObject = JSONObject(),
        coordinator: JSONObject = JSONObject(), scanner: JSONObject = JSONObject(),
        nonAtomic: Boolean=false, events: JSONArray=JSONArray()): JSONObject =
        WilliamsDiagnosticChainAudit.build(
            status,coordinator,scanner,checks,now,events,nonAtomic)

    private fun core(report:JSONObject,id:String):JSONObject =
        (0 until report.getJSONArray("components").length()).map {
            report.getJSONArray("components").getJSONObject(it)
        }.single { it.getString("id")==id }

    private fun findingIds(report:JSONObject):List<String> =
        (0 until report.getJSONArray("cross_chain_findings").length()).map {
            report.getJSONArray("cross_chain_findings").getJSONObject(it).getString("id")
        }

    private fun allPassingCheckRows():JSONArray {
        val empty=audit()
        val ids=linkedSetOf<String>()
        for(index in 0 until empty.getJSONArray("components").length()) {
            val missing=empty.getJSONArray("components").getJSONObject(index)
                .getJSONArray("missing_check_ids")
            for(j in 0 until missing.length()) ids.add(missing.getString(j))
        }
        return JSONArray().apply {
            for(id in ids) put(JSONObject().put("id",id).put("severity","PASS"))
        }
    }

    @Test fun missingSubsystemEvidenceDoesNotInventPassingSystemsOrDemoAcceptance() {
        val report=audit()
        assertEquals("WILLIAMS_ALL_CORE_CHAIN_AUDIT_V1",report.getString("schema"))
        assertEquals(22,report.getInt("component_count"))
        assertTrue(report.getInt("dependency_edge_count")>=20)
        assertEquals(22,report.getJSONObject("summary").getInt("NOT_VERIFIED"))
        assertEquals(0,report.getJSONObject("summary").getInt("PASS"))
        assertFalse(report.getBoolean("e2e_demo_certified"))
        assertFalse(report.getBoolean("can_place_orders"))
        assertFalse(report.getBoolean("can_clear_execution_gate"))
        assertEquals("NOT_VERIFIED",core(report,"STOP_PROTECTION").getString("status"))
    }

    @Test fun everyExistingAnchorCanPassButNeverCertifiesAnExchangeChain() {
        val report=audit(allPassingCheckRows())
        assertEquals(22,report.getJSONObject("summary").getInt("PASS"))
        assertEquals(0,report.getJSONArray("unmapped_check_ids").length())
        val links=report.getJSONArray("dependencies")
        for(i in 0 until links.length()) {
            val row=links.getJSONObject(i)
            assertEquals("PASS",row.getString("endpoint_status"))
            assertFalse(row.getBoolean("end_to_end_exchange_verified"))
            assertTrue(row.getString("missing_proof").isNotBlank())
        }
        assertFalse(report.getBoolean("e2e_demo_certified"))
    }

    @Test fun missingActualStopProofIsVisibleEvenIfUiClaimsProtected() {
        val checks=allPassingCheckRows()
        for(i in 0 until checks.length()) {
            val row=checks.getJSONObject(i)
            if(row.getString("id")=="EXE-13") row.put("severity","NOT_VERIFIED")
        }
        val positions=JSONArray().put(JSONObject()
            .put("symbol","BCHUSDT").put("positionAmt","-0.506"))
        val report=audit(checks,JSONObject().put("futures_positions",positions))
        assertEquals("NOT_VERIFIED",core(report,"STOP_PROTECTION").getString("status"))
        assertTrue(findingIds(report).contains("LIVE_POSITION_STOP_PROOF_NOT_VERIFIED"))
        assertFalse(report.getBoolean("e2e_demo_certified"))
    }

    @Test fun unsafeExecutionClaimWithoutSignedClockProofRaisesIndependentFinding() {
        val status=JSONObject().put("execution_enabled",true)
            .put("user_stream_signed_account_snapshot_at_ms",now-95_000)
        val report=audit(allPassingCheckRows(),status)
        assertTrue(findingIds(report).contains("EXECUTION_CLAIM_WITH_STALE_SIGNED_PROOF"))
        val coordinator=JSONObject().put("state","RECONCILE_REQUIRED")
            .put("ready_for_new_entry",true)
        assertTrue(findingIds(audit(allPassingCheckRows(),JSONObject(),coordinator))
            .contains("COORDINATOR_READY_DURING_RECONCILE_REQUIRED"))
    }

    @Test fun nonAtomicCoordinatorChangeNeverCertifiesNewEntryAuthority() {
        val report=audit(allPassingCheckRows(),
            JSONObject().put("execution_enabled",false),nonAtomic=true)
        assertTrue(report.getBoolean("snapshot_non_atomic"))
        assertTrue(findingIds(report).contains("MIXED_TIME_COORDINATOR_SNAPSHOT"))
        assertFalse(report.getBoolean("can_clear_execution_gate"))
    }

    @Test fun duplicateOrUnmappedTestNamesAreExposedWithoutDroppingOtherEvidence() {
        val tests=JSONArray()
            .put(JSONObject().put("id","DATA-01").put("severity","PASS"))
            .put(JSONObject().put("id","DATA-01").put("severity","FAIL"))
            .put(JSONObject().put("id","UNKNOWN_NEW_CORE").put("severity","PASS"))
        val report=audit(tests)
        assertTrue(findingIds(report).contains("DUPLICATE_DIAGNOSTIC_TEST_IDS"))
        assertTrue(findingIds(report).contains("UNMAPPED_DIAGNOSTIC_TEST_IDS"))
        assertEquals("DATA-01",core(report,"PUBLIC_REST")
            .getJSONArray("check_evidence").getJSONObject(0).getString("id").takeIf { it=="DATA-01" }
            ?: "DATA-01")
        assertEquals("UNKNOWN_NEW_CORE",report.getJSONArray("unmapped_check_ids").getString(0))
    }

    @Test fun eventTraceNeverEchoesRawPrivatePayloadOrApiIdentifiers() {
        val secret="NeverDisplayBinanceKeyFromUserEvent"
        val events=JSONArray().put(JSONObject()
            .put("e","COCKPIT_USER_WS").put("E",now-5000)
            .put("detail",secret).put("api_key",secret))
        val report=audit(events=events)
        assertEquals(1,report.getJSONObject("event_category_counts")
            .getInt("COCKPIT_USER_WS"))
        val compact=report.getJSONArray("last_events_metadata_only").getJSONObject(0)
        assertFalse(compact.has("detail"))
        assertFalse(compact.has("api_key"))
        assertFalse(report.toString().contains(secret))
    }

    @Test fun comparisonUsesOnlyOlderFullyObservedCoreStatesNotClaimsOfRepair() {
        val before=audit(allPassingCheckRows())
        val afterChecks=allPassingCheckRows()
        for(i in 0 until afterChecks.length()) {
            val row=afterChecks.getJSONObject(i)
            if(row.getString("id")=="EXE-13") row.put("severity","WARNING")
        }
        val after=audit(afterChecks)
        val comparison=WilliamsDiagnosticChainAudit.compare(before,after,now-20_000,now)
        assertEquals("COMPARABLE",comparison.getString("status"))
        assertEquals(1,comparison.getInt("worsened_count"))
        assertEquals("STOP_PROTECTION",comparison.getJSONArray("worsened")
            .getJSONObject(0).getString("id"))
        assertFalse(comparison.getBoolean("can_clear_trade_gate"))
        assertEquals("NOT_COMPARABLE",WilliamsDiagnosticChainAudit.compare(
            null,after,0L,now).getString("status"))
        assertEquals("NOT_COMPARABLE",WilliamsDiagnosticChainAudit.compare(
            after,before,now,now).getString("status"))
    }

    @Test fun signedDataTimestampsAreNotFreshenedByDiagnosticCollectionTime() {
        val status=JSONObject()
            .put("execution_enabled",true)
            .put("user_stream_signed_account_snapshot_at_ms",now+5000)
        val report=audit(allPassingCheckRows(),status)
        val age=core(report,"SIGNED_ACCOUNT").getJSONObject("telemetry")
            .get("signed_snapshot_age_ms")
        assertEquals(JSONObject.NULL,age)
        assertTrue(findingIds(report).contains("EXECUTION_CLAIM_WITH_STALE_SIGNED_PROOF"))
    }
}

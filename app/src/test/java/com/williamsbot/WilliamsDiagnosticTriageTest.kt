package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDiagnosticTriageTest {
    private fun check(id:String,domain:String,severity:String) = JSONObject()
        .put("id",id).put("domain",domain).put("severity",severity)
        .put("name","check $id").put("detail","source evidence for $id")

    @Test fun failsBeforeWarningsAndDoesNotCertifyBooksOrTrading() {
        val tests=JSONArray()
            .put(check("DATA-06","strategy","NOT_VERIFIED"))
            .put(check("EXE-02","execution","FAIL"))
            .put(check("WEB-05","websocket","WARN"))
            .put(check("SAFE-01","risk","PASS"))
        val triage=williamsDiagnosticTriage(tests,false)
        assertEquals("FAIL",triage.getString("overall"))
        assertEquals(4,triage.getInt("component_test_count"))
        assertEquals(3,triage.getInt("issue_count"))
        assertEquals(1,triage.getInt("warning_count"))
        assertEquals(1,triage.getInt("unverified_count"))
        assertEquals("EXE-02",triage.getJSONArray("priority_issues").getJSONObject(0).getString("id"))
        assertTrue(triage.getJSONArray("priority_issues").getJSONObject(0)
            .getString("next_action").contains("Binance Demo"))
        assertFalse(triage.getBoolean("accuracy_certified"))
        assertFalse(triage.getBoolean("book_100_percent_verified"))
        assertFalse(triage.getBoolean("demo_end_to_end_verified"))
        assertFalse(triage.getBoolean("new_entries_allowed_at_snapshot"))
    }

    @Test fun noChecksIsUnverifiedNotSuccess() {
        val t=williamsDiagnosticTriage(JSONArray(),false)
        assertEquals("NOT_VERIFIED",t.getString("overall"))
        assertEquals(0,t.getInt("component_test_count"))
        assertTrue(t.getString("status_note").contains("не успешная"))
    }

    @Test fun greenComponentTestsDoNotFabricateCertifiedTrading() {
        val t=williamsDiagnosticTriage(JSONArray().put(check("UI","ui","PASS")),true)
        assertEquals("PASS",t.getString("overall"))
        assertEquals(0,t.getInt("issue_count"))
        assertFalse(t.getBoolean("accuracy_certified"))
        assertFalse(t.getBoolean("book_100_percent_verified"))
    }

    @Test fun exchangeStopFailureRemainsVisibleWhenManyStrategyFailuresExist() {
        val checks=JSONArray()
        repeat(20) { checks.put(check("A-BOOK-$it","strategy","FAIL")) }
        checks.put(check("EXE-STOP-01","execution","FAIL"))
        checks.put(check("RESTART","runtime","FAIL"))
        val triage=williamsDiagnosticTriage(checks,false)
        assertEquals(22,triage.getInt("issue_count"))
        val top=triage.getJSONArray("priority_issues")
        assertEquals("EXE-STOP-01",top.getJSONObject(0).getString("id"))
        assertEquals("RESTART",top.getJSONObject(1).getString("id"))
        assertTrue(triage.getBoolean("priority_issues_truncated"))
    }

    @Test fun liveAvaxUnverifiedSignedStopOutranksUiWarningsButNeverConfirmedFailure() {
        val checks=JSONArray()
            .put(check("AND-03","ui","WARNING"))
            .put(check("EXE-07","execution","WARNING"))
            .put(check("EXE-03","execution","NOT_VERIFIED"))
            .put(check("EXE-04","execution","NOT_VERIFIED"))
            .put(check("WAVE-04","strategy","PASS"))
        val triage=williamsDiagnosticTriage(checks,false,livePositionCount=1)
        assertTrue(triage.getBoolean("exchange_stop_proof_refresh_urgent"))
        assertEquals(1,triage.getInt("live_position_count_at_snapshot"))
        val top=triage.getJSONArray("priority_issues")
        assertEquals("EXE-03",top.getJSONObject(0).getString("id"))
        assertEquals("NOT_VERIFIED",top.getJSONObject(0).getString("severity"))
        assertEquals("EXE-04",top.getJSONObject(1).getString("id"))
        val withoutPosition=williamsDiagnosticTriage(checks,false,livePositionCount=0)
        assertFalse(withoutPosition.getBoolean("exchange_stop_proof_refresh_urgent"))
        assertEquals("EXE-07",withoutPosition.getJSONArray("priority_issues")
            .getJSONObject(0).getString("id"))
        checks.put(check("RISK-SIGNED-STOP","risk","FAIL"))
        val failed=williamsDiagnosticTriage(checks,false,livePositionCount=1)
        assertEquals("RISK-SIGNED-STOP",failed.getJSONArray("priority_issues")
            .getJSONObject(0).getString("id"))
    }

    @Test fun nonAtomicDiagnosticGateAlwaysExplainsBlockedAdmission() {
        assertEquals(listOf("DIAGNOSTIC_COORDINATOR_CHANGED_DURING_SNAPSHOT"),
            williamsDiagnosticGateReasons(emptyList(),true,false))
        assertEquals(listOf("EXECUTION_GATE_NOT_VERIFIED_RECHECK_SIGNED_EXCHANGE"),
            williamsDiagnosticGateReasons(emptyList(),false,false))
        assertTrue(williamsDiagnosticGateReasons(emptyList(),false,true).isEmpty())
        assertEquals(listOf("BLOCKED_KILL","DIAGNOSTIC_COORDINATOR_CHANGED_DURING_SNAPSHOT"),
            williamsDiagnosticGateReasons(listOf("BLOCKED_KILL","BLOCKED_KILL"),true,false))
    }

    @Test fun priorityListIsBoundedWithoutLosingFailureCount() {
        val tests=JSONArray()
        repeat(24) { tests.put(check("FAIL-$it","execution","FAIL")) }
        val t=williamsDiagnosticTriage(tests,false)
        assertEquals(24,t.getInt("issue_count"))
        assertEquals(12,t.getJSONArray("priority_issues").length())
        assertTrue(t.getBoolean("priority_issues_truncated"))
    }
}

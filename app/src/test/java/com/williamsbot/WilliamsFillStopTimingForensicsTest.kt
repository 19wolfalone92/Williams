package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsFillStopTimingForensicsTest {
    private val now=1_800_000_000_000L
    private val fillAt=now-30_000L
    private fun event(t:Long,kind:String,state:String,detail:String,symbol:String="BCHUSDT") =
        JSONObject().put("E",t).put("e",kind).put("state",state)
            .put("symbol",symbol).put("detail",detail)

    @Test fun samsungPhoneFillAndStopWitnessesAreTimedEvenWhenTimelineIsReverseSorted() {
        // The real diagnostic event_timeline is newest-first. We MUST sort.
        val events=JSONArray()
            .put(event(fillAt+13_153,"COCKPIT_EXECUTION","OPEN_PROTECTED",
                "Post-change audit=PASS; protection_status=PROTECTED"))
            .put(event(fillAt+11_166,"COCKPIT_USER_WS","ALGO_UPDATE","status=NEW"))
            .put(event(fillAt,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED; executed_qty=0.506"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals(1,report.getInt("fill_event_count"))
        assertEquals(1,report.getInt("observed_slow_confirmation_count"))
        assertEquals("WARNING",report.getString("severity"))
        val row=report.getJSONArray("observations").getJSONObject(0)
        assertEquals(11_166L,row.getLong("fill_to_algo_ws_ms"))
        assertEquals(13_153L,row.getLong("fill_to_protected_audit_ms"))
        assertFalse(row.getBoolean("authenticated_exchange_timing_verified"))
        assertFalse(report.getBoolean("independent_signed_protective_stop_placement_timing_verified"))
    }

    @Test fun noWitnessDoesNotPretendNoStopWasEverOnExchange() {
        val events=JSONArray().put(event(fillAt,"COCKPIT_USER_WS",
            "ORDER_TRADE_UPDATE","status=FILLED;executed_qty=1"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals("NOT_VERIFIED",report.getString("severity"))
        assertEquals(1,report.getInt("unlinked_or_ambiguous_fill_count"))
        assertEquals("NOT_VERIFIED_NO_PROTECTION_AUDIT_IN_RETAINED_EVENTS",
            report.getJSONArray("observations").getJSONObject(0).getString("status"))
    }

    @Test fun diag5_suiExchangeExitFillIsNotMistakenForUnprotectedInitialEntry() {
        // Diagnostic #5: SUI SHORT was already protected and EXIT_PENDING
        // before a BUY/close FILLED; the previous symbol-only heuristic
        // incorrectly demanded a second initial STOP following this exit.
        val events=JSONArray()
            .put(event(fillAt-5_500L,"COCKPIT_EXECUTION","EXIT_PENDING",
                "Post-change audit=NOT_VERIFIED; protection_status=PROTECTED", "SUIUSDT"))
            .put(event(fillAt-5_450L,"COCKPIT_EXECUTION","ENTRY_PENDING",
                "Post-change audit=WARNING; exchange_status=SUBMISSION_UNKNOWN", "SUIUSDT"))
            .put(event(fillAt,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED; executed_qty=77.9", "SUIUSDT"))
            .put(event(fillAt+15_900L,"COCKPIT_EXECUTION","CLOSED_OR_STOPPED",
                "Post-change audit=PASS; protection_status=PROTECTED", "SUIUSDT"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals(1, report.getInt("fill_event_count"))
        assertEquals(1, report.getInt("possible_exit_fill_count"))
        assertEquals(0, report.getInt("observed_slow_confirmation_count"))
        assertEquals(1, report.getInt("unlinked_or_ambiguous_fill_count"))
        val row=report.getJSONArray("observations").getJSONObject(0)
        assertEquals("AMBIGUOUS_EXIT_PENDING_BEFORE_FILL",row.getString("status"))
        assertEquals("MIXED_ENTRY_EXIT_PENDING_NO_SIGNED_ORDER_ID",row.getString("role_hint"))
        assertTrue(row.isNull("fill_to_protected_audit_ms"))
        assertFalse(row.getBoolean("authenticated_exchange_timing_verified"))
    }

    @Test fun diag5_ltcEntryFillAfterEarlierPendingEntryStillRequiresStopTimingProof() {
        val events=JSONArray()
            .put(event(fillAt-20_000L,"COCKPIT_EXECUTION","ENTRY_PENDING",
                "Post-change audit=PASS; protection_status=NOT_SUBMITTED", "LTCUSDT"))
            .put(event(fillAt,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED; executed_qty=1.778", "LTCUSDT"))
            .put(event(fillAt+15_357L,"COCKPIT_USER_WS","ALGO_UPDATE",
                "status=NEW", "LTCUSDT"))
            .put(event(fillAt+19_741L,"COCKPIT_EXECUTION","OPEN_PROTECTED",
                "Post-change audit=PASS; protection_status=PROTECTED", "LTCUSDT"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals(0,report.getInt("possible_exit_fill_count"))
        assertEquals(1,report.getInt("observed_slow_confirmation_count"))
        assertEquals(19_741L,report.getLong("max_fill_to_protected_audit_ms"))
        assertEquals("WARNING_OBSERVED_SLOW_PROTECTION_CONFIRMATION",
            report.getJSONArray("observations").getJSONObject(0).getString("status"))
    }

    @Test fun competingSameSymbolFillsCannotBeAttributedToUnidentifiedStop() {
        val events=JSONArray()
            .put(event(fillAt,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED"))
            .put(event(fillAt+500,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED"))
            .put(event(fillAt+1000,"COCKPIT_EXECUTION","OPEN_PROTECTED",
                "Post-change audit=PASS; protection_status=PROTECTED"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals("AMBIGUOUS_MULTIPLE_FILLS",
            report.getJSONArray("observations").getJSONObject(0).getString("status"))
        assertEquals(0,report.getInt("observed_slow_confirmation_count"))
        assertEquals("NOT_VERIFIED",report.getString("severity"))
    }

    @Test fun olderThan24hOrFutureEventsAreNotFalselyCorrelated() {
        val events=JSONArray()
            .put(event(now-86_400_001L,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED"))
            .put(event(now+5_000L,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED"))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertEquals(0,report.getInt("fill_event_count"))
        assertEquals("NOT_VERIFIED",report.getString("severity"))
    }

    @Test fun secretBearingUserWsDetailsAreNotCopiedToForensicOutput() {
        val token="SECRET_SHOULD_NEVER_LEAVE_FORENSIC_ANALYZER"
        val events=JSONArray()
            .put(event(fillAt,"COCKPIT_USER_WS","ORDER_TRADE_UPDATE",
                "status=FILLED; "+token))
            .put(event(fillAt+1_000,"COCKPIT_EXECUTION","OPEN_PROTECTED",
                "Post-change audit=PASS; protection_status=PROTECTED; "+token))
        val report=WilliamsFillStopTimingForensics.analyze(events,now)
        assertFalse(report.toString().contains(token))
    }
}

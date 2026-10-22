package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsMarketExitPolicyTest {
    @Test fun tc2AcceptsOnlyBookQualifiedOppositeExitReasonAtFinalDoor() {
        for(reason in listOf("STRATEGY_EXIT","CORRECTION_PARENT_PRICE_INVALIDATED",
            "", "TAKE_PROFIT","LIPS_CLOSE_EXIT","LIQUIDATION_GUARD")) {
            assertEquals("TC2_EXIT_REQUIRES_BOOK_OPPOSITE_TRIGGER",williamsMarketExitBlocker(williamsTc2Profile,reason))
        }
        for(reason in listOf("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
            "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"))
            assertNull(williamsMarketExitBlocker(williamsTc2Profile,reason))
        assertEquals("EXIT_STRATEGY_OWNER_UNVERIFIED",williamsMarketExitBlocker("UNKNOWN","STRATEGY_EXIT"))
    }
    @Test fun explicitOperatorAndKillPathsRemainSubjectToCoordinatorPreflight() {
        assertNull(williamsMarketExitBlocker(williamsTc2Profile,"MANUAL_EXIT"))
        assertNull(williamsMarketExitBlocker(williamsTc2Profile,"KILL_SWITCH"))
        assertNull(williamsMarketExitBlocker(williamsNtdProfile,"TEETH_CLOSE_EXIT"))
        assertNull(williamsMarketExitBlocker(williamsCourseProfile,"COURSE_STAGE_TRIM"))
    }
    @Test fun tc2BookExitRequiresActualPersistedSignalAndCurrentExchangeMark() {
        val now=10_000L*3_600_000L
        val opened=now-3*3_600_000L
        val signalAt=now-2*3_600_000L
        val triggered=now-60_000L
        val observed=now-10_000L
        val opposite=JSONObject().put("signal_id","BOOK:WM1:SELL")
            .put("direction","SHORT").put("strategy_profile",williamsTc2Profile)
            .put("execution_semantics","STOP_ENTRY")
            .put("signal_bar_time_ms",signalAt).put("trigger",96.0)
            .put("event",JSONObject().put("state","TRIGGERED").put("known_at_ms",triggered)
                .put("basis","CLOSED_BAR_EXTREME"))
        val decision=JSONObject().put("direction","LONG").put("scope_started_at_ms",opened)
            .put("observed_at_ms",observed).put("opposition",opposite)
        val proof=JSONObject().put("reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            .put("signal_id","BOOK:WM1:SELL").put("observed_at_ms",observed)
            .put("opposition_check",decision)
        val owner=JSONObject().put("symbol","BTCUSDT").put("direction","LONG")
            .put("strategy_profile",williamsTc2Profile).put("edition_management",JSONObject().put("exit_evidence",proof))
        fun blocked(o:JSONObject=owner,d:JSONObject=decision,at:Long=now,mark:Double=95.0):String? =
            williamsTc2OppositeExitEvidenceBlocker(o,d.toString(),"BTCUSDT",
                "LONG","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",opened,at,mark)
        assertNull(blocked())
        assertNotNull(blocked(mark=100.0))
        assertNotNull(blocked(at=now+121_000L))
        assertNotNull(blocked(d=JSONObject(decision.toString()).put("direction","SHORT")))
        assertNotNull(blocked(d=JSONObject(decision.toString()).put("opposition",
            JSONObject(opposite.toString()).put("strategy_profile",williamsNtdProfile))))
        assertNotNull(blocked(o=JSONObject(owner.toString()).put("symbol","ETHUSDT")))
        assertNotNull(blocked(o=JSONObject(owner.toString()).apply { remove("edition_management") }))
        assertNotNull(williamsTc2OppositeExitEvidenceBlocker(owner,decision.toString(),
            "BTCUSDT","LONG","TAKE_PROFIT",opened,now,95.0))
    }
    @Test fun stopMoveLabelsSeparateTheProjectOverlayFromBookStops() {
        assertTrue(williamsStopMoveLabel("TC2_LAST_5_CLOSED_BARS").contains("5 закрытыми H1"))
        assertTrue(williamsStopMoveLabel("COST_BUFFERED_BREAKEVEN_1R").contains("правило проекта"))
        assertEquals("FUTURE_REASON",williamsStopMoveLabel("FUTURE_REASON"))
    }
}

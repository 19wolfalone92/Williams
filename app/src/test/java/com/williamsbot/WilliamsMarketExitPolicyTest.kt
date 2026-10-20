package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsMarketExitPolicyTest {
    @Test fun tc2CannotMarketCloseOnArbitraryOrOpposingStrategyReasons() {
        for(reason in listOf("STRATEGY_EXIT","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
            "CORRECTION_PARENT_PRICE_INVALIDATED","", "TAKE_PROFIT")) {
            assertEquals("TC2_PROTECTIVE_STOP_ONLY",williamsMarketExitBlocker(williamsTc2Profile,reason))
        }
        assertEquals("EXIT_STRATEGY_OWNER_UNVERIFIED",williamsMarketExitBlocker("UNKNOWN","STRATEGY_EXIT"))
    }
    @Test fun explicitOperatorAndKillPathsRemainSubjectToCoordinatorPreflight() {
        assertNull(williamsMarketExitBlocker(williamsTc2Profile,"MANUAL_EXIT"))
        assertNull(williamsMarketExitBlocker(williamsTc2Profile,"KILL_SWITCH"))
        assertNull(williamsMarketExitBlocker(williamsNtdProfile,"TEETH_CLOSE_EXIT"))
        assertNull(williamsMarketExitBlocker(williamsCourseProfile,"COURSE_STAGE_TRIM"))
    }
    @Test fun stopMoveLabelsSeparateTheProjectOverlayFromBookStops() {
        assertTrue(williamsStopMoveLabel("TC2_LAST_5_CLOSED_BARS").contains("5 закрытыми H1"))
        assertTrue(williamsStopMoveLabel("COST_BUFFERED_BREAKEVEN_1R").contains("правило проекта"))
        assertEquals("FUTURE_REASON",williamsStopMoveLabel("FUTURE_REASON"))
    }
}

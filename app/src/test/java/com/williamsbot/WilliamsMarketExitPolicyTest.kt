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
    @Test fun stopMoveLabelsSeparateTheProjectOverlayFromBookStops() {
        assertTrue(williamsStopMoveLabel("TC2_LAST_5_CLOSED_BARS").contains("5 закрытыми H1"))
        assertTrue(williamsStopMoveLabel("COST_BUFFERED_BREAKEVEN_1R").contains("правило проекта"))
        assertEquals("FUTURE_REASON",williamsStopMoveLabel("FUTURE_REASON"))
    }
}

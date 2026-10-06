package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingStateMachineTest {
    @Test
    fun normalLifecycleIsEnforced() {
        val fsm = TradingStateMachine()
        assertTrue(fsm.transition(TradingState.INITIALIZING, "start"))
        assertTrue(fsm.transition(TradingState.READY_FLAT, "sync ok"))
        assertTrue(fsm.transition(TradingState.ENTRY_PENDING, "buy sent"))
        assertTrue(fsm.transition(TradingState.OPEN_UNPROTECTED, "buy filled"))
        assertTrue(fsm.transition(TradingState.PROTECTED, "oco live"))
        assertTrue(fsm.transition(TradingState.EXIT_PENDING, "sell trigger"))
        assertTrue(fsm.transition(TradingState.READY_FLAT, "exit filled"))
        assertTrue(fsm.executionAllowed())
    }

    @Test
    fun protectedPosition_canAdmitAnotherEntry_andReturnProtected() {
        val fsm = TradingStateMachine(TradingState.PROTECTED)
        assertTrue(fsm.transition(TradingState.ENTRY_PENDING, "second entry"))
        assertTrue(fsm.transition(TradingState.OPEN_UNPROTECTED, "second entry filled"))
        assertTrue(fsm.transition(TradingState.PROTECTED, "second entry protected"))
        assertTrue(fsm.executionAllowed())
    }

    @Test
    fun unsafeJumpIsRejected() {
        val fsm = TradingStateMachine()
        assertTrue(fsm.transition(TradingState.INITIALIZING, "start"))
        assertFalse(fsm.transition(TradingState.PROTECTED, "illegal"))
    }

    @Test
    fun killLatchBlocksExecution() {
        val fsm = TradingStateMachine(TradingState.KILL_SWITCH_LATCHED)
        assertFalse(fsm.executionAllowed())
        assertFalse(fsm.transition(TradingState.READY_FLAT, "not until reset"))
    }
}

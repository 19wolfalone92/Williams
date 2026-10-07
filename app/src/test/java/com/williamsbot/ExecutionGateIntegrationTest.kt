package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutionGateIntegrationTest {
    @Test
    fun reconcileRequired_blocks_execution_until_successful_reconciliation() {
        val fsm = TradingStateMachine(TradingState.RECONCILE_REQUIRED)

        assertTrue(!fsm.executionAllowed())
        assertTrue(
            !fsm.canAdmitEntry(
                openPositions = 0,
                maxOpenPositions = 1
            )
        )

        assertTrue(
            fsm.transition(
                TradingState.SYNC_REQUIRED,
                "reconciliation started"
            )
        )
        assertTrue(
            fsm.transition(
                TradingState.INITIALIZING,
                "reconciliation completed"
            )
        )
        assertTrue(
            fsm.transition(
                TradingState.READY_FLAT,
                "runtime reconciled"
            )
        )

        assertTrue(fsm.executionAllowed())
        assertTrue(
            fsm.canAdmitEntry(
                openPositions = 0,
                maxOpenPositions = 1
            )
        )
    }

    @Test
    fun twoSimultaneousSignals_onlyOneEntersPending_andResultReturnsToLoop() {
        val loop = TradingEventLoop("execution-gate-test")
        val gate = ExecutionGate()
        val fsm = TradingStateMachine(TradingState.READY_FLAT)
        val accepted = AtomicInteger(0)
        val results = AtomicInteger(0)
        val acceptedDone = CountDownLatch(2)
        val resultDone = CountDownLatch(1)

        repeat(2) {
            Thread {
                loop.post {
                    if (
                        fsm.state == TradingState.READY_FLAT &&
                        gate.tryReserve("BTCUSDT")
                    ) {
                        assertTrue(
                            fsm.transition(
                                TradingState.ENTRY_PENDING,
                                "test intent"
                            )
                        )
                        accepted.incrementAndGet()
                    }
                    acceptedDone.countDown()
                }
            }.start()
        }

        assertTrue(acceptedDone.await(2, TimeUnit.SECONDS))

        loop.post {
            gate.release("BTCUSDT")
            fsm.transition(
                TradingState.READY_FLAT,
                "test ExecutionResult"
            )
            results.incrementAndGet()
            resultDone.countDown()
        }

        assertTrue(resultDone.await(2, TimeUnit.SECONDS))
        assertEquals(1, accepted.get())
        assertEquals(1, results.get())
        assertEquals(TradingState.READY_FLAT, fsm.state)
        loop.close()
    }
}

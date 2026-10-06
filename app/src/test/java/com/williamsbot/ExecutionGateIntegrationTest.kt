package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutionGateIntegrationTest {
    @Test
    fun twoSimultaneousSignals_onlyOneEntersPending_andResultReturnsToLoop() {
        val loop = TradingEventLoop("execution-gate-test")
        val gate = ExecutionGate()
        val fsm = TradingStateMachine(TradingState.READY_FLAT)
        val accepted = AtomicInteger(0)
        val results = AtomicInteger(0)
        val done = CountDownLatch(3)

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
                    done.countDown()
                }
            }.start()
        }

        loop.post {
            loop.post {
                gate.release("BTCUSDT")
                fsm.transition(
                    TradingState.READY_FLAT,
                    "test ExecutionResult"
                )
                results.incrementAndGet()
                done.countDown()
            }
        }

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, accepted.get())
        assertEquals(1, results.get())
        assertEquals(TradingState.READY_FLAT, fsm.state)
        loop.close()
    }
}

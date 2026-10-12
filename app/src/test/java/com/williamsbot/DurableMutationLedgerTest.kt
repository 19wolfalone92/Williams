package com.williamsbot

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableMutationLedgerTest {
    private lateinit var context: Context
    private var ledger: DurableMutationLedger? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("williams_mutation_ledger.db")
        ledger = DurableMutationLedger(context)
    }

    @After
    fun tearDown() {
        ledger?.close()
        context.deleteDatabase("williams_mutation_ledger.db")
    }

    @Test
    fun preparedIntentSurvivesLedgerReopen() {
        val id = ledger!!.prepare(
            operationId = "op-restart",
            kind = "POST:/api/v3/order",
            symbol = "BTCUSDT",
            requestFingerprint = "fingerprint-a",
            exchangeClientId = "W21_restart"
        )
        assertEquals(DurableMutationLedger.State.PREPARED, id.state)

        ledger!!.close()
        ledger = DurableMutationLedger(context)

        assertEquals(
            DurableMutationLedger.State.PREPARED,
            ledger!!.find("op-restart")!!.state
        )
    }

    @Test
    fun duplicateIdenticalIntentIsIdempotent() {
        val first = ledger!!.prepare("op-duplicate", "POST:/api/v3/order", "BTCUSDT", "fp", "client-a")
        val second = ledger!!.prepare("op-duplicate", "POST:/api/v3/order", "BTCUSDT", "fp", "client-a")

        assertEquals(first.operationId, second.operationId)
        assertEquals(DurableMutationLedger.State.PREPARED, second.state)
        assertEquals(1, ledger!!.unresolved().size)
    }

    @Test
    fun conflictingReuseOfOperationIdIsRejected() {
        ledger!!.prepare("op-conflict", "POST:/api/v3/order", "BTCUSDT", "fp-a", "client-a")

        assertThrows(DurableMutationLedger.IntentConflictException::class.java) {
            ledger!!.prepare("op-conflict", "POST:/api/v3/order", "ETHUSDT", "fp-b", "client-b")
        }
    }

    @Test
    fun unknownIntentRemainsUnresolvedAfterRestart() {
        ledger!!.prepare("op-unknown", "POST:/api/v3/order", "BTCUSDT", "fp", "client-unknown")
        ledger!!.transition(
            "op-unknown",
            setOf(DurableMutationLedger.State.PREPARED),
            DurableMutationLedger.State.SUBMITTING
        )
        ledger!!.transition(
            "op-unknown",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.UNKNOWN
        )
        ledger!!.close()
        ledger = DurableMutationLedger(context)

        assertTrue(ledger!!.hasUnresolved())
        assertEquals(DurableMutationLedger.State.UNKNOWN, ledger!!.find("op-unknown")!!.state)
    }

    @Test
    fun acknowledgedIntentIsNotClassifiedAsUnresolved() {
        ledger!!.prepare("op-ack", "POST:/api/v3/order", "BTCUSDT", "fp", "client-ack")
        ledger!!.transition(
            "op-ack",
            setOf(DurableMutationLedger.State.PREPARED),
            DurableMutationLedger.State.SUBMITTING
        )
        ledger!!.transition(
            "op-ack",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.ACKNOWLEDGED,
            exchangeOrderId = "12345"
        )

        assertFalse(ledger!!.hasUnresolved())
        assertEquals("12345", ledger!!.find("op-ack")!!.exchangeOrderId)
    }

    @Test
    fun illegalStateTransitionIsRejected() {
        ledger!!.prepare("op-invalid", "POST:/api/v3/order", "BTCUSDT", "fp", "client-invalid")

        assertThrows(DurableMutationLedger.InvalidTransitionException::class.java) {
            ledger!!.transition(
                "op-invalid",
                setOf(DurableMutationLedger.State.PREPARED),
                DurableMutationLedger.State.RECONCILED
            )
        }
    }
}

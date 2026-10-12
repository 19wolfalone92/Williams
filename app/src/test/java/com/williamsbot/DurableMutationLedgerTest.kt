package com.williamsbot

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun generatedClientOrderIdIsStableAcrossRepeatedIdenticalRequests() {
        val first = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        )
        val repeated = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        )
        assertEquals(first, repeated)
        assertTrue(first.startsWith("W21_"))
        assertTrue(first.length <= 36)
    }

    @Test
    fun generatedClientOrderIdChangesWhenUnsignedRequestChanges() {
        val buy = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        )
        val sell = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=SELL&type=MARKET&quantity=0.01"
        )
        assertFalse(buy == sell)
    }

    @Test
    fun mutationFingerprintDoesNotNeedCredentialsOrSignature() {
        val fingerprint = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        )
        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains("SECRET"))
        assertFalse(fingerprint.contains("signature"))
    }


    @Test
    fun canonicalFingerprintIgnoresParameterOrderEncodingAndTransportMetadata() {
        val first = MutationIdentity.fingerprint(
            "post", "/api/v3/order", "btcusdt",
            "symbol=btcusdt&side=buy&type=market&quantity=0.0100&price=1.0&timestamp=123&recvWindow=5000"
        )
        val equivalent = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT",
            "recvWindow=10000&price=1.00&type=MARKET&quantity=1e-2&side=BUY&symbol=BTCUSDT&timestamp=456"
        )
        assertEquals(first, equivalent)
    }

    @Test
    fun canonicalFingerprintPreservesAbsentVersusExplicitEmpty() {
        val absent = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY"
        )
        val empty = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&emptyField="
        )
        assertFalse(absent == empty)
    }

    @Test
    fun duplicateParametersAreRejectedRatherThanSilentlyCollapsed() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationIdentity.canonicalUnsignedParams("symbol=BTCUSDT&side=BUY&side=SELL")
        }
    }

    @Test
    fun credentialAndSignatureParametersAreRejectedBeforeFingerprinting() {
        val markers = listOf(
            "signature=SYNTHETIC_SIGNATURE_MARKER",
            "apiKey=SYNTHETIC_API_KEY_MARKER",
            "api_secret=SYNTHETIC_API_SECRET_MARKER",
            "authorization=SYNTHETIC_AUTH_HEADER_MARKER"
        )
        for (marker in markers) {
            assertThrows(IllegalArgumentException::class.java) {
                MutationIdentity.canonicalUnsignedParams("symbol=BTCUSDT&$marker")
            }
        }
    }

    @Test
    fun distinctLogicalOperationsWithIdenticalParametersHaveDistinctClientIds() {
        val params = "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        val first = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", params, "campaign-1-signal-10-entry"
        )
        val second = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", params, "campaign-2-signal-20-entry"
        )
        assertFalse(first == second)
        assertTrue(first.length <= 36)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{1,36}")))
    }

    @Test
    fun clientIdIsStableForSameLogicalOperationAcrossRestartEquivalentInvocation() {
        val params = "quantity=0.0100&side=buy&type=market&symbol=btcusdt"
        val beforeRestart = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", params, "persisted-operation-42"
        )
        val afterRestart = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT",
            "symbol=BTCUSDT&type=MARKET&side=BUY&quantity=0.01",
            "persisted-operation-42"
        )
        assertEquals(beforeRestart, afterRestart)
    }

    @Test
    fun changedEconomicParametersChangeFingerprint() {
        val small = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        )
        val large = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.02"
        )
        assertFalse(small == large)
    }


    @Test
    fun zeroEconomicQuantityIsRejectedAsUnsupportedOrderInput() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationIdentity.canonicalUnsignedParams("symbol=BTCUSDT&quantity=0")
        }
    }

    @Test
    fun unsupportedDecimalRepresentationFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationIdentity.canonicalUnsignedParams("symbol=BTCUSDT&quantity=not-a-number")
        }
    }


    @Test
    fun ledgerPersistsTypedOperationMetadataAndStableAttemptIdentity() {
        val prepared = ledger!!.prepare(
            operationId = "typed-operation",
            kind = "POST:/api/v3/order",
            symbol = "btcusdt",
            requestFingerprint = "e".repeat(64),
            exchangeClientId = "W21_typed",
            exchange = "binance_spot",
            campaignId = "campaign-17",
            signalId = "signal-22"
        )
        assertEquals("BINANCE_SPOT", prepared.exchange)
        assertEquals("BTCUSDT", prepared.symbol)
        assertEquals("campaign-17", prepared.campaignId)
        assertEquals("signal-22", prepared.signalId)
        assertEquals("typed-operation:attempt-1", prepared.attemptId)
        assertEquals(prepared.createdAtMs, prepared.updatedAtMs)
    }


    @Test
    fun twoLedgerInstancesCannotBothClaimOnePreparedIntent() {
        val operationId = "op-atomic-claim"
        ledger!!.prepare(
            operationId,
            "POST:/api/v3/order",
            "BTCUSDT",
            "9".repeat(64),
            "W21_atomic"
        )
        val secondLedger = DurableMutationLedger(context)
        ledger!!.claimForSubmission(operationId)
        assertThrows(DurableMutationLedger.InvalidTransitionException::class.java) {
            secondLedger.claimForSubmission(operationId)
        }
        secondLedger.close()
        assertEquals(DurableMutationLedger.State.SUBMITTING, ledger!!.find(operationId)!!.state)
    }

    @Test
    fun simultaneousCallersOnOneLedgerCannotBothClaimOnePreparedIntent() {
        val operationId = "op-thread-claim"
        ledger!!.prepare(
            operationId,
            "POST:/api/v3/order",
            "ETHUSDT",
            "8".repeat(64),
            "W21_thread"
        )
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        fun claimant() = Thread {
            start.await()
            try {
                ledger!!.claimForSubmission(operationId)
                successes.incrementAndGet()
            } catch (_: Exception) {
                failures.incrementAndGet()
            }
        }
        val first = claimant()
        val second = claimant()
        first.start()
        second.start()
        start.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))
        second.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(1, successes.get())
        assertEquals(1, failures.get())
        assertEquals(DurableMutationLedger.State.SUBMITTING, ledger!!.find(operationId)!!.state)
    }

    @Test
    fun unresolvedClaimBlocksConflictingSameSymbolMutationButNotOtherSymbol() {
        ledger!!.prepare("op-btc-1", "POST:/api/v3/order", "BTCUSDT", "1".repeat(64), "client-btc-1")
        ledger!!.claimForSubmission("op-btc-1")
        ledger!!.transition(
            "op-btc-1",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.UNKNOWN
        )

        ledger!!.prepare("op-btc-2", "POST:/api/v3/order", "BTCUSDT", "2".repeat(64), "client-btc-2")
        assertThrows(IllegalStateException::class.java) {
            ledger!!.claimForSubmission("op-btc-2")
        }

        ledger!!.prepare("op-eth-1", "POST:/api/v3/order", "ETHUSDT", "3".repeat(64), "client-eth-1")
        assertEquals(
            DurableMutationLedger.State.SUBMITTING,
            ledger!!.claimForSubmission("op-eth-1").state
        )
        assertTrue(ledger!!.hasUnresolved())
    }

    @Test
    fun schemaV1MigrationPreservesUnresolvedIntent() {
        ledger!!.close()
        ledger = null
        context.deleteDatabase("williams_mutation_ledger.db")
        val oldDb = context.openOrCreateDatabase("williams_mutation_ledger.db", 0, null)
        oldDb.execSQL(
            "CREATE TABLE mutation_intents (" +
                "operation_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, symbol TEXT NOT NULL, " +
                "request_fingerprint TEXT NOT NULL, exchange_client_id TEXT, state TEXT NOT NULL, " +
                "exchange_order_id TEXT, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)"
        )
        oldDb.execSQL(
            "INSERT INTO mutation_intents(operation_id,kind,symbol,request_fingerprint,exchange_client_id,state," +
                "exchange_order_id,created_at_ms,updated_at_ms) VALUES(" +
                "'legacy-unresolved','POST:/api/v3/order','BTCUSDT','" + "f".repeat(64) +
                "','W21_legacy','SUBMITTING',NULL,100,101)"
        )
        oldDb.version = 1
        oldDb.close()

        ledger = DurableMutationLedger(context)
        val migrated = ledger!!.find("legacy-unresolved")!!
        assertEquals(DurableMutationLedger.State.SUBMITTING, migrated.state)
        assertEquals("BINANCE_SPOT", migrated.exchange)
        assertEquals("legacy-unresolved:attempt-1", migrated.attemptId)
        assertTrue(ledger!!.hasUnresolved())
    }


    @Test
    fun protectionClientIdIsStableAndDistinctByLogicalPositionContext() {
        val params = "symbol=BTCUSDT&side=SELL&quantity=0.0100&abovePrice=110.0&aboveStopPrice=111.0" +
            "&aboveTimeInForce=gtc&aboveType=TAKE_PROFIT_LIMIT&belowType=STOP_LOSS_LIMIT&belowStopPrice=90.0&belowPrice=89.90&belowTimeInForce=gtc"
        val equivalent = "belowTimeInForce=GTC&belowPrice=89.9&belowStopPrice=90.00&belowType=STOP_LOSS_LIMIT" +
            "&aboveTimeInForce=GTC&aboveType=TAKE_PROFIT_LIMIT&aboveStopPrice=111.00&abovePrice=110&quantity=0.01&side=SELL&symbol=btcusdt"
        val first = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/orderList/oco", "BTCUSDT", params, "position-entry-client-1", "W4O_"
        )
        val retry = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/orderList/oco", "BTCUSDT", equivalent, "position-entry-client-1", "W4O_"
        )
        val distinctPosition = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/orderList/oco", "BTCUSDT", equivalent, "position-entry-client-2", "W4O_"
        )
        assertEquals(first, retry)
        assertFalse(first == distinctPosition)
        assertTrue(first.length <= 36)
        assertTrue(first.matches(Regex("W4O_[A-Za-z0-9_-]{1,32}")))
    }

    @Test
    fun preparedIntentSurvivesLedgerReopen() {
        val id = ledger!!.prepare(
            operationId = "op-restart",
            kind = "POST:/api/v3/order",
            symbol = "BTCUSDT",
            requestFingerprint = "a".repeat(64),
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
        val first = ledger!!.prepare("op-duplicate", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-a")
        val second = ledger!!.prepare("op-duplicate", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-a")

        assertEquals(first.operationId, second.operationId)
        assertEquals(DurableMutationLedger.State.PREPARED, second.state)
        assertEquals(1, ledger!!.unresolved().size)
    }

    @Test
    fun conflictingReuseOfOperationIdIsRejected() {
        ledger!!.prepare("op-conflict", "POST:/api/v3/order", "BTCUSDT", "b".repeat(64), "client-a")

        assertThrows(DurableMutationLedger.IntentConflictException::class.java) {
            ledger!!.prepare("op-conflict", "POST:/api/v3/order", "ETHUSDT", "c".repeat(64), "client-b")
        }
    }

    @Test
    fun unknownIntentRemainsUnresolvedAfterRestart() {
        ledger!!.prepare("op-unknown", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-unknown")
        ledger!!.claimForSubmission("op-unknown")
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
        ledger!!.prepare("op-ack", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-ack")
        ledger!!.claimForSubmission("op-ack")
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
        ledger!!.prepare("op-invalid", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-invalid")

        assertThrows(DurableMutationLedger.InvalidTransitionException::class.java) {
            ledger!!.transition(
                "op-invalid",
                setOf(DurableMutationLedger.State.PREPARED),
                DurableMutationLedger.State.RECONCILED
            )
        }
    }
}

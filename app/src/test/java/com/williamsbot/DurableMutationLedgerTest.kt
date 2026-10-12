package com.williamsbot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
            "POST", "/api/v3/order", "BTCUSDT", "symbol=BTCUSDT&side=BUY&timeInForce="
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
        val second = ledger!!.prepare("op-duplicate", "POST:/api/v3/order", "BTCUSDT", "fp", "client-a")

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

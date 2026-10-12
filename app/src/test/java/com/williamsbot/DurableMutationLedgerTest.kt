package com.williamsbot

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun clientIdGenerationFailsClosedWhenLogicalOperationIdentityIsMissing() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationIdentity.generatedClientOrderId(
                "POST", "/api/v3/order", "BTCUSDT",
                "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
            )
        }
    }

    @Test
    fun identicalEconomicsCanProduceDistinctIdsOnlyWithDistinctLogicalOperations() {
        val params = "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
        val first = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", params, "entry-operation-1"
        )
        val second = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT", params, "entry-operation-2"
        )
        assertFalse(first == second)
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
    fun parameterKeysThatDifferOnlyByCaseAreRejectedAsDuplicates() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationIdentity.canonicalUnsignedParams("symbol=BTCUSDT&side=BUY&SIDE=SELL")
        }
    }

    @Test
    fun transportMetadataIsExcludedCaseInsensitively() {
        val first = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT",
            "symbol=BTCUSDT&side=BUY&timestamp=123&recvWindow=5000"
        )
        val equivalent = MutationIdentity.fingerprint(
            "POST", "/api/v3/order", "BTCUSDT",
            "symbol=BTCUSDT&side=BUY&TIMESTAMP=456&recvwindow=10000"
        )
        assertEquals(first, equivalent)
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
    fun sameLogicalOperationKeepsClientIdWhenRequestEconomicsChange() {
        val original = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT",
            "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01",
            "persisted-entry-operation-77"
        )
        val changed = MutationIdentity.generatedClientOrderId(
            "POST", "/api/v3/order", "BTCUSDT",
            "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.02",
            "persisted-entry-operation-77"
        )

        // Client identity names the logical operation. The ledger fingerprint
        // must reject changed economics under that same operation ID.
        assertEquals(original, changed)
        assertFalse(
            MutationIdentity.fingerprint(
                "POST", "/api/v3/order", "BTCUSDT",
                "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.01"
            ) == MutationIdentity.fingerprint(
                "POST", "/api/v3/order", "BTCUSDT",
                "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.02"
            )
        )
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
    fun sameOperationIdWithChangedRequestFingerprintIsRejected() {
        ledger!!.prepare(
            operationId = "stable-business-operation",
            kind = "POST:/api/v3/order",
            symbol = "BTCUSDT",
            requestFingerprint = "1".repeat(64),
            exchangeClientId = "W21_stable"
        )

        assertThrows(DurableMutationLedger.IntentConflictException::class.java) {
            ledger!!.prepare(
                operationId = "stable-business-operation",
                kind = "POST:/api/v3/order",
                symbol = "BTCUSDT",
                requestFingerprint = "2".repeat(64),
                exchangeClientId = "W21_stable"
            )
        }
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
    fun conflictingPrepareRollsBackAndPreservesOriginalIntent() {
        val original = ledger!!.prepare(
            "immutable-operation",
            "POST:/api/v3/order",
            "BTCUSDT",
            "a".repeat(64),
            "W21_immutable"
        )
        assertThrows(DurableMutationLedger.IntentConflictException::class.java) {
            ledger!!.prepare(
                "immutable-operation",
                "POST:/api/v3/order",
                "BTCUSDT",
                "b".repeat(64),
                "W21_immutable"
            )
        }
        val after = ledger!!.find("immutable-operation")!!
        assertEquals(original.requestFingerprint, after.requestFingerprint)
        assertEquals(original.exchangeClientId, after.exchangeClientId)
        assertEquals(DurableMutationLedger.State.PREPARED, after.state)
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
    fun acknowledgedIntentRemainsBlockedUntilExchangeReconciliation() {
        ledger!!.prepare("op-ack", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-ack")
        ledger!!.claimForSubmission("op-ack")
        ledger!!.transition(
            "op-ack",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.ACKNOWLEDGED,
            exchangeOrderId = "12345"
        )

        assertTrue(ledger!!.hasUnresolved())
        assertEquals(DurableMutationLedger.State.ACKNOWLEDGED, ledger!!.unresolved().single().state)
        assertEquals("12345", ledger!!.find("op-ack")!!.exchangeOrderId)
    }

    @Test
    fun acknowledgedEntryRemainsUnresolvedButAllowsDependentProtectionMutation() {
        ledger!!.prepare("entry-op", "POST:/api/v3/order", "BTCUSDT", "a".repeat(64), "entry-client")
        ledger!!.claimForSubmission("entry-op")
        ledger!!.transition(
            "entry-op",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.ACKNOWLEDGED,
            exchangeOrderId = "12345"
        )

        // A response is not exchange reconciliation, so the execution gate
        // must remain blocked; however, a dependent protection mutation must
        // not be prevented solely by the acknowledged entry journal row.
        assertTrue(ledger!!.hasUnresolved())
        ledger!!.prepare(
            "protection-op",
            "POST:/api/v3/orderList/oco",
            "BTCUSDT",
            "b".repeat(64),
            "protection-client"
        )
        assertEquals(
            DurableMutationLedger.State.SUBMITTING,
            ledger!!.claimForSubmission("protection-op").state
        )
        assertTrue(ledger!!.hasUnresolved())
    }

    @Test
    fun ambiguousSingleOrderIntentCanBeReconciledOnlyWithExplicitExchangeOrderId() {
        ledger!!.prepare(
            "op-ambiguous-reconcile",
            "POST:/api/v3/order",
            "BTCUSDT",
            "c".repeat(64),
            "W21_reconcile"
        )
        ledger!!.claimForSubmission("op-ambiguous-reconcile")
        ledger!!.transition(
            "op-ambiguous-reconcile",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.UNKNOWN
        )

        val reconciled = ledger!!.transition(
            "op-ambiguous-reconcile",
            setOf(DurableMutationLedger.State.UNKNOWN),
            DurableMutationLedger.State.RECONCILED,
            exchangeOrderId = "987654",
            reconciliationEvidenceJson = """{"source":"BINANCE_SPOT_TESTNET","scope":"ORDER_MUTATION","query_complete":true,"order_verified":true,"fills_verified":true,"checked_at_ms":1760000000000}"""
        )

        assertEquals(DurableMutationLedger.State.RECONCILED, reconciled.state)
        assertEquals("987654", reconciled.exchangeOrderId)
        assertFalse(ledger!!.hasUnresolved())
    }

    @Test
    fun acknowledgedIntentCanRemainUnresolvedUntilReconciliationEvidenceIsPersisted() {
        ledger!!.prepare("op-ack-reconcile", "POST:/api/v3/order", "BTCUSDT", "a".repeat(64), "W21_ack")
        ledger!!.claimForSubmission("op-ack-reconcile")
        ledger!!.transition(
            "op-ack-reconcile",
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.ACKNOWLEDGED,
            exchangeOrderId = "123"
        )
        assertTrue(ledger!!.hasUnresolved())

        val reconciled = ledger!!.transition(
            "op-ack-reconcile",
            setOf(DurableMutationLedger.State.ACKNOWLEDGED),
            DurableMutationLedger.State.RECONCILED,
            exchangeOrderId = "123",
            reconciliationEvidenceJson = """{"source":"BINANCE_SPOT_TESTNET","scope":"ORDER_MUTATION","query_complete":true,"order_verified":true,"fills_verified":true,"checked_at_ms":1760000000000}"""
        )
        assertEquals(DurableMutationLedger.State.RECONCILED, reconciled.state)
        assertFalse(ledger!!.hasUnresolved())
    }

    @Test
    fun reconciledStateRequiresCompletePersistedExchangeEvidence() {
        val prepared = ledger!!.prepare(
            "evidence-required-op",
            "POST:/api/v3/order",
            "BTCUSDT",
            "e".repeat(64),
            "W21_evidence_required"
        )
        ledger!!.claimForSubmission(prepared.operationId)
        ledger!!.transition(
            prepared.operationId,
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.UNKNOWN
        )

        assertThrows(IllegalArgumentException::class.java) {
            ledger!!.transition(
                prepared.operationId,
                setOf(DurableMutationLedger.State.UNKNOWN),
                DurableMutationLedger.State.RECONCILED
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger!!.transition(
                prepared.operationId,
                setOf(DurableMutationLedger.State.UNKNOWN),
                DurableMutationLedger.State.RECONCILED,
                reconciliationEvidenceJson = """{"source":"BINANCE_SPOT_TESTNET","scope":"ORDER_MUTATION","query_complete":false,"order_verified":true,"fills_verified":true,"checked_at_ms":1760000000000}"""
            )
        }

        assertTrue(ledger!!.hasUnresolved())
        assertEquals(DurableMutationLedger.State.UNKNOWN, ledger!!.find(prepared.operationId)!!.state)
        assertNull(ledger!!.find(prepared.operationId)!!.reconciliationEvidenceJson)
    }

    @Test
    fun legacyReconciledRowsWithoutEvidenceAreReopenedAsUnresolvedDuringMigration() {
        ledger!!.close()
        context.deleteDatabase("williams_mutation_ledger.db")
        val path = context.getDatabasePath("williams_mutation_ledger.db")
        path.parentFile?.mkdirs()
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        legacy.execSQL(
            """CREATE TABLE mutation_intents (
                operation_id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                symbol TEXT NOT NULL,
                request_fingerprint TEXT NOT NULL,
                exchange_client_id TEXT,
                state TEXT NOT NULL,
                exchange_order_id TEXT,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                exchange TEXT NOT NULL DEFAULT 'BINANCE_SPOT',
                campaign_id TEXT,
                signal_id TEXT,
                attempt_id TEXT NOT NULL
            )""".trimIndent()
        )
        legacy.execSQL("CREATE INDEX mutation_intents_state_idx ON mutation_intents(state)")
        legacy.execSQL("CREATE INDEX mutation_intents_symbol_idx ON mutation_intents(symbol, state)")
        legacy.execSQL("CREATE INDEX mutation_intents_exchange_client_idx ON mutation_intents(exchange, exchange_client_id)")
        legacy.execSQL(
            "INSERT INTO mutation_intents(operation_id,kind,symbol,request_fingerprint,exchange_client_id,state,created_at_ms,updated_at_ms,exchange,attempt_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf("legacy-reconciled", "POST:/api/v3/order", "BTCUSDT", "f".repeat(64), "W21_legacy", "RECONCILED", 100L, 200L, "BINANCE_SPOT", "legacy-attempt-1")
        )
        legacy.version = 2
        legacy.close()

        ledger = DurableMutationLedger(context)
        val migrated = ledger!!.find("legacy-reconciled")!!
        assertEquals(DurableMutationLedger.State.RECONCILING, migrated.state)
        assertTrue(ledger!!.hasUnresolved())
        assertNull(migrated.reconciliationEvidenceJson)
    }

    @Test
    fun illegalStateTransitionIsRejected() {
        ledger!!.prepare("op-invalid", "POST:/api/v3/order", "BTCUSDT", "d".repeat(64), "client-invalid")

        assertThrows(DurableMutationLedger.InvalidTransitionException::class.java) {
            ledger!!.transition(
                "op-invalid",
                setOf(DurableMutationLedger.State.PREPARED),
                DurableMutationLedger.State.RECONCILED,
                reconciliationEvidenceJson = """{"source":"BINANCE_SPOT_TESTNET","scope":"ORDER_MUTATION","query_complete":true,"order_verified":true,"fills_verified":true,"checked_at_ms":1760000000000}"""
            )
        }
    }

    @Test
    fun reconcilingIntentRemainsUnresolvedAcrossWorkerCrash() {
        val prepared = ledger!!.prepare(
            operationId = "reconcile-crash-op",
            kind = "POST:/api/v3/order",
            symbol = "BTCUSDT",
            requestFingerprint = "a".repeat(64),
            exchangeClientId = "W21_reconcile_crash"
        )
        ledger!!.claimForSubmission(prepared.operationId)
        ledger!!.transition(
            prepared.operationId,
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.UNKNOWN
        )
        ledger!!.transition(
            prepared.operationId,
            setOf(DurableMutationLedger.State.UNKNOWN),
            DurableMutationLedger.State.RECONCILING
        )

        assertTrue(ledger!!.hasUnresolved())
        assertEquals(1, ledger!!.unresolved().size)
        assertEquals(
            DurableMutationLedger.State.RECONCILING,
            ledger!!.find(prepared.operationId)!!.state
        )
    }

    @Test
    fun reconcilingIntentCanResumeAndOnlyReconciledClosesTheBarrier() {
        val prepared = ledger!!.prepare(
            operationId = "reconcile-resume-op",
            kind = "POST:/api/v3/order",
            symbol = "ETHUSDT",
            requestFingerprint = "b".repeat(64),
            exchangeClientId = "W21_reconcile_resume"
        )
        ledger!!.claimForSubmission(prepared.operationId)
        ledger!!.transition(
            prepared.operationId,
            setOf(DurableMutationLedger.State.SUBMITTING),
            DurableMutationLedger.State.RECONCILING
        )

        assertTrue(ledger!!.hasUnresolved())
        ledger!!.transition(
            prepared.operationId,
            setOf(DurableMutationLedger.State.RECONCILING),
            DurableMutationLedger.State.RECONCILED,
            exchangeOrderId = "testnet-order-123",
            reconciliationEvidenceJson = """{"source":"BINANCE_SPOT_TESTNET","scope":"ORDER_MUTATION","query_complete":true,"order_verified":true,"fills_verified":true,"checked_at_ms":1760000000000}"""
        )
        assertFalse(ledger!!.hasUnresolved())
        assertEquals("testnet-order-123", ledger!!.find(prepared.operationId)!!.exchangeOrderId)
    }
}

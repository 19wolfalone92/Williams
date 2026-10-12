package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * Durable journal for exchange mutations.
 *
 * A durable SUBMITTING claim is intentionally unresolved after a crash: local
 * SQLite and Binance do not share a transaction. A journal state is not proof
 * of the exchange outcome.
 */
class DurableMutationLedger(context: Context) :
    SQLiteOpenHelper(context, "williams_mutation_ledger.db", null, 2) {

    enum class State {
        PREPARED,
        SUBMITTING,
        UNKNOWN,
        ACKNOWLEDGED,
        RECONCILED,
        REJECTED
    }

    data class Intent(
        val operationId: String,
        val kind: String,
        val symbol: String,
        val requestFingerprint: String,
        val exchangeClientId: String?,
        val state: State,
        val exchangeOrderId: String?,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val exchange: String,
        val campaignId: String?,
        val signalId: String?,
        val attemptId: String
    )

    class IntentConflictException : IllegalStateException("MUTATION_INTENT_CONFLICT")
    class InvalidTransitionException : IllegalStateException("MUTATION_STATE_TRANSITION_REJECTED")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE mutation_intents (
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
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX mutation_intents_state_idx ON mutation_intents(state)")
        db.execSQL("CREATE INDEX mutation_intents_symbol_idx ON mutation_intents(symbol, state)")
        db.execSQL("CREATE INDEX mutation_intents_exchange_client_idx ON mutation_intents(exchange, exchange_client_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migration is additive and preserves every existing intent, including
        // unresolved SUBMITTING/UNKNOWN rows. Never drop or recreate the table.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE mutation_intents ADD COLUMN exchange TEXT NOT NULL DEFAULT 'BINANCE_SPOT'")
            db.execSQL("ALTER TABLE mutation_intents ADD COLUMN campaign_id TEXT")
            db.execSQL("ALTER TABLE mutation_intents ADD COLUMN signal_id TEXT")
            db.execSQL("ALTER TABLE mutation_intents ADD COLUMN attempt_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "UPDATE mutation_intents SET attempt_id = operation_id || ':attempt-1' " +
                    "WHERE attempt_id = ''"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS mutation_intents_exchange_client_idx " +
                    "ON mutation_intents(exchange, exchange_client_id)"
            )
        }
        if (newVersion > 2) {
            throw IllegalStateException("MUTATION_LEDGER_MIGRATION_REQUIRED")
        }
    }

    @Synchronized
    fun prepare(
        operationId: String,
        kind: String,
        symbol: String,
        requestFingerprint: String,
        exchangeClientId: String? = null,
        exchange: String = "BINANCE_SPOT",
        campaignId: String? = null,
        signalId: String? = null,
        attemptId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Intent {
        require(operationId.isNotBlank() && kind.isNotBlank() && symbol.isNotBlank() &&
            requestFingerprint.matches(Regex("[0-9a-fA-F]{64}")) && exchange.isNotBlank()) {
            "MUTATION_INTENT_FIELDS_REQUIRED"
        }
        val normalizedSymbol = symbol.uppercase(Locale.US)
        val normalizedExchange = exchange.uppercase(Locale.US)
        val stableAttemptId = attemptId ?: "$operationId:attempt-1"
        require(stableAttemptId.isNotBlank()) { "MUTATION_ATTEMPT_ID_REQUIRED" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readById(db, operationId)
            if (existing != null) {
                if (existing.kind != kind || existing.symbol != normalizedSymbol ||
                    existing.requestFingerprint != requestFingerprint.lowercase(Locale.US) ||
                    existing.exchangeClientId != exchangeClientId ||
                    existing.exchange != normalizedExchange ||
                    existing.campaignId != campaignId ||
                    existing.signalId != signalId ||
                    existing.attemptId != stableAttemptId) {
                    throw IntentConflictException()
                }
                db.setTransactionSuccessful()
                return existing
            }

            val values = ContentValues().apply {
                put("operation_id", operationId)
                put("kind", kind)
                put("symbol", normalizedSymbol)
                put("request_fingerprint", requestFingerprint.lowercase(Locale.US))
                if (exchangeClientId == null) putNull("exchange_client_id")
                else put("exchange_client_id", exchangeClientId)
                put("state", State.PREPARED.name)
                putNull("exchange_order_id")
                put("created_at_ms", nowMs)
                put("updated_at_ms", nowMs)
                put("exchange", normalizedExchange)
                if (campaignId == null) putNull("campaign_id") else put("campaign_id", campaignId)
                if (signalId == null) putNull("signal_id") else put("signal_id", signalId)
                put("attempt_id", stableAttemptId)
            }
            check(db.insertOrThrow("mutation_intents", null, values) != -1L) {
                "MUTATION_INTENT_PERSIST_FAILED"
            }
            db.setTransactionSuccessful()
            return requireNotNull(readById(db, operationId))
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun transition(
        operationId: String,
        expected: Set<State>,
        next: State,
        exchangeOrderId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Intent {
        require(expected.isNotEmpty()) { "MUTATION_EXPECTED_STATE_REQUIRED" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readById(db, operationId)
                ?: throw IllegalStateException("MUTATION_INTENT_NOT_FOUND")
            // Never treat an already-claimed target state as a successful claim.
            // In particular, a second worker asking PREPARED -> SUBMITTING must fail
            // after the first worker commits; otherwise both could submit the mutation.
            if (current.state !in expected || !allowed(current.state, next)) {
                throw InvalidTransitionException()
            }
            val values = ContentValues().apply {
                put("state", next.name)
                put("updated_at_ms", nowMs)
                if (exchangeOrderId != null) put("exchange_order_id", exchangeOrderId)
            }
            val changed = db.update(
                "mutation_intents",
                values,
                "operation_id = ? AND state = ?",
                arrayOf(operationId, current.state.name)
            )
            check(changed == 1) { "MUTATION_CONCURRENT_TRANSITION" }
            db.setTransactionSuccessful()
            return requireNotNull(readById(db, operationId))
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomically acquire the only submission claim for this symbol while another
     * operation is in SUBMITTING/UNKNOWN. A second worker cannot reuse a claim
     * merely because the row already has the target state.
     */
    @Synchronized
    fun claimForSubmission(operationId: String, nowMs: Long = System.currentTimeMillis()): Intent {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readById(db, operationId)
                ?: throw IllegalStateException("MUTATION_INTENT_NOT_FOUND")
            if (current.state != State.PREPARED) throw InvalidTransitionException()

            val activeConflict = db.rawQuery(
                "SELECT operation_id FROM mutation_intents " +
                    "WHERE symbol = ? AND state IN (?, ?) AND operation_id <> ? LIMIT 1",
                arrayOf(
                    current.symbol,
                    State.SUBMITTING.name,
                    State.UNKNOWN.name,
                    operationId
                )
            ).use { it.moveToFirst() }
            if (activeConflict) {
                throw IllegalStateException("MUTATION_RECONCILIATION_REQUIRED")
            }

            val values = ContentValues().apply {
                put("state", State.SUBMITTING.name)
                put("updated_at_ms", nowMs)
            }
            val changed = db.update(
                "mutation_intents",
                values,
                "operation_id = ? AND state = ?",
                arrayOf(operationId, State.PREPARED.name)
            )
            check(changed == 1) { "MUTATION_SUBMISSION_CLAIM_FAILED" }
            db.setTransactionSuccessful()
            return requireNotNull(readById(db, operationId))
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun unresolved(): List<Intent> {
        val db = readableDatabase
        val result = mutableListOf<Intent>()
        db.rawQuery(
            "SELECT operation_id, kind, symbol, request_fingerprint, exchange_client_id, state, " +
                "exchange_order_id, created_at_ms, updated_at_ms, exchange, campaign_id, signal_id, attempt_id " +
                "FROM mutation_intents WHERE state IN (?, ?, ?) ORDER BY created_at_ms",
            arrayOf(State.PREPARED.name, State.SUBMITTING.name, State.UNKNOWN.name)
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursorToIntent(cursor)
        }
        return result
    }

    @Synchronized
    fun hasUnresolved(): Boolean = unresolved().isNotEmpty()

    @Synchronized
    fun find(operationId: String): Intent? = readById(readableDatabase, operationId)

    private fun readById(db: SQLiteDatabase, operationId: String): Intent? =
        db.rawQuery(
            "SELECT operation_id, kind, symbol, request_fingerprint, exchange_client_id, state, " +
                "exchange_order_id, created_at_ms, updated_at_ms, exchange, campaign_id, signal_id, attempt_id " +
                "FROM mutation_intents WHERE operation_id = ?",
            arrayOf(operationId)
        ).use { cursor -> if (cursor.moveToFirst()) cursorToIntent(cursor) else null }

    private fun cursorToIntent(cursor: android.database.Cursor): Intent = Intent(
        operationId = cursor.getString(0),
        kind = cursor.getString(1),
        symbol = cursor.getString(2),
        requestFingerprint = cursor.getString(3),
        exchangeClientId = if (cursor.isNull(4)) null else cursor.getString(4),
        state = State.valueOf(cursor.getString(5)),
        exchangeOrderId = if (cursor.isNull(6)) null else cursor.getString(6),
        createdAtMs = cursor.getLong(7),
        updatedAtMs = cursor.getLong(8),
        exchange = cursor.getString(9),
        campaignId = if (cursor.isNull(10)) null else cursor.getString(10),
        signalId = if (cursor.isNull(11)) null else cursor.getString(11),
        attemptId = cursor.getString(12)
    )

    private fun allowed(from: State, to: State): Boolean = when (from) {
        State.PREPARED -> to == State.REJECTED
        State.SUBMITTING -> to == State.ACKNOWLEDGED || to == State.UNKNOWN ||
            to == State.REJECTED || to == State.RECONCILED
        State.UNKNOWN -> to == State.ACKNOWLEDGED || to == State.RECONCILED
        State.ACKNOWLEDGED -> to == State.RECONCILED || to == State.UNKNOWN
        State.RECONCILED, State.REJECTED -> false
    }
}

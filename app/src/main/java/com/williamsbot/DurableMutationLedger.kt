package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable journal for exchange mutations.
 *
 * The journal is deliberately conservative: SUBMITTING is treated as unresolved
 * after a crash because the process may have died after Binance accepted a
 * request but before the response was persisted. It does not claim a SQLite
 * transaction is atomic with Binance.
 *
 * Integration requirement: every exchange mutation must obtain an operation
 * from this journal before reaching the network sink. This class alone does
 * not provide that integration.
 */
class DurableMutationLedger(context: Context) :
    SQLiteOpenHelper(context, "williams_mutation_ledger.db", null, 1) {

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
        val updatedAtMs: Long
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
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX mutation_intents_state_idx ON mutation_intents(state)")
        db.execSQL("CREATE INDEX mutation_intents_symbol_idx ON mutation_intents(symbol, state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations must be added explicitly; never discard unresolved intents.
        throw IllegalStateException("MUTATION_LEDGER_MIGRATION_REQUIRED")
    }

    @Synchronized
    fun prepare(
        operationId: String,
        kind: String,
        symbol: String,
        requestFingerprint: String,
        exchangeClientId: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Intent {
        require(operationId.isNotBlank() && kind.isNotBlank() && symbol.isNotBlank() &&
            requestFingerprint.isNotBlank()) { "MUTATION_INTENT_FIELDS_REQUIRED" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readById(db, operationId)
            if (existing != null) {
                if (existing.kind != kind || existing.symbol != symbol ||
                    existing.requestFingerprint != requestFingerprint ||
                    existing.exchangeClientId != exchangeClientId) {
                    throw IntentConflictException()
                }
                db.setTransactionSuccessful()
                return existing
            }

            val values = ContentValues().apply {
                put("operation_id", operationId)
                put("kind", kind)
                put("symbol", symbol.uppercase(java.util.Locale.US))
                put("request_fingerprint", requestFingerprint)
                if (exchangeClientId == null) putNull("exchange_client_id")
                else put("exchange_client_id", exchangeClientId)
                put("state", State.PREPARED.name)
                putNull("exchange_order_id")
                put("created_at_ms", nowMs)
                put("updated_at_ms", nowMs)
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
            if (current.state == next) {
                db.setTransactionSuccessful()
                return current
            }
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

    @Synchronized
    fun unresolved(): List<Intent> {
        val db = readableDatabase
        val result = mutableListOf<Intent>()
        db.rawQuery(
            "SELECT operation_id, kind, symbol, request_fingerprint, exchange_client_id, state, exchange_order_id, updated_at_ms " +
                "FROM mutation_intents WHERE state IN (?, ?, ?) ORDER BY created_at_ms",
            arrayOf(State.PREPARED.name, State.SUBMITTING.name, State.UNKNOWN.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Intent(
                    operationId = cursor.getString(0),
                    kind = cursor.getString(1),
                    symbol = cursor.getString(2),
                    requestFingerprint = cursor.getString(3),
                    exchangeClientId = if (cursor.isNull(4)) null else cursor.getString(4),
                    state = State.valueOf(cursor.getString(5)),
                    exchangeOrderId = if (cursor.isNull(6)) null else cursor.getString(6),
                    updatedAtMs = cursor.getLong(7)
                )
            }
        }
        return result
    }

    @Synchronized
    fun hasUnresolved(): Boolean = unresolved().isNotEmpty()

    @Synchronized
    fun find(operationId: String): Intent? = readById(readableDatabase, operationId)

    private fun readById(db: SQLiteDatabase, operationId: String): Intent? =
        db.rawQuery(
            "SELECT operation_id, kind, symbol, request_fingerprint, exchange_client_id, state, exchange_order_id, updated_at_ms " +
                "FROM mutation_intents WHERE operation_id = ?",
            arrayOf(operationId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else Intent(
                operationId = cursor.getString(0),
                kind = cursor.getString(1),
                symbol = cursor.getString(2),
                requestFingerprint = cursor.getString(3),
                exchangeClientId = if (cursor.isNull(4)) null else cursor.getString(4),
                state = State.valueOf(cursor.getString(5)),
                exchangeOrderId = if (cursor.isNull(6)) null else cursor.getString(6),
                updatedAtMs = cursor.getLong(7)
            )
        }

    private fun allowed(from: State, to: State): Boolean = when (from) {
        State.PREPARED -> to == State.SUBMITTING || to == State.REJECTED
        State.SUBMITTING -> to == State.ACKNOWLEDGED || to == State.UNKNOWN ||
            to == State.REJECTED || to == State.RECONCILED
        State.UNKNOWN -> to == State.ACKNOWLEDGED || to == State.RECONCILED
        State.ACKNOWLEDGED -> to == State.RECONCILED || to == State.UNKNOWN
        State.RECONCILED, State.REJECTED -> false
    }
}

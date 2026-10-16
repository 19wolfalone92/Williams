package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONObject

/**
 * Durable on-device source of truth for Futures execution intents and campaign state.
 *
 * Only nonsensitive execution metadata is stored here. API credentials stay in the
 * existing Keystore-backed EncryptedSharedPreferences store. Every order intent is
 * committed to SQLite before the corresponding Binance mutation is attempted.
 */
internal class FuturesExecutionStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    @Synchronized
    fun healthSnapshot(): JSONObject = runCatching {
        val check = readableDatabase.rawQuery("PRAGMA quick_check(1)", null).use {
            if (it.moveToFirst()) it.getString(0) else "No result"
        }
        JSONObject().put("quick_check", check).put("ok", check == "ok")
            .put("observed_at_ms", System.currentTimeMillis()).put("schema_version", DATABASE_VERSION)
    }.getOrElse { JSONObject().put("ok", false).put("error", safeDiagnosticText(it.message.orEmpty())) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runtime_state (
                state_key TEXT PRIMARY KEY NOT NULL,
                state_value TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The key/value journal schema is forward-compatible; upgrades must never
        // discard persisted orders, campaigns, or unresolved execution state.
        onCreate(db)
    }

    /** Migrate pre-SQLite JSON state once; SQLite wins after the first successful copy. */
    @Synchronized
    fun migrateLegacyState(legacy: SharedPreferences, keys: List<String>) {
        val db = writableDatabase
        db.transaction {
            keys.forEach { key ->
                val exists = db.rawQuery(
                    "SELECT 1 FROM runtime_state WHERE state_key = ? LIMIT 1",
                    arrayOf(key)
                ).use { it.moveToFirst() }
                if (!exists) {
                    // Legacy state includes String snapshots and Boolean safety latches.
                    // Convert by type so migration cannot crash on getString().
                    val oldValue = legacy.all[key]?.toString() ?: return@forEach
                    val values = ContentValues().apply {
                        put("state_key", key)
                        put("state_value", oldValue)
                        put("updated_at_ms", System.currentTimeMillis())
                    }
                    check(db.insertOrThrow("runtime_state", null, values) != -1L) {
                        "SQLite migration insert failed for $key"
                    }
                }
            }
        }
    }

    @Synchronized
    fun readState(key: String): String? {
        return readableDatabase.query(
            "runtime_state",
            arrayOf("state_value"),
            "state_key = ?",
            arrayOf(key),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    @Synchronized
    fun writeState(key: String, value: String): Boolean =
        writeStates(mapOf(key to value))

    /**
     * Persist the account-wide execution-door lease atomically before an exchange
     * mutation. Empty values clear the lease after the mutation has been reconciled.
     */
    @Synchronized
    fun writeExecutionGate(symbol: String, purpose: String): Boolean =
        writeStates(
            mapOf(
                "execution_gate_symbol" to symbol,
                "execution_gate_purpose" to purpose
            )
        )

    /** Commit positions and pending entries atomically so a crash cannot split a snapshot. */
    @Synchronized
    fun writeRuntimeState(positionsJson: String, pendingEntriesJson: String): Boolean =
        writeStates(
            mapOf(
                KEY_POSITIONS to positionsJson,
                KEY_PENDING_ENTRIES to pendingEntriesJson
            )
        )

    private fun writeStates(values: Map<String, String>): Boolean {
        return try {
            val db = writableDatabase
            db.transaction {
                values.forEach { (key, value) ->
                    val row = ContentValues().apply {
                        put("state_key", key)
                        put("state_value", value)
                        put("updated_at_ms", System.currentTimeMillis())
                    }
                    check(
                        db.insertWithOnConflict(
                            "runtime_state",
                            null,
                            row,
                            SQLiteDatabase.CONFLICT_REPLACE
                        ) != -1L
                    ) { "SQLite write failed for $key" }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val DATABASE_NAME = "williams_futures_execution.sqlite"
        private const val DATABASE_VERSION = 1
        private const val KEY_POSITIONS = "positions_json"
        private const val KEY_PENDING_ENTRIES = "pending_entries_json"
    }
}

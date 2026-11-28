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
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION), FuturesCampaignStateStore {

    @Synchronized
    fun healthSnapshot(): JSONObject = runCatching {
        val check = readableDatabase.rawQuery("PRAGMA quick_check(1)", null).use {
            if (it.moveToFirst()) it.getString(0) else "No result"
        }
        JSONObject().put("quick_check", check).put("ok", check == "ok")
            .put("observed_at_ms", System.currentTimeMillis()).put("schema_version", DATABASE_VERSION)
    }.getOrElse { JSONObject().put("ok", false).put("error", safeDiagnosticText(it.message.orEmpty())) }

    private fun ensureIntentShadowSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS futures_intent_shadow(
                ordinal INTEGER PRIMARY KEY NOT NULL,
                client_id TEXT NOT NULL DEFAULT '',
                symbol TEXT NOT NULL DEFAULT '',
                intent_role TEXT NOT NULL DEFAULT 'ENTRY',
                campaign_status TEXT NOT NULL DEFAULT '',
                order_status TEXT NOT NULL DEFAULT '',
                exchange_status TEXT NOT NULL DEFAULT '',
                protection_status TEXT NOT NULL DEFAULT '',
                strategy_profile TEXT NOT NULL DEFAULT '',
                payload_sha256 TEXT NOT NULL,
                projected_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_futures_intent_shadow_client ON futures_intent_shadow(client_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_futures_intent_shadow_symbol ON futures_intent_shadow(symbol, intent_role, campaign_status)")
    }

    /** The shadow is rebuilt atomically but never becomes execution authority in this phase. */
    private fun replaceIntentShadow(db: SQLiteDatabase, rawJson: String) {
        val projection = projectFuturesCampaignIntents(rawJson)
            ?: error("FUTURES_INTENT_SHADOW_PROJECTION_INVALID")
        db.delete("futures_intent_shadow", null, null)
        val now = System.currentTimeMillis()
        projection.rows.forEach { item ->
            val row = ContentValues().apply {
                put("ordinal", item.ordinal)
                put("client_id", item.clientId)
                put("symbol", item.symbol)
                put("intent_role", item.intentRole)
                put("campaign_status", item.campaignStatus)
                put("order_status", item.orderStatus)
                put("exchange_status", item.exchangeStatus)
                put("protection_status", item.protectionStatus)
                put("strategy_profile", item.strategyProfile)
                put("payload_sha256", item.payloadSha256)
                put("projected_at_ms", now)
            }
            check(db.insertOrThrow("futures_intent_shadow", null, row) != -1L) {
                "FUTURES_INTENT_SHADOW_WRITE_FAILED"
            }
        }
    }

    @Synchronized
    private fun readIntentShadowRows(): List<FuturesIntentShadowRow> {
        val rows = mutableListOf<FuturesIntentShadowRow>()
        readableDatabase.query(
            "futures_intent_shadow",
            arrayOf("ordinal", "client_id", "symbol", "intent_role", "campaign_status",
                "order_status", "exchange_status", "protection_status", "strategy_profile", "payload_sha256"),
            null, null, null, null, "ordinal ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += FuturesIntentShadowRow(
                    ordinal = cursor.getInt(0),
                    clientId = cursor.getString(1),
                    symbol = cursor.getString(2),
                    intentRole = cursor.getString(3),
                    campaignStatus = cursor.getString(4),
                    orderStatus = cursor.getString(5),
                    exchangeStatus = cursor.getString(6),
                    protectionStatus = cursor.getString(7),
                    strategyProfile = cursor.getString(8),
                    payloadSha256 = cursor.getString(9)
                )
            }
        }
        return rows
    }

    /** Observational dual-write parity. Legacy JSON remains the only source of truth. */
    @Synchronized
    fun campaignIntentShadowSnapshot(): JSONObject = runCatching {
        val canonicalRaw = readState(KEY_CAMPAIGN_INTENTS)
        val canonical = canonicalRaw?.let(::projectFuturesCampaignIntents)
        futuresIntentShadowParity(canonical, readIntentShadowRows())
    }.getOrElse {
        JSONObject()
            .put("schema", "FUTURES_INTENT_SHADOW_PARITY_V1")
            .put("severity", "NOT_VERIFIED")
            .put("authority", "LEGACY_JSON_ONLY")
            .put("error", safeDiagnosticText(it.message ?: it.javaClass.simpleName))
    }

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
        ensureIntentShadowSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The key/value journal schema is forward-compatible; upgrades must never
        // discard persisted orders, campaigns, or unresolved execution state.
        onCreate(db)
        ensureIntentShadowSchema(db)
        // The app's explicit migrateLegacyState() call performs the first shadow
        // projection after this schema upgrade. Do not parse or rewrite financial
        // state inside onUpgrade; a malformed legacy blob must remain preserved.
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
            db.rawQuery(
                "SELECT state_value FROM runtime_state WHERE state_key=? LIMIT 1",
                arrayOf(KEY_CAMPAIGN_INTENTS)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val raw = cursor.getString(0)
                    // Preserve legacy JSON exactly even when it is malformed.
                    // An unprojectable shadow remains NOT_VERIFIED; it never
                    // replaces the canonical execution journal.
                    if (projectFuturesCampaignIntents(raw) != null) {
                        replaceIntentShadow(db, raw)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun readState(key: String): String? {
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
    override fun writeState(key: String, value: String): Boolean =
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

    /** One transaction for the displayed Model B financial measurements. */
    @Synchronized
    fun writeDailyRiskMetrics(lossFraction: String, pnl: String, externalFlows: String): Boolean =
        writeStates(mapOf(
            "futures_daily_loss_pct" to lossFraction,
            "futures_daily_pnl_usdt" to pnl,
            "futures_daily_capital_flow_usdt" to externalFlows
        ))

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
                values[KEY_CAMPAIGN_INTENTS]?.let { rawJson ->
                    replaceIntentShadow(db, rawJson)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val DATABASE_NAME = "williams_futures_execution.sqlite"
        private const val DATABASE_VERSION = 2
        private const val KEY_POSITIONS = "positions_json"
        private const val KEY_PENDING_ENTRIES = "pending_entries_json"
        private const val KEY_CAMPAIGN_INTENTS = "futures_campaign_intents_v2"
    }
}

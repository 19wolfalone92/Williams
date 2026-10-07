package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable intent log for the non-atomic OCO cancel/replacement sequence.
 *
 * The journal is written before the first network mutation. A process death
 * therefore leaves an explicit recovery obligation that survives restart.
 */
class OcoReplacementTxJournal(context: Context) {
    data class Pending(
        val txId: String,
        val symbol: String,
        val oldOcoId: String,
        val oldOcoClientId: String,
        val quantity: Double,
        val step: String,
        val updatedAt: Long,
        val error: String?
    )

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext,
        "williams_runtime_safety.db",
        null,
        1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS oco_replacement_tx (
                    tx_id TEXT PRIMARY KEY,
                    symbol TEXT NOT NULL,
                    old_oco_id TEXT NOT NULL,
                    old_oco_client_id TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    step TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    error TEXT,
                    completed INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_oco_replacement_pending " +
                    "ON oco_replacement_tx(completed, updated_at)"
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // Version 1 is intentionally append-only. Future schema changes
            // must preserve unresolved transactions.
        }
    }

    fun begin(
        symbol: String,
        oldOcoId: String,
        oldOcoClientId: String,
        quantity: Double
    ): String {
        val txId = "OCO:" + symbol.uppercase() + ":" + System.currentTimeMillis() +
            ":" + java.util.UUID.randomUUID().toString().take(8)
        write(
            txId = txId,
            symbol = symbol.uppercase(),
            oldOcoId = oldOcoId,
            oldOcoClientId = oldOcoClientId,
            quantity = quantity,
            step = "INITIATED",
            error = null,
            completed = false
        )
        return txId
    }

    fun updateStep(txId: String, step: String, error: String? = null) {
        helper.writableDatabase.update(
            "oco_replacement_tx",
            ContentValues().apply {
                put("step", step)
                put("updated_at", System.currentTimeMillis())
                if (error == null) putNull("error") else put("error", error)
            },
            "tx_id=?",
            arrayOf(txId)
        )
    }

    fun markCompleted(txId: String) {
        helper.writableDatabase.update(
            "oco_replacement_tx",
            ContentValues().apply {
                put("completed", 1)
                put("step", "COMPLETED")
                put("updated_at", System.currentTimeMillis())
            },
            "tx_id=?",
            arrayOf(txId)
        )
    }

    fun markFailed(txId: String, error: String) {
        updateStep(txId, "FAILED", error)
    }

    fun markIncident(txId: String, error: String) {
        updateStep(txId, "UNKNOWN_STATE_REQUIRES_RECONCILIATION", error)
    }

    fun hasPending(): Boolean = pending() != null

    fun pending(): Pending? =
        helper.readableDatabase.query(
            "oco_replacement_tx",
            arrayOf(
                "tx_id",
                "symbol",
                "old_oco_id",
                "old_oco_client_id",
                "quantity",
                "step",
                "updated_at",
                "error"
            ),
            "completed=0",
            null,
            null,
            null,
            "updated_at ASC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Pending(
                txId = cursor.getString(0),
                symbol = cursor.getString(1),
                oldOcoId = cursor.getString(2),
                oldOcoClientId = cursor.getString(3),
                quantity = cursor.getDouble(4),
                step = cursor.getString(5),
                updatedAt = cursor.getLong(6),
                error = if (cursor.isNull(7)) null else cursor.getString(7)
            )
        }

    private fun write(
        txId: String,
        symbol: String,
        oldOcoId: String,
        oldOcoClientId: String,
        quantity: Double,
        step: String,
        error: String?,
        completed: Boolean
    ) {
        helper.writableDatabase.insertOrThrow(
            "oco_replacement_tx",
            null,
            ContentValues().apply {
                put("tx_id", txId)
                put("symbol", symbol)
                put("old_oco_id", oldOcoId)
                put("old_oco_client_id", oldOcoClientId)
                put("quantity", quantity)
                put("step", step)
                put("updated_at", System.currentTimeMillis())
                if (error == null) putNull("error") else put("error", error)
                put("completed", if (completed) 1 else 0)
            }
        )
    }
}

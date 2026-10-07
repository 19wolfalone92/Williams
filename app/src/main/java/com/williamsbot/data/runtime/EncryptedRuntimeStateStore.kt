package com.williamsbot.data.runtime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.williamsbot.domain.runtime.RuntimeStateStore

class EncryptedRuntimeStateStore(
    private val prefs: SharedPreferences
) : RuntimeStateStore {

    companion object {
        private const val PREFS_NAME = "williams_native_secure"
        private const val KEY_POSITIONS_JSON = "positions_json"
        private const val KEY_PENDING_ENTRIES_JSON = "pending_entries_json"
        private const val KEY_RECONCILE_REQUIRED = "reconcile_required"
        private const val KEY_KILL_LATCHED = "kill_latched"
        private const val KEY_EXECUTION_GATE_SYMBOL = "execution_gate_symbol"

        fun create(context: Context): EncryptedRuntimeStateStore =
            EncryptedRuntimeStateStore(
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            )
    }

    override val positionsJson: String
        get() = prefs.getString(KEY_POSITIONS_JSON, "[]") ?: "[]"

    override val pendingEntriesJson: String
        get() = prefs.getString(KEY_PENDING_ENTRIES_JSON, "[]") ?: "[]"

    override val reconcileRequired: Boolean
        get() = prefs.getBoolean(KEY_RECONCILE_REQUIRED, false)

    override val killLatched: Boolean
        get() = prefs.getBoolean(KEY_KILL_LATCHED, false)

    override val executionGateSymbol: String
        get() = prefs.getString(KEY_EXECUTION_GATE_SYMBOL, "") ?: ""

    override fun saveTradingState(
        positionsJson: String,
        pendingEntriesJson: String
    ): Boolean =
        prefs.edit()
            .putString(KEY_POSITIONS_JSON, positionsJson)
            .putString(KEY_PENDING_ENTRIES_JSON, pendingEntriesJson)
            .commit()

    override fun setReconcileRequired(required: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_RECONCILE_REQUIRED, required).commit()

    override fun setKillLatched(latched: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_KILL_LATCHED, latched).commit()

    override fun setExecutionGateSymbol(symbol: String): Boolean =
        prefs.edit().putString(KEY_EXECUTION_GATE_SYMBOL, symbol).commit()

    override fun clearExecutionGateSymbol(): Boolean =
        prefs.edit().remove(KEY_EXECUTION_GATE_SYMBOL).commit()
}

package com.williamsbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Restarts the on-device Futures service after reboot or an app update only if
 * the user had explicitly enabled auto-run. The service performs exchange
 * reconciliation before it may admit any new entries.
 */
class TradingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        val autoRunEnabled = runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "williams_native_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).getBoolean("auto_run", false)
        }.onFailure {
            // Fail closed: do not resume trading if the persisted opt-in cannot be read.
            Log.e(TAG, "Could not verify persisted auto-run preference; service not resumed", it)
        }.getOrDefault(false)

        if (!autoRunEnabled) return

        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, TradingForegroundService::class.java)
                    .setAction(TradingForegroundService.ACTION_START)
            )
        }.onFailure {
            Log.e(TAG, "Could not restart local Futures service after $action", it)
        }
    }

    private companion object {
        const val TAG = "WilliamsFutures"
    }
}

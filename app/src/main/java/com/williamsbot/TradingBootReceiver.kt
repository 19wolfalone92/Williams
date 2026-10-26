package com.williamsbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restarts the on-device Futures service after reboot or an app update only if
 * the user had explicitly enabled auto-run. The service performs exchange
 * reconciliation before it may admit any new entries.
 */
class TradingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED && action != Intent.ACTION_USER_UNLOCKED
        ) return

        // Keystore/disk access must not block Android's broadcast main thread.
        // Also finish before the broadcast deadline if a hardware key stalls.
        val pending=goAsync()
        val finished=AtomicBoolean(false)
        val timeout=deadline.schedule({
            if(finished.compareAndSet(false,true)) {
                pending.finish()
                RuntimeStartupEvidence.failure(context.applicationContext,"BOOT_SECURE_PREFS_TIMEOUT",null)
            }
        },8,TimeUnit.SECONDS)
        Thread({
            try { resumeIfOptedIn(context,action) { !finished.get() } }
            finally {
                timeout.cancel(false)
                if(finished.compareAndSet(false,true)) pending.finish()
            }
        },"williams-boot-recovery").apply { isDaemon=true;start() }
    }

    private fun resumeIfOptedIn(context:Context,action:String,withinDeadline:()->Boolean) {

        val appContext = context.applicationContext
        // Device-local, secret-free milestones for physical reboot acceptance.
        // They DO NOT prove the exchange position/STOP has been reconciled.
        RuntimeContinuityEvidence.event(appContext, when(action) {
            Intent.ACTION_BOOT_COMPLETED -> "BOOT_COMPLETED_OBSERVED"
            Intent.ACTION_USER_UNLOCKED -> "BOOT_USER_UNLOCK_OBSERVED"
            else -> "PACKAGE_REPLACED_OBSERVED"
        })
        val unlocked=(appContext.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked
        if(!unlocked) {
            // Credential-encrypted Keystore keys cannot be read before first
            // unlock after reboot. Never mislabel this as corrupt credentials
            // or copy secrets into device-protected storage.
            RuntimeContinuityEvidence.event(appContext,"BOOT_WAITING_FOR_USER_UNLOCK")
            return
        }
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
            // Expose the Keystore/prefs error in the cockpit on next Activity
            // launch; never erase the encrypted file or create fake new keys.
            RuntimeStartupEvidence.failure(appContext, "BOOT_SECURE_PREFS_UNREADABLE", it)
            RuntimeServiceState.lastError = "BOOT_SECURE_PREFS_UNREADABLE: " +
                safeDiagnosticText(it.message ?: it.javaClass.simpleName)
            Log.e(TAG, "Could not verify persisted auto-run preference; service not resumed", it)
        }.getOrDefault(false)

        if (!autoRunEnabled || !withinDeadline()) return
        RuntimeContinuityEvidence.event(appContext,"BOOT_OPT_IN_VERIFIED")
        RuntimeContinuityEvidence.event(appContext,"BOOT_FGS_START_REQUESTED")
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, TradingForegroundService::class.java)
                    .setAction(TradingForegroundService.ACTION_START)
            )
        }.onFailure {
            RuntimeStartupEvidence.failure(appContext, "BOOT_FGS_START_BLOCKED", it)
            RuntimeServiceState.lastError = "BOOT_FGS_START_BLOCKED: " +
                safeDiagnosticText(it.message ?: it.javaClass.simpleName)
            Log.e(TAG, "Could not restart local Futures service after $action", it)
        }
    }

    private companion object {
        const val TAG = "WilliamsFutures"
        val deadline=Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task,"williams-boot-deadline").apply { isDaemon=true }
        }
    }
}

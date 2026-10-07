package com.williamsbot.data.credentials

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.williamsbot.domain.credentials.CredentialState
import com.williamsbot.domain.credentials.CredentialsStore
import com.williamsbot.domain.credentials.StoredCredentials

@SuppressLint("UseKtx")
class EncryptedCredentialsStore(
    private val prefs: SharedPreferences
) : CredentialsStore {

    companion object {
        const val KEY_API_KEY = "binance_api_key"
        const val KEY_API_SECRET = "binance_api_secret"
        const val KEY_TESTNET = "binance_testnet"
        const val KEY_VERIFIED_MARKER = "binance_credentials_verified"

        private const val PREFS_NAME = "williams_native_secure"

        fun create(context: Context): EncryptedCredentialsStore =
            EncryptedCredentialsStore(
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

    @Volatile
    private var currentState: CredentialState = evaluateCurrentState()

    override fun state(): CredentialState {
        currentState = evaluateCurrentState()
        return currentState
    }

    override fun read(): StoredCredentials? {
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val apiSecret = prefs.getString(KEY_API_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: return null

        return StoredCredentials(
            apiKey = apiKey,
            apiSecret = apiSecret,
            testnet = prefs.getBoolean(KEY_TESTNET, true)
        )
    }

    override fun save(credentials: StoredCredentials): Boolean {
        val previous = read()
        val previousState = state()

        val committed = prefs.edit()
            .putString(KEY_API_KEY, credentials.apiKey)
            .putString(KEY_API_SECRET, credentials.apiSecret)
            .putBoolean(KEY_TESTNET, credentials.testnet)
            .putBoolean(KEY_VERIFIED_MARKER, true)
            .commit()

        if (!committed) {
            return handleCommitFailure(previous, previousState)
        }

        if (read() == credentials) {
            currentState = CredentialState.VERIFIED
            return true
        }

        return rollback(previous)
    }

    override fun clear(): Boolean {
        val committed = prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_SECRET)
            .remove(KEY_TESTNET)
            .remove(KEY_VERIFIED_MARKER)
            .commit()

        val fullyCleared =
            committed &&
                !prefs.contains(KEY_API_KEY) &&
                !prefs.contains(KEY_API_SECRET) &&
                !prefs.contains(KEY_TESTNET) &&
                !prefs.contains(KEY_VERIFIED_MARKER)

        currentState = evaluateCurrentState()
        return fullyCleared
    }

    private fun handleCommitFailure(
        previous: StoredCredentials?,
        previousState: CredentialState
    ): Boolean {
        currentState = evaluateCurrentState()

        if (previous != null && previousState != CredentialState.MISSING) {
            rollback(previous)
        } else if (currentState != CredentialState.MISSING) {
            clear()
        }

        return false
    }

    private fun rollback(previous: StoredCredentials?): Boolean {
        if (previous == null) {
            clear()
            return false
        }

        val rollbackCommitted = prefs.edit()
            .putString(KEY_API_KEY, previous.apiKey)
            .putString(KEY_API_SECRET, previous.apiSecret)
            .putBoolean(KEY_TESTNET, previous.testnet)
            .putBoolean(KEY_VERIFIED_MARKER, true)
            .commit()

        val rollbackMatches =
            rollbackCommitted &&
                read() == previous &&
                prefs.getBoolean(KEY_VERIFIED_MARKER, false)

        if (rollbackMatches) {
            currentState = CredentialState.VERIFIED
        } else {
            clear()
        }

        return false
    }

    override fun markUnverified(): Boolean {
        return try {
            val committed = prefs.edit()
                .putBoolean(KEY_VERIFIED_MARKER, false)
                .commit()

            if (!committed) {
                currentState = evaluateCurrentState()
                false
            } else {
                val readBack = runCatching {
                    prefs.getBoolean(KEY_VERIFIED_MARKER, true)
                }.getOrElse {
                    currentState = evaluateCurrentState()
                    return false
                }

                currentState = evaluateCurrentState()
                !readBack && currentState == CredentialState.SAVED_UNVERIFIED
            }
        } catch (_: Throwable) {
            currentState = runCatching { evaluateCurrentState() }
                .getOrDefault(CredentialState.SAVED_UNVERIFIED)
            false
        }
    }

    private fun evaluateCurrentState(): CredentialState {
        val apiKey = prefs.getString(KEY_API_KEY, null)
        val apiSecret = prefs.getString(KEY_API_SECRET, null)

        if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            return CredentialState.MISSING
        }

        return if (prefs.getBoolean(KEY_VERIFIED_MARKER, false)) {
            CredentialState.VERIFIED
        } else {
            CredentialState.SAVED_UNVERIFIED
        }
    }
}

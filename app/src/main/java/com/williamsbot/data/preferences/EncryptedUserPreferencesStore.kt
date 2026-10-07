package com.williamsbot.data.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.williamsbot.domain.preferences.UserPreferencesStore

@SuppressLint("UseKtx")
class EncryptedUserPreferencesStore(
    private val prefs: SharedPreferences
) : UserPreferencesStore {

    companion object {
        private const val PREFS_NAME = "williams_native_secure"
        private const val KEY_AUTO_RUN = "auto_run"
        private const val KEY_MAX_OPEN_POSITIONS = "max_open_positions"

        fun create(context: Context): EncryptedUserPreferencesStore =
            EncryptedUserPreferencesStore(
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

    override val autoRun: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RUN, false)

    override val maxOpenPositions: Int
        get() = prefs.getInt(KEY_MAX_OPEN_POSITIONS, 5).coerceIn(1, 10)

    override fun setAutoRun(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_AUTO_RUN, enabled).commit()
}

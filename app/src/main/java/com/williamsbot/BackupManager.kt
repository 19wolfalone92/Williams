package com.williamsbot

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKey
import kotlin.math.max

/**
 * Device-independent encrypted backup.
 *
 * Android Keystore protects the live preferences on the current phone.
 * A portable backup therefore uses a user-supplied password instead of
 * exporting the Keystore-bound ciphertext.
 *
 * Trade state is intentionally NOT included. After import, Binance remains
 * the source of truth and the engine must reconcile open positions/orders.
 */
object BackupManager {
    private const val MAGIC = "WILLIAMS_BACKUP_V1"
    private const val PBKDF2_ITERATIONS = 150_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MIN_PASSWORD_LENGTH = 10

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            "williams_native_secure",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun validatePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Пароль backup должен содержать минимум $MIN_PASSWORD_LENGTH символов"
        }
    }

    fun export(context: Context, output: OutputStream, password: String) {
        validatePassword(password)

        val p = prefs(context)
        val payload = JSONObject()
            .put("schema", 1)
            .put("magic", MAGIC)
            .put("app_version", "4.20.1")
            .put("testnet", true)
            .put("api_key", p.getString("api_key", "") ?: "")
            .put("api_secret", p.getString("api_secret", "") ?: "")
            .put("created_at_ms", System.currentTimeMillis())

        val encrypted = encrypt(payload.toString().toByteArray(Charsets.UTF_8), password)

        JSONObject()
            .put("magic", MAGIC)
            .put("version", 1)
            .put("salt", encrypted.salt)
            .put("iv", encrypted.iv)
            .put("ciphertext", encrypted.ciphertext)
            .toString()
            .toByteArray(Charsets.UTF_8)
            .let(output::write)

        output.flush()
    }

    fun import(context: Context, input: InputStream, password: String): JSONObject {
        validatePassword(password)

        val envelopeText = input.readBytes().toString(Charsets.UTF_8)
        val envelope = runCatching { JSONObject(envelopeText) }
            .getOrElse { throw IllegalArgumentException("Некорректный backup-файл") }

        require(envelope.optString("magic") == MAGIC) {
            "Это не backup Williams"
        }

        val plaintext = decrypt(
            salt = envelope.getString("salt"),
            iv = envelope.getString("iv"),
            ciphertext = envelope.getString("ciphertext"),
            password = password
        )

        val payload = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8))
        }.getOrElse {
            throw IllegalArgumentException("Неверный пароль или повреждённый backup")
        }

        require(payload.optString("magic") == MAGIC) {
            "Неверный backup"
        }
        require(payload.optInt("schema", -1) == 1) {
            "Неподдерживаемая версия backup"
        }
        require(payload.optBoolean("testnet", true)) {
            "Безопасность: backup не является Testnet-конфигурацией"
        }

        val apiKey = payload.optString("api_key").trim()
        val apiSecret = payload.optString("api_secret").trim()
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) {
            "Backup не содержит Binance API credentials"
        }

        prefs(context).edit()
            .putString("api_key", apiKey)
            .putString("api_secret", apiSecret)
            .putBoolean("recovery_pending", true)
            .apply()

        return JSONObject()
            .put("restored", true)
            .put("testnet", true)
            .put("recovery_pending", true)
    }

    private data class Encrypted(
        val salt: String,
        val iv: String,
        val ciphertext: String
    )

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .let { javax.crypto.spec.SecretKeySpec(it.encoded, "AES") }
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(data: ByteArray, password: String): Encrypted {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(salt)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(128, iv)
        )

        val ciphertext = cipher.doFinal(data)

        return Encrypted(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    private fun decrypt(
        salt: String,
        iv: String,
        ciphertext: String,
        password: String
    ): ByteArray {
        val saltBytes = decode(salt)
        val ivBytes = decode(iv)
        val cipherBytes = decode(ciphertext)

        require(saltBytes.size >= max(8, SALT_BYTES / 2)) {
            "Повреждённый backup"
        }
        require(ivBytes.size == IV_BYTES) {
            "Повреждённый backup"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, saltBytes),
                GCMParameterSpec(128, ivBytes)
            )
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "Неверный пароль или повреждённый backup"
            )
        }
    }

    private fun decode(value: String): ByteArray =
        runCatching {
            Base64.decode(value, Base64.NO_WRAP)
        }.getOrElse {
            throw IllegalArgumentException("Повреждённый backup")
        }
}

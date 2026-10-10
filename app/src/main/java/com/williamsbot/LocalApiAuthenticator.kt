package com.williamsbot

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Authentication for the device-local HTTP control surface.
 *
 * Loopback binding limits network exposure but does not authenticate other
 * processes on the same device. The bearer token is stored only in the
 * encrypted native-runtime preferences and is never returned by diagnostics.
 */
internal object LocalApiAuthenticator {
    private const val TOKEN_BYTES = 32
    private const val TOKEN_PATTERN = "[0-9a-f]{64}"
    private val secureRandom = SecureRandom()

    @Synchronized
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun isAuthorized(
        authorizationHeader: String?,
        expectedToken: String
    ): Boolean {
        if (!Regex(TOKEN_PATTERN).matches(expectedToken)) return false

        val parts = authorizationHeader
            ?.trim()
            ?.split(Regex("\\s+"), limit = 2)
            ?: return false
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
            return false
        }

        val supplied = parts[1].trim()
        if (supplied.length != expectedToken.length) return false

        return MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8)
        )
    }
}

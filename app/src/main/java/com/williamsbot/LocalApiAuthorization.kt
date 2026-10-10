package com.williamsbot

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Authorization for the device-local HTTP control bridge.
 *
 * Binding to loopback prevents network exposure, but does not by itself
 * prevent another application on the same device from calling control routes.
 */
object LocalApiAuthorization {
    fun isAuthorized(expectedToken: String, authorizationHeader: String?): Boolean {
        if (expectedToken.isBlank()) return false

        val header = authorizationHeader?.trim() ?: return false
        val separator = header.indexOf(' ')
        if (separator <= 0) return false
        if (!header.substring(0, separator).equals("Bearer", ignoreCase = true)) {
            return false
        }

        val provided = header.substring(separator + 1).trim()
        if (provided.isBlank() || provided.any { it.isWhitespace() }) return false

        return MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            provided.toByteArray(StandardCharsets.UTF_8)
        )
    }
}

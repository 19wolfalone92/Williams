package com.williamsbot

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable, credential-free identity helpers for mutation retries.
 * Inputs must be unsigned operation parameters only, never credentials or signatures.
 */
internal object MutationIdentity {
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun generatedClientOrderId(method: String, path: String, symbol: String, unsignedParams: String): String {
        val canonical = "$method|$path|${symbol.uppercase(Locale.US)}|$unsignedParams"
        return "W21_" + sha256(canonical).take(28)
    }

    fun fingerprint(method: String, path: String, symbol: String, unsignedParams: String): String =
        sha256("$method|$path|${symbol.uppercase(Locale.US)}|$unsignedParams")
}

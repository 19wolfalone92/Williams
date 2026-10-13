package com.williamsbot

import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Typed, credential-free identity contract for one logical exchange mutation.
 *
 * [operationId] identifies the logical action, not a network attempt. [attemptId]
 * is diagnostic-only and must never be used to create a new exchange operation.
 * The current Android ledger has not yet persisted every field in this contract;
 * callers must not infer that this model alone proves durable integration.
 */
internal data class LogicalMutationIdentity(
    val operationId: String,
    val operationType: String,
    val campaignId: String?,
    val signalId: String?,
    val exchange: String,
    val symbol: String,
    val mutationType: String,
    val canonicalRequestFingerprint: String,
    val createdAtMs: Long,
    val durableState: String,
    val exchangeClientIds: List<String>,
    val attemptId: String
) {
    init {
        require(operationId.isNotBlank()) { "MUTATION_OPERATION_ID_REQUIRED" }
        require(operationType.isNotBlank()) { "MUTATION_OPERATION_TYPE_REQUIRED" }
        require(exchange.isNotBlank()) { "MUTATION_EXCHANGE_REQUIRED" }
        require(symbol.isNotBlank()) { "MUTATION_SYMBOL_REQUIRED" }
        require(mutationType.isNotBlank()) { "MUTATION_TYPE_REQUIRED" }
        require(canonicalRequestFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "MUTATION_FINGERPRINT_INVALID"
        }
        require(attemptId.isNotBlank()) { "MUTATION_ATTEMPT_ID_REQUIRED" }
    }
}

/**
 * Canonicalization for unsigned Binance Spot form parameters.
 *
 * Parameter order and percent-encoding are normalized. Duplicate keys and
 * credential/signature parameters are rejected instead of silently hashed.
 * Missing keys remain absent; an explicitly empty value remains "key=".
 */
internal object MutationIdentity {
    private val sensitiveKeys = setOf(
        "signature", "apikey", "api_key", "apisecret", "api_secret",
        "secret", "authorization", "x-mbx-apikey"
    )
    private val transportOnlyKeys = setOf("timestamp", "recvwindow")
    private val decimalKeys = setOf(
        "quantity", "quoteorderqty", "price", "stopprice", "stoplimitprice",
        "icebergqty", "origqty", "executedqty", "aboveprice", "abovestopprice", "belowprice", "belowstopprice"
    )
    private val enumKeys = setOf(
        "side", "type", "timeinforce", "cancelreplacemode", "neworderresptype",
        "stoplimittimeinforce", "abovetype", "belowtype", "abovetimeinforce", "belowtimeinforce", "selftradpreventionmode", "workingtype",
        "responsetype"
    )

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun canonicalUnsignedParams(unsignedParams: String): String {
        if (unsignedParams.isBlank()) return ""
        val values = linkedMapOf<String, String>()
        val seenNormalizedKeys = mutableSetOf<String>()
        for (part in unsignedParams.split("&")) {
            require(part.isNotEmpty()) { "MUTATION_PARAMETER_EMPTY_SEGMENT" }
            val split = part.indexOf('=')
            require(split > 0) { "MUTATION_PARAMETER_INVALID" }
            val key = decode(part.substring(0, split))
            val normalizedKey = key.lowercase(Locale.US)
            val value = decode(part.substring(split + 1))
            require(key.isNotBlank()) { "MUTATION_PARAMETER_KEY_REQUIRED" }
            require(normalizedKey !in sensitiveKeys) { "MUTATION_CREDENTIAL_PARAMETER_REJECTED" }
            require(seenNormalizedKeys.add(normalizedKey)) { "MUTATION_DUPLICATE_PARAMETER_REJECTED" }
            if (normalizedKey !in transportOnlyKeys) {
                values[key] = normalizeValue(normalizedKey, value)
            }
        }
        return values.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    private fun normalizeValue(key: String, value: String): String {
        if (key == "symbol") {
            require(value.isNotBlank()) { "MUTATION_SYMBOL_REQUIRED" }
            return value.uppercase(Locale.US)
        }
        if (key in enumKeys) {
            require(value.isNotBlank()) { "MUTATION_ENUM_VALUE_REQUIRED" }
            return value.uppercase(Locale.US)
        }
        if (key in decimalKeys) {
            require(value.isNotBlank()) { "MUTATION_DECIMAL_REQUIRED" }
            val decimal = try {
                BigDecimal(value)
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("MUTATION_DECIMAL_INVALID")
            }
            require(decimal.signum() > 0) { "MUTATION_DECIMAL_NON_POSITIVE" }
            return decimal.stripTrailingZeros().toPlainString()
        }
        return value
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("MUTATION_PARAMETER_ENCODING_INVALID")
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    fun canonicalRequest(method: String, path: String, symbol: String, unsignedParams: String): String {
        require(method.isNotBlank() && path.startsWith("/")) { "MUTATION_REQUEST_IDENTITY_INVALID" }
        require(symbol.isNotBlank()) { "MUTATION_SYMBOL_REQUIRED" }
        return listOf(
            method.uppercase(Locale.US),
            path,
            symbol.uppercase(Locale.US),
            canonicalUnsignedParams(unsignedParams)
        ).joinToString("|")
    }

    fun fingerprint(method: String, path: String, symbol: String, unsignedParams: String): String =
        sha256(canonicalRequest(method, path, symbol, unsignedParams))

    /**
     * Fail closed for callers that do not supply a business-operation identity.
     * Request economics are not an operation identity: two independent orders
     * can legitimately have byte-for-byte identical parameters. Callers must
     * provide a persisted logicalOperationId created at the business-action boundary.
     */
    @Deprecated("Pass a persisted logicalOperationId; request economics are not identity")
    fun generatedClientOrderId(method: String, path: String, symbol: String, unsignedParams: String): String {
        throw IllegalArgumentException("MUTATION_LOGICAL_OPERATION_ID_REQUIRED")
    }

    fun generatedClientOrderId(
        method: String,
        path: String,
        symbol: String,
        unsignedParams: String,
        logicalOperationId: String
    ): String = generatedClientOrderId(method, path, symbol, unsignedParams, logicalOperationId, "W21_")

    fun generatedClientOrderId(
        method: String,
        path: String,
        symbol: String,
        unsignedParams: String,
        logicalOperationId: String,
        prefix: String
    ): String {
        require(logicalOperationId.isNotBlank()) { "MUTATION_OPERATION_ID_REQUIRED" }
        require(prefix.matches(Regex("[A-Za-z0-9_-]{1,8}"))) { "MUTATION_CLIENT_ID_PREFIX_INVALID" }
        // Validate the request, but do not make exchange identity depend on
        // mutable request economics. A changed request under the same logical
        // operation must collide with the durable ledger fingerprint and fail
        // closed, rather than silently becoming a second exchange order.
        canonicalRequest(method, path, symbol, unsignedParams)
        val suffixLength = 36 - prefix.length
        return prefix + sha256(logicalOperationId).take(suffixLength)
    }

    fun operationId(operationType: String, exchange: String, symbol: String, logicalKey: String): String {
        require(operationType.isNotBlank() && exchange.isNotBlank() &&
            symbol.isNotBlank() && logicalKey.isNotBlank()) { "MUTATION_OPERATION_IDENTITY_REQUIRED" }
        return "mut_" + sha256(
            listOf(operationType.uppercase(Locale.US), exchange.uppercase(Locale.US),
                symbol.uppercase(Locale.US), logicalKey).joinToString("|")
        ).take(48)
    }
}

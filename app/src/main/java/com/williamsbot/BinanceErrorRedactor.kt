package com.williamsbot

import org.json.JSONObject

/** Sanitizes exchange errors before they reach logs, diagnostics, or HTTP responses. */
object BinanceErrorRedactor {
    private val numericCode = Regex("-?\\d{1,12}")
    const val REDACTED_BODY = "response body redacted"

    fun safeApiCode(body: String): String {
        val value = runCatching { JSONObject(body).opt("code") }.getOrNull()
        val text = when (value) {
            is Number -> value.toString()
            is String -> value
            else -> return "unknown"
        }
        return if (numericCode.matches(text)) text else "unknown"
    }

    fun safeFailure(status: Int, body: String): String =
        "Binance HTTP " + status + "; API code=" + safeApiCode(body) + "; " + REDACTED_BODY
}

package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Read-only migration projection for the legacy campaign-intent JSON array.
 * The JSON state remains authoritative until an independent acceptance gate
 * explicitly approves a future authority switch.
 */
internal data class FuturesIntentShadowRow(
    val ordinal: Int,
    val clientId: String,
    val symbol: String,
    val intentRole: String,
    val campaignStatus: String,
    val orderStatus: String,
    val exchangeStatus: String,
    val protectionStatus: String,
    val strategyProfile: String,
    val payloadSha256: String
)

internal data class FuturesIntentShadowProjection(
    val rows: List<FuturesIntentShadowRow>,
    val payloadSha256: String
)

internal fun projectFuturesCampaignIntents(rawJson: String): FuturesIntentShadowProjection? =
    runCatching {
        require(rawJson.isNotBlank()) { "FUTURES_INTENT_LEDGER_EMPTY" }
        val array = JSONArray(rawJson)
        require(array.length() <= 10_000) { "FUTURES_INTENT_LEDGER_TOO_LARGE" }
        val rows = ArrayList<FuturesIntentShadowRow>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: error("FUTURES_INTENT_LEDGER_ROW_NOT_OBJECT:$index")
            val rawRow = item.toString()
            rows += FuturesIntentShadowRow(
                ordinal = index,
                clientId = item.optString("client_id").take(160),
                symbol = item.optString("symbol").take(32),
                intentRole = item.optString("intent_role", "ENTRY").take(32),
                campaignStatus = item.optString("campaign_status").take(64),
                orderStatus = item.optString("order_status").take(64),
                exchangeStatus = item.optString("exchange_status").take(64),
                protectionStatus = item.optString("protection_status").take(64),
                strategyProfile = item.optString("strategy_profile").take(64),
                payloadSha256 = futuresIntentSha256(rawRow)
            )
        }
        FuturesIntentShadowProjection(rows, futuresIntentSha256(array.toString()))
    }.getOrNull()

internal fun futuresIntentSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** No missing/malformed source or missing shadow projection may report PASS. */
internal fun futuresIntentShadowParity(
    canonical: FuturesIntentShadowProjection?,
    shadow: List<FuturesIntentShadowRow>?
): JSONObject {
    if (canonical == null || shadow == null) {
        return JSONObject()
            .put("schema", "FUTURES_INTENT_SHADOW_PARITY_V1")
            .put("severity", "NOT_VERIFIED")
            .put("canonical_count", canonical?.rows?.size ?: JSONObject.NULL)
            .put("shadow_count", shadow?.size ?: JSONObject.NULL)
            .put("authority", "LEGACY_JSON_ONLY")
    }
    val equal = canonical.rows.size == shadow.size &&
        canonical.rows.indices.all { index ->
            val left = canonical.rows[index]
            val right = shadow[index]
            left == right
        }
    return JSONObject()
        .put("schema", "FUTURES_INTENT_SHADOW_PARITY_V1")
        .put("severity", if (equal) "PASS" else "WARNING")
        .put("canonical_count", canonical.rows.size)
        .put("shadow_count", shadow.size)
        .put("canonical_payload_sha256", canonical.payloadSha256)
        .put("parity", equal)
        .put("authority", "LEGACY_JSON_ONLY")
        .put("authority_switch_allowed", false)
}

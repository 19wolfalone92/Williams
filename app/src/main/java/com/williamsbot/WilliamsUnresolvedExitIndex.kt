package com.williamsbot

import org.json.JSONArray
import java.util.Locale

/** A single immutable index for the flat-campaign finalization pass.
 * The legacy rule is conservative: any malformed intent means the presence of
 * an unresolved EXIT cannot be excluded for ANY symbol. Never erase that veto.
 */
internal data class WilliamsUnresolvedExitIndex(
    val symbols: Set<String>,
    val unknownMalformedIntent: Boolean
) {
    fun mayHaveUnresolvedExit(symbol: String): Boolean =
        unknownMalformedIntent || symbol.uppercase(Locale.US) in symbols
}

internal fun buildWilliamsUnresolvedExitIndex(intents: JSONArray): WilliamsUnresolvedExitIndex {
    val symbols = HashSet<String>()
    var malformed = false
    for (index in 0 until intents.length()) {
        val intent = intents.optJSONObject(index)
        if (intent == null) {
            malformed = true
            continue
        }
        if (intent.optString("intent_role") == "EXIT" &&
            intent.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED"
        ) {
            symbols.add(intent.optString("symbol").uppercase(Locale.US))
        }
    }
    return WilliamsUnresolvedExitIndex(symbols, malformed)
}

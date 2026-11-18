package com.williamsbot

import org.json.JSONArray

/** Pure fail-closed policy for two signed OLD-account position snapshots.
 * Public ticker/mark or the configured NEW account cannot attest that the old
 * account lacks active exposure. Credential rotation never cancels orders. */
internal fun futuresOldAccountHasNoExposure(
    before:JSONArray?,standardOpen:JSONArray?,algoOpen:JSONArray?,after:JSONArray?
):Boolean {
    if(before==null || standardOpen==null || algoOpen==null || after==null ||
        standardOpen.length()!=0 || algoOpen.length()!=0) return false
    fun flat(snapshot:JSONArray):Boolean =
        (0 until snapshot.length()).all { i ->
            val row=snapshot.optJSONObject(i) ?: return@all false
            row.optString("symbol").isNotBlank() &&
                row.optString("positionAmt").toBigDecimalOrNull()?.signum()==0
        }
    return flat(before) && flat(after)
}

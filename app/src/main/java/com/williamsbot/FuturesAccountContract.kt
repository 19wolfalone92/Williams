package com.williamsbot

import java.io.IOException
import org.json.JSONObject

/** V3 balances and V1 configuration are separate signed reads; never invent permission. */
internal fun futuresAccountWithConfiguration(account: JSONObject, configuration: JSONObject? = null): JSONObject {
    if (account.has("canTrade")) {
        if (account.opt("canTrade") !is Boolean)
            throw IOException("FUTURES_ACCOUNT_PERMISSION_INVALID")
        return JSONObject(account.toString()).put("account_permission_source", "/fapi/v3/account")
    }
    val config = configuration
        ?: throw IOException("FUTURES_ACCOUNT_CONFIGURATION_PERMISSION_UNAVAILABLE")
    if (config.opt("canTrade") !is Boolean)
        throw IOException("FUTURES_ACCOUNT_CONFIGURATION_PERMISSION_UNAVAILABLE")
    val result = JSONObject(account.toString())
    for (field in listOf("canTrade", "canDeposit", "canWithdraw", "dualSidePosition", "multiAssetsMargin")) {
        if (!config.has(field)) continue
        val value = config.opt(field)
        if (value !is Boolean)
            throw IOException("FUTURES_ACCOUNT_CONFIGURATION_BOOLEAN_INVALID:$field")
        if (account.has(field) && account.opt(field) != value)
            throw IOException("FUTURES_ACCOUNT_CONFIGURATION_CONFLICT:$field")
        result.put(field, value)
    }
    return result.put("account_permission_source", "/fapi/v1/accountConfig")
}

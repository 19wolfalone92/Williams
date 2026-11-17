package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesAccountContractTest {
    @Test fun v3BalancesJoinOnlyWhitelistedConfigurationFlags() {
        for (permission in listOf(true, false)) {
            val account=JSONObject("""{"totalMarginBalance":"990","assets":[{"asset":"USDT"}]}""")
            val config=JSONObject().put("canTrade",permission).put("multiAssetsMargin",false)
                .put("dualSidePosition",false).put("totalMarginBalance","999999")
                .put("assets",org.json.JSONArray().put(JSONObject().put("asset","WRONG")))
            val result=futuresAccountWithConfiguration(account,config)
            assertEquals(permission,result.getBoolean("canTrade"))
            assertEquals("990",result.getString("totalMarginBalance"))
            assertEquals("USDT",result.getJSONArray("assets").getJSONObject(0).getString("asset"))
            assertFalse(account.has("canTrade"))
            assertFalse(result.getBoolean("multiAssetsMargin"))
            assertEquals("/fapi/v1/accountConfig",result.getString("account_permission_source"))
        }
    }

    @Test fun malformedPermissionIsNotCoercedOrOverridden() {
        for (value in listOf(JSONObject.NULL,"true",0,1,JSONObject())) {
            try {
                futuresAccountWithConfiguration(JSONObject().put("canTrade",value),JSONObject().put("canTrade",true))
                fail("Malformed present permission cannot be replaced by a favorable config")
            } catch (e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("PERMISSION_INVALID")) }
        }
        for (config in listOf(null,JSONObject(),JSONObject().put("canTrade","true"),JSONObject().put("canTrade",0))) {
            try {
                futuresAccountWithConfiguration(JSONObject(),config)
                fail("Missing/invalid configuration is unknown, never true")
            } catch (e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("PERMISSION_UNAVAILABLE")) }
        }
    }

    @Test fun explicitFalseAndModeConflictStayFailClosed() {
        val legacy=futuresAccountWithConfiguration(JSONObject().put("canTrade",false))
        assertFalse(legacy.getBoolean("canTrade"))
        assertEquals("/fapi/v3/account",legacy.getString("account_permission_source"))
        try {
            futuresAccountWithConfiguration(JSONObject().put("multiAssetsMargin",true),
                JSONObject().put("canTrade",true).put("multiAssetsMargin",false))
            fail("Collateral modes cannot be silently overwritten")
        } catch (e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("CONFIGURATION_CONFLICT")) }
    }
}

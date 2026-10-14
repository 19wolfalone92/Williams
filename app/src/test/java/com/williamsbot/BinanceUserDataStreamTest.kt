package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceUserDataStreamTest {
    @Test
    fun listenKeyExpiryRequiresReconnectAndRestResynchronization() {
        assertTrue(shouldReconnectFuturesUserStream(JSONObject().put("e", "listenKeyExpired")))
    }

    @Test
    fun serverShutdownRequiresReconnect() {
        assertTrue(shouldReconnectFuturesUserStream(JSONObject().put("e", "serverShutdown")))
    }

    @Test
    fun ordinaryAccountEventsDoNotForceReconnect() {
        assertFalse(shouldReconnectFuturesUserStream(JSONObject().put("e", "ACCOUNT_UPDATE")))
        assertFalse(shouldReconnectFuturesUserStream(JSONObject().put("e", "ORDER_TRADE_UPDATE")))
    }

    @Test
    fun privateEventFilterUsesBinanceSlashSeparatedSubscriptionSyntax() {
        assertTrue(FUTURES_USER_STREAM_EVENT_FILTER.contains("ORDER_TRADE_UPDATE/ACCOUNT_UPDATE"))
        assertTrue(FUTURES_USER_STREAM_EVENT_FILTER.contains("ACCOUNT_UPDATE/ALGO_UPDATE"))
        assertFalse(FUTURES_USER_STREAM_EVENT_FILTER.contains(","))
    }
}

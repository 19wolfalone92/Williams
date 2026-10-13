package com.williamsbot

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TradingAuditStoreSecretRedactionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = TradingAuditStore(context)

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("williams_trading_audit.db")
    }

    @Test
    fun persistedRestAndStreamAuditPayloadsDoNotContainSyntheticSecrets() {
        context.deleteDatabase("williams_trading_audit.db")
        store.recordRestCall(
            method = "POST",
            path = "/api/v3/order",
            statusCode = 400,
            requestJson = "symbol=BTCUSDT&apiKey=TEST_API_KEY_000000000000&signature=TEST_SIGNATURE_111111111111",
            responseJson = """{"code":-1021,"apiSecret":"TEST_API_SECRET_222222222222","token":"TEST_TOKEN_333333333333"}"""
        )

        store.readableDatabase.rawQuery(
            "SELECT request_json, response_json FROM rest_calls ORDER BY id DESC LIMIT 1",
            null
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            val request = cursor.getString(0)
            val response = cursor.getString(1)
            listOf(
                "TEST_API_KEY_000000000000",
                "TEST_SIGNATURE_111111111111",
                "TEST_API_SECRET_222222222222",
                "TEST_TOKEN_333333333333"
            ).forEach { sentinel ->
                assertFalse("secret persisted in REST audit: $sentinel", request.contains(sentinel) || response.contains(sentinel))
            }
        }

        store.recordUserEvent(JSONObject("""{"e":"testEvent","apiSecret":"TEST_STREAM_SECRET_444444444444","s":"BTCUSDT"}"""))
        store.readableDatabase.rawQuery(
            "SELECT raw_json FROM events ORDER BY id DESC LIMIT 1",
            null
        ).use { cursor ->
            assert(cursor.moveToFirst())
            assertFalse(cursor.getString(0).contains("TEST_STREAM_SECRET_444444444444"))
        }
    }
}

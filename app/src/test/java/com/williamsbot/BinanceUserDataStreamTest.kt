package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BinanceUserDataStreamTest {
    private class TestSocket(private val original: Request) : WebSocket {
        override fun request(): Request = original
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }


    @Test
    fun failingPrivateListenKeyPreservesRealNetworkErrorInReconnectingHealth() {
        val disconnects = LinkedBlockingQueue<String>()
        val http = OkHttpClient.Builder().addInterceptor {
            throw java.io.IOException("demo-fapi private transport unreachable")
        }.build()
        val stream = BinanceUserDataStream(
            http, "wss://demo-fstream.binance.com/private/ws", { "test-key" },
            { connected, reason -> if (!connected && reason != null) disconnects.put(reason) },
            { }
        )
        try {
            stream.start()
            val initialFailure = requireNotNull(disconnects.poll(5L, TimeUnit.SECONDS))
            val reconnectStatus = requireNotNull(disconnects.poll(5L, TimeUnit.SECONDS))
            assertTrue(initialFailure.contains("demo-fapi private transport unreachable"))
            assertTrue(reconnectStatus.contains("demo-fapi private transport unreachable"))
            assertFalse(reconnectStatus == "reconnecting")
            assertFalse(stream.isConnected())
        } finally {
            stream.stop()
        }
    }

    @Test
    fun delayedCallbacksAfterStopCannotReconnectOrDeliverOldFills() {
        val sessions = LinkedBlockingQueue<Pair<TestSocket, WebSocketListener>>()
        val connections = Collections.synchronizedList(mutableListOf<Boolean>())
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"listenKey":"test"}""".toResponseBody()).build()
        }.build()
        val stream = BinanceUserDataStream(
            http, "wss://demo-fstream.binance.com/private/ws", { "test-key" },
            { connected, _ -> connections += connected }, { events += it },
            { request, listener -> TestSocket(request).also { sessions.put(it to listener) } }
        )
        try {
            stream.start()
            val (socket, listener) = requireNotNull(sessions.poll(5, TimeUnit.SECONDS))
            stream.stop()
            listener.onOpen(socket, socketResponse(socket.request()))
            listener.onMessage(socket, """{"e":"ORDER_TRADE_UPDATE"}""")
            assertFalse(stream.isConnected())
            assertFalse(connections.any { it })
            assertTrue(events.isEmpty())
        } finally {
            stream.stop()
        }
    }

    @Test
    fun closingHandshakeWithoutOnClosedStillReconnectsWithNewListenKey() {
        val sessions = LinkedBlockingQueue<Pair<TestSocket, WebSocketListener>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"listenKey":"test"}""".toResponseBody()).build()
        }.build()
        val stream = BinanceUserDataStream(
            http, "wss://demo-fstream.binance.com/private/ws", { "test-key" },
            { _, _ -> }, { },
            { request, listener -> TestSocket(request).also { sessions.put(it to listener) } }
        )
        try {
            stream.start()
            val (oldSocket, oldListener) = requireNotNull(sessions.poll(5, TimeUnit.SECONDS))
            oldListener.onOpen(oldSocket, socketResponse(oldSocket.request()))
            assertTrue(stream.isConnected())
            // Mobile peer initiated a close, but transport never delivers onClosed.
            oldListener.onClosing(oldSocket, 1001, "server going away")
            assertFalse(stream.isConnected())
            val (newSocket, newListener) = requireNotNull(sessions.poll(5, TimeUnit.SECONDS))
            newListener.onOpen(newSocket, socketResponse(newSocket.request()))
            assertTrue(stream.isConnected())
            // The old WebSocket cannot reset a later session.
            oldListener.onClosed(oldSocket, 1001, "late old session close")
            assertTrue(stream.isConnected())
        } finally {
            stream.stop()
        }
    }

    @Test
    fun oldSocketCloseCannotInvalidateAReplacementSession() {
        val sessions = LinkedBlockingQueue<Pair<TestSocket, WebSocketListener>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"listenKey":"test"}""".toResponseBody()).build()
        }.build()
        val stream = BinanceUserDataStream(
            http, "wss://demo-fstream.binance.com/private/ws", { "test-key" }, { _, _ -> }, { },
            { request, listener -> TestSocket(request).also { sessions.put(it to listener) } }
        )
        try {
            stream.start()
            val (oldSocket, oldListener) = requireNotNull(sessions.poll(5, TimeUnit.SECONDS))
            oldListener.onOpen(oldSocket, socketResponse(oldSocket.request()))
            oldListener.onMessage(oldSocket, """{"e":"listenKeyExpired"}""")
            val (newSocket, newListener) = requireNotNull(sessions.poll(5, TimeUnit.SECONDS))
            newListener.onOpen(newSocket, socketResponse(newSocket.request()))
            assertTrue(stream.isConnected())
            oldListener.onClosed(oldSocket, 1000, "late close")
            oldListener.onFailure(oldSocket, java.io.IOException("late failure"), null)
            assertTrue(stream.isConnected())
        } finally {
            stream.stop()
        }
    }

    private fun socketResponse(request: Request): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build()

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

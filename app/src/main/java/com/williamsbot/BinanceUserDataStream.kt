package com.williamsbot

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Binance Spot user-data stream using the current signed WebSocket API
 * subscription. This replaces the legacy listen-key keepalive model.
 */
class BinanceUserDataStream(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val apiKeyProvider: () -> String,
    private val apiSecretProvider: () -> String,
    private val timestampProvider: () -> Long,
    private val onConnection: (Boolean, String?) -> Unit,
    private val onEvent: (JSONObject) -> Unit
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var connected = false
    private val stopping = AtomicBoolean(false)

    fun isConnected(): Boolean = connected

    fun start() {
        if (worker?.isAlive == true) return
        stopping.set(false)
        worker = Thread({ loop() }, "williams-user-data-ws").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopping.set(true)
        connected = false
        socket?.close(1000, "Williams stopped")
        socket = null
        lastPongMs = 0L
        worker?.interrupt()
        worker = null
        onConnection(false, "stopped")
    }

    private fun loop() {
        var delayMs = 1000L
        while (!stopping.get()) {
            val closed = CountDownLatch(1)
            val apiKey = apiKeyProvider().trim()
            val secret = apiSecretProvider().trim()

            if (apiKey.isBlank() || secret.isBlank()) {
                onConnection(false, "credentials_not_configured")
                sleepBackoff(delayMs)
                continue
            }

            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("X-MBX-APIKEY", apiKey)
                    .build()

                socket = http.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response
                        ) {
                            sendSubscription(webSocket, apiKey, secret)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String
                        ) {
                            val msg = runCatching { JSONObject(text) }
                                .getOrNull() ?: return

                            val status = msg.optInt("status", 0)
                            val subscriptionId =
                                msg.optJSONObject("result")
                                    ?.optLong("subscriptionId", -1L) ?: -1L
                            if (
                                status == 200 &&
                                subscriptionId >= 0L
                            ) {
                                connected = true
                                delayMs = 1000L
                                onConnection(true, null)
                                return
                            }

                            if (status >= 400) {
                                val error = msg.optJSONObject("error")
                                onConnection(
                                    false,
                                    "Binance WS " +
                                        (error?.optInt("code") ?: 0) +
                                        ": " +
                                        (error?.optString("msg") ?: "error")
                                )
                                closed.countDown()
                                return
                            }

                            val event = msg.optJSONObject("event") ?: msg
                            if (
                                event.optString("e") ==
                                    "eventStreamTerminated"
                            ) {
                                connected = false
                                onConnection(
                                    false,
                                    "event_stream_terminated"
                                )
                                webSocket.close(
                                    1000,
                                    "resubscribe"
                                )
                                closed.countDown()
                                return
                            }

                            onEvent(event)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?
                        ) {
                            connected = false
                            onConnection(
                                false,
                                t.message ?: t.javaClass.simpleName
                            )
                            closed.countDown()
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String
                        ) {
                            connected = false
                            onConnection(
                                false,
                                "closed $code ${reason.ifBlank { "none" }}"
                            )
                            closed.countDown()
                        }
                    }
                )

                // Binance WebSocket connections have a maximum lifetime;
                // reconnect proactively before the 24h boundary.
                val deadlineMs = System.currentTimeMillis() + 23L * 60L * 60L * 1000L
                while (!stopping.get() && System.currentTimeMillis() < deadlineMs) {
                    if (closed.await(5L, TimeUnit.SECONDS)) break
                }
                if (!stopping.get()) {
                    socket?.close(1000, "planned_24h_reconnect")
                }
            } catch (t: Throwable) {
                connected = false
                onConnection(
                    false,
                    t.message ?: t.javaClass.simpleName
                )
            } finally {
                socket = null
                connected = false
            }

            if (stopping.get()) break
            sleepBackoff(delayMs)
            delayMs = (delayMs * 2L).coerceAtMost(30_000L)
        }
    }

    private fun sendSubscription(
        socket: WebSocket,
        apiKey: String,
        secret: String
    ) {
        val values = linkedMapOf(
            "apiKey" to apiKey,
            "recvWindow" to "5000",
            "timestamp" to timestampProvider().toString()
        )
        val canonical = values.toSortedMap()
            .entries
            .joinToString("&") { "${it.key}=${it.value}" }
        val signature = hmac(canonical, secret)

        val params = JSONObject()
            .put("apiKey", apiKey)
            .put("recvWindow", 5000)
            .put("timestamp", values["timestamp"]!!.toLong())
            .put("signature", signature)

        socket.send(
            JSONObject()
                .put("id", "williams-user-${System.currentTimeMillis()}")
                .put("method", "userDataStream.subscribe.signature")
                .put("params", params)
                .toString()
        )
    }

    private fun hmac(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secret.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
        )
        return mac.doFinal(
            value.toByteArray(StandardCharsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
    }

    private fun sleepBackoff(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

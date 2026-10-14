package com.williamsbot

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USDⓈ-M Futures Demo user-data stream.
 *
 * A Futures listenKey is created and renewed over the Futures REST API. User events
 * are consumed from the post-migration private stream endpoint, not the Spot signed
 * WebSocket subscription protocol. The caller reconciles against REST on reconnect;
 * socket connectivity alone never means execution is ready.
 */
class BinanceUserDataStream(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val apiKeyProvider: () -> String,
    @Suppress("UNUSED_PARAMETER") private val apiSecretProvider: () -> String,
    @Suppress("UNUSED_PARAMETER") private val timestampProvider: () -> Long,
    private val onConnection: (Boolean, String?) -> Unit,
    private val onEvent: (JSONObject) -> Unit
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var connected = false
    private val stopping = AtomicBoolean(false)

    fun isConnected(): Boolean = connected

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        stopping.set(false)
        worker = Thread({ loop() }, "williams-futures-user-data-ws").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        stopping.set(true)
        connected = false
        socket?.close(1000, "Williams stopped")
        socket = null
        worker?.interrupt()
        worker = null
        onConnection(false, "stopped")
    }

    private fun loop() {
        var delayMs = 1000L
        while (!stopping.get()) {
            val closed = CountDownLatch(1)
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                onConnection(false, "credentials_not_configured")
                sleepBackoff(delayMs)
                delayMs = (delayMs * 2L).coerceAtMost(30_000L)
                continue
            }

            try {
                val listenKey = createListenKey(apiKey)
                val separator = if (endpoint.contains("?")) "&" else "?"
                val streamUrl = endpoint + separator +
                    "listenKey=" + URLEncoder.encode(listenKey, "UTF-8")
                val request = Request.Builder().url(streamUrl).build()

                socket = http.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connected = true
                            delayMs = 1000L
                            onConnection(true, null)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                            val event = message.optJSONObject("data") ?: message
                            if (event.has("e")) {
                                runCatching { onEvent(event) }
                                    .onFailure {
                                        connected = false
                                        onConnection(false, "event_handler_failed: " +
                                            (it.message ?: it.javaClass.simpleName))
                                        // A failed account/order event means the local view can no
                                        // longer be trusted. Force the reconnect loop to reconnect
                                        // and let the caller complete REST reconciliation before
                                        // any new entry is allowed.
                                        webSocket.close(1011, "event_handler_failed")
                                        closed.countDown()
                                    }
                            } else if (message.has("code") || message.has("error")) {
                                onConnection(false, "Futures user stream error: " +
                                    message.optString("msg", message.optString("error", "unknown")))
                                closed.countDown()
                                webSocket.close(1011, "exchange_error")
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            onMessage(webSocket, bytes.utf8())
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            connected = false
                            onConnection(false, "closing: " + code + ": " +
                                (reason.ifBlank { "none" }))
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            connected = false
                            onConnection(false, "closed: " + code + ": " +
                                (reason.ifBlank { "none" }))
                            closed.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            connected = false
                            onConnection(false, t.message ?: t.javaClass.simpleName)
                            closed.countDown()
                        }
                    }
                )

                // Futures listenKeys expire after 60 minutes; renew at 50 minutes.
                val renewAt = System.currentTimeMillis() + 50L * 60L * 1000L
                while (!stopping.get()) {
                    if (closed.await(30L, TimeUnit.SECONDS)) break
                    if (System.currentTimeMillis() >= renewAt) {
                        renewListenKey(apiKey)
                        break
                    }
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (failure: Throwable) {
                connected = false
                onConnection(false, failure.message ?: failure.javaClass.simpleName)
            } finally {
                socket?.close(1000, "Futures user stream reconnect")
                socket = null
                connected = false
            }

            if (stopping.get()) break
            sleepBackoff(delayMs)
            delayMs = (delayMs * 2L).coerceAtMost(30_000L)
        }
    }

    private fun createListenKey(apiKey: String): String {
        val request = Request.Builder()
            .url(REST_BASE + "/fapi/v1/listenKey")
            .header("X-MBX-APIKEY", apiKey)
            .post(ByteArray(0).toRequestBody(FORM_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Futures listenKey creation failed (HTTP " + response.code + "): " + body.take(240)
            }
            return JSONObject(body).optString("listenKey").also {
                check(it.isNotBlank()) { "Futures listenKey response is empty" }
            }
        }
    }

    private fun renewListenKey(apiKey: String) {
        val request = Request.Builder()
            .url(REST_BASE + "/fapi/v1/listenKey")
            .header("X-MBX-APIKEY", apiKey)
            .put(ByteArray(0).toRequestBody(FORM_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Futures listenKey renewal failed (HTTP " + response.code + "): " + body.take(240)
            }
        }
    }

    private fun sleepBackoff(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val REST_BASE = "https://demo-fapi.binance.com"
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Binance's migrated USD-M private stream takes multiple event filters separated by "/".
 * A comma-delimited list is treated as one invalid event filter and can silently starve
 * ACCOUNT_UPDATE/ORDER_TRADE_UPDATE delivery while the WebSocket itself appears open.
 */
internal const val FUTURES_USER_STREAM_EVENT_FILTER =
    "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE/ACCOUNT_CONFIG_UPDATE/CONDITIONAL_ORDER_TRIGGER_REJECT/listenKeyExpired"

/** Return true when the Futures private stream must be re-established. */
internal fun shouldReconnectFuturesUserStream(event: JSONObject): Boolean =
    event.optString("e") in setOf("listenKeyExpired", "serverShutdown")

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
    private val onConnection: (Boolean, String?) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = http::newWebSocket
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var connected = false
    private val stopping = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)

    private fun acceptsCallback(generation: Long): Boolean =
        !stopping.get() && connectionGeneration.get() == generation

    fun isConnected(): Boolean = connected

    @Synchronized
    fun start() {
        val activeWorker = worker
        if (activeWorker?.isAlive == true) {
            // A runtime stop/start sequence can overlap the user-stream worker's
            // final network cleanup. Queue exactly one restart rather than either
            // abandoning the stream or starting a duplicate listener thread.
            if (stopping.get()) restartRequested.set(true)
            return
        }
        restartRequested.set(false)
        launchWorkerLocked()
    }

    @Synchronized
    private fun launchWorkerLocked() {
        stopping.set(false)
        worker = Thread({
            try {
                loop()
            } finally {
                workerExited(Thread.currentThread())
            }
        }, "williams-futures-user-data-ws").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun workerExited(finishedWorker: Thread) {
        // A newer worker may already have started after this thread actually died;
        // an old thread's finally block must never clear its reference.
        if (worker !== finishedWorker) return
        worker = null
        if (restartRequested.getAndSet(false)) {
            launchWorkerLocked()
        }
    }

    @Synchronized
    fun stop() {
        restartRequested.set(false)
        stopping.set(true)
        connectionGeneration.incrementAndGet()
        connected = false
        val activeSocket = socket
        socket = null
        activeSocket?.close(1000, "Williams stopped")
        worker?.interrupt()
        // Keep the thread reference until its finally block confirms termination.
        // start() will queue a restart if teardown is still in progress.
        onConnection(false, "stopped")
    }

    private fun loop() {
        var delayMs = 1000L
        while (!stopping.get()) {
            val generation = connectionGeneration.incrementAndGet()
            val closed = CountDownLatch(1)
            // Preserve the first actionable cause across the disconnect,
            // close callback and finally-block. A generic "reconnecting"
            // status must not erase a real TLS/REST/authentication error.
            val reconnectCause = AtomicReference<String?>(null)
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) {
                onConnection(false, "credentials_not_configured")
                sleepBackoff(delayMs)
                delayMs = (delayMs * 2L).coerceAtMost(30_000L)
                continue
            }

            try {
                val listenKey = createListenKey(apiKey)
                if (!acceptsCallback(generation)) break
                check(!endpoint.contains("?")) {
                    "Futures private stream endpoint must be a base path; query parameters are composed locally"
                }
                val streamUrl = endpoint + "?listenKey=" +
                    URLEncoder.encode(listenKey, "UTF-8") +
                    "&events=" + EVENT_TYPES
                val request = Request.Builder().url(streamUrl).build()

                socket = webSocketFactory(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (!acceptsCallback(generation)) {
                                webSocket.cancel()
                                return
                            }
                            connected = true
                            delayMs = 1000L
                            onConnection(true, null)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!acceptsCallback(generation)) return
                            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                            val event = message.optJSONObject("data") ?: message
                            if (event.has("e")) {
                                runCatching { onEvent(event) }
                                    .onFailure {
                                        connected = false
                                        val detail = "event_handler_failed: " +
                                            (it.message ?: it.javaClass.simpleName)
                                        reconnectCause.compareAndSet(null, detail)
                                        onConnection(false, detail)
                                        // A failed account/order event means the local view can no
                                        // longer be trusted. Force the reconnect loop to reconnect
                                        // and let the caller complete REST reconciliation before
                                        // any new entry is allowed.
                                        webSocket.close(1011, "event_handler_failed")
                                        closed.countDown()
                                    }
                                // The listenKey can expire without a WebSocket close. Reconnect
                                // immediately so a fresh key is created and the runtime's onOpen
                                // path performs REST reconciliation before the entry gate clears.
                                if (shouldReconnectFuturesUserStream(event)) {
                                    val reason = event.optString("e")
                                    connected = false
                                    reconnectCause.compareAndSet(null, reason)
                                    onConnection(false, reason)
                                    webSocket.close(1000, "Futures private stream $reason; reconnect")
                                    closed.countDown()
                                }
                            } else if (message.has("code") || message.has("error")) {
                                connected = false
                                val detail = "Futures user stream error: " +
                                    message.optString("msg", message.optString("error", "unknown"))
                                reconnectCause.compareAndSet(null, detail)
                                onConnection(false, detail)
                                closed.countDown()
                                webSocket.close(1011, "exchange_error")
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            onMessage(webSocket, bytes.utf8())
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (!acceptsCallback(generation)) return
                            connected = false
                            val detail = "closing: " + code + ": " +
                                (reason.ifBlank { "none" })
                            reconnectCause.compareAndSet(null, detail)
                            onConnection(false, detail)
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!acceptsCallback(generation)) return
                            connected = false
                            val detail = "closed: " + code + ": " +
                                (reason.ifBlank { "none" })
                            reconnectCause.compareAndSet(null, detail)
                            onConnection(false, detail)
                            closed.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!acceptsCallback(generation)) return
                            connected = false
                            val detail = t.message ?: t.javaClass.simpleName
                            reconnectCause.compareAndSet(null, detail)
                            onConnection(false, detail)
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
                val detail = failure.message ?: failure.javaClass.simpleName
                reconnectCause.compareAndSet(null, detail)
                onConnection(false, detail)
            } finally {
                // Invalidate callbacks before closing. Old onClosed/onFailure events
                // must not overwrite a later socket's readiness or deliver stale fills.
                connectionGeneration.compareAndSet(generation, generation + 1L)
                socket?.close(1000, "Futures user stream reconnect")
                socket = null
                connected = false
                if (!stopping.get()) onConnection(false,
                    reconnectCause.get() ?: "listen_key_rotation")
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
        private const val EVENT_TYPES = FUTURES_USER_STREAM_EVENT_FILTER
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

package com.williamsbot

import okhttp3.OkHttpClient
import java.util.WeakHashMap

/**
 * Process-local unsigned Binance Demo UTC proof, shared only by adapters that
 * use the SAME transport client. No credentials, account state or REST order
 * evidence is shared. A new transport/Android process begins unverified.
 */
internal class FuturesDemoVerifiedClock {
    val lock = Any()
    @Volatile var timeSynced = false
    @Volatile var serverAnchorMs = 0L
    @Volatile var anchorMonoNs = 0L
    @Volatile var anchorWallMs = 0L
    @Volatile var lastClockProbeRttMs = Long.MAX_VALUE
}

internal object FuturesDemoClockRegistry {
    private val clients = WeakHashMap<OkHttpClient, FuturesDemoVerifiedClock>()

    @Synchronized
    fun forTransport(client: OkHttpClient): FuturesDemoVerifiedClock =
        clients.getOrPut(client) { FuturesDemoVerifiedClock() }
}

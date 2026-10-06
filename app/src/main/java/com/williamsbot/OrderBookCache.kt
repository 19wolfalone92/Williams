package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Local Binance Spot diff-depth cache.
 *
 * A REST snapshot seeds lastUpdateId. Subsequent depthUpdate events are
 * accepted only when their sequence overlaps the next expected update.
 * Sequence gaps invalidate the cache and force a REST resync.
 */
class OrderBookCache(
    private val maxLevels: Int = 100
) {
    data class Snapshot(
        val symbol: String,
        val lastUpdateId: Long,
        val updatedAtMs: Long,
        val bids: Map<Double, Double>,
        val asks: Map<Double, Double>
    )

    private data class Book(
        var lastUpdateId: Long = -1L,
        var updatedAtMs: Long = 0L,
        val bids: MutableMap<Double, Double> = mutableMapOf(),
        val asks: MutableMap<Double, Double> = mutableMapOf()
    )

    private val books = mutableMapOf<String, Book>()

    @Synchronized
    fun seed(symbol: String, snapshot: JSONObject) {
        val book = Book(
            lastUpdateId = snapshot.optLong("lastUpdateId", -1L),
            updatedAtMs = System.currentTimeMillis()
        )
        loadLevels(book.bids, snapshot.optJSONArray("bids"))
        loadLevels(book.asks, snapshot.optJSONArray("asks"))
        books[symbol.uppercase()] = book
    }

    @Synchronized
    fun apply(
        symbol: String,
        firstUpdateId: Long,
        finalUpdateId: Long,
        bids: JSONArray,
        asks: JSONArray
    ): Boolean {
        if (finalUpdateId < firstUpdateId) return false
        val book = books[symbol.uppercase()] ?: return false

        // Old/duplicate event.
        if (finalUpdateId <= book.lastUpdateId) return true

        // Binance diff-depth continuity rule:
        // firstUpdateId <= previousLastUpdateId + 1 <= finalUpdateId.
        if (firstUpdateId > book.lastUpdateId + 1L) return false

        applyLevels(book.bids, bids)
        applyLevels(book.asks, asks)
        book.lastUpdateId = finalUpdateId
        book.updatedAtMs = System.currentTimeMillis()
        trim(book)
        return true
    }

    @Synchronized
    fun estimateBuy(
        symbol: String,
        quoteNotional: Double,
        maxAgeMs: Long = 1500L
    ): Pair<Double, Double>? {
        if (quoteNotional <= 0.0) return null
        val book = books[symbol.uppercase()] ?: return null
        if (book.lastUpdateId < 0L) return null
        if (System.currentTimeMillis() - book.updatedAtMs > maxAgeMs) return null

        val asks = book.asks.entries
            .filter { it.key > 0.0 && it.value > 0.0 }
            .sortedBy { it.key }
        if (asks.isEmpty()) return null

        var remaining = quoteNotional
        var base = 0.0
        var quote = 0.0
        var bestAsk = 0.0

        for ((price, qty) in asks) {
            if (bestAsk <= 0.0) bestAsk = price
            val levelQuote = price * qty
            val used = min(remaining, levelQuote)
            quote += used
            base += used / price
            remaining -= used
            if (remaining <= 1e-9) break
        }

        if (remaining > 1e-9 || base <= 0.0 || bestAsk <= 0.0) {
            return null
        }

        val vwap = quote / base
        return vwap to (vwap / bestAsk - 1.0).coerceAtLeast(0.0)
    }

    @Synchronized
    fun status(): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().apply {
            put("symbols", JSONArray().apply {
                books.keys.sorted().forEach { put(it) }
            })
            put("ready", books.values.count { it.lastUpdateId >= 0L })
            put("fresh", books.values.count {
                it.lastUpdateId >= 0L && now - it.updatedAtMs <= 1500L
            })
            put("max_age_ms", 1500)
            put("books", JSONObject().apply {
                books.forEach { (symbol, book) ->
                    put(
                        symbol,
                        JSONObject()
                            .put("last_update_id", book.lastUpdateId)
                            .put("age_ms", now - book.updatedAtMs)
                            .put("bid_levels", book.bids.size)
                            .put("ask_levels", book.asks.size)
                    )
                }
            })
        }
    }

    private fun loadLevels(
        target: MutableMap<Double, Double>,
        rows: JSONArray?
    ) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (price > 0.0 && qty > 0.0) target[price] = qty
        }
        trimMap(target, maxLevels)
    }

    private fun applyLevels(
        target: MutableMap<Double, Double>,
        rows: JSONArray
    ) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (price <= 0.0) continue
            if (qty == 0.0) target.remove(price)
            else target[price] = qty
        }
    }

    private fun trim(book: Book) {
        trimMap(book.bids, maxLevels)
        trimMap(book.asks, maxLevels)
    }

    private fun trimMap(
        map: MutableMap<Double, Double>,
        limit: Int
    ) {
        if (map.size <= limit) return
        val keep = map.keys.sorted().take(limit).toSet()
        map.keys.toList()
            .filter { it !in keep }
            .forEach { map.remove(it) }
    }
}

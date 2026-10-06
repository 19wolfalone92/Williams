package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Local Binance Spot diff-depth cache.
 *
 * The stream is buffered before the REST snapshot is available. After the
 * snapshot arrives, buffered events are discarded/applied according to the
 * documented U/u continuity rule. A gap invalidates the local book.
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

    private data class Event(
        val first: Long,
        val last: Long,
        val bids: JSONArray,
        val asks: JSONArray
    )

    private data class Book(
        var lastUpdateId: Long = -1L,
        var updatedAtMs: Long = 0L,
        val bids: MutableMap<Double, Double> = mutableMapOf(),
        val asks: MutableMap<Double, Double> = mutableMapOf(),
        val pending: MutableList<Event> = mutableListOf()
    )

    private val books = mutableMapOf<String, Book>()

    @Synchronized
    fun seed(symbol: String, snapshot: JSONObject): Boolean {
        val key = symbol.uppercase()
        val book = books.getOrPut(key) { Book() }
        val pending = book.pending.sortedBy { it.first }.toList()

        book.lastUpdateId = snapshot.optLong("lastUpdateId", -1L)
        book.updatedAtMs = System.currentTimeMillis()
        book.bids.clear()
        book.asks.clear()
        loadLevels(book.bids, snapshot.optJSONArray("bids"), keepHighest = true)
        loadLevels(book.asks, snapshot.optJSONArray("asks"), keepHighest = false)

        var ready = book.lastUpdateId >= 0L
        book.pending.clear()

        for (event in pending) {
            if (event.last <= book.lastUpdateId) continue
            if (event.first > book.lastUpdateId + 1L) {
                ready = false
                break
            }
            applyLevels(book.bids, event.bids)
            applyLevels(book.asks, event.asks)
            book.lastUpdateId = event.last
            book.updatedAtMs = System.currentTimeMillis()
            trim(book)
        }

        return ready
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
        val key = symbol.uppercase()
        val book = books.getOrPut(key) { Book() }

        if (book.lastUpdateId < 0L) {
            if (book.pending.size >= 200) book.pending.removeAt(0)
            book.pending += Event(firstUpdateId, finalUpdateId, bids, asks)
            return true
        }

        if (finalUpdateId <= book.lastUpdateId) return true
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

        if (remaining > 1e-9 || base <= 0.0 || bestAsk <= 0.0) return null
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
                            .put("pending_events", book.pending.size)
                            .put("bid_levels", book.bids.size)
                            .put("ask_levels", book.asks.size)
                    )
                }
            })
        }
    }

    private fun loadLevels(
        target: MutableMap<Double, Double>,
        rows: JSONArray?,
        keepHighest: Boolean
    ) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (price > 0.0 && qty > 0.0) target[price] = qty
        }
        trimMap(target, maxLevels, keepHighest)
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
        trimMap(book.bids, maxLevels, keepHighest = true)
        trimMap(book.asks, maxLevels, keepHighest = false)
    }

    private fun trimMap(
        map: MutableMap<Double, Double>,
        limit: Int,
        keepHighest: Boolean
    ) {
        if (map.size <= limit) return
        val sorted = map.keys.sorted()
        val keep = if (keepHighest) sorted.takeLast(limit).toSet()
                   else sorted.take(limit).toSet()
        map.keys.toList().filter { it !in keep }.forEach { map.remove(it) }
    }
}

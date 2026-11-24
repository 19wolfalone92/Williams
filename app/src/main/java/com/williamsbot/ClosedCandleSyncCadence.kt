package com.williamsbot

/**
 * Schedule the next PUBLIC read-only OHLCV catch-up AFTER the expected UTC
 * close of the shortest persisted frame. Binance exchangeNowMs must have been
 * independently verified by the caller; never use this timer as order/fill/
 * signed STOP proof. A missed tick is caught up from SQLite's latest cursor.
 */
internal fun nextClosedCandleSyncDelayMs(
    verifiedExchangeNowMs:Long,frame:String
):Long {
    val intervalMs=when(frame) {
        "1m"->60_000L; "3m"->180_000L; "5m"->300_000L
        "15m"->900_000L; "30m"->1_800_000L
        "1h"->3_600_000L; "2h"->7_200_000L
        "4h"->14_400_000L; "6h"->21_600_000L
        "8h"->28_800_000L; "12h"->43_200_000L
        "1d"->86_400_000L; "3d"->259_200_000L
        "1w"->604_800_000L
        else->return 30_000L
    }
    if(verifiedExchangeNowMs<=0L) return 30_000L
    val nextClose=(Math.floorDiv(verifiedExchangeNowMs,intervalMs)+1L)*intervalMs
    return (nextClose-verifiedExchangeNowMs+2_500L).coerceIn(1_000L,intervalMs)
}

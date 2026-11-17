package com.williamsbot

/**
 * An exchange STOP_MARKET is armed now but executed only if FUTURE contract
 * trade price crosses the book's H1 trigger. Today's best bid/ask is not the
 * future execution quote. It must not veto placement of a valid conditional
 * entry; slippage and fees remain part of stop-distance sizing, and signed
 * account/STOP ownership fences remain mandatory in the coordinator.
 *
 * An immediate MARKET order is different: it consumes the order book NOW.
 * LIMIT/TAKE_PROFIT require their own explicit submission policy and cannot
 * silently reuse the STOP exception.
 */
internal fun futuresEntryQuoteBlocker(
    kind: NativeFuturesCampaignCoordinator.EntryKind,
    bid: Double?,
    ask: Double?,
    maxSpreadPct: Double
): String? {
    if(kind == NativeFuturesCampaignCoordinator.EntryKind.STOP) return null
    if(kind != NativeFuturesCampaignCoordinator.EntryKind.MARKET)
        return "FUTURES_ENTRY_QUOTE_KIND_UNSUPPORTED"
    if(!maxSpreadPct.isFinite() || maxSpreadPct < 0.0 ||
        bid == null || ask == null || !bid.isFinite() || !ask.isFinite() ||
        bid <= 0.0 || ask < bid)
        return "FUTURES_ENTRY_TOP_OF_BOOK_UNVERIFIED"
    return if((ask-bid)/bid > maxSpreadPct)
        "FUTURES_ENTRY_SPREAD_BLOCKED"
    else null
}

package com.williamsbot

/** Entry-only daily halt. It never owns operator pause, kill or reconciliation. */
internal data class FuturesDailyEntryHaltDecision(
    val available:Boolean,val allowNewEntries:Boolean,val untilMs:Long?,val reason:String?
)

internal fun evaluateFuturesDailyEntryHalt(
    persistedUtcDayStart:String?,exchangeNowMs:Long,exchangeClockFresh:Boolean
):FuturesDailyEntryHaltDecision {
    if(persistedUtcDayStart==null)
        return FuturesDailyEntryHaltDecision(true,true,null,null)
    val day=persistedUtcDayStart.toLongOrNull()
    val duration=86_400_000L
    if(day==null || day<=0L || day%duration!=0L || day>Long.MAX_VALUE-duration)
        return FuturesDailyEntryHaltDecision(false,false,null,"FUTURES_DAILY_ENTRY_HALT_STATE_INVALID")
    val until=day+duration
    if(!exchangeClockFresh || exchangeNowMs<=0L)
        return FuturesDailyEntryHaltDecision(false,false,until,"FUTURES_DAILY_ENTRY_HALT_CLOCK_UNVERIFIED")
    // An earlier/rolled-back clock cannot expire a later recorded halt.
    return if(exchangeNowMs<until)
        FuturesDailyEntryHaltDecision(true,false,until,"FUTURES_DAILY_LOSS_LIMIT")
    else FuturesDailyEntryHaltDecision(true,true,null,null)
}

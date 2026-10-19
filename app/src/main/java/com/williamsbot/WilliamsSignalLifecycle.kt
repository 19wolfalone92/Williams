package com.williamsbot
import org.json.JSONArray
import org.json.JSONObject

internal fun williamsReasonCategory(reason:String):String = when {
    reason.startsWith("RUNTIME_OR_CAMPAIGN_NOT_READY",true) || reason.startsWith("Addition profile/session/daily risk",true) -> "RUNTIME_GATE"
    listOf("ENTRY_TIME").any { reason.contains(it,true) } -> "ENTRY_TIME"
    listOf("H4","D1","HTF","DIRECTIONAL_CONTEXT").any { reason.contains(it,true) } -> "HTF_CONTEXT"
    listOf("RISK","EQUITY","FUNDS","MARGIN","LOSS").any { reason.contains(it,true) } -> "RISK"
    listOf("STRUCTUR","H1","FORMATION","CORRECTION").any { reason.contains(it,true) } -> "H1_STRUCTURE"
    listOf("BINANCE","EXCHANGE","ORDER","RECONCIL").any { reason.contains(it,true) } -> "EXCHANGE"
    else -> "RUNTIME_GATE"
}
internal fun signalFactMatchesIntent(s:JSONObject,o:JSONObject):Boolean =
    if(o.optString("signal_id").isNotBlank()) o.optString("signal_id")==s.optString("signal_id")
    else o.optString("signal_type")==s.optString("type") && o.optLong("signal_bar_time_ms")==s.optLong("signal_bar_time_ms") &&
        o.optString("strategy_profile")==s.optString("strategy_profile") && o.optString("direction")==s.optString("direction")

/** A valid formation is not permission to submit an order. */
internal fun williamsSignalLifecycle(s:JSONObject,owner:JSONObject?,now:Long,snapshotAt:Long,current:Boolean):String {
    if(owner!=null) {
        val order=owner.optString("order_status",owner.optString("exchange_status")).uppercase()
        if(order in setOf("CANCELED","CANCELLED","EXPIRED","EXPIRED_IN_MATCH","REJECTED")) return if(order.startsWith("CANCEL")) "CANCELLED" else order
        if(order=="FILLED") return "FILLED"
        if(order=="PARTIALLY_FILLED") return "PARTIAL_FILL"
        if(order in setOf("NEW","ACCEPTED","WORKING") && owner.optString("campaign_status")=="ENTRY_PENDING") {
            val observed=maxOf(owner.optLong("last_reconciled_ms"),owner.optLong("created_at_ms"))
            return if(observed>0 && now>=observed && now-observed<=120_000L) "BINANCE_PENDING" else "PENDING_UNVERIFIED"
        }
        if(order.contains("UNKNOWN") || order in setOf("TRIGGERED","PENDING")) return "PENDING_UNVERIFIED"
    }
    val known=s.optLong("known_at_ms");val expiry=s.optLong("expires_at_ms",s.optLong("signal_bar_time_ms")+18_000_000L)
    if(known<=0 || known>now) return "DETECTED"
    if(expiry<=now) return "EXPIRED"
    if(!current) return "INVALIDATED"
    if(snapshotAt<=0 || now<snapshotAt || now-snapshotAt>120_000L) return "DETECTED"
    return "VALID"
}
/** Bounded durable observations; no trading API or order authority. */
internal class WilliamsSignalJournal(private val store:FuturesCampaignStateStore) {
    companion object { private val lock=Any() }
    private fun rows(a:JSONArray)=(0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun load(symbol:String)=store.readState("signal_timeline_"+symbol)?.let { JSONObject(it) }
        ?: JSONObject().put("signals",JSONArray()).put("events",JSONArray())
    private fun save(symbol:String,data:JSONObject)=store.writeState("signal_timeline_"+symbol,data.toString())
    private fun event(data:JSONObject,s:JSONObject,state:String,stage:String,reason:String,now:Long,scope:String="SIGNAL") {
        val events=data.optJSONArray("events") ?: JSONArray();val id=s.optString("signal_id")+":"+stage+":"+state+":"+safeDiagnosticText(reason)
        if(rows(events).any { it.optString("event_id")==id }) return
        val row=JSONObject(s.toString()).put("event_id",id).put("state",state).put("stage",stage)
            .put("reason",safeDiagnosticText(reason)).put("reason_category",williamsReasonCategory(reason))
            .put("observed_at_ms",now).put("attribution",scope).put("can_place_orders",false)
        data.put("events",JSONArray((rows(events)+row).takeLast(256)))
    }
    fun observe(symbol:String,signals:JSONArray,now:Long,policy:WilliamsEntryTimePolicy):Boolean=synchronized(lock) {
        val data=load(symbol);val known=rows(data.getJSONArray("signals")).associateBy { it.optString("signal_id") }.toMutableMap()
        val currentIds=rows(signals).map { it.optString("signal_id") }.toSet()
        known.values.filter { it.optLong("last_seen_at_ms")==data.optLong("snapshot_at_ms") && it.optString("signal_id") !in currentIds }.forEach {
            val state=if(it.optLong("expires_at_ms")<=now) "EXPIRED" else "INVALIDATED"
            event(data,it,state,"FORMATION","FORMATION_NO_LONGER_PRESENT_IN_CLOSED_BAR_SCAN",now)
        }
        rows(signals).forEach { source ->
            val raw=JSONObject(source.toString()).put("last_seen_at_ms",now)
                .put("first_seen_at_ms",known[source.optString("signal_id")]?.optLong("first_seen_at_ms") ?: now)
            event(data,raw,"DETECTED","FORMATION","FORMATION_OBSERVED",now)
            val state=williamsSignalLifecycle(raw,null,now,now,true)
            event(data,raw,state,"CONFIRMATION",if(state=="VALID") "CLOSED_BAR_FORMATION_CONFIRMED" else state,now)
            if(!policy.allows(now)) event(data,raw,"BLOCKED","ENTRY_TIME","ENTRY_TIME_POLICY_BLOCKED: "+policy.toJson(),now,"PAIR_GATE")
            known[raw.getString("signal_id")]=raw
        }
        data.put("signals",JSONArray(known.values.sortedBy { it.optLong("last_seen_at_ms") }.takeLast(100))).put("snapshot_at_ms",now)
        save(symbol,data)
    }
    fun decision(symbol:String,stage:String,reason:String,now:Long,signalId:String="",accepted:Boolean=false):Boolean=synchronized(lock) {
        val data=load(symbol)
        rows(data.getJSONArray("signals")).filter { if(signalId.isNotBlank()) it.optString("signal_id")==signalId else it.optLong("last_seen_at_ms")==data.optLong("snapshot_at_ms") }
            .forEach { event(data,it,if(accepted) "ADMITTED" else "BLOCKED",stage,reason,now,if(signalId.isBlank()) "PAIR_GATE" else "SIGNAL") }
        save(symbol,data)
    }
    fun view(symbol:String,intents:JSONArray,now:Long):JSONObject=synchronized(lock) {
        val data=load(symbol);val owners=rows(intents).filter { it.optString("symbol")==symbol }
        val facts=rows(data.getJSONArray("signals")).map { s ->
            val owner=owners.lastOrNull { signalFactMatchesIntent(s,it) }
            JSONObject(s.toString()).put("state",williamsSignalLifecycle(s,owner,now,data.optLong("snapshot_at_ms"),s.optLong("last_seen_at_ms")==data.optLong("snapshot_at_ms")))
                .put("can_place_orders",false).apply { if(owner!=null) {
                    put("client_id",owner.optString("client_id")).put("order_status",owner.optString("order_status"))
                    put("cancel_reason",owner.optString("cancel_reason")).put("exchange_observed_at_ms",maxOf(owner.optLong("last_reconciled_ms"),owner.optLong("created_at_ms")))
                    put("trigger",owner.optString("entry_price").toDoubleOrNull() ?: s.optDouble("trigger"))
                    put("protective_stop",owner.optString("protective_stop").toDoubleOrNull() ?: s.optDouble("protective_stop"))
                } }
        }
        JSONObject().put("signals",JSONArray(facts)).put("events",data.getJSONArray("events"))
            .put("snapshot_at_ms",data.optLong("snapshot_at_ms")).put("retention_events_per_symbol",256)
    }
    fun orders(intents:JSONArray,now:Long) = synchronized(lock) {
        rows(intents).map { it.optString("symbol") }.distinct().filter { it.isNotBlank() }.forEach { symbol ->
            val data=load(symbol);rows(view(symbol,intents,now).getJSONArray("signals")).filter { it.has("client_id") }.forEach {
                event(data,it,it.getString("state"),"EXCHANGE",it.optString("cancel_reason").ifBlank { it.optString("order_status") },now)
            };save(symbol,data)
        }
    }
}
internal data class WilliamsChartTrigger(val state:String,val trigger:Double?=null,val stop:Double?=null,val direction:String="")
internal fun williamsChartTrigger(raw:String,symbol:String,now:Long,livePrice:Double?):WilliamsChartTrigger {
    val signals=runCatching { JSONObject(raw).getJSONObject(symbol).getJSONObject("signal_history").getJSONArray("signals") }.getOrNull()
        ?: return WilliamsChartTrigger("NO_VERIFIED_PENDING_ORDER")
    val pending=(0 until signals.length()).map { signals.getJSONObject(it) }.filter { it.optString("state")=="BINANCE_PENDING" }
    if(pending.size!=1) return WilliamsChartTrigger(if(pending.size>1) "AMBIGUOUS_PENDING_ORDERS" else "NO_VERIFIED_PENDING_ORDER")
    val p=pending.single();val trigger=p.optDouble("trigger");val direction=p.optString("direction")
    if(!trigger.isFinite() || trigger<=0 || p.optLong("expires_at_ms",Long.MAX_VALUE)<=now) return WilliamsChartTrigger("PENDING_REQUIRES_RECONCILIATION")
    if(livePrice!=null && livePrice.isFinite() && (if(direction=="LONG") livePrice>=trigger else livePrice<=trigger))
        return WilliamsChartTrigger("TRIGGER_CROSSED_AWAITING_EXCHANGE")
    return WilliamsChartTrigger("BINANCE_PENDING",trigger,p.optDouble("protective_stop").takeIf { it.isFinite() && it>0 },direction)
}

internal fun williamsTriggerStateLabel(state:String):String = when(state) {
    "DETECTED" -> "обнаружен; актуальность не подтверждена"
    "VALID" -> "действующая формация; требуется допуск"
    "BINANCE_PENDING" -> "ожидающая заявка подтверждена"
    "CANCELLED","CANCELED" -> "отменён"
    "EXPIRED","EXPIRED_IN_MATCH" -> "истёк"
    "INVALIDATED" -> "формация больше не действительна"
    "FILLED" -> "исполнен"
    "PARTIAL_FILL" -> "частично исполнен"
    "BLOCKED" -> "вход заблокирован"
    "ADMITTED" -> "допуск выполнен"
    "TRIGGER_CROSSED_AWAITING_EXCHANGE" -> "уровень пересечён; ожидается сверка исполнения"
    "PENDING_UNVERIFIED","PENDING_REQUIRES_RECONCILIATION" -> "состояние заявки требует сверки"
    "AMBIGUOUS_PENDING_ORDERS" -> "несколько заявок; нужен журнал исполнения"
    "NO_VERIFIED_PENDING_ORDER" -> "подтверждённой ожидающей заявки нет"
    else -> state
}

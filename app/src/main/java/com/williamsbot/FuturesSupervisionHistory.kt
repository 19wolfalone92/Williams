package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Durable facts about operator-approved exposure, without inventing ENTRY ownership. */
internal fun appendSupervisionHistory(record:JSONObject, kind:String, atMs:Long, detail:String, stop:String?=null) {
    val history=record.optJSONArray("management_history") ?: JSONArray().also { record.put("management_history",it) }
    val event=JSONObject().put("kind",kind).put("at_ms",atMs).put("detail",detail)
    if(stop!=null) event.put("stop",stop)
    history.put(event)
    if(history.length()>2000) {
        history.remove(1) // Preserve the approval and explicitly disclose compaction.
        record.put("management_history_compacted",record.optInt("management_history_compacted")+1)
    }
}

internal fun ensureSupervisionHistory(record:JSONObject) {
    if(record.has("management_history")) return
    appendSupervisionHistory(record,"APPROVED",record.optLong("approved_at_ms"),
        "Подключено сопровождение существующей позиции; исходный сигнал и время открытия не сохранены",
        record.optString("initial_stop",record.optString("stop")))
    val trailed=record.optLong("last_confirmed_trail_at_ms")
    if(trailed>0L) appendSupervisionHistory(record,"LAST_STOP_IMPORTED",trailed,
        "Последний подтверждённый SL из прежнего журнала; промежуточные переносы не сохранены",record.optString("stop"))
}

/** Call only with a fresh, validated, signed position snapshot. Absence proves no
 * matching exposure now, never the price, time, PnL or reason of its disappearance. */
internal fun reconcileSupervisionHistory(records:JSONArray, positions:JSONArray, nowMs:Long):JSONArray {
    require(nowMs>0L)
    validateFuturesPositionSnapshot(positions)
    val result=JSONArray(records.toString())
    for(i in 0 until result.length()) {
        val record=result.getJSONObject(i)
        ensureSupervisionHistory(record)
        if(record.optString("state") !in setOf("ACTIVE","REPLACEMENT_UNRESOLVED")) continue
        val direction=record.getString("direction")
        val live=(0 until positions.length()).map { positions.getJSONObject(it) }.singleOrNull {
            val qty=it.getString("positionAmt").toBigDecimal()
            it.optString("symbol")==record.getString("symbol") && qty.signum()!=0 &&
                (qty.signum()>0)==(direction=="LONG")
        }
        if(live==null) {
            record.put("state","CLOSED_PENDING_EVIDENCE").put("position_absent_observed_at_ms",nowMs)
                .put("last_evaluation_status","CLOSED")
                .put("last_evaluation_reason","Прежней позиции нет в свежем снимке Binance; ожидаются исполнения закрытия")
            appendSupervisionHistory(record,"POSITION_ABSENT",nowMs,
                "Прежняя позиция отсутствует; причина и время закрытия пока не подтверждены")
        } else {
            val qty=live.getString("positionAmt").toBigDecimal().abs()
            val entry=live.optString("entryPrice").toBigDecimalOrNull()
            val approvedEntry=record.getString("entry_price").toBigDecimal()
            val tolerance=maxOf(BigDecimal("0.00000001"),approvedEntry.abs()*BigDecimal("0.00000001"))
            if(live.optString("positionSide","BOTH")!="BOTH" ||
                qty.compareTo(record.getString("quantity").toBigDecimal())!=0 || entry==null ||
                (entry-approvedEntry).abs()>tolerance) {
                record.put("state","SUSPENDED_CHANGED").put("last_evaluation_status","BLOCKED")
                    .put("last_evaluation_reason","Объём или вход изменился; прежнее разрешение сопровождения приостановлено")
                appendSupervisionHistory(record,"EXPOSURE_CHANGED",nowMs,record.getString("last_evaluation_reason"))
            }
        }
    }
    return result
}

/** A trigger is not a fill price. Bind the precise STOP parent, spawned child and
 * all child fills before publishing an exchange-confirmed closure. Entry fees and
 * funding remain unknown when the original ENTRY journal was lost. */
internal fun verifiedSupervisedStopClosure(record:JSONObject, algo:JSONObject, child:JSONObject, fills:JSONArray):JSONObject? =
    runCatching {
        val symbol=record.getString("symbol")
        val side=if(record.getString("direction")=="LONG") "SELL" else "BUY"
        val ids=setOf(record.optString("protection_client_id"),record.optString("pending_protection_client_id"))-""
        val id=algo.optString("actualOrderId")
        val quantity=record.getString("quantity").toBigDecimal()
        require(quantity.signum()>0 && id.toLongOrNull()?.let { it>0 }==true)
        require(algo.optString("symbol")==symbol && algo.optString("clientAlgoId") in ids &&
            algo.optString("orderType").ifBlank { algo.optString("type") }=="STOP_MARKET" &&
            algo.optString("side")==side && algo.optString("positionSide","BOTH")=="BOTH" &&
            algo.optString("algoStatus")=="FINISHED")
        val expectedStop=if(algo.optString("clientAlgoId")==record.optString("pending_protection_client_id"))
            record.getString("pending_stop") else record.getString("stop")
        require(algo.getString("triggerPrice").toBigDecimal().compareTo(expectedStop.toBigDecimal())==0)
        require(child.optString("symbol")==symbol && child.optString("orderId")==id &&
            child.optString("side")==side && child.optString("positionSide","BOTH")=="BOTH" &&
            child.optString("status")=="FILLED" &&
            child.getString("executedQty").toBigDecimal().compareTo(quantity)==0)
        require(fills.length() in 1..999) { "Fill history is empty or possibly truncated" }
        var total=BigDecimal.ZERO;var cost=BigDecimal.ZERO;var gross=BigDecimal.ZERO;var fee=BigDecimal.ZERO
        var exitFeeKnown=true;var closed=0L
        val seen=mutableSetOf<String>()
        for(i in 0 until fills.length()) {
            val fill=fills.getJSONObject(i)
            require(fill.optString("symbol")==symbol && fill.optString("orderId")==id &&
                fill.optString("side")==side && fill.optString("positionSide","BOTH")=="BOTH" &&
                fill.optString("id").toLongOrNull()?.let { it>0 }==true && seen.add(fill.optString("id")))
            val q=fill.getString("qty").toBigDecimal();val price=fill.getString("price").toBigDecimal()
            val time=fill.getLong("time")
            require(q.signum()>0 && price.signum()>0 && time>=record.getLong("approved_at_ms"))
            total+=q;cost+=q*price;gross+=fill.getString("realizedPnl").toBigDecimal()
            val commission=fill.getString("commission").toBigDecimal()
            require(commission.signum()>=0)
            if(fill.optString("commissionAsset")=="USDT") fee+=commission else exitFeeKnown=false
            closed=maxOf(closed,time)
        }
        require(total.compareTo(quantity)==0 && gross.toDouble().isFinite() && fee.toDouble().isFinite())
        val exit=cost.divide(total,18,java.math.RoundingMode.HALF_UP).toDouble()
        require(exit.isFinite() && exit>0)
        JSONObject().put("closed_at",closed).put("exit_price",exit).put("gross_pnl_usdt",gross.toDouble())
            .put("exit_fee_usdt",if(exitFeeKnown) fee.toDouble() else JSONObject.NULL)
            .put("exit_fee_known",exitFeeKnown).put("exit_order_id",id)
            .put("exit_algo_client_id",algo.getString("clientAlgoId"))
            .put("fill_count",fills.length()).put("exit_reason","Стоп-лосс исполнен на Binance")
            .put("proof","EXACT_STOP_CHILD_AND_COMPLETE_USER_TRADES")
    }.getOrNull()

/** Read-only projection shared by the UI, exported diagnostics and history. Stored
 * reconciled journal rows win over placeholders by stable trade identity. */
internal fun futuresVisibleTradeHistory(journal:JSONArray, supervisions:JSONArray, intents:JSONArray):JSONArray {
    val byId=linkedMapOf<String,JSONObject>()
    for(i in 0 until supervisions.length()) {
        val r=JSONObject(supervisions.getJSONObject(i).toString());ensureSupervisionHistory(r)
        val state=r.optString("state");val closure=r.optJSONObject("confirmed_closure")
        val row=JSONObject().put("id","SUPERVISION:${r.getString("symbol")}:${r.getLong("approved_at_ms")}")
            .put("symbol",r.getString("symbol")).put("direction",r.getString("direction"))
            .put("side",if(r.getString("direction")=="LONG") "BUY" else "SELL")
            .put("status",when { closure!=null -> "CLOSED";state=="CLOSED_PENDING_EVIDENCE" -> "CLOSED_UNVERIFIED"
                state=="SUSPENDED_CHANGED" -> "SUSPENDED";else -> "OPEN" })
            .put("entry_price",r.getString("entry_price").toDouble()).put("qty",r.getString("quantity").toDouble())
            .put("stop_price",r.optString("initial_stop",r.getString("stop")).toDouble())
            .put("last_stop",r.getString("stop").toDouble()).put("supervision_started_at",r.getLong("approved_at_ms"))
            .put("opened_at",0L).put("entry_time_known",false).put("entry_reason","Исходный сигнал не сохранён; существующая позиция подключена к сопровождению SL")
            .put("closure_observed_at",r.optLong("position_absent_observed_at_ms"))
            .put("closed_at",closure?.optLong("closed_at") ?: 0L)
            .put("exit_price",closure?.opt("exit_price") ?: JSONObject.NULL)
            .put("gross_pnl_usdt",closure?.opt("gross_pnl_usdt") ?: JSONObject.NULL)
            .put("exit_fee_usdt",closure?.opt("exit_fee_usdt") ?: JSONObject.NULL)
            .put("exit_reason",closure?.optString("exit_reason") ?: if(state=="CLOSED_PENDING_EVIDENCE")
                "Прежней позиции нет на бирже; причина закрытия ещё не подтверждена" else "")
            .put("outcome",when { closure!=null -> "CLOSED";state=="CLOSED_PENDING_EVIDENCE" -> "CLOSED_UNVERIFIED"
                state=="SUSPENDED_CHANGED" -> "SUSPENDED";else -> "OPEN" })
            .put("pnl",JSONObject.NULL).put("pnl_complete",false).put("fee_known",false)
            .put("statistics_note","Чистый результат неизвестен: исходные комиссии и платежи за финансирование не восстановлены")
            .put("management_history",r.getJSONArray("management_history"))
            .put("management_reason",r.optString("last_evaluation_reason"))
            .put("history_recovery_detail",r.optString("closure_history_detail"))
            .put("trade_audit_source","OPERATOR_APPROVED_STOP_ONLY")
            .put("strategy_ownership_verified",false)
        byId[row.getString("id")]=row
    }
    for(i in 0 until intents.length()) {
        val r=intents.getJSONObject(i)
        if(r.optString("intent_role","ENTRY")!="ENTRY" || r.optDouble("actual_entry_price",0.0)<=0 ||
            r.optString("client_id").isBlank()) continue
        val qty=r.optString("confirmed_position_qty").toDoubleOrNull()?.takeIf { it>0 } ?: continue
        val closed=campaignLifecycleStatus(r)=="CLOSED_OR_STOPPED"
        val confirmedStop=if(r.optString("protection_status")=="PROTECTED")
            r.optString("protective_stop").toDoubleOrNull()
            else r.optString("previous_protective_stop").toDoubleOrNull()
        val row=JSONObject().put("id","FUTURES:"+r.getString("client_id"))
            .put("symbol",r.getString("symbol")).put("direction",r.getString("direction"))
            .put("side",if(r.getString("direction")=="LONG") "BUY" else "SELL")
            .put("status",if(closed) "CLOSED_UNVERIFIED" else "OPEN")
            .put("outcome",if(closed) "CLOSED_UNVERIFIED" else "OPEN")
            .put("entry_price",r.getDouble("actual_entry_price")).put("qty",qty)
            .put("stop_price",r.optString("initial_protective_stop").toDoubleOrNull() ?: JSONObject.NULL)
            .put("last_stop",confirmedStop?.takeIf { it.isFinite() && it>0 } ?: JSONObject.NULL)
            .put("opened_at",r.optLong("position_open_observed_at_ms",r.optLong("fill_observed_at_ms")))
            .put("entry_time_known",false).put("closed_at",0L)
            .put("closure_observed_at",r.optLong("closed_observed_at_ms"))
            .put("entry_reason",r.optString("signal_reason"))
            .put("management_reason",r.optString("trail_error").ifBlank { r.optString("trade_journal_detail") })
            .put("pnl",JSONObject.NULL).put("pnl_complete",false).put("fee_known",false)
            .put("statistics_note",if(closed) "Ожидается восстановление биржевых исполнений и комиссий" else "Сделка открыта; итоговый результат ещё не сформирован")
            .put("trade_audit_source","FUTURES_CAMPAIGN_SNAPSHOT")
        byId[row.getString("id")]=row
    }
    for(i in 0 until journal.length()) {
        val row=journal.getJSONObject(i);byId[row.getString("id")]=row
    }
    return JSONArray(byId.values.sortedByDescending {
        maxOf(it.optLong("closed_at"),it.optLong("closure_observed_at"),it.optLong("opened_at"),it.optLong("supervision_started_at"))
    })
}

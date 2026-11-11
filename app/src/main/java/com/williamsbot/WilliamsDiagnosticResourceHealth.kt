package com.williamsbot

import org.json.JSONObject

/**
 * Read-only cross-resource pressure audit. No JNI heap dump, forced GC,
 * thread suspension or network operation. Missing values NEVER grant PASS.
 * This report cannot start/stop trading or replace signed STOP monitoring.
 */
internal object WilliamsDiagnosticResourceHealth {
    private const val MiB=1024.0*1024.0

    fun heap(memory: JSONObject?): JSONObject {
        val result=JSONObject().put("schema","WILLIAMS_DIAG_HEAP_V1")
            .put("read_only",true).put("force_gc",false)
        val used=memory?.optLong("java_heap_used_bytes",-1L) ?: -1L
        val limit=memory?.optLong("java_heap_limit_bytes",-1L) ?: -1L
        val committed=memory?.optLong("java_heap_committed_bytes",-1L) ?: -1L
        val native=memory?.optLong("native_heap_allocated_bytes",-1L) ?: -1L
        if(used<0 || limit<=0 || used>limit || committed<used || committed>limit) {
            return result.put("severity","NOT_VERIFIED").put("reason","JAVA_HEAP_COUNTERS_MISSING_OR_INVALID")
        }
        val headroom=limit-used
        val ratio=used.toDouble()/limit.toDouble()
        val severity=when {
            ratio>=0.95 || headroom<8L*1024*1024 -> "WARNING"
            ratio>=0.80 || headroom<24L*1024*1024 -> "WARNING"
            else -> "PASS"
        }
        return result.put("severity",severity)
            .put("java_heap_used_bytes",used).put("java_heap_limit_bytes",limit)
            .put("java_heap_headroom_bytes",headroom)
            .put("java_heap_used_pct",(ratio*100.0))
            .put("java_heap_committed_bytes",committed)
            .put("native_heap_allocated_bytes",if(native>=0) native else JSONObject.NULL)
            .put("java_heap_headroom_mib",headroom/MiB)
            .put("reason",if(severity=="PASS") "HEADROOM_AT_CAPTURE_ONLY"
                else "JAVA_HEAP_PRESSURE_REDUCE_DIAGNOSTIC_ALLOCATION")
            .put("historic_oom_is_separate_evidence",true)
    }

    fun rateLimits(rate:JSONObject?):JSONObject {
        val result=JSONObject().put("schema","WILLIAMS_DIAG_RATE_LIMITS_V1")
            .put("read_only",true).put("can_override_exchange_rate_guard",false)
        val used=rate?.optLong("used_weight_1m",-1L) ?: -1L
        val limit=rate?.optLong("weight_limit_1m",-1L) ?: -1L
        val status=rate?.optInt("last_http_status",-1) ?: -1
        val cooldown=rate?.optLong("cooldown_ms",-1L) ?: -1L
        val lastResponse=rate?.optLong("last_response_at_ms",0L) ?: 0L
        if(used<0 || limit<=0 || used>limit*10 || cooldown<0 || lastResponse<=0L)
            return result.put("severity","NOT_VERIFIED").put("reason","RATE_GOVERNOR_COUNTERS_UNKNOWN")
        val fraction=used.toDouble()/limit.toDouble()
        val blocked=status==429 || status==418 || cooldown>0L
        val danger=blocked || fraction>=0.90
        return result.put("severity",if(danger) "WARNING" else "PASS")
            .put("used_weight_1m",used).put("weight_limit_1m",limit)
            .put("weight_usage_pct",fraction*100.0)
            .put("last_http_status",status).put("cooldown_ms",cooldown)
            .put("last_response_at_ms",lastResponse)
            .put("historical_rate_limit_responses",
                rate?.optLong("rate_limit_response_count",0L) ?: 0L)
            .put("reason",when {
                blocked -> "BINANCE_RATE_LIMIT_ACTIVE"
                fraction>=0.90 -> "RATE_BUDGET_NEAR_LIMIT"
                else -> "HEADROOM_AT_CAPTURE_ONLY"
            })
    }

    fun queues(snapshot:JSONObject?):JSONObject {
        val out=JSONObject().put("schema","WILLIAMS_DIAG_EXECUTOR_QUEUES_V1")
            .put("read_only",true).put("can_interrupt_threads",false)
        if(snapshot==null) return out.put("severity","NOT_VERIFIED")
            .put("reason","EXECUTOR_SNAPSHOT_MISSING")
        val scanQueue=snapshot.optInt("scanner_queue_size",-1)
        val scanCapacity=snapshot.optInt("scanner_queue_capacity",-1)
        val researchQueue=snapshot.optInt("tc2_research_queue_size",-1)
        val historyQueue=snapshot.optInt("history_backfill_queue_size",-1)
        val indicatorQueue=snapshot.optInt("indicator_queue_size",-1)
        val active=snapshot.optInt("scanner_active_threads",-1)
        if(scanQueue<0 || scanCapacity<=0 || researchQueue<0 ||
            historyQueue<0 || indicatorQueue<0 || active<0)
            return out.put("severity","NOT_VERIFIED").put("reason","EXECUTOR_COUNTERS_UNKNOWN")
        val pressure=(scanQueue>=scanCapacity*9/10 && scanQueue>0) ||
            researchQueue>=12 || historyQueue>=100 || indicatorQueue>=100
        return out.put("severity",if(pressure) "WARNING" else "PASS")
            .put("scanner_active_threads",active)
            .put("scanner_queue_size",scanQueue)
            .put("scanner_queue_capacity",scanCapacity)
            .put("research_queue_size",researchQueue)
            .put("history_queue_size",historyQueue)
            .put("indicator_queue_size",indicatorQueue)
            .put("history_and_indicator_queues_unbounded",true)
            .put("reason",if(pressure) "EXECUTOR_BACKLOG_PRESSURE"
                else "QUEUE_BELOW_WARNING_THRESHOLD_AT_CAPTURE")
    }

    fun snapshot(memory:JSONObject?,rate:JSONObject?,executor:JSONObject?):JSONObject {
        val heap=heap(memory)
        val rateResult=rateLimits(rate)
        val queues=queues(executor)
        return JSONObject().put("schema","WILLIAMS_RESOURCE_DIAGNOSTICS_V1")
            .put("heap",heap).put("rate_limits",rateResult).put("executors",queues)
            .put("read_only",true).put("can_clear_new_entry_gate",false)
            .put("uncaptured_peak_memory_cannot_be_inferred",true)
    }
}

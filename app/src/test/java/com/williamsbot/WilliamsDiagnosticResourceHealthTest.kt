package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDiagnosticResourceHealthTest {
    private val MiB=1024L*1024L
    private fun heap(used:Long,limit:Long=256L*MiB):JSONObject =
        JSONObject().put("java_heap_used_bytes",used)
            .put("java_heap_limit_bytes",limit)
            .put("java_heap_committed_bytes",minOf(limit,used+16L*MiB))
            .put("native_heap_allocated_bytes",30L*MiB)

    private fun rate(weight:Long=1000L,cooldown:Long=0L,status:Int=200) =
        JSONObject().put("used_weight_1m",weight)
            .put("weight_limit_1m",6000L)
            .put("last_http_status",status)
            .put("last_response_at_ms",1_800_000_000_000L)
            .put("cooldown_ms",cooldown)

    private fun queues(scan:Int=0,capacity:Int=30,history:Int=0,indicator:Int=0) =
        JSONObject().put("scanner_queue_size",scan)
            .put("scanner_queue_capacity",capacity)
            .put("scanner_active_threads",3)
            .put("tc2_research_queue_size",0)
            .put("history_backfill_queue_size",history)
            .put("indicator_queue_size",indicator)

    @Test fun noNumbersMustNotBecomeGreen() {
        val result=WilliamsDiagnosticResourceHealth.snapshot(null,null,null)
        assertEquals("NOT_VERIFIED",result.getJSONObject("heap").getString("severity"))
        assertEquals("NOT_VERIFIED",result.getJSONObject("rate_limits").getString("severity"))
        assertEquals("NOT_VERIFIED",result.getJSONObject("executors").getString("severity"))
        assertFalse(result.getBoolean("can_clear_new_entry_gate"))
    }

    @Test fun lastPhoneHeap162MiBOf256MiBNotAnInventedCurrentOom() {
        val result=WilliamsDiagnosticResourceHealth.heap(heap(162L*MiB))
        assertEquals("PASS",result.getString("severity"))
        assertTrue(result.getBoolean("historic_oom_is_separate_evidence"))
    }

    @Test fun nearLimitHeapAndTooManyScannerOrBackfillTasksAreVisible() {
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.heap(heap(245L*MiB))
            .getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.queues(queues(scan=28))
            .getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.queues(queues(history=150))
            .getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.queues(queues(indicator=101))
            .getString("severity"))
    }

    @Test fun rateLimitIsNotSilencedButHistorical429AloneDoesNotPretendCurrentOutage() {
        assertEquals("PASS",WilliamsDiagnosticResourceHealth.rateLimits(
            rate(weight=1611)).getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.rateLimits(
            rate(weight=5500)).getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.rateLimits(
            rate(weight=1000,status=429)).getString("severity"))
        assertEquals("WARNING",WilliamsDiagnosticResourceHealth.rateLimits(
            rate(cooldown=45_000L)).getString("severity"))
        val old=rate().put("rate_limit_response_count",8)
        assertEquals("PASS",WilliamsDiagnosticResourceHealth.rateLimits(old)
            .getString("severity"))
    }

    @Test fun noResourceDiagnosticIsAllowedToMutateTrading() {
        val result=WilliamsDiagnosticResourceHealth.snapshot(
            heap(150L*MiB),rate(),queues())
        assertTrue(result.getBoolean("read_only"))
        assertFalse(result.getBoolean("can_clear_new_entry_gate"))
        assertTrue(result.getBoolean("uncaptured_peak_memory_cannot_be_inferred"))
    }
}

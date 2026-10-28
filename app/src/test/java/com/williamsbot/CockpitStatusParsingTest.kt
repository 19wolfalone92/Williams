package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CockpitStatusParsingTest {
    @Test fun terminalBnbExitIsHistoryInBothOldAndNewRuntimePayloads() {
        val exit = JSONObject().put("symbol", "BNBUSDT").put("direction", "SHORT")
            .put("role", "EXIT").put("order_status", "EXIT_TERMINAL_POSITION_OPEN")
            .put("executed_qty", "0.22")
        val xrp = JSONObject().put("symbol", "XRPUSDT").put("direction", "SHORT")
            .put("role", "ENTRY").put("campaign_status", "OPEN_PROTECTED")
        for (legacy in listOf(true, false)) {
            val runtime = JSONObject().put("pending_intent_details",
                if (legacy) JSONArray().put(exit).put(xrp) else JSONArray().put(xrp))
            if (!legacy) runtime.put("exit_reconciliation_details", JSONArray().put(exit))
            val status = parseStatus(JSONObject().put("futures_runtime", runtime))
            assertEquals(1, status.futuresIntents.size)
            assertEquals("XRPUSDT", status.futuresIntents.single().getString("symbol"))
            assertEquals(1, status.exitReconciliationIntents.size)
            assertEquals("BNBUSDT", status.exitReconciliationIntents.single().getString("symbol"))
        }
    }
    @Test fun scannerProgressAndUniverseComeFromCapturedRuntimeEnvelope() {
        val status = parseStatus(JSONObject().put("scanner_state", "READY_BLOCKED")
            .put("scanner_progress_symbols", 10).put("scanner_progress_total", 10)
            .put("scanner_configured_symbols", 10).put("scanner_symbols", 10)
            .put("scanner_universe", JSONArray(APPROVED_FUTURES_SYMBOLS))
            .put("scanner_error", JSONObject.NULL).put("last_error", JSONObject.NULL)
            .put("snapshot_at_ms", 10000).put("positions_snapshot_at_ms", 9000)
            .put("positions_snapshot_ok", false))
        assertEquals("READY_BLOCKED", status.scannerState)
        assertEquals(10, status.scannerProgressSymbols)
        assertEquals(10, status.scannerProgressTotal)
        assertEquals(APPROVED_FUTURES_SYMBOLS, status.scannerUniverse)
        assertNull(status.scannerError)
        assertNull(status.error)
        assertFalse(status.positionsSnapshotOk)
        assertEquals(9000L, status.positionsSnapshotAtMs)
    }
    @Test fun normalizedPositiveQuantityDoesNotTurnShortPositionIntoLong() {
        val status = parseStatus(JSONObject().put("positions", JSONArray().put(JSONObject()
            .put("symbol", "SOLUSDT").put("qty", 2).put("direction", "SHORT")
            .put("entry", 100).put("mark_price", 90).put("unrealized_pnl_usdt", 20))))
        assertEquals("SHORT", status.positions.single().direction)
        assertEquals(180.0, status.positions.single().positionValueUsdt, 1e-9)
        assertEquals(0.1, status.positions.single().pnlPct, 1e-9)
    }
}


package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CockpitStatusParsingTest {
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


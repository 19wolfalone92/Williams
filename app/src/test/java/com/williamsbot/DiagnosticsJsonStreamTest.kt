package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

class DiagnosticsJsonStreamTest {
    @Test fun roundtripEscapesArraysNullNumbersAndExchangeStopEvidence() {
        val raw="line\nquote\" tab\t slash\\ control\u0001 unicode\u2028"
        val input=JSONObject().put("created_at_ms",1_782_567_891_234L)
            .put("created_at","27.10.2026")
            .put("status",JSONObject().put("execution_enabled",false)
                .put("reason",raw).put("missing",JSONObject.NULL))
            .put("futures_exchange_stop_proofs",JSONArray()
                .put(JSONObject().put("symbol","BTCUSDT")
                    .put("stop_proof","OWNED_STOP_SEEN")
                    .put("stop","75000.25")
                    .put("quantity","0.002")))
            .put("entry_risk_pct",0.01)
        val writer=StringWriter()
        writeDiagnosticsJsonStreaming(input,writer)
        val parsed=JSONObject(writer.toString())
        assertEquals(input.getLong("created_at_ms"),parsed.getLong("created_at_ms"))
        assertEquals(raw,parsed.getJSONObject("status").getString("reason"))
        assertFalse(parsed.getJSONObject("status").getBoolean("execution_enabled"))
        assertTrue(parsed.getJSONObject("status").isNull("missing"))
        assertEquals("75000.25",parsed.getJSONArray("futures_exchange_stop_proofs")
            .getJSONObject(0).getString("stop"))
        assertEquals(0.01,parsed.getDouble("entry_risk_pct"),0.0)
    }

    @Test fun megabyteScalarIsStreamingNotDuplicatedIntoOneWriteOrPreview() {
        // More than the 9437192-byte failed allocation observed on Android.
        // The sink counts output without retaining the 9.5 MB JSON serialization.
        val scalar="A".repeat(9_500_000)
        val json=JSONObject().put("big",scalar)
        var total=0L
        var largestWrite=0
        val counting=object:Writer() {
            private fun count(length:Int) {
                total+=length
                largestWrite=maxOf(largestWrite,length)
            }
            override fun write(cbuf:CharArray,off:Int,len:Int) = count(len)
            override fun write(str:String,off:Int,len:Int) = count(len)
            override fun flush() {}
            override fun close() {}
        }
        writeDiagnosticsJsonStreaming(json,counting)
        assertEquals(9_500_010L,total)
        assertTrue("No contiguous allocation for a huge encoded string",largestWrite<=8193)
        val preview=diagnosticsJsonPreview(json,2000)
        assertTrue(preview.length in 2000..2200)
        assertTrue(preview.contains("Полный JSON"))
        assertEquals(scalar.length,json.getString("big").length)
    }

    @Test fun invalidNumbersAndExcessivelyNestedEvidenceDoNotSilentlyEmitBadJson() {
        try {
            writeDiagnosticsJsonStreaming(JSONObject().put("x",JSONArray().put(Double.NaN)),StringWriter())
            fail("org.json may reject NaN while adding; write test must be reached")
        } catch (_:org.json.JSONException) {
            // org.json itself refuses to accept non-finite numbers.
        } catch (_:IOException) {
            // Streaming safety gate also refuses invalid numbers.
        }
        var deep:Any?="leaf"
        repeat(110) { deep=JSONArray().put(deep) }
        try {
            writeDiagnosticsJsonStreaming(deep,StringWriter())
            fail("Depth >100 must fail closed instead of stack-overflowing")
        } catch (_:IOException) { }
    }
}

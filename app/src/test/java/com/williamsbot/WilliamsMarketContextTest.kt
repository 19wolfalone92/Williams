package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsMarketContextTest {
    private val now=1_792_578_000_000L
    private fun history(sign:Int=1)=williamsObservedMarketFrames.associateWith { frame ->
        val duration=williamsWaveFrames.getValue(frame);val end=now/duration*duration
        (0 until 80).map { i -> WilliamsWaveBar(end-(80-i)*duration,100+sign*i*.1,
            101+sign*i*.1,99+sign*i*.1,100.5+sign*i*.1,100.0+i) }
    }
    private fun frames(raw:JSONObject)=williamsMarketPhaseContext(raw).getJSONArray("frames").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("frame") }
    }
    @Test fun everyTimeframeIsObservedEvenWhenNoElliottCountCanBeProven() {
        for(sign in listOf(1,-1)) {
            val raw=buildWilliamsObservedMarketContext(history(sign),now)
            assertEquals(now,raw.getLong("as_of"))
            val rows=frames(raw)
            assertEquals(williamsObservedMarketFrames.toSet(),rows.keys)
            for((frame,row) in rows) {
                assertEquals("UNRESOLVED",row.getString("state"))
                val reading=row.getJSONObject("market_reading")
                assertEquals("OBSERVED",reading.getString("state"))
                assertEquals(if(sign==1) "UP" else "DOWN",reading.getString("observed_bias"))
                assertEquals(now/williamsWaveFrames.getValue(frame)*williamsWaveFrames.getValue(frame),reading.getLong("as_of_close_ms"))
                assertFalse(reading.getBoolean("bias_is_forecast"))
                assertFalse(reading.getBoolean("can_place_orders"))
                assertFalse(reading.getBoolean("can_close"))
                assertTrue(williamsMarketObservationLabel(row).contains(if(sign==1) "восходящий" else "нисходящий"))
                assertTrue(williamsWaveObservationLabel(row).contains("разметка не найдена"))
            }
            assertFalse(raw.getBoolean("can_place_orders"))
            assertFalse(raw.getBoolean("can_close"))
            assertFalse(raw.getBoolean("can_reverse"))
        }
    }
    @Test fun futureBarsCannotContaminateTheSnapshotOrEvictKnownCandles() {
        val history=history();val expected=buildWilliamsObservedMarketContext(history,now)
        val poisoned=history.mapValues { (frame,bars) -> bars+(0 until 400).map { i ->
            WilliamsWaveBar(now+i*williamsWaveFrames.getValue(frame),1.0,1e12,.01,1.0,1e12) } }
        assertEquals(expected.toString(),buildWilliamsObservedMarketContext(poisoned,now).toString())
    }
    @Test fun rightHandAlligatorTailUsesKnownSmmasAndNeverExtrapolatesMissingLips() {
        val reading=williamsMarketReading(history().getValue("1h"),JSONObject(),"1h",now)
        val tail=reading.getJSONObject("alligator_known_tail");val points=tail.getJSONArray("points")
        assertEquals(3,tail.getInt("common_known_plot_steps"))
        assertEquals(8,points.length())
        for(i in 0 until points.length()) {
            assertFalse(points.getJSONObject(i).isNull("jaw"))
            assertEquals(i>=3,points.getJSONObject(i).isNull("lips"))
            assertEquals(i>=5,points.getJSONObject(i).isNull("teeth"))
        }
        assertFalse(tail.getBoolean("uses_future_prices"))
        assertFalse(tail.getBoolean("extrapolated"))
        assertFalse(tail.getBoolean("price_forecast"))
        assertTrue(tail.getLong("source_through_close_ms")<=now)
    }
    @Test fun missingStaleGappedAndFailedHistoryDoNotMasqueradeAsUnknownWaves() {
        val history=history().toMutableMap()
        history["1d"]=emptyList()
        history["6h"]=history.getValue("6h").filterIndexed { i,_ -> i!=50 }
        history["4h"]=history.getValue("4h").dropLast(2)
        val rows=frames(buildWilliamsObservedMarketContext(history,now,mapOf("15m" to "HISTORY_READ_FAILED:SQLiteException")))
        assertEquals("INSUFFICIENT_CLOSED_HISTORY",rows.getValue("1d").getString("history_error"))
        assertEquals("GAPPED_OR_UNORDERED_HISTORY",rows.getValue("6h").getString("history_error"))
        assertEquals("STALE_CLOSED_HISTORY",rows.getValue("4h").getString("history_error"))
        assertEquals("HISTORY_READ_FAILED",rows.getValue("15m").getJSONObject("market_reading").getString("state"))
        assertEquals("OBSERVED",rows.getValue("1h").getJSONObject("market_reading").getString("state"))
        assertTrue(williamsMarketObservationLabel(rows.getValue("15m")).contains("прочитать историю"))
        for(row in frames(JSONObject()).values) {
            assertEquals("NOT_ANALYSED",row.getString("state"))
            assertTrue(williamsWaveObservationLabel(row).contains("не выполнялся"))
        }
    }
    @Test fun actualXrpDiagnosticReachesTheDecisionCardWithItsRealObservationTime() {
        val fixture=JSONObject(requireNotNull(javaClass.getResource("/williams_market_context_xrp.json")).readText())
        val bars=fixture.getJSONArray("bars").let { a -> (0 until a.length()).map { a.getJSONArray(it) }.map {
            WilliamsWaveBar(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getDouble(5)) } }
        val hierarchy=buildWilliamsObservedMarketContext(mapOf("1h" to bars),fixture.getLong("now"))
        val trace=buildWilliamsDecisionTrace("XRPUSDT",williamsUnifiedProfile,bars,fixture.getLong("now"),JSONObject(),org.json.JSONArray(),hierarchy)
        val phase=trace.getJSONObject("market_phase")
        assertTrue(phase.getLong("as_of")>0L)
        val row=phase.getJSONArray("frames").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("frame")=="1h" } }
        val reading=row.getJSONObject("market_reading")
        assertEquals("SATIATED",reading.getJSONObject("alligator").getString("state"))
        assertEquals(.02362529411764691,reading.getDouble("ao"),1e-12)
        assertEquals(.003266294117646895,reading.getDouble("ac"),1e-12)
        assertEquals("UP",reading.getString("observed_bias"))
        assertEquals("GREEN",reading.getString("zone"))
        assertTrue(williamsMarketObservationLabel(row).contains("восходящий контекст"))
        assertTrue(williamsOscillatorObservationLabel(reading).contains("AO >0, растёт"))
        assertFalse(trace.getJSONObject("decision").getBoolean("can_place_orders"))
    }
}

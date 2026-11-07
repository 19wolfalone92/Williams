package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc1MarketFacilitationTest {
    private val h=3_600_000L
    private val prev=WilliamsWaveBar(h,101.0,110.0,100.0,101.0,100.0)
    private fun next(high:Double,low:Double,volume:Double,time:Long=2*h)=
        WilliamsWaveBar(time,101.0,high,low,101.0,volume)

    @Test fun exactFourTC1WindowsShareThePythonPriceVolumeFixtures() {
        val file=requireNotNull(javaClass.getResource("/williams_tc1_mfi_windows_contract.json")).readText()
        val cases=JSONObject(file).getJSONArray("cases")
        assertEquals(7,cases.length())
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i)
            val report=williamsTc1MarketFacilitation(
                listOf(prev,next(case.getDouble("high"),case.getDouble("low"),
                    case.getDouble("volume"))),"1h",3*h)
            assertEquals(case.getString("id"),case.getString("status"),
                report.getString("status"))
            assertEquals(case.getString("id"),case.getString("window"),
                report.getString("window"))
            assertFalse(report.getBoolean("canonical_tick_volume_verified"))
            assertFalse(report.getBoolean("execution_authority"))
            assertFalse(report.getBoolean("can_place_orders"))
            assertFalse(report.getBoolean("can_move_stop"))
            assertFalse(report.getBoolean("can_close"))
            assertFalse(report.getBoolean("pattern_can_prove_w5_end"))
            if(report.getString("status")=="OBSERVED_BASE_VOLUME_PROXY") {
                assertEquals(3*h,report.getLong("known_at_ms"))
                assertTrue(report.getString("explanation_ru").contains("Binance"))
                assertEquals(.1,report.getDouble("mfi_previous"),1e-12)
            }
        }
    }

    @Test fun zeroVolumeGapsPartialAndStaleBarsNeverProduceSignal() {
        val asOf=3*h
        val invalid=listOf(
            listOf(prev,next(112.0,100.0,0.0)),
            listOf(prev,next(112.0,100.0,120.0,4*h)),
            listOf(prev,next(112.0,100.0,120.0,asOf)),
            listOf(prev,next(112.0,100.0,120.0).copy(h=100.0,l=112.0)),
            listOf(prev,next(Double.NaN,100.0,120.0))
        )
        for(case in invalid) {
            val report=williamsTc1MarketFacilitation(case,"1h",asOf)
            assertEquals("SOURCE_H1_OR_FRAME_UNVERIFIED",report.getString("status"))
            assertFalse(report.getBoolean("can_place_orders"))
        }
        val stale=williamsTc1MarketFacilitation(
            listOf(prev,next(112.0,100.0,120.0)),"1h",5*h)
        assertEquals("SOURCE_H1_OR_FRAME_UNVERIFIED",stale.getString("status"))
        assertEquals("SOURCE_H1_OR_FRAME_UNVERIFIED",
            williamsTc1MarketFacilitation(listOf(prev),"1h",asOf).getString("status"))
    }

    @Test fun marketNarrativeExplainsMfiProxyAndCannotUseSquatToCloseTrades() {
        val now=200*86_400_000L
        val frames=williamsObservedMarketFrames.associateWith { frame ->
            val step=williamsWaveFrames.getValue(frame)
            (0 until 80).map { i ->
                WilliamsWaveBar(now-(80-i)*step,101.0,110.0,100.0,101.0,100.0+i)
            }
        }
        val analysis=buildWilliamsObservedMarketContext(frames,now)
        val phase=williamsMarketPhaseContext(analysis)
        val narration=phase.getJSONObject("market_narrative")
        val windows=narration.getJSONArray("frames")
        assertEquals(5,windows.length())
        for(i in 0 until windows.length()) {
            val row=windows.getJSONObject(i)
            val evidence=row.optJSONObject("tc1_mfi_window")
            assertNotNull(evidence)
            assertEquals("SQUAT",evidence!!.getString("window"))
            assertTrue(row.getString("explanation_ru").contains("Приседающий"))
            assertFalse(row.getBoolean("tc1_mfi_can_place_orders"))
            assertFalse(row.getBoolean("can_close"))
        }
        assertFalse(narration.getBoolean("can_place_orders"))
    }
}

package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * TC1 ch7 PDF53; Profitunity Course Level III p2 (PDF77).
 * Synthetic CLOSED-bar boundary examples, not digitized book charts.
 * NO test here grants order, stop or close authority.
 */
class WilliamsCourseTerminalEvidenceTest {
    private val hour=3_600_000L

    private fun upwardBars():List<WilliamsWaveBar> {
        val bars=(0 until 180).map { i ->
            WilliamsWaveBar(i*hour,129.0,131.0,128.0,129.0,100.0)
        }.toMutableList()
        // Fifth-wave terminal pivot has the greatest high; two earlier
        // fifth-wave highs outrank the squat on the adjacent bar.
        bars[120]=bars[120].copy(o=154.0,h=155.0,l=153.0,c=154.0)
        bars[125]=bars[125].copy(o=152.0,h=153.0,l=151.0,c=152.0)
        bars[129]=bars[129].copy(o=150.9,h=151.0,l=150.9,c=150.9,v=300.0)
        bars[130]=bars[130].copy(o=159.0,h=160.0,l=158.0,c=159.0)
        return bars
    }

    private fun mirror(bars:List<WilliamsWaveBar>):List<WilliamsWaveBar> =
        bars.map { b -> b.copy(o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c) }

    @Test fun adjacentSquatOutsideTopThreeDoesNotCertifyTerminalFifthWave() {
        for(side in listOf("UP","DOWN")) {
            val rows=upwardBars().let { if(side=="UP") it else mirror(it) }
            assertFalse(williamsTerminalSquatVolumeProxy(rows,side,100,130))
            // Make an actual extreme qualifying bar squat against the
            // immediately preceding candle, without using future data.
            val corrected=rows.toMutableList()
            corrected[120]=if(side=="UP")
                corrected[120].copy(o=154.8,h=155.0,l=154.6,c=154.8,v=300.0)
                else corrected[120].copy(o=145.2,h=145.4,l=145.0,c=145.2,v=300.0)
            assertTrue(williamsTerminalSquatVolumeProxy(corrected,side,100,130))
            assertFalse(williamsTerminalSquatVolumeProxy(corrected,side,100,119))
            assertFalse(williamsTerminalSquatVolumeProxy(corrected,side,130,130))
        }
    }

    @Test fun missingInvalidOrZeroVolumeCannotInventTheSquat() {
        val rows=upwardBars().toMutableList()
        rows[120]=rows[120].copy(o=154.8,l=154.6,c=154.8,v=300.0)
        assertTrue(williamsTerminalSquatVolumeProxy(rows,"UP",100,130))
        rows[120]=rows[120].copy(v=Double.NaN)
        assertFalse(williamsTerminalSquatVolumeProxy(rows,"UP",100,130))
        assertFalse(williamsTerminalSquatVolumeProxy(rows,"BAD",100,130))
    }

    @Test fun fourSignsRemainUncertifiedAndNeverAuthorizeTradeOrExit() {
        for(side in listOf("UP","DOWN")) {
            val evidence=JSONObject().put("label","W5").put("direction",side)
                .put("scale_bars",120).put("target_zone",true).put("divergence",true)
                .put("terminal_fractal",true).put("squat",true)
                .put("frame","1h").put("status","PROVISIONAL").put("observed_at",160*hour)
                .put("anchors",JSONArray((0..5).map { i -> JSONObject()
                    .put("kind",if((i%2==0)==(side=="UP")) "LOW" else "HIGH")
                    .put("price",if(side=="UP") listOf(100.0,110.0,105.0,130.0,120.0,140.0)[i]
                        else 300-listOf(100.0,110.0,105.0,130.0,120.0,140.0)[i])
                    .put("extreme_at",(100+i*10)*hour).put("known_at",(103+i*10)*hour) }))
            val all=williamsCourseFourSignEvidence(evidence)
            assertEquals("FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED",all.getString("status"))
            assertEquals(4,all.getInt("observed_count"))
            assertTrue(all.getBoolean("scale_100_140_observed"))
            for(field in listOf("can_place_orders","can_close","can_reverse","wave_degree_confirmed"))
                assertFalse(all.getBoolean(field))
            assertEquals("COURSE_TERMINAL_CRITERIA_INCOMPLETE",
                williamsCourseFourSignEvidence(evidence.put("squat",false)).getString("status"))
            assertEquals("W3_W5_DIVERGENCE_SCALE_UNVERIFIED",
                williamsCourseFourSignEvidence(evidence.put("squat",true).put("scale_bars",141)).getString("status"))
            assertEquals("NO_COMPLETED_W5_PIVOT_HYPOTHESIS",
                williamsCourseFourSignEvidence(evidence.put("scale_bars",120).put("label","W4")).getString("status"))
        }
    }

    @Test fun fourSignLedgerIsExposedInsideEachMarketPhaseHypothesis() {
        val base=1_000_000L
        val anchors=JSONArray().put(JSONObject().put("known_at",base))
            .put(JSONObject().put("known_at",base+hour))
        val scenario=JSONObject().put("scenario_id","1h:synthetic:5")
            .put("frame","1h").put("status","PROVISIONAL")
            .put("observed_at",base+2*hour).put("anchors",anchors)
            .put("label","W5").put("active_label","A")
            .put("active_direction","DOWN").put("direction","UP")
            .put("scale_bars",120).put("divergence",true)
            .put("target_zone",true).put("terminal_fractal",true).put("squat",false)
        val hierarchy=JSONObject().put("as_of",base+3*hour)
            .put("scenarios",JSONArray().put(scenario))
            .put("frame_errors",JSONObject()).put("frame_candle_counts",JSONObject().put("1h",180))
        val phase=williamsMarketPhaseContext(hierarchy)
        val frames=phase.getJSONArray("frames")
        val row=(0 until frames.length()).map { frames.getJSONObject(it) }.single { it.getString("frame")=="1h" }
        val hypotheses=row.getJSONArray("hypotheses")
        assertEquals(1,hypotheses.length())
        val checklist=hypotheses.getJSONObject(0).getJSONObject("course_four_signs")
        assertEquals(3,checklist.getInt("observed_count"))
        assertFalse(checklist.getBoolean("can_close"))
    }
}

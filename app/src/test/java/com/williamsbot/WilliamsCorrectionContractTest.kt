package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsCorrectionContractTest {
    @Test fun authenticFourDegreeRequirementRejectsUnprovenH4CorrectionBothSides() {
        val fixture=JSONObject(requireNotNull(javaClass.getResource("/williams_correction_contract.json")).readText())
        val raw=fixture.getJSONObject("frames")
        val total=fixture.getDouble("mirror_sum")
        for (mirror in listOf(false,true)) {
            val side=if (mirror) "SHORT" else "LONG"
            val frames=(williamsRequiredWaveFrames + "6h").distinct().associateWith { frame ->
                val rows=raw.getJSONArray(frame)
                (0 until rows.length()).map { i ->
                    val r=rows.getJSONArray(i)
                    val b=WilliamsWaveBar(r.getLong(0),r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5))
                    if (mirror) b.copy(o=total-b.o,h=total-b.l,l=total-b.h,c=total-b.c) else b
                }
            }
            val now=fixture.getLong("as_of")
            val report=buildWilliamsWaveHierarchy(frames,now)
            assertTrue(report.frameErrors.isEmpty())
            // The previously approved H6 -> H1 -> M15 example has no
            // independently confirmed H4 swing sequence: H4 is now real
            // closed four-hour OHLCV (aggregated from the same H1 source),
            // but no fabricated W4 is allowed to complete its ancestry.
            assertEquals(60,frames.getValue("4h").size)
            assertFalse(report.toJson().getBoolean("contained_chain_observed"))
            val h1=frames.getValue("1h")
            val teeth=shiftAlligatorForDisplay(smmaSeededValues(h1.map { (it.h+it.l)/2 },8),5)
                .map { it?:Double.NaN }
            // Production WM3 geometry and indicator math: no invented scenarios,
            // links or replacement signal detector in this complete-chain test.
            val signal=requireNotNull(selectWilliamsPendingFractal(side,h1,teeth,.1))
            val signalAt=h1[signal.confirmation].t
            assertEquals(fixture.getLong("signal_at"),signalAt)
            val expectedTrigger=if (mirror) total-fixture.getDouble("long_trigger") else fixture.getDouble("long_trigger")
            assertEquals(expectedTrigger,signal.trigger,1e-9)
            val type=if (mirror) "SHORT_FRACTAL" else "FRACTAL"
            val proof=williamsCorrectionEntryContext(report,side,signalAt,type)
            // The H1 Wise Man remains a valid detected event; however a
            // proposed correction entry must be DEFERRED in both directions
            // if the newly required H4 ancestry cannot be verified.
            assertFalse(proof.toJson().toString(),proof.allowed)
            assertEquals("CORRECTION_REQUIRED_FOUR_DEGREE_CHAIN_UNPROVEN",proof.reason)
            assertFalse(williamsCorrectionPriceAllowed(proof.toJson(),expectedTrigger))
            assertFalse(report.scenarios.any { it.frame=="6h" && it.label=="W4" &&
                it.status=="PROVISIONAL" && it.anchors.size < 5 })
            for (required in williamsRequiredWaveFrames) {
                val missing=buildWilliamsWaveHierarchy(frames.filterKeys { it!=required },now)
                assertFalse("required="+required,
                    williamsCorrectionEntryContext(missing,side,signalAt,type).allowed)
            }
            val future=frames.mapValues { (frame,bars) ->
                bars+bars.last().copy(t=bars.last().t+williamsWaveFrames.getValue(frame),h=Double.NaN)
            }
            assertEquals(report.toJson().toString(),buildWilliamsWaveHierarchy(future,now).toJson().toString())
        }
    }
}

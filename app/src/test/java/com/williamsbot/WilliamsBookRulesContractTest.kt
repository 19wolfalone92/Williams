package com.williamsbot

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic boundary cases shared with Python, each linked to a book page.
 * Passing them is evidence for these rules, never a whole-book certificate. */
class WilliamsBookRulesContractTest {
    private val hour=3_600_000L
    private fun corpus()=JSONObject(requireNotNull(javaClass.getResource("/williams_book_rules_contract.json")).readText())
    private fun cases(name:String):List<JSONObject> {
        val rows=corpus().getJSONArray(name)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    @Test fun exactTranchesUseConfirmedQuantitiesAndRejectPartialAdds() {
        for(c in cases("pyramid")) for(side in listOf("LONG","SHORT")) {
            val owner=JSONObject().put("client_id","root").put("symbol","BTCUSDT").put("direction",side)
                .put("actual_entry_executed_qty",c.getString("unit")).put("confirmed_position_qty",c.getString("owned"))
                .put("setup_context",JSONObject().put("tc2_reverse_pyramid",JSONObject()
                    .put("policy",WILLIAMS_TC2_PYRAMID_POLICY)).toString())
            val fills=c.getJSONArray("fills");val intents=JSONArray().put(owner)
            for(i in 0 until fills.length()) intents.put(JSONObject().put("intent_role","ADD_ON")
                .put("entry_client_id","root").put("client_id","add-$i").put("symbol","BTCUSDT").put("direction",side)
                .put("order_status","FILLED").put("executed_qty",fills.getString(i)).put("quantity",fills.getString(i)))
            // A cancelled zero-fill attempt consumes no book quantity slot.
            intents.put(JSONObject().put("intent_role","ADD_ON").put("entry_client_id","root")
                .put("client_id","cancelled").put("order_status","CANCELED").put("executed_qty","0"))
            val result=williamsTc2NextTranche(owner,intents)
            if(c.has("blocker")) assertEquals(c.getString("id"),c.getString("blocker"),result.blocker)
            else {
                assertNull(result.blocker)
                assertEquals(0,BigDecimal(c.getString("next")).compareTo(result.quantity))
                assertNotNull(williamsTc2TrancheQuantityBlocker(result.quantity!!,"0.0001"))
            }
        }
    }
    @Test fun profitAndRiskCapsAreIndependentFromQuantityRatios() {
        assertNull(williamsTc2PyramidPolicy(JSONObject().put("setup_context","")))
        assertNotNull(williamsTc2PyramidPolicy(JSONObject().put("setup_context","malformed")))
        assertNotNull(williamsTc2PyramidPolicy(JSONObject().put("setup_context","")
            .put("tc2_quantity_policy",WILLIAMS_TC2_PYRAMID_POLICY)))
        assertEquals(.0004,williamsTc2InitialUnitRisk(.002,.006,.0025),1e-12)
        assertEquals(.0002,williamsTc2InitialUnitRisk(.002,.006,.001),1e-12)
        for(side in listOf("LONG","SHORT")) {
            val sign=if(side=="LONG") 1 else -1
            assertNull(williamsTc2ProfitBlocker(side,100.0,100.0+sign,100.0+2*sign))
            for(mark in listOf(100.0,100.0-sign)) assertEquals("TC2_ADDITION_REQUIRES_PROFITABLE_POSITION",
                williamsTc2ProfitBlocker(side,100.0,mark,100.0+2*sign))
            assertNotNull(williamsTc2ProfitBlocker(side,Double.NaN,101.0,102.0))
        }
    }
    @Test fun courseW3ExtensionAndW5QuantitiesNeedActualLengthRatio() {
        for(c in cases("course")) {
            val expected=if(c.isNull("target")) null else c.getInt("target")
            val ratio=if(c.isNull("ratio")) null else c.getDouble("ratio")
            assertEquals(c.toString(),expected,williamsCourseTarget(c.getString("stage"),ratio))
        }
    }
    @Test fun thumbWeakAndIdealUseImmediatePreviousBarWithoutInventingColours() {
        for(c in cases("thumb")) for(mirror in listOf(false,true)) {
            val highs=c.getJSONArray("highs");val lows=c.getJSONArray("lows");val volumes=c.getJSONArray("volumes")
            val bars=(0 until highs.length()).map { i ->
                val h=highs.getDouble(i);val l=lows.getDouble(i);val v=volumes.getDouble(i)
                if(!mirror) WilliamsWaveBar((i+1)*hour,(h+l)/2,h,l,(h+l)/2,v)
                else WilliamsWaveBar((i+1)*hour,240-(h+l)/2,240-l,240-h,240-(h+l)/2,v)
            }
            val setup=requireNotNull(williamsCourseThumbSetup(if(mirror) "LONG" else "SHORT",bars,.1))
            assertEquals(c.getString("strength"),setup.context.getString("thumb_strength"))
            assertEquals(c.getJSONArray("colours").toString(),setup.context.getJSONArray("finger_profitunity_colours").toString())
            assertEquals("COURSE_PDF69_VOLUME_SHORTCUT",setup.context.getString("thumb_admission_basis"))
        }
    }
    @Test fun entryAndProtectionAgreeForFiveSixSevenAndNineBarFractals() {
        for(c in cases("fractals")) for(mirror in listOf(false,true)) {
            val values=c.getJSONArray("extrema")
            val bars=(0 until values.length()).map { i ->
                val x=values.getDouble(i)
                if(!mirror) WilliamsWaveBar((i+1)*hour,x+1,120.0,x,x+1,1000.0)
                else WilliamsWaveBar((i+1)*hour,239-x,240-x,120.0,239-x,1000.0)
            }
            val side=if(mirror) "SHORT" else "LONG"
            fun trail(series:List<WilliamsWaveBar>,opened:Long=hour)=planFuturesStopTrail(side,
                if(mirror) 150.0 else 90.0,120.0,Double.NaN,if(mirror) 115.0 else 125.0,.1,opened,
                series.map { FuturesTrailBar(it.t,it.h,it.l) })
            val proposal=trail(bars)
            assertNotNull(c.getString("id"),proposal)
            assertEquals(if(mirror) 132.1 else 107.9,proposal!!.stop,1e-9)
            assertEquals((c.getInt("center")+1)*hour,proposal.fractalAtMs)
            assertTrue(confirmedWilliamsEditionFractals(bars).any {
                it.center==c.getInt("center") && it.kind==if(mirror) "HIGH" else "LOW"
            })
            assertNull(trail(bars.dropLast(1)))
            assertNull(trail(bars,4*hour))
        }
    }
    @Test fun ntdScannersAgreeOnLocalBaseInsteadOfOldGlobalExtremum() {
        for(c in cases("balance_line")) for(mirror in listOf(false,true)) {
            val highs=c.getJSONArray("highs")
            val bars=(0 until highs.length()).map { i ->
                val h=highs.getDouble(i);val l=h-2
                if(!mirror) WilliamsWaveBar((i+1)*hour,h-1,h,l,h-1,1000.0)
                else WilliamsWaveBar((i+1)*hour,241-h,242-h,240-h,241-h,1000.0)
            }
            val side=if(mirror) "SHORT" else "LONG"
            val jaw=List(bars.size) { if(mirror) 150.0 else 90.0 }
            val teeth=List(bars.size) { if(mirror) 151.0 else 89.0 }
            val lips=List(bars.size) { if(mirror) 149.0 else 91.0 };val zeros=List(bars.size) { 0.0 }
            val diagnostic=williamsNtdBalanceLine(bars.indices.map { i ->
                WilliamsNtdBar(bars[i].h,bars[i].l,jaw[i],teeth[i],lips[i],0.0,0.0)
            },side,.1)
            val edition=williamsNtdBalanceLineSetup(side,bars,jaw,teeth,lips,zeros,zeros,.1)
            assertEquals("CANDIDATE",diagnostic.status);assertNotNull(edition)
            assertEquals(c.getInt("base"),diagnostic.baseIndex)
            assertEquals(diagnostic.baseIndex,edition!!.context.getInt("base_index"))
            assertEquals(c.getInt("reference"),diagnostic.referenceIndex)
            assertEquals(diagnostic.referenceIndex,edition.context.getInt("signal_index"))
            assertEquals(if(mirror) 240-c.getDouble("trigger") else c.getDouble("trigger"),diagnostic.triggerPrice,1e-9)
            assertEquals(diagnostic.triggerPrice,edition.trigger,1e-9)
        }
    }
}

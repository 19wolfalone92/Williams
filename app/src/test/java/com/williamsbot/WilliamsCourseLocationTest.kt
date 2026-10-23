package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseLocationTest {
    private val hour=3_600_000L
    private fun jsonValue(value:Any?):Any?=when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
    @Test fun sharedPriceLocationsUseTheCorrectLegOriginAndClosedPivots() {
        val fixture=JSONObject(javaClass.classLoader!!.getResource("williams_course_location_contract.json")!!.readText())
        val cases=fixture.getJSONArray("cases")
        for(side in listOf("LONG","SHORT")) for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val stage=c.getString("stage");val fault=c.optString("fault")
            val prices=(0 until 6).map { fixture.getJSONArray("base_prices").getDouble(it) }.toMutableList()
            prices[2]=c.optDouble("p2",prices[2]);prices[4]=c.optDouble("p4",prices[4])
            val n=if(fault=="WRONG_DEGREE") 6 else mapOf("W1" to 2,"W2" to 2,"W3" to 3,"W3_EXTENSION" to 3,"W4" to 4,"W5" to 5,"EXIT" to 6).getValue(stage)
            fun mirror(p:Double)=if(side=="LONG") p else 400-p
            val anchors=JSONArray(prices.take(n).mapIndexed { j,p -> JSONObject()
                .put("kind",if((j%2==0)==(side=="LONG")) "LOW" else "HIGH").put("price",mirror(p))
                .put("extreme_at",(100+j*10)*hour).put("known_at",(103+j*10)*hour) })
            if(fault=="FUTURE") anchors.getJSONObject(n-1).put("known_at",161*hour)
            if(fault=="UNORDERED") anchors.getJSONObject(n-1).put("extreme_at",100*hour)
            val direction=if((side=="LONG")!=(fault=="WRONG_SIDE")) "UP" else "DOWN"
            val scenario=JSONObject().put("frame","1h").put("status","PROVISIONAL").put("direction",direction)
                .put("observed_at",160*hour).put("anchors",anchors).put("scenario_id","source-parent")
            val result=williamsCoursePriceLocation(stage,side,scenario,mirror(c.getDouble("price")),160*hour)
            if(c.isNull("inside")) assertEquals(c.getString("id"),"UNVERIFIED_PRICE_LOCATION",result.getString("status"))
            else assertEquals(c.getString("id")+side,c.getBoolean("inside"),result.getBoolean("price_in_zone"))
            if(c.has("reason")) assertEquals(c.getString("reason"),result.getString("reason"))
            for(key in listOf("can_place_orders","can_close","can_reverse","wave_degree_confirmed")) assertFalse(result.getBoolean(key))
        }
    }

    private data class Fixture(val report:JSONObject,val bars:List<WilliamsWaveBar>,val now:Long)
    private fun trimFixture(previous:String,side:String):Fixture {
        val long=side=="LONG";fun mirror(p:Double)=if(long) p else 400-p
        val end=if(previous=="W1") 110 else 130;val start=if(previous=="W1") 100 else 120
        fun anchor(p:Double,t:Int,i:Int,duration:Long)=JSONObject().put("kind",if((i%2==0)==long) "LOW" else "HIGH")
            .put("price",mirror(p)).put("extreme_at",t*hour).put("known_at",t*hour+3*duration)
        val parent=JSONObject().put("scenario_id","parent").put("frame","1h").put("status","PROVISIONAL")
            .put("direction",if(long) "UP" else "DOWN").put("label",if(previous=="W1") "W1" else "W3")
            .put("active_label",if(previous=="W1") "W2" else "W4").put("observed_at",(end+3)*hour)
            .put("anchors",JSONArray((if(previous=="W1") listOf(100.0,120.0) else listOf(100.0,120.0,108.0,148.0))
                .mapIndexed { i,p -> anchor(p,100+i*10,i,hour) }))
        val child=JSONObject().put("scenario_id","child").put("frame","5m").put("status","PROVISIONAL")
            .put("direction",if(long) "UP" else "DOWN").put("label","W5").put("observed_at",(end+3)*hour)
            .put("scale_bars",120).put("divergence",true).put("squat",true).put("target_zone",true).put("terminal_fractal",true)
            .put("anchors",JSONArray((if(previous=="W1") listOf(100.0,108.0,104.0,116.0,110.0,120.0) else listOf(108.0,126.0,118.0,144.0,132.0,148.0))
                .mapIndexed { i,p -> anchor(p,start+i*2,i,300_000L) }))
        val rows=(0 until end+3).map { i ->
            val price=mirror(when { i<=100 ->100.0;i<=110 ->100+2.0*(i-100);i<=120 ->120-1.2*(i-110);else ->108+4.0*(minOf(i,130)-120) })
            WilliamsWaveBar(i*hour,price,price+.1,price-.1,price,1000.0)
        }
        return Fixture(JSONObject().put("as_of",(end+3)*hour).put("frame_errors",JSONObject()).put("scenarios",JSONArray(listOf(parent,child))),rows,(end+3)*hour+1000)
    }

    @Test fun matchedTerminalReductionsNeedTheOwnedOriginAndARealFinerDegree() {
        for(side in listOf("LONG","SHORT")) for(previous in listOf("W1","W3","W3_EXTENSION")) {
            val f=trimFixture(previous,side)
            val proof=williamsCourseTrimProof(f.report,side,100*hour,previous,101*hour,f.now,f.bars)
            assertEquals(proof.toString(),"OBSERVED_SOURCE_PINNED_REDUCTION",proof.getString("status"))
            assertEquals(if(previous=="W1") 1 else 3,proof.getInt("target_units"))
            assertFalse(proof.getBoolean("can_place_orders"));assertFalse(proof.getBoolean("wave_degree_confirmed"))
        }
    }

    @Test fun waveLabelsCannotBypassDegreeTimeAndMomentumGuards() {
        val faults=listOf("LABEL_ONLY","FUTURE","EARLY_CONFIRMATION","PREENTRY","ORIGIN","SCALE","SQUAT","AMBIGUOUS","PARENT_AMBIGUOUS","CHILD_FRAME_ERROR","H1_ERROR","GAP","WEAK_AO")
        for(side in listOf("LONG","SHORT")) for(fault in faults) {
            val f=trimFixture("W3_EXTENSION",side);val children=f.report.getJSONArray("scenarios")
            val child=children.getJSONObject(1);var opened=101*hour;var origin=100*hour;var bars=f.bars
            when(fault) {
                "LABEL_ONLY"->children.remove(1)
                "FUTURE"->child.getJSONArray("anchors").getJSONObject(5).put("known_at",f.now+1)
                "EARLY_CONFIRMATION"->{ val p=child.getJSONArray("anchors").getJSONObject(5);p.put("known_at",p.getLong("extreme_at")+600_000) }
                "PREENTRY"->opened=f.report.getLong("as_of")
                "ORIGIN"->origin-=hour
                "SCALE"->child.put("scale_bars",141)
                "SQUAT"->child.put("squat",false)
                "AMBIGUOUS"->children.put(JSONObject(child.toString()))
                "PARENT_AMBIGUOUS"->children.put(JSONObject(children.getJSONObject(0).toString()))
                "CHILD_FRAME_ERROR"->f.report.getJSONObject("frame_errors").put("5m","MISSING")
                "H1_ERROR"->f.report.getJSONObject("frame_errors").put("1h","MISSING")
                "GAP"->bars=bars.filterIndexed { i,_ ->i!=80 }
                "WEAK_AO"->bars=bars.map { it.copy(o=100.0,h=100.1,l=99.9,c=100.0) }
            }
            val result=williamsCourseTrimProof(f.report,side,origin,"W3_EXTENSION",opened,f.now,bars)
            assertEquals(fault+side+result,"BLOCKED",result.getString("status"))
        }
    }

    @Test fun realClosedH1PrefixesCannotBorrowFuturePricesOrCertifyCourse() {
        for(symbol in listOf("BTCUSDT","ETHUSDT","SOLUSDT","BNBUSDT","XRPUSDT")) {
            val data=JSONObject(javaClass.classLoader!!.getResource("blind_wave_20241109_29_$symbol.json")!!.readText()).getJSONArray("rows")
            val bars=(0 until data.length()).map { i -> val r=data.getJSONArray(i)
                WilliamsWaveBar(r.getLong(0),r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)) }
            var count=0
            for(n in listOf(120,180,240,300,360,420,480)) {
                val asOf=bars[n-1].t+hour
                val prefix=buildWilliamsWaveHierarchy(mapOf("1h" to bars.take(n)),asOf).toJson()
                val future=bars.mapIndexed { i,b -> if(i<n) b else b.copy(h=Double.NaN,c= -1.0) }
                val replay=buildWilliamsWaveHierarchy(mapOf("1h" to future),asOf).toJson()
                assertEquals(jsonValue(prefix),jsonValue(replay))
                val scenarios=replay.getJSONArray("scenarios")
                for(i in 0 until scenarios.length()) {
                    val s=scenarios.getJSONObject(i)
                    if(s.optString("status")!="PROVISIONAL") continue
                    assertFalse(williamsCourseFourSignEvidence(s).getBoolean("can_close"))
                    val locations=williamsCourseStageLocations(s,asOf)
                    for(j in 0 until locations.length()) {
                        val location=locations.getJSONObject(j)
                        assertFalse(location.getBoolean("can_place_orders"))
                        if(location.has("evidence_known_at")) assertTrue(location.getLong("evidence_known_at")<=asOf)
                        count++
                    }
                }
            }
            assertTrue(symbol,count>0)
        }
    }
}

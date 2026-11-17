package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsAngulationDepartureTest {
    @Test fun sharedDepartureCasesPreservePriceEdgesAndRefuseGapOnlyAngles() {
        val data=javaClass.classLoader!!.getResourceAsStream("tc2_wm1_departure_path_contract.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val cases=data.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i)
            val raw=case.getJSONArray("bars")
            val bars=(0 until raw.length()).map { j -> raw.getJSONArray(j).let {
                WilliamsWaveBar(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getDouble(5)) } }
            fun line(key:String)=case.getJSONArray(key).let { a -> (0 until a.length()).map { a.getDouble(it) } }
            val proof=williamsAngulation(case.getString("side"),bars,line("jaw"),line("teeth"),line("lips"),3_600_000L)
            assertEquals(case.getString("id"),case.getBoolean("valid"),proof.valid)
            val json=proof.toJson()
            assertEquals(if(case.getString("side")=="LONG") "HIGH" else "LOW",json.getString("price_edge"))
            assertEquals(case.getLong("anchor_time_ms"),json.getLong("anchor_time_ms"))
            assertEquals(bars.last().t,json.getLong("source_end_bar_open_ms"))
            assertTrue(json.getDouble("numeric_price_resolution")>0)
        }
    }

    @Test fun reflectedShortEvidenceUsesActualExchangeSlopeSignsAndLowerEdges() {
        val facts=JSONObject().put("price_edge","HIGH").put("price_slope",-3.0)
            .put("jaw_slope",-1.0).put("teeth_slope",-0.5)
            .put("departure_price_slope",-2.0).put("departure_jaw_slope",-1.0)
            .put("departure_teeth_slope",-0.5).put("departure_relative_slope",1.0)
        val source=JSONObject().put("angulation",facts)
        val short=JSONObject(reflectedTc2SetupContext("REVERSAL",source.toString(),250.0))
            .getJSONObject("angulation")
        assertEquals("LOW",short.getString("price_edge"))
        assertEquals(2.0,short.getDouble("departure_price_slope"),0.0)
        assertEquals(1.0,short.getDouble("departure_jaw_slope"),0.0)
        assertEquals(0.5,short.getDouble("departure_teeth_slope"),0.0)
        assertEquals(1.0,short.getDouble("departure_relative_slope"),0.0)
        assertEquals(-2.0,facts.getDouble("departure_price_slope"),0.0)
    }

    @Test fun exactDecimalParallelPathsStayInvalidAcrossScaleTranslationAndDirection() {
        val h=3_600_000L
        for(short in listOf(false,true)) for(scale in listOf("0.00001","1","10000"))
            for(shift in listOf("0","100000")) for(n in listOf(4,7,12))
                for(step in listOf("0.001","0.01","0.1")) {
            val s=step.toBigDecimal();val gap="0.1".toBigDecimal()
            val base="10".toBigDecimal()+s*n.toBigDecimal()
            val sign=if(short) 1.toBigDecimal() else (-1).toBigDecimal()
            val lines=(0 until n).map { base+sign*it.toBigDecimal()*s }
            val edges=lines.mapIndexed { i,v -> if(i==0) base else v+sign*gap }
            fun value(v:java.math.BigDecimal)=(v*scale.toBigDecimal()+shift.toBigDecimal()).toDouble()
            val bars=edges.mapIndexed { i,e ->
                val values=if(i==0) listOf(e,e+gap/10.toBigDecimal(),e-gap/10.toBigDecimal(),e)
                else if(short) listOf(e+gap/50.toBigDecimal(),e+gap/25.toBigDecimal(),e,e+gap/100.toBigDecimal())
                else listOf(e-gap/50.toBigDecimal(),e,e-gap/25.toBigDecimal(),e-gap/100.toBigDecimal())
                WilliamsWaveBar((i+1)*h,value(values[0]),value(values[1]),value(values[2]),value(values[3]),10.0)
            }
            val line=lines.map(::value)
            val proof=williamsAngulation(if(short) "SHORT" else "LONG",bars,line,line,line,h)
            assertFalse("$short $scale $shift $n $step ${proof.toJson()}",proof.valid)
        }
    }
}

package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** TC2_EN245-247 numeric semantics; constructed Decimal vectors, not book OHLC. */
class WilliamsIndicatorAtomicContractTest {
    private fun data(): JSONObject = javaClass.classLoader!!
        .getResourceAsStream("tc2_indicator_atomic_contract.json")!!.bufferedReader()
        .use { JSONObject(it.readText()) }

    private fun number(point: JSONObject, name: String, actual: Double?, context: String) {
        if (point.isNull(name)) assertTrue(context,actual==null || !actual.isFinite())
        else {
            assertNotNull(context,actual)
            val expected=point.getString(name).toDouble()
            assertEquals(context,expected,actual!!,maxOf(2e-10,kotlin.math.abs(expected)*2e-12))
        }
    }

    @Test fun realIndicatorFunctionsMatchSharedIndependentDecimalVectors() {
        val cases=data().getJSONArray("numeric_cases")
        for(c in 0 until cases.length()) {
            val case=cases.getJSONObject(c);val input=case.getJSONArray("median_prices")
            val prices=List(input.length()) { input.getString(it).toDouble() }
            val lines=listOf(Triple("jaw",13,8),Triple("teeth",8,5),Triple("lips",5,3))
                .associate { (name,period,shift) ->
                    val raw=smmaSeededValues(prices,period)
                    name to Pair(raw,shiftAlligatorForDisplay(raw,shift))
                }
            val points=case.getJSONArray("checkpoints")
            for(p in 0 until points.length()) {
                val point=points.getJSONObject(p);val i=point.getInt("index")
                val context=case.getString("id")+":"+i
                for((name,values) in lines) {
                    number(point,name,values.first[i],context+":"+name)
                    number(point,name+"_display",values.second[i],context+":"+name+"_display")
                }
                val now=awesomeOscillatorAt(prices,i)
                number(point,"ao",now,context+":ao")
                val before=awesomeOscillatorAt(prices,i-1)
                val colour=if(!now.isFinite() || !before.isFinite()) "UNKNOWN"
                    else if(now>before) "GREEN" else if(now<before) "RED" else "FLAT"
                assertEquals(context,point.getString("ao_colour"),colour)
            }
            for((name,period,shift) in listOf(Triple("jaw",13,8),Triple("teeth",8,5),Triple("lips",5,3))) {
                for(end in listOf(21,37,61).filter { it<prices.size }) {
                    assertEquals(case.getString("id")+":"+name,
                        lines.getValue(name).second.take(end),
                        shiftAlligatorForDisplay(smmaSeededValues(prices.take(end),period),shift))
                }
            }
        }
    }

    @Test fun realAoTraceFindsOriginalThirdChangeInBothDirectionsWithoutCachedCounts() {
        val cases=data().getJSONArray("ordinal_cases")
        for(c in 0 until cases.length()) {
            val case=cases.getJSONObject(c);val input=case.getJSONArray("ao_values")
            val values=List(input.length()) { input.getString(it).toDouble() }
            val short=case.getString("side")=="SHORT"
            val count=williamsTc2ConsecutiveAoRise(values.lastIndex) { if(short) -values[it] else values[it] }
            assertEquals(case.getString("id"),case.getInt("expected_directional_run"),count)
            if(case.isNull("expected_third_index")) assertTrue(count<3)
            else assertEquals(case.getString("id"),case.getInt("expected_third_index"),values.lastIndex-(count-3))
        }
    }

    @Test fun finiteInputOverflowNeverPublishesInfiniteAo() {
        // Finite inputs can overflow a34-price accumulation. UNKNOWN must not
        // become a directional AO value or a nonfinite JSON diagnostic.
        assertTrue(awesomeOscillatorAt(List(34) { 1e307 },33).isNaN())
        assertTrue(awesomeOscillatorAt(List(34) { -1e307 },33).isNaN())
    }
}

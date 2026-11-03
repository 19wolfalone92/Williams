package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Test-only independent source observations, no live trading permissions.
 * NTD figures 5-4 through 5-23 and 6-3 through 6-18 (PDF57–82).
 */
class WilliamsNtdSourceColourProofTest {
    @Test fun nativeNtdSourceScannerDoesNotDivergeFromReadOnlyAoAcBookProof() {
        val h=3_600_000L
        val n=48
        val bars=(1..n).map { i ->
            WilliamsWaveBar(i*h,100.0,105.0,95.0,100.0,100.0)
        }
        val jaw=List(n){100.0}
        val teeth=List(n){100.0}
        val lips=List(n){100.0}
        for(side in listOf("LONG","SHORT")) {
            val factor=if(side=="LONG") 1.0 else -1.0
            val ao=MutableList(n){0.0}
            val ac=MutableList(n){0.0}
            val tailAo=listOf(0.7,0.6,0.4,0.5)
            val tailAc=listOf(0.8,0.5,0.6,0.7)
            for(k in 0 until 4) {
                ao[n-4+k]=factor*tailAo[k]
                ac[n-4+k]=factor*tailAc[k]
            }
            assertEquals("AO_SAUCER",williamsNtdLastClosedAoFormation(ao,side))
            assertEquals("AC_SIGNAL",williamsNtdLastClosedAcFormation(ac,side))
            val actual=williamsNtdSetups(side,bars,jaw,teeth,lips,
                ao,ac,0.1,exitMode=true,includePendingAo=false)
            val aoEvents=actual.filter { it.kind=="AO_SAUCER" }
            val acEvents=actual.filter { it.kind=="AC_SIGNAL" }
            assertEquals(side+": "+actual.toString(),1,aoEvents.size)
            assertEquals(side+": "+actual.toString(),1,acEvents.size)
            assertEquals(n-1,aoEvents.single().knownIndex)
            assertEquals(n-1,acEvents.single().knownIndex)
            assertEquals(if(side=="LONG") 105.1 else 94.9,aoEvents.single().trigger,0.0)
        }
    }

    private fun contract()=JSONObject(requireNotNull(
        javaClass.getResource("/williams_ntd_source_colour_contract.json")).readText())

    @Test fun originalAoSourceColoursMatchPythonWithMirroredBookSetups() {
        val corpus=contract()
        assertEquals("NTD_AO_AC_SOURCE_COLOUR_CONTRACT_V1",corpus.getString("schema"))
        val cases=corpus.getJSONArray("ao")
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i)
            val raw=row.getJSONArray("values")
            val values=(0 until raw.length()).map {raw.getString(it).toDouble()}
            val result=williamsNtdLastClosedAoFormation(values,row.getString("side"))
            if(row.isNull("expected")) assertNull(row.toString(),result)
            else assertEquals(row.toString(),row.getString("expected"),result)
        }
    }

    @Test fun acAboveBelowAndSecondThirdZeroCrossMatchPython() {
        val cases=contract().getJSONArray("ac")
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i)
            val raw=row.getJSONArray("values")
            val values=(0 until raw.length()).map {raw.getString(it).toDouble()}
            val result=williamsNtdLastClosedAcFormation(values,row.getString("side"))
            if(row.isNull("expected")) assertNull(row.toString(),result)
            else assertEquals(row.toString(),row.getString("expected"),result)
        }
    }

    @Test fun noUnknownOrNonfiniteSourceCanProveAnAoAcFormation() {
        assertNull(williamsNtdLastClosedAoFormation(listOf(0.1,0.2),"UNKNOWN"))
        assertNull(williamsNtdLastClosedAoFormation(
            listOf(-0.3,Double.NaN,0.1),"LONG"))
        assertNull(williamsNtdLastClosedAoFormation(
            listOf(-0.3,Double.POSITIVE_INFINITY,0.1),"SHORT"))
        assertNull(williamsNtdLastClosedAcFormation(
            listOf(1.0,0.8,0.9,Double.NaN),"LONG"))
        assertNull(williamsNtdLastClosedAcFormation(
            listOf(1.0,0.8,0.9,Double.NEGATIVE_INFINITY),"SHORT"))
        assertNull(williamsNtdLastClosedAcFormation(
            listOf(1.0,0.8,0.9,1.0),"FOREIGN"))
    }

    @Test fun paperObserverPersistsDerivedBookFormationsWithoutWritingOrders() {
        val h=3_600_000L
        val n=48
        val bars=(1..n).map { i ->
            WilliamsWaveBar(i*h,100.0,105.0,95.0,100.0,100.0)
        }
        val ao=MutableList(n){0.0}
        val ac=MutableList(n){0.0}
        val aoTail=listOf(0.7,0.6,0.4,0.5)
        val acTail=listOf(0.8,0.5,0.6,0.7)
        for(k in 0 until 4) {
            ao[n-4+k]=aoTail[k]
            ac[n-4+k]=acTail[k]
        }
        val paper=JSONObject().put("profile",williamsNtdProfile)
            .put("symbol","BTCUSDT").put("can_place_orders",false)
            .put("last_processed_open_ms",n*h)
            .put("paper_position",JSONObject()
                .put("direction","LONG").put("entry_bar_open_ms",(n-2)*h)
                .put("entry_price",110.0).put("initial_stop",90.0))
        val result=williamsNtdObserverBookEvidence(
            paper,"BTCUSDT",bars,List(n){101.0},List(n){102.0},
            ao,ac,0.1,(n+1)*h)
        assertEquals("AO_SAUCER",
            result.getString("ntd_last_closed_ao_source_formation"))
        assertEquals("AC_SIGNAL",
            result.getString("ntd_last_closed_ac_source_formation"))
        assertEquals((n+1)*h,result.getLong("ntd_oscillator_source_h1_close_at_ms"))
        assertFalse(result.getBoolean("ntd_oscillator_signal_exchange_confirmed"))
        assertFalse(result.getBoolean("can_place_orders"))
    }
}

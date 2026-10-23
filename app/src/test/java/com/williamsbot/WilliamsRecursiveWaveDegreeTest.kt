package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Course PDF59-60 diagrams; numerical pivots are SYNTHETIC rule boundaries. */
class WilliamsRecursiveWaveDegreeTest {
    private val fixture=JSONObject(requireNotNull(
        javaClass.getResource("/williams_recursive_degree_course_contract.json")).readText())
    private val duration=mapOf("6h" to 21_600_000L,"1h" to 3_600_000L,"15m" to 900_000L)
    private fun anchors(rows:JSONArray,mirror:Boolean=false):List<WilliamsWaveAnchor> =
        (0 until rows.length()).map { i ->
            val r=rows.getJSONObject(i)
            val kind=r.getString("kind")
            WilliamsWaveAnchor(
                if(mirror) if(kind=="HIGH") "LOW" else "HIGH" else kind,
                if(mirror) 400.0-r.getDouble("price") else r.getDouble("price"),
                i,r.getLong("extreme_at"),r.getLong("known_at"))
        }

    private fun case():JSONObject=fixture.getJSONArray("patterns").getJSONObject(0)
    private fun trace(mirror:Boolean,depth:Int,missingFine:Boolean=false,poison:Boolean=false):JSONObject {
        val c=case()
        val parent=anchors(c.getJSONArray("parent"),mirror)
        val child=anchors(c.getJSONArray("children"),mirror)
        val finer=anchors(c.getJSONArray("grandchildren"),mirror).toMutableList()
        if(missingFine) finer.removeAt(finer.lastIndex)
        if(poison) {
            val last=finer.last()
            finer+=last.copy(extremeAt=fixture.getLong("as_of")+900_000L,
                knownAt=fixture.getLong("as_of")+1_800_000L,price=Double.NaN)
        }
        return traceWilliamsRecursivePriceGrammar(
            c.getString("pattern"),parent,mapOf("6h" to parent,"1h" to child,"15m" to finer),
            duration,c.getString("top_frame"),fixture.getLong("as_of"),depth)
    }

    @Test fun multiDegreeSourceContractRequiresRealConfirmedChildPriceLegs() {
        for(mirror in listOf(false,true)) {
            val first=trace(mirror,1)
            val second=trace(mirror,2)
            assertEquals("OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",first.getString("status"))
            assertEquals("OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",second.getString("status"))
            assertEquals(4,second.getInt("observed_node_count"))
            assertEquals(3,second.getJSONArray("branches").length())
            assertEquals("6h",second.getJSONArray("frame_path").getString(0))
            assertEquals("15m",second.getJSONArray("frame_path").getString(2))
            assertFalse(second.getBoolean("unique_elliott_genealogy_proven"))
            assertFalse(second.getBoolean("can_place_orders"))
            assertFalse(second.getBoolean("can_close"))
            assertFalse(second.getBoolean("can_reverse"))
            assertEquals("PARTIAL_PRICE_GRAMMAR",trace(mirror,3).getString("status"))
            assertEquals("PARTIAL_PRICE_GRAMMAR",trace(mirror,2,true).getString("status"))
            assertEquals(second.toString(),trace(mirror,2,false,true).toString())
        }
    }
    @Test fun scanChecklistDoesNotMisnameThreeSwingsAsCompletedWaveThree() {
        assertEquals("RULE_BOUNDARY_SYNTHETIC_NOT_BOOK_OHLC",fixture.getString("source_kind"))
        val cases=fixture.getJSONArray("checklist")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            assertEquals(c.getString("expected"),williamsCourseChecklistLeg(
                c.getString("leg"),c.getInt("swings")))
        }
        assertEquals("RECOUNT_W3_WRONG_WAVE_NUMBER",williamsCourseChecklistLeg("W3",3))
    }
    @Test fun rootFlatCannotBorrowFiveThreeFiveZigzagProof() {
        val c=case()
        val parent=anchors(c.getJSONArray("parent"))
        val child=anchors(c.getJSONArray("children"))
        val got=traceWilliamsRecursivePriceGrammar("REGULAR_FLAT_ABC_CANDIDATE",parent,
            mapOf("6h" to parent,"1h" to child),duration,"6h",fixture.getLong("as_of"),1)
        assertEquals("UNRESOLVED",got.getString("status"))
        assertEquals("SUBDIVISION_COUNT_FAILED",
            got.getJSONObject("internal_proof").getString("reason"))
    }
    @Test fun missingFinerResolutionCannotBeCalledComplete() {
        val c=case()
        val parent=anchors(c.getJSONArray("parent"))
        val got=traceWilliamsRecursivePriceGrammar(c.getString("pattern"),parent,
            mapOf("6h" to parent),duration,"6h",fixture.getLong("as_of"),1)
        assertEquals("CHILD_TIMEFRAME_UNAVAILABLE",got.getString("reason"))
        assertEquals("UNRESOLVED",got.getString("status"))
    }
}

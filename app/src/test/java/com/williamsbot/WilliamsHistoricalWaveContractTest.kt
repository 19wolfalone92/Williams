package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsHistoricalWaveContractTest {
    private fun anchors(rows:JSONArray):List<WilliamsWaveAnchor> = (0 until rows.length()).map { i ->
        val p=rows.getJSONObject(i)
        WilliamsWaveAnchor(p.getString("kind"),p.getDouble("price"),p.getInt("index"),p.getLong("extreme_at"),p.getLong("known_at"))
    }
    @Test fun actualDailyDiagonalUsesTheResolvedChildDegreeWhenNearestFrameCannotProveIt() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/williams_historical_wave_cases.json")).readText()).getJSONArray("cases")
        val c=(0 until cases.length()).map { cases.getJSONObject(it) }.first {
            it.getString("frame")=="1d" && it.optString("child_frame")=="6h" &&
                it.getJSONObject("candidate").getString("pattern")=="ENDING_OR_LEADING_DIAGONAL_CANDIDATE"
        }
        val parent=anchors(c.getJSONArray("parent"));val child=anchors(c.getJSONArray("child"))
        val now=c.getLong("as_of")
        val pattern=c.getJSONObject("candidate").getString("pattern")
        val evidence=detectWilliamsComplexWave(parent,c.getJSONObject("candidate").getString("direction"),now)
            .first { it.pattern==pattern }
        val result=adaptiveComplexSubdivisionEvidence(evidence,parent,
            mapOf("12h" to emptyList(),"6h" to child),"1d",now)
        assertEquals("6h",result.getString("child_frame"))
        assertEquals("PRICE_SUBDIVISIONS_CONFIRMED",result.getJSONObject("internal_proof").getString("status"))
        assertFalse(result.getBoolean("recursive_genealogy_proven"))
        assertEquals(2,result.getJSONArray("child_frame_evidence").length())
        val future=adaptiveComplexSubdivisionEvidence(evidence,parent,
            mapOf("6h" to child.map { it.copy(knownAt=now+1) }),"1d",now)
        assertEquals("UNRESOLVED",future.getJSONObject("internal_proof").getString("status"))
    }
    @Test fun historicalEndpointsAndFlatCounterexampleMatchPythonInBothDirections() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/williams_historical_wave_cases.json")).readText()).getJSONArray("cases")
        val duration=mapOf("1d" to 86_400_000L,"6h" to 21_600_000L,"1h" to 3_600_000L,"15m" to 900_000L)
        for(i in 0 until cases.length()) for(mirror in listOf(false,true)) {
            val c=cases.getJSONObject(i)
            var parent=anchors(c.getJSONArray("parent"));var child=anchors(c.optJSONArray("child") ?: JSONArray())
            var direction=c.getJSONObject("candidate").getString("direction")
            if(mirror) {
                val total=2*(parent+child).maxOf { it.price }
                fun flip(p:WilliamsWaveAnchor)=p.copy(kind=if(p.kind=="LOW") "HIGH" else "LOW",price=total-p.price)
                parent=parent.map(::flip);child=child.map(::flip);direction=if(direction=="UP") "DOWN" else "UP"
            }
            val now=c.getLong("as_of");val frameDuration=duration.getValue(c.getString("frame"))
            if(c.getString("operation")=="INVALIDATION") {
                val rows=c.getJSONObject("frames").getJSONArray(c.getString("frame"))
                var bars=(0 until rows.length()).map { j -> val b=rows.getJSONObject(j)
                    WilliamsWaveBar(b.getLong("t"),b.getDouble("o"),b.getDouble("h"),b.getDouble("l"),b.getDouble("c"),b.getDouble("v")) }
                if(mirror) {
                    val total=2*anchors(c.getJSONArray("parent")).maxOf { it.price }
                    bars=bars.map { b -> b.copy(o=total-b.o,h=total-b.l,l=total-b.h,c=total-b.c) }
                }
                val frame=c.getString("frame")
                fun target(graph:WilliamsWaveHierarchy)=graph.scenarios.first { it.frame==frame && it.anchors.size==parent.size && it.anchors.first().extremeAt==parent.first().extremeAt }
                val scenario=target(buildWilliamsWaveHierarchy(mapOf(frame to bars),now))
                assertEquals("ALTERNATIVE",scenario.status)
                assertEquals(parent.last().extremeAt+frameDuration,scenario.legObservationStart)
                assertFalse("LOWER_FRAME_OBSERVED_INVALIDATION" in scenario.invalidReasons)
                val b=bars.last()
                val changed=if(direction=="UP") b.copy(l=minOf(b.l,scenario.invalidation-.01)) else b.copy(h=maxOf(b.h,scenario.invalidation+.01))
                val broken=target(buildWilliamsWaveHierarchy(mapOf(frame to bars.dropLast(1)+changed),now))
                assertEquals(6,parent.size)
                assertEquals("ALTERNATIVE",broken.status)
                assertFalse("LOWER_FRAME_OBSERVED_INVALIDATION" in broken.invalidReasons)
                assertEquals("HISTORICAL_REFERENCE_NOT_ACTIVE_INVALIDATION",broken.toJson().getString("invalidation_scope"))
                continue
            }
            if(c.getString("operation")=="CLASSIFY") {
                val labels=detectWilliamsComplexWave(parent,direction,now).map { it.pattern }.toSet()
                val expected=c.getString("expected_pattern")
                assertTrue(labels.toString(),expected in labels)
                assertFalse("REGULAR_FLAT_ABC_CANDIDATE" in labels)
                assertEquals("UNRESOLVED",enrichWilliamsComplexSubdivisions(expected,parent,child,frameDuration,now).status)
                continue
            }
            val pattern=c.getJSONObject("candidate").getString("pattern")
            val proof=enrichWilliamsComplexSubdivisions(pattern,parent,child,frameDuration,now)
            val counts=c.getJSONObject("proof").getJSONArray("expected")
            assertEquals("PRICE_SUBDIVISIONS_CONFIRMED",proof.status)
            assertEquals((0 until counts.length()).map { counts.getInt(it) },proof.observed)
            assertTrue(proof.childKnownAt<=now)
            assertEquals("UNRESOLVED",enrichWilliamsComplexSubdivisions(pattern,parent,child.dropLast(2),frameDuration,now).status)
            assertEquals("UNRESOLVED",enrichWilliamsComplexSubdivisions(pattern,parent,child.map { it.copy(knownAt=now+1) },frameDuration,now).status)
        }
    }
}

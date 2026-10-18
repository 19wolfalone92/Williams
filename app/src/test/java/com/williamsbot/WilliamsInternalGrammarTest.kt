package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WilliamsInternalGrammarTest {
    private fun anchors(prices:List<Double>,mirror:Boolean)=prices.mapIndexed { i,p ->
        WilliamsWaveAnchor(if((i%2==0)!=mirror) "LOW" else "HIGH",if(mirror) 400-p else p,
            i,(i+1)*1000L,(i+1)*1000L+100)
    }

    @Test fun deepBRetainsBothGrammarsAndCannotAuthorizeOrders() {
        for(mirror in listOf(false,true)) {
            val points=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,135.0,154.0,120.0),mirror)
            val result=detectWilliamsComplexWave(points,if(mirror) "DOWN" else "UP",10000)
            assertTrue(result.any { it.pattern=="ZIGZAG_ABC_CANDIDATE" })
            assertTrue(result.any { it.pattern=="REGULAR_FLAT_ABC_CANDIDATE" })
            assertTrue(result.all { !it.toJson().getBoolean("can_place_orders") })
        }
    }

    @Test fun diagonalThirdMustExceedFirstIncludingBeforeAnAbc() {
        for(mirror in listOf(false,true)) for(prices in listOf(
            listOf(100.0,120.0,110.0,118.0,115.0,121.0),
            listOf(100.0,120.0,110.0,118.0,115.0,121.0,105.0,119.0,95.0))) {
            val result=detectWilliamsComplexWave(anchors(prices,mirror),if(mirror) "DOWN" else "UP",10000)
            assertFalse(result.any { it.pattern.contains("DIAGONAL") || it.pattern.contains("ABC_CANDIDATE") })
        }
    }

    @Test fun internalFlatsAndTruncatedFifthsRetainTheirActualPriceEnvelope() {
        val cases=listOf(
            Triple(listOf(100.0,120.0,95.0,125.0),3,"EXPANDED_FLAT_PRICE_SKELETON"),
            Triple(listOf(100.0,120.0,95.0,115.0),3,"RUNNING_FLAT_PRICE_SKELETON"),
            Triple(listOf(100.0,120.0,100.0,121.0),3,"REGULAR_FLAT_PRICE_SKELETON"),
            Triple(listOf(100.0,120.0,110.0,145.0,125.0,142.0),5,"TRUNCATED_IMPULSE_PRICE_SKELETON"))
        for(mirror in listOf(false,true)) for((prices,count,shape) in cases) {
            val children=anchors(prices,mirror);val parent=listOf(children.first(),children.last())
            val proof=proveWilliamsWaveSubdivisions(parent,children,listOf(count),500,10000)
            assertEquals("PRICE_SUBDIVISIONS_CONFIRMED",proof.status)
            assertEquals(listOf(shape),proof.legPatterns)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children.dropLast(1),listOf(count),500,10000).status)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children.map { it.copy(knownAt=10001) },listOf(count),500,10000).status)
            assertEquals(proof,proveWilliamsWaveSubdivisions(parent,children+children.last().copy(price=Double.NaN,extremeAt=11000,knownAt=12000),listOf(count),500,10000))
            if(count==3) assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children,listOf(count),500,10000,false).status)
        }
    }

    @Test fun invalidCOverlapAndShortestThirdStillFail() {
        val cases=listOf(listOf(100.0,120.0,110.0,115.0),listOf(100.0,120.0,95.0,99.0),
            listOf(100.0,120.0,110.0,145.0,119.0,142.0),listOf(100.0,130.0,110.0,135.0,132.0,165.0))
        for(mirror in listOf(false,true)) for(prices in cases) {
            val children=anchors(prices,mirror)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(listOf(children.first(),children.last()),children,listOf(prices.size-1),500,10000).status)
        }
    }

    @Test fun historicalCounterexamplesConsumeTheSameContractAsPython() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/williams_grammar_historical_cases.json")).readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) for(mirror in listOf(false,true)) {
            val case=cases.getJSONObject(i);val rows=case.getJSONArray("parent")
            val raw=(0 until rows.length()).map { j ->
                val p=rows.getJSONObject(j)
                WilliamsWaveAnchor(p.getString("kind"),p.getDouble("price"),p.getInt("index"),p.getLong("extreme_at"),p.getLong("known_at"))
            }
            val total=2*raw.maxOf { it.price }
            val points=if(mirror) raw.map { it.copy(kind=if(it.kind=="LOW") "HIGH" else "LOW",price=total-it.price) } else raw
            val original=case.getJSONObject("candidate").getString("direction")
            val direction=if(mirror) { if(original=="UP") "DOWN" else "UP" } else original
            val result=detectWilliamsComplexWave(points,direction,case.getLong("as_of"))
            val labels=result.map { it.pattern }.toSet()
            if(case.getString("operation")=="REJECTED_NONEXTENDING_W3")
                assertFalse(case.getJSONObject("candidate").getString("pattern") in labels)
            else {
                assertTrue("ZIGZAG_ABC_CANDIDATE" in labels)
                assertTrue("REGULAR_FLAT_ABC_CANDIDATE" in labels)
            }
            assertTrue(result.all { !it.toJson().getBoolean("can_place_orders") })
        }
    }
}

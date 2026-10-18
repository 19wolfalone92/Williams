package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsWaveHierarchyTest {
    private val hour = 3_600_000L
    private fun fixtureCases() = JSONObject(requireNotNull(javaClass.getResource("/williams_wave_contract.json")).readText())
        .getJSONArray("cases")
    private fun fixtureBars(case: JSONObject): List<WilliamsWaveBar> {
        val rows = case.getJSONArray("bars")
        return (0 until rows.length()).map { i -> val r=rows.getJSONArray(i)
            WilliamsWaveBar(r.getLong(0),r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)) }
    }
    @Test fun pythonAndAndroidConsumeTheSameWaveContractInBothDirections() {
        val cases=fixtureCases()
        for (i in 0 until cases.length()) {
            val case=cases.getJSONObject(i)
            val report=buildWilliamsWaveHierarchy(mapOf("1h" to fixtureBars(case)),case.getLong("as_of"))
            val expected=case.getJSONArray("expected")
            assertEquals(expected.length(),report.scenarios.size)
            for ((j,s) in report.scenarios.withIndex()) {
                val e=expected.getJSONObject(j)
                assertEquals(e.getString("label"),s.label)
                assertEquals(e.getString("direction"),s.direction)
                assertEquals(e.getString("status"),s.status)
                assertEquals(e.getString("w4_zero"),s.w4Zero)
                assertEquals(e.getInt("scale_bars"),s.scaleBars)
            }
            val completed=report.scenarios.first { it.label=="W5" }
            assertEquals(121,completed.scaleBars)
            assertEquals(50*hour,completed.anchors.first().extremeAt)
            assertEquals(53*hour,completed.anchors.first().knownAt)
            assertFalse(williamsCorrectionEntryContext(report,"LONG",169*hour,"REVERSAL").allowed)
        }
    }
    @Test fun fourthWaveOverlapRemainsResearchOnlyDiagonalAlternative() {
        val candles=(0 until 180).map { i ->
            WilliamsWaveBar(i*hour,130.0,131.0,129.0,130.0,1000.0)
        }
        fun count(w4:Double):WilliamsWaveScenario {
            val parts=listOf(
                "LOW" to 100.0,"HIGH" to 120.0,"LOW" to 110.0,
                "HIGH" to 140.0,"LOW" to w4,"HIGH" to 150.0
            )
            val positions=listOf(10,30,50,75,100,130)
            val anchors=parts.mapIndexed { i,(k,p) ->
                WilliamsWaveAnchor(k,p,positions[i],positions[i]*hour,(positions[i]+3)*hour)
            }
            return williamsHypothesesFromAnchors("1h",candles,anchors).first { it.label=="A" }
        }
        val diagonal=count(115.0)
        assertEquals("ALTERNATIVE",diagonal.status)
        assertTrue(diagonal.invalidReasons.isEmpty())
        assertTrue(diagonal.alternatives.contains("UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP"))
        val invalid=count(109.0)
        assertEquals("INVALIDATED",invalid.status)
        assertTrue(invalid.invalidReasons.contains("W4_BREACHES_W3_OR_W4_END"))
        assertEquals("PROVISIONAL",count(125.0).status)
    }
    @Test fun allCausalPrefixesIgnoreFutureCandles() {
        val bars=fixtureBars(fixtureCases().getJSONObject(0))
        for (n in 40..bars.size step 3) {
            val full=buildWilliamsWaveHierarchy(mapOf("1h" to bars),n*hour)
            val prefix=buildWilliamsWaveHierarchy(mapOf("1h" to bars.take(n)),n*hour)
            assertEquals(prefix.toJson().toString(),full.toJson().toString())
        }
    }
    @Test fun gapsDuplicatesNonFiniteAndStaleHistoryCannotAuthorizeWaves() {
        val original=fixtureBars(fixtureCases().getJSONObject(0))
        val gap=original.filterIndexed { i,_ -> i!=60 }
        val duplicate=original.mapIndexed { i,b -> if (i==60) b.copy(t=original[59].t) else b }
        val nan=original.mapIndexed { i,b -> if (i==60) b.copy(l=Double.NaN) else b }
        for (bars in listOf(gap,duplicate,nan)) {
            val report=buildWilliamsWaveHierarchy(mapOf("1h" to bars),original.size*hour)
            assertTrue("1h" in report.frameErrors)
            assertTrue(report.scenarios.isEmpty())
        }
        assertTrue("1h" in buildWilliamsWaveHierarchy(mapOf("1h" to original),(original.size+2)*hour).frameErrors)
    }
    @Test fun plateauNeedsTwoRealRightHandCloses() {
        val lows=listOf(105.0,104.0,100.0,100.0,100.0,102.0,103.0)
        val bars=lows.mapIndexed { i,l -> WilliamsWaveBar(i*hour,l+1,l+2,l,l+1) }
        val p=confirmedWilliamsWaveAnchors(bars,hour).single()
        assertEquals("LOW",p.kind)
        assertEquals(4*hour,p.extremeAt)
        assertEquals(7*hour,p.knownAt)
        assertTrue(confirmedWilliamsWaveAnchors(bars.dropLast(1),hour).isEmpty())
    }
    private fun hypothesis(frame:String,direction:String,label:String,prices:List<Double>,times:List<Int>,
                           legStart:Int,low:Double,high:Double): WilliamsWaveScenario {
        val anchors=prices.mapIndexed { i,p -> WilliamsWaveAnchor(
            if ((i%2==0)==(direction=="UP")) "LOW" else "HIGH",p,i,times[i]*hour,(times[i]+3)*hour) }
        return WilliamsWaveScenario("$frame:$label",frame,direction,label,anchors,340*hour,legStart*hour,low,high,
            if (prices.size>=4) prices[1] else prices[0])
    }
    private fun correction(mirror:Boolean=false): WilliamsWaveHierarchy {
        var s=listOf(
            hypothesis("1d","DOWN","W3",listOf(260.0,210.0,240.0),listOf(5,20,50),50,50.0,280.0),
            hypothesis("6h","DOWN","W4",listOf(220.0,180.0,200.0,100.0),listOf(80,100,200,300),300,95.0,180.0),
            hypothesis("1h","UP","W3",listOf(110.0,145.0,130.0),listOf(310,320,330),330,125.0,165.0),
            hypothesis("15m","UP","W3",listOf(131.0,143.0,137.0),listOf(331,334,336),336,136.0,150.0))
        s[1].w4Zero="MINIMUM_ONLY";s[1].momentumShift=true
        if (mirror) s=s.map { h -> h.copy(direction=if (h.direction=="UP") "DOWN" else "UP",
            anchors=h.anchors.map { a -> a.copy(price=400-a.price,kind=if (a.kind=="LOW") "HIGH" else "LOW") },
            legLow=400-h.legHigh,legHigh=400-h.legLow,invalidation=400-h.invalidation) }
        return WilliamsWaveHierarchy(340*hour,s,linkWilliamsWaveHypotheses(s),emptyMap(),emptyList())
    }
    @Test fun parentRequiresBothTimeAndPriceContainment() {
        val report=correction()
        assertEquals(setOf("1d:W3>6h:W4","6h:W4>1h:W3","1h:W3>15m:W3"),
            report.links.map { "${it.parent}>${it.child}" }.toSet())
        val old=report.scenarios[2]
        val earlier=old.copy(anchors=listOf(old.anchors[0].copy(extremeAt=290*hour))+old.anchors.drop(1))
        val changed=report.scenarios.map { if (it.id==old.id) earlier else it }
        assertFalse(linkWilliamsWaveHypotheses(changed).any { it.parent=="6h:W4" && it.child=="1h:W3" })
        val expensive=old.copy(anchors=listOf(old.anchors[0],old.anchors[1].copy(price=300.0),old.anchors[2]))
        assertFalse(linkWilliamsWaveHypotheses(report.scenarios.map { if (it.id==old.id) expensive else it })
            .any { it.parent=="6h:W4" && it.child=="1h:W3" })
    }
    @Test fun correctionModeIsSymmetricAndUsesStructuralBounds() {
        for (mirror in listOf(false,true)) {
            val proof=williamsCorrectionEntryContext(correction(mirror),if (mirror) "SHORT" else "LONG",339*hour,"REVERSAL")
            assertTrue(proof.allowed)
            assertTrue(williamsCorrectionPriceAllowed(proof.toJson(),if (mirror) 250.0 else 150.0))
            assertFalse(williamsCorrectionPriceAllowed(proof.toJson(),proof.parentFloor))
            assertFalse(williamsCorrectionPriceAllowed(proof.toJson(),proof.parentCeiling))
        }
    }
    @Test fun nearZeroMissingHistoryConflictsAndMissingJuniorCannotApproveCorrection() {
        var report=correction()
        report.scenarios[1].w4Zero="NEAR_ZERO_UNCONFIRMED"
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"REVERSAL").allowed)
        report=correction().copy(frameErrors=mapOf("15m" to "STALE"))
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"REVERSAL").allowed)
        report=correction()
        val competing=report.scenarios[1].copy(id="competing",label="W3",w4Zero="NO_ZERO_CROSS")
        report=report.copy(scenarios=report.scenarios+competing)
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"REVERSAL").allowed)
        report=correction()
        assertFalse(williamsCorrectionEntryContext(report,"LONG",340*hour,"REVERSAL").allowed)
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"AO_DIVERGENCE").allowed)
        report=report.copy(links=report.links.filter { it.child!="15m:W3" })
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"REVERSAL").allowed)
    }
    @Test fun oppositeSignalMustActuallyTriggerAndMustPostdateTheEntry() {
        assertFalse(williamsOpposingLevelTriggered("LONG",110.0,20,10,109.0,emptyList(),30,50))
        assertTrue(williamsOpposingLevelTriggered("LONG",110.0,20,10,110.0,emptyList(),30,50))
        assertFalse(williamsOpposingLevelTriggered("SHORT",90.0,20,10,91.0,emptyList(),30,50))
        assertTrue(williamsOpposingLevelTriggered("SHORT",90.0,20,10,90.0,emptyList(),30,50))
        assertFalse(williamsOpposingLevelTriggered("LONG",110.0,20,25,111.0,emptyList(),30,50))
        assertFalse(williamsOpposingLevelTriggered("LONG",110.0,20,10,111.0,emptyList(),50,50))
        assertTrue(williamsOpposingLevelTriggered("LONG",110.0,20,10,105.0,
            listOf(WilliamsWaveBar(20,100.0,111.0,99.0,105.0)),30,50))
    }
    @Test fun tc2WindowTrailsBeforeOneRWithoutNewFractalAndWithoutOriginalR() {
        val long=listOf(95.0,96.0,97.0,98.0,99.0).mapIndexed { i,l -> FuturesTrailBar(i*hour,l+2,l) }
        val short=long.map { FuturesTrailBar(it.openTimeMs,200-it.low,200-it.high) }
        for (initial in listOf(90.0,null)) {
            val plan=planWilliamsCampaignStop("LONG",90.0,100.0,initial,104.0,.1,1,long)
            assertEquals("TC2_LAST_5_CLOSED_BARS",plan?.reason)
            assertEquals(94.9,plan!!.stop,1e-8)
        }
        val plan=planWilliamsCampaignStop("SHORT",110.0,100.0,null,96.0,.1,1,short)
        assertEquals(105.1,plan!!.stop,1e-8)
        assertNull(planWilliamsCampaignStop("LONG",99.0,100.0,90.0,104.0,.1,1,long))
    }
}

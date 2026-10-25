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
    @Test fun sourceW4CrossRequiresPassingZeroAtTheExaminedDegree() {
        val cases=JSONObject(requireNotNull(javaClass.getResource(
            "/williams_w4_zero_source_contract.json")).readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i)
            for(sign in listOf(1,-1)) {
                val rawW3=sign*case.getDouble("third_peak")
                val rawW4=sign*case.getDouble("fourth_minimum")
                assertEquals(case.getString("id"),case.getString("expected"),
                    classifyWilliamsFourthAo(sign*rawW3,sign*rawW4,case.getInt("scale_bars")))
            }
        }
        // The hierarchy scalar and the separate rhythm module must agree
        // that an exact touch has not met the book minimum in either direction.
        for(direction in listOf("UP","DOWN")) {
            val sign=if(direction=="UP") 1 else -1
            val ao=MutableList(120) { 4.0*sign };ao[55]=8.0*sign;ao[95]=0.0
            assertEquals(Wave4ZeroStatus.NEAR_ZERO_UNCONFIRMED,
                evaluateWilliamsWave4Zero(ao,0,55,119,direction).status)
            assertEquals("NEAR_ZERO_UNCONFIRMED",classifyWilliamsFourthAo(8.0,0.0,120))
        }
    }

    @Test fun realH4BarsParticipateInHypothesesAndUseH1ForSubdivisionEvidence() {
        val fixture=fixtureCases().getJSONObject(0)
        val h4=fixtureBars(fixture).map { it.copy(t=it.t*4) }
        val h1=h4.flatMap { b -> (0..3).map { i -> b.copy(t=b.t+i*hour) } }
        val now=fixture.getLong("as_of")*4
        val report=buildWilliamsWaveHierarchy(mapOf("4h" to h4,"1h" to h1),now)
        assertTrue(report.scenarios.any { it.frame=="4h" })
        assertEquals(setOf("4h","1h"),report.frameCandleCounts.keys)
        val structures=report.complexStructures.filter { it.getString("frame")=="4h" }
        assertTrue(structures.isNotEmpty())
        assertTrue(structures.all { it.getString("child_frame")=="1h" && !it.getBoolean("can_place_orders") })
        val cutoff=160*4*hour
        val all=buildWilliamsWaveHierarchy(mapOf("4h" to h4,"1h" to h1),cutoff).toJson()
        val prefix=buildWilliamsWaveHierarchy(mapOf("4h" to h4.take(160),"1h" to h1.take(640)),cutoff).toJson()
        assertEquals(prefix.toString(),all.toString())
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
    @Test fun h1WaveRhythmCannotBorrowASeniorH4AoScale() {
        val hour=3_600_000L
        val h1=(0 until 620).map { i ->
            val v=100.0+20.0*kotlin.math.sin(i/15.0)
            WilliamsWaveBar((i+1)*hour,v,v+1,v-1,v,1.0)
        }
        val h4=(0 until 155).map { i ->
            val v=100.0+30.0*kotlin.math.sin(i/9.0)
            WilliamsWaveBar(i*4*hour,v,v+1,v-1,v,1.0)
        }
        val scenario=WilliamsWaveScenario(
            id="H1:CHILD",frame="1h",direction="UP",label="W3",
            anchors=listOf(
                WilliamsWaveAnchor("LOW",95.0,100,100*hour,103*hour),
                WilliamsWaveAnchor("HIGH",110.0,180,180*hour,183*hour),
                WilliamsWaveAnchor("LOW",100.0,260,260*hour,263*hour),
                WilliamsWaveAnchor("HIGH",130.0,420,420*hour,423*hour)
            ),observedAt=600*hour,legStart=420*hour,
            legLow=95.0,legHigh=130.0,invalidation=100.0
        )
        val method=Class.forName("com.williamsbot.WilliamsWaveHierarchyKt")
            .getDeclaredMethod("enrichWaveRhythm",WilliamsWaveScenario::class.java,Map::class.java)
        method.isAccessible=true
        method.invoke(null,scenario,mapOf("1h" to h1,"4h" to h4))
        assertEquals("1h",scenario.scaleFrame)
        assertTrue(scenario.scaleBars>140)
        assertEquals("WRONG_SCALE",scenario.w4Zero)
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
            return williamsHypothesesFromAnchors("1h",candles,anchors).first { it.label=="W5" }
        }
        val diagonal=count(115.0)
        assertEquals("ALTERNATIVE",diagonal.status)
        assertEquals(110.0,diagonal.invalidation,1e-9)
        assertTrue(diagonal.invalidReasons.isEmpty())
        assertTrue(diagonal.alternatives.contains("UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP"))
        val invalid=count(109.0)
        assertEquals("INVALIDATED",invalid.status)
        assertTrue(invalid.invalidReasons.contains("W4_BREACHES_W3_OR_W4_END"))
        assertEquals("PROVISIONAL",count(125.0).status)
    }

    @Test fun courseW2ExactOriginTouchIsAnUnverifiedAlternativeNotInvalidation() {
        val cases=JSONObject(requireNotNull(javaClass.getResource(
            "/williams_w2_boundary_source_contract.json")).readText()).getJSONArray("cases")
        for (mirror in listOf(false,true)) for(caseIndex in 0 until cases.length()) {
            val case=cases.getJSONObject(caseIndex)
            val secondLow=case.getDouble("second_low")
            val knots=listOf(0 to 120.0,50 to 100.0,65 to 110.0,78 to secondLow,
                110 to 150.0,145 to 118.0,160 to 156.0,170 to 154.0)
            val bars=(0..170).map { i ->
                val pair=knots.zipWithNext().firstOrNull { (a,b) -> i in a.first..b.first }
                val price=if(pair==null) knots.last().second else {
                    val (a,b)=pair; a.second+(b.second-a.second)*(i-a.first)/(b.first-a.first)
                }
                val original=WilliamsWaveBar(i*hour,price,price+.1,price-.1,price,1000.0)
                if(mirror) original.copy(o=300-original.o,h=300-original.l,
                    l=300-original.h,c=300-original.c) else original
            }
            // A suffix after W5 would be a DIFFERENT provisional wave count.
            val prefix=bars.take(82)
            val report=buildWilliamsWaveHierarchy(mapOf("1h" to prefix),prefix.size*hour)
            val direction=if(mirror) "DOWN" else "UP"
            val counts=report.scenarios.filter { it.direction==direction && it.anchors.size==3 &&
                it.anchors.first().extremeAt==50*hour }
            assertEquals("W2 case count; mirror=$mirror, w2=$secondLow",1,counts.size)
            val s=counts.single()
            assertEquals(case.getString("expected"),s.status)
            if(secondLow==100.0) {
                assertEquals(listOf("W2_ORIGIN_TOUCH_100_PERCENT_UNCONFIRMED"),s.alternatives)
                assertTrue(s.invalidReasons.isEmpty())
            }
            if(secondLow==99.5) assertTrue(s.invalidReasons.contains("W2_INVALIDATES_ORIGIN"))
            assertFalse(report.toJson().getBoolean("can_place_orders"))
        }
    }
    @Test fun unconfirmedRightBarCannotRevealEitherDirectionFractal() {
        val case=JSONObject(requireNotNull(javaClass.getResource(
            "/williams_w2_boundary_source_contract.json")).readText()).getJSONArray("fractals").getJSONObject(0)
        val levels=case.getJSONArray("levels").let { a -> (0 until a.length()).map { a.getDouble(it) } }
        for(mirror in listOf(false,true)) {
            val bars=levels.mapIndexed { i,v ->
                val b=WilliamsWaveBar(i*hour,v,v+1,v-1,v,100.0)
                if(mirror) b.copy(o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c) else b
            }
            assertTrue(confirmedWilliamsWaveAnchors(bars.dropLast(1),hour).isEmpty())
            val p=confirmedWilliamsWaveAnchors(bars,hour).single()
            assertEquals(if(mirror) "HIGH" else "LOW",p.kind)
            assertEquals(2*hour,p.extremeAt)
            assertEquals(case.getInt("known_at_bar")*hour,p.knownAt)
        }
    }
    @Test fun allCausalPrefixesIgnoreFutureCandles() {
        val bars=fixtureBars(fixtureCases().getJSONObject(0))
        for (n in 40..bars.size step 3) {
            val full=buildWilliamsWaveHierarchy(mapOf("1h" to bars),n*hour)
            val prefix=buildWilliamsWaveHierarchy(mapOf("1h" to bars.take(n)),n*hour)
            assertEquals(prefix.toJson().toString(),full.toJson().toString())
        }
    }
    @Test fun aDeepPostFifthCorrectionDoesNotEraseTheHistoricalMotiveInEitherDirection() {
        for(mirror in listOf(false,true)) {
            val bars=fixtureBars(fixtureCases().getJSONObject(0)).toMutableList()
            for(i in 1..15) {
                val price=154.0-i*4.5
                bars+=WilliamsWaveBar(bars.size*hour,price,price+.1,price-.1,price,1000.0)
            }
            val source=if(mirror) bars.map { it.copy(o=300-it.o,h=300-it.l,l=300-it.h,c=300-it.c) } else bars
            val report=buildWilliamsWaveHierarchy(mapOf("1h" to source),source.size*hour)
            val fifth=report.scenarios.single { it.anchors.size==6 && it.anchors.first().extremeAt==50*hour }
            assertEquals("W5",fifth.label)
            assertEquals("A",fifth.activeLabel)
            assertEquals("PROVISIONAL",fifth.status)
            assertFalse("LIVE_PRICE_INVALIDATED_COUNT" in fifth.invalidReasons)
            assertFalse("LOWER_FRAME_OBSERVED_INVALIDATION" in fifth.invalidReasons)
            assertEquals("HISTORICAL_REFERENCE_NOT_ACTIVE_INVALIDATION",fifth.toJson().getString("invalidation_scope"))
        }
    }
    @Test fun anUnfinishedFifthStillInvalidatesOnAWickWhenTheCloseHolds() {
        for(mirror in listOf(false,true)) {
            val original=fixtureBars(fixtureCases().getJSONObject(0)).take(150)
            val bars=(if(mirror) original.map { it.copy(o=300-it.o,h=300-it.l,l=300-it.h,c=300-it.c) } else original).toMutableList()
            fun target(rows:List<WilliamsWaveBar>)=buildWilliamsWaveHierarchy(mapOf("1h" to rows),rows.size*hour)
                .scenarios.single { it.anchors.size==5 && it.anchors.first().extremeAt==50*hour }
            val initial=target(bars)
            assertEquals("PROVISIONAL",initial.status)
            val b=bars.last()
            bars[bars.lastIndex]=if(mirror) b.copy(h=maxOf(b.h,initial.invalidation+.01))
                else b.copy(l=minOf(b.l,initial.invalidation-.01))
            val broken=target(bars)
            assertEquals("INVALIDATED",broken.status)
            assertTrue("LOWER_FRAME_OBSERVED_INVALIDATION" in broken.invalidReasons)
            assertFalse("LIVE_PRICE_INVALIDATED_COUNT" in broken.invalidReasons)
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
            hypothesis("1d","DOWN","W2",listOf(260.0,210.0,240.0),listOf(5,20,50),50,50.0,280.0),
            hypothesis("6h","DOWN","W3",listOf(220.0,180.0,200.0,100.0),listOf(80,100,200,300),300,95.0,180.0),
            hypothesis("1h","UP","W2",listOf(110.0,145.0,130.0),listOf(310,320,330),330,125.0,165.0),
            hypothesis("15m","UP","W2",listOf(131.0,143.0,137.0),listOf(331,334,336),336,136.0,150.0))
        s[1].w4Zero="MINIMUM_ONLY";s[1].momentumShift=true
        if (mirror) s=s.map { h -> h.copy(direction=if (h.direction=="UP") "DOWN" else "UP",
            anchors=h.anchors.map { a -> a.copy(price=400-a.price,kind=if (a.kind=="LOW") "HIGH" else "LOW") },
            legLow=400-h.legHigh,legHigh=400-h.legLow,invalidation=400-h.invalidation) }
        return WilliamsWaveHierarchy(340*hour,s,linkWilliamsWaveHypotheses(s),emptyMap(),emptyList())
    }
    @Test fun parentRequiresBothTimeAndPriceContainment() {
        val report=correction()
        assertEquals(setOf("1d:W2>6h:W3","6h:W3>1h:W2","1h:W2>15m:W2"),
            report.links.map { "${it.parent}>${it.child}" }.toSet())
        val old=report.scenarios[2]
        val earlier=old.copy(anchors=listOf(old.anchors[0].copy(extremeAt=290*hour))+old.anchors.drop(1))
        val changed=report.scenarios.map { if (it.id==old.id) earlier else it }
        assertFalse(linkWilliamsWaveHypotheses(changed).any { it.parent=="6h:W3" && it.child=="1h:W2" })
        val expensive=old.copy(anchors=listOf(old.anchors[0],old.anchors[1].copy(price=300.0),old.anchors[2]))
        assertFalse(linkWilliamsWaveHypotheses(report.scenarios.map { if (it.id==old.id) expensive else it })
            .any { it.parent=="6h:W3" && it.child=="1h:W2" })
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
        val competing=report.scenarios[1].copy(id="competing",label="W2",anchors=report.scenarios[1].anchors.take(3),w4Zero="NO_ZERO_CROSS")
        report=report.copy(scenarios=report.scenarios+competing)
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"REVERSAL").allowed)
        report=correction()
        assertFalse(williamsCorrectionEntryContext(report,"LONG",340*hour,"REVERSAL").allowed)
        assertFalse(williamsCorrectionEntryContext(report,"LONG",339*hour,"AO_DIVERGENCE").allowed)
        report=report.copy(links=report.links.filter { it.child!="15m:W2" })
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
            listOf(WilliamsWaveBar(20,100.0,111.0,99.0,105.0)),30,50,frameMs=10))
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
    @Test fun defaultLiveWaveHierarchyCannotSilentlyOmitH4() {
        // The trading passport has already required D1/H6/H4/H1/M15.
        // The separate wave hierarchy must cover the same five frames.
        assertEquals(listOf("1d","6h","4h","1h","15m"),williamsRequiredWaveFrames)
        val fixture=fixtureCases().getJSONObject(0)
        val report=buildWilliamsWaveHierarchy(
            mapOf("1h" to fixtureBars(fixture)),fixture.getLong("as_of"))
        assertTrue("H4 missing is never silently treated as a full five-frame chain",
            report.frameErrors.containsKey("4h"))
        val json=report.toJson()
        assertEquals(5,json.getJSONArray("chain").length())
        assertFalse(json.getBoolean("contained_chain_observed"))
        assertFalse(json.getBoolean("can_place_orders"))
    }

    @Test fun observedFiveFrameChainCannotSkipRequestedH4Evidence() {
        val frames=listOf("1d","6h","1h","15m")
        val scenarios=frames.map { WilliamsWaveScenario(it,it,"UP","W1",emptyList(),100L,1L,90.0,110.0,80.0) }
        val links=frames.zipWithNext().map { (a,b) -> WilliamsWaveLink(a,b,"W1","CONTAINED") }
        val four=WilliamsWaveHierarchy(100L,scenarios,links,emptyMap(),emptyList(),analysisFrames=frames).toJson()
        val five=WilliamsWaveHierarchy(100L,scenarios,links,emptyMap(),emptyList(),
            analysisFrames=listOf("1d","6h","4h","1h","15m")).toJson()
        assertTrue(four.getBoolean("contained_chain_observed"))
        assertFalse(five.getBoolean("contained_chain_observed"))
        assertFalse(five.getBoolean("can_place_orders"))
    }
}

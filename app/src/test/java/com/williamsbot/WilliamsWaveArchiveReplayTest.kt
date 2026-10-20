package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class WilliamsWaveArchiveReplayTest {
    private val h=3_600_000L
    private val start=1_000L*h
    private fun bars(n:Int,step:Long=h):List<WilliamsWaveBar> = (0 until n).map { i->
        val p=100.0+8*sin(i/4.0)+3*sin(i/17.0)
        WilliamsWaveBar(start+i*step,p,p+1,p-1,p,10.0)
    }
    private fun flat(n:Int):List<WilliamsWaveBar> = (0 until n).map { i->
        WilliamsWaveBar(start+i*h,100.0,101.0,99.0,100.0,1.0)
    }

    @Test fun moreThan1800FutureBarsCannotEvictTheHistoricalCausalPrefix() {
        val known=bars(90)
        val asOf=start+90*h
        val expected=buildAdaptiveHistoricalWaveReport(mapOf("1h" to known),asOf)
        val actual=buildAdaptiveHistoricalWaveReport(mapOf("1h" to bars(2090)),asOf)
        assertEquals(canonicalWaveJson(expected),canonicalWaveJson(actual))
        assertEquals(90,actual.getJSONObject("historical_frame_counts").getInt("1h"))
        assertTrue(expected.getJSONArray("hypotheses").length()>0)
    }

    @Test fun archiveTimelineIsNotTruncatedToTheWorkingWindow() {
        val moments=mutableListOf<Long>()
        val result=replayWilliamsWaveArchive(mapOf("1h" to flat(1810)),start+1810*h,
            analysisLimit=40) { moments+=it.getLong("as_of_closed_ms") }
        assertEquals(1810,result.getInt("processed_close_events"))
        assertEquals(start+h,moments.first())
        assertEquals(start+1810*h,moments.last())
        assertEquals(1810,moments.distinct().size)
        assertFalse(result.getBoolean("all_exchange_history_proven"))
    }

    @Test fun everyRealFrameClosureIsVisitedAndPartialHigherFrameBarIsInvisible() {
        val history=mapOf("1h" to bars(8),"4h" to bars(2,4*h))
        val snapshots=mutableListOf<JSONObject>()
        replayWilliamsWaveArchive(history,start+8*h,onSnapshot={snapshots+=it})
        assertEquals(8,snapshots.size)
        val before=snapshots[2].getJSONObject("report").getJSONObject("historical_frame_counts")
        val at=snapshots[3].getJSONObject("report").getJSONObject("historical_frame_counts")
        assertFalse(before.has("4h"))
        assertEquals(1,at.getInt("4h"))
        val fine=mapOf("15m" to bars(8,h/4),"1h" to bars(2))
        val result=replayWilliamsWaveArchive(fine,start+2*h)
        assertEquals(8,result.getInt("processed_close_events"))
    }

    @Test fun futurePriceShockCannotChangeAnyEarlierSnapshotOrRevision() {
        val original=mapOf("1h" to bars(100))
        val withFuture=mapOf("1h" to (bars(100)+(100..2050).map { i->
            WilliamsWaveBar(start+i*h,999.0,1001.0,998.0,1000.0,1.0)
        }))
        val a=mutableListOf<String>();val b=mutableListOf<String>()
        replayWilliamsWaveArchive(original,start+100*h) { a+=canonicalWaveJson(it) }
        replayWilliamsWaveArchive(withFuture,start+100*h) { b+=canonicalWaveJson(it) }
        assertEquals(a,b)
        assertEquals(100,a.size)
    }

    @Test fun resumePreservesTheExactEvidenceChainAndRevisionHistory() {
        val history=mapOf("1h" to bars(120))
        val all=mutableListOf<String>();val chunks=mutableListOf<String>()
        var checkpoint:JSONObject?=null
        replayWilliamsWaveArchive(history,start+120*h) { all+=canonicalWaveJson(it) }
        replayWilliamsWaveArchive(history,start+60*h) {
            checkpoint=it;chunks+=canonicalWaveJson(it)
        }
        replayWilliamsWaveArchive(history,start+120*h,afterMs=start+60*h,
            previousSnapshot=checkpoint) { chunks+=canonicalWaveJson(it) }
        assertEquals(all,chunks)
    }

    @Test fun cancellationLeavesAConcreteResumableCursor() {
        var visited=0
        val result=replayWilliamsWaveArchive(mapOf("1h" to bars(120)),start+120*h,
            cancelled={visited==7}) { visited++ }
        assertEquals("INTERRUPTED_RESUMABLE",result.getString("status"))
        assertEquals(start+7*h,result.getLong("last_replayed_ms"))
        assertEquals(7,result.getInt("processed_close_events"))
    }

    @Test fun gapsAndDuplicatesAreExplicitAndNeverFilledWithSyntheticBars() {
        val input=bars(65).filterIndexed { i,_->i!=22 }.toMutableList()
        input.add(input[30])
        var last:JSONObject?=null
        replayWilliamsWaveArchive(mapOf("1h" to input),start+65*h) { last=it }
        val quality=last!!.getJSONObject("archive_quality").getJSONObject("1h")
        assertEquals(1,quality.getInt("gap_boundaries"))
        assertEquals(1,quality.getInt("duplicate_open_times"))
        assertEquals(65,quality.getInt("closed_input_bars"))
        assertEquals(65,last.getJSONObject("report").getJSONObject("historical_frame_counts").getInt("1h"))
    }

    @Test fun everyAnchorAndComplexEndpointMustAlreadyBeKnownAtItsSnapshot() {
        var observations=0
        replayWilliamsWaveArchive(mapOf("1h" to bars(180)),start+180*h) { snapshot->
            val now=snapshot.getLong("as_of_closed_ms")
            val report=snapshot.getJSONObject("report")
            val rows=report.getJSONArray("hypotheses")
            observations+=rows.length()
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                assertTrue(row.getLong("known_at_ms")<=now)
                val anchors=row.getJSONArray("anchors")
                for(j in 0 until anchors.length()) {
                    val p=anchors.getJSONObject(j)
                    assertTrue(p.getLong("known_at")<=now)
                    assertTrue(p.getLong("known_at")>=p.getLong("extreme_at")+3*h)
                }
            }
            val patterns=report.getJSONArray("complex_wave_patterns")
            for(i in 0 until patterns.length()) assertTrue(patterns.getJSONObject(i).getLong("known_at")<=now)
            assertFalse(snapshot.getBoolean("can_place_orders"))
            assertFalse(snapshot.getBoolean("can_close_positions"))
        }
        assertTrue(observations>0)
    }

    @Test fun seniorFifthRemainsADifferentDegreeFromItsChildThird() {
        fun anchor(kind:String,p:Double,time:Long)=WilliamsWaveAnchor(kind,p,0,time,time+3*h)
        val parentPoints=listOf(anchor("LOW",100.0,start),anchor("HIGH",120.0,start+8*h),
            anchor("LOW",110.0,start+16*h),anchor("HIGH",145.0,start+24*h),
            anchor("LOW",125.0,start+32*h))
        val childPoints=listOf(anchor("LOW",126.0,start+36*h),
            anchor("HIGH",130.0,start+38*h),anchor("LOW",128.0,start+40*h))
        val now=start+60*h
        val parent=WilliamsWaveScenario("parent","4h","UP","W4",parentPoints,now,
            start+32*h,125.0,160.0,120.0)
        val child=WilliamsWaveScenario("child","1h","UP","W2",childPoints,now,
            start+40*h,128.0,150.0,126.0)
        val links=linkWilliamsWaveHypotheses(listOf(parent,child))
        val contexts=adaptiveSeniorFifthContexts(listOf(parent,child),links,now)
        assertEquals(1,contexts.length())
        assertEquals("W5",contexts.getJSONObject(0).getString("parent_active_label"))
        assertEquals("W3",contexts.getJSONObject(0).getString("child_active_label"))
        assertFalse(contexts.getJSONObject(0).getBoolean("senior_fifth_end_confirmed"))
        assertEquals(0,adaptiveSeniorFifthContexts(listOf(parent,child),links,start+41*h).length())
    }

    @Test fun resumingAfterAnOldPriceEditCannotSilentlyReuseTheCheckpoint() {
        val original=flat(120)
        var checkpoint:JSONObject?=null
        replayWilliamsWaveArchive(mapOf("1h" to original),start+60*h,
            analysisLimit=40) { checkpoint=it }
        val edited=original.toMutableList()
        // Outside the final 40-bar analysis window, but inside source history.
        edited[0]=edited[0].copy(v=9.0)
        try {
            replayWilliamsWaveArchive(mapOf("1h" to edited),start+120*h,
                afterMs=start+60*h,analysisLimit=40,previousSnapshot=checkpoint)
            fail("Changed historical source must reject a resumed evidence chain")
        } catch(e:IllegalArgumentException) {
            assertEquals("REPLAY_CHECKPOINT_SOURCE_OR_MODEL_CHANGED",e.message)
        }
    }
}

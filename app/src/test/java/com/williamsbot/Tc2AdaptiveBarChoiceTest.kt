package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class Tc2AdaptiveBarChoiceTest {
    private val h=3_600_000L
    private val now=180*h
    private val approved=174*h

    private fun slowH1():List<WilliamsWaveBar>=(0 until 180).map { i ->
        val lo=100.0+0.04*i
        WilliamsWaveBar(i*h,lo+2,lo+4,lo,lo+2,1.0)
    }
    private fun h4():List<WilliamsWaveBar>=(0 until 45).map { i ->
        val lo=95.0+0.25*i
        WilliamsWaveBar(i*4*h,lo+2,lo+5,lo,lo+3,2.0)
    }
    private fun accelerated(strong:Boolean):List<WilliamsWaveBar> {
        val rows=slowH1().toMutableList()
        val last=if(strong) listOf(
            doubleArrayOf(118.0,130.0,116.0,128.0),
            doubleArrayOf(128.0,135.0,124.0,133.0),
            doubleArrayOf(133.0,140.0,129.0,139.0)
        ) else listOf(
            doubleArrayOf(118.0,125.0,116.0,122.0),
            doubleArrayOf(122.0,126.0,118.0,123.0),
            doubleArrayOf(123.0,127.0,120.0,125.0)
        )
        last.forEachIndexed { i,x ->
            val n=177+i
            rows[n]=WilliamsWaveBar(n*h,x[0],x[1],x[2],x[3],1.0)
        }
        return rows
    }
    private fun mirror(bars:List<WilliamsWaveBar>):List<WilliamsWaveBar> =
        bars.map { b->WilliamsWaveBar(b.t,250-b.o,250-b.l,250-b.h,250-b.c,b.v) }
    private fun choice(side:String,strong:Boolean):Tc2AdaptiveBarChoice {
        val first=accelerated(strong);val senior=h4()
        return chooseTc2AdaptiveBarWindow(side,approved,
            if(side=="LONG") first else mirror(first),
            if(side=="LONG") senior else mirror(senior))
    }
    private fun live(side:String,strong:Boolean):WilliamsStopTrailReview {
        val first=accelerated(strong);val senior=h4()
        return reviewWilliamsTc2StopTightening(
            direction=side,oldStop=if(side=="LONG")90.0 else 160.0,
            entry=if(side=="LONG")105.0 else 145.0,
            initialStop=if(side=="LONG")90.0 else 160.0,
            mark=if(side=="LONG")145.0 else 105.0,
            tick=0.01,openedAtMs=approved,asOfMs=now,
            h1Input=if(side=="LONG") first else mirror(first),
            h4Input=if(side=="LONG") senior else mirror(senior))
    }

    @Test fun strongH1AndAlignedH4ActuallyExecuteThreeBarBookWindowForLongAndShort() {
        for(side in listOf("LONG","SHORT")) {
            val decision=choice(side,true)
            assertEquals(side,3,decision.window)
            assertTrue(decision.h1MoveInRanges>=1.25)
            assertTrue(decision.favorableH4)
            val reviewed=live(side,true)
            assertNotNull(side+" "+reviewed.blocker,reviewed.proposal)
            assertEquals("TC2_LAST_3_CLOSED_BARS",reviewed.evidence.getString("source_rule"))
            assertEquals(3,reviewed.evidence.getInt("tc2_live_bar_window"))
            assertEquals(3,reviewed.evidence.getJSONObject("tc2_live_345_bar_choice")
                .getInt("chosen_window"))
            assertTrue(reviewed.evidence.getBoolean("chosen_window_full_h1_bars_after_entry"))
            assertFalse(reviewed.evidence.getJSONObject("tc2_live_345_bar_choice")
                .getBoolean("can_authorize_exchange_order_by_itself"))
            assertTrue(reviewed.evidence.getBoolean("existing_exchange_stop_retained_if_blocked"))
            val proposal=reviewed.proposal!!
            assertTrue(proposal.reason.startsWith("TC2_LAST_3_CLOSED_BARS"))
            assertEquals(if(side=="LONG")115.99 else 134.01,proposal.stop,1e-7)
        }
    }

    @Test fun moderateH1AndAlignedH4ActuallyExecuteFourBarBookWindowForBothSides() {
        for(side in listOf("LONG","SHORT")) {
            val decision=choice(side,false)
            assertEquals(side,4,decision.window)
            assertTrue(decision.h1MoveInRanges>=0.60)
            assertTrue(decision.h1MoveInRanges<1.25)
            val reviewed=live(side,false)
            assertNotNull(side+" "+reviewed.blocker,reviewed.proposal)
            assertEquals("TC2_LAST_4_CLOSED_BARS",reviewed.evidence.getString("source_rule"))
            assertEquals(4,reviewed.evidence.getInt("tc2_live_bar_window"))
        }
    }

    @Test fun slowWeakOrOpposingH4KeepsWiderFiveBarModeWithoutFakingBookRules() {
        for(side in listOf("LONG","SHORT")) {
            val h1=if(side=="LONG")slowH1() else mirror(slowH1())
            val senior=if(side=="LONG")h4() else mirror(h4())
            val mode=chooseTc2AdaptiveBarWindow(side,approved,h1,senior)
            assertEquals(5,mode.window)
            assertTrue(mode.reason.contains("KEEP_FIVE"))
            val report=reviewWilliamsTc2StopTightening(
                side,if(side=="LONG")90.0 else 160.0,
                if(side=="LONG")105.0 else 145.0,
                if(side=="LONG")90.0 else 160.0,
                if(side=="LONG")145.0 else 105.0,0.01,approved,now,h1,senior)
            assertNotNull(report.proposal)
            assertEquals("TC2_LAST_5_CLOSED_BARS",report.evidence.getString("source_rule"))
            val opposing=senior.toMutableList()
            val last=opposing.last()
            opposing[opposing.lastIndex]=if(side=="LONG")
                last.copy(h=last.h-3,l=last.l-3,c=last.c-3)
                else last.copy(h=last.h+3,l=last.l+3,c=last.c+3)
            assertEquals(5,chooseTc2AdaptiveBarWindow(side,approved,h1,opposing).window)
        }
    }

    @Test fun missingPostEntryBarsOrTamperedCandlesCannotPromoteThreeOrFour() {
        val h1=accelerated(true)
        assertEquals(5,chooseTc2AdaptiveBarWindow("LONG",178*h,h1,h4()).window)
        assertEquals(5,chooseTc2AdaptiveBarWindow("LONG",approved,h1,h4().dropLast(1)).window)
        val broken=h1.toMutableList()
        broken[broken.lastIndex]=broken.last().copy(t=broken.last().t+h)
        val uncertain=chooseTc2AdaptiveBarWindow("LONG",approved,broken,h4())
        assertEquals(5,uncertain.window)
        assertTrue(uncertain.reason.contains("NOT_VERIFIED"))
        val invalid=chooseTc2AdaptiveBarWindow("LONG",approved,
            h1.dropLast(1)+h1.last().copy(c=Double.NaN),h4())
        assertEquals(5,invalid.window)
        assertEquals(5,chooseTc2AdaptiveBarWindow("SHORT",approved,h1,h4()).window)
    }

    @Test fun eachBookBarWindowUsesClosedExtremesAndNeverWidensSignedStop() {
        val bars=accelerated(true).map { FuturesTrailBar(it.t,it.h,it.l) }
        val s3=planWilliamsCampaignStop("LONG",90.0,105.0,90.0,145.0,0.01,
            approved,bars,window=3)
        val s4=planWilliamsCampaignStop("LONG",90.0,105.0,90.0,145.0,0.01,
            approved,bars,window=4)
        val s5=planWilliamsCampaignStop("LONG",90.0,105.0,90.0,145.0,0.01,
            approved,bars,window=5)
        assertNotNull(s3);assertNotNull(s4);assertNotNull(s5)
        assertEquals("TC2_LAST_3_CLOSED_BARS",s3!!.reason)
        assertEquals("TC2_LAST_4_CLOSED_BARS",s4!!.reason)
        assertEquals("TC2_LAST_5_CLOSED_BARS",s5!!.reason)
        assertTrue(s3.stop>=s4.stop && s4.stop>=s5.stop)
        for(window in 3..5) {
            val noWiden=planWilliamsCampaignStop("LONG",140.0,105.0,90.0,
                145.0,0.01,approved,bars,window=window)
            assertNull("Already tighter signed stop cannot be loosened ($window)",noWiden)
        }
    }
}

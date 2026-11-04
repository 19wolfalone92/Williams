package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * NTD source pp.115-120, figs 9-2..9-6.
 * Lips is a selected NEXT-bar close-only regime, not another Binance STOP.
 */
class WilliamsNtdTeethLipsSourceTest {
    private fun bars(closes:List<Double>,side:String):List<WilliamsWaveBar> =
        closes.mapIndexed { i,c ->
            val b=WilliamsWaveBar((i+1L)*WILLIAMS_H1_MS,c,c+1,c-1,c,100.0)
            if(side=="LONG") b else b.copy(o=300-b.o,h=300-b.l,
                l=300-b.h,c=300-b.c)
        }

    private fun plan(
        series:List<WilliamsWaveBar>,
        side:String,
        openedAt:Long=WILLIAMS_H1_MS,
        state:JSONObject=JSONObject()
    ):WilliamsNtdManagement {
        val n=series.size
        return williamsNtdManagementPlan(
            side,series,
            List(n){if(side=="LONG") 90.0 else 210.0},
            List(n){if(side=="LONG") 102.0 else 198.0},
            List(n){0.0},List(n){0.0},0.1,openedAt,state
        )
    }

    @Test fun fastLipsNeedsCompletedSelectionThenOneMoreClosedH1() {
        for(side in listOf("LONG","SHORT")) {
            val base=bars(listOf(100.0,101.0,102.0,103.0,104.0,105.0),side)
            val first=plan(base,side)
            assertEquals("",first.exitReason)
            assertNull(first.stop)
            assertEquals(base.last().t,first.state.getLong("lips_selected_at"))
            assertEquals("",plan(base,side,state=first.state).exitReason)
            val next=bars(listOf(100.0,101.0,102.0,103.0,104.0,105.0,101.0),side)
            val second=plan(next,side,state=first.state)
            assertEquals(side,"LIPS_CLOSE_EXIT",second.exitReason)
            assertNull(second.stop)
        }
    }

    @Test fun teethSourceDoesNotExitOnWickAndDoesOnCompletedClose() {
        for(side in listOf("LONG","SHORT")) {
            val source=bars(List(6){100.0},side).toMutableList()
            val last=source.last()
            source[source.lastIndex]=if(side=="LONG") last.copy(l=80.0)
                else last.copy(h=220.0)
            val observed=plan(source,side)
            assertEquals(side,"",observed.exitReason)
            assertFalse(observed.state.has("lips_selected_at"))
            val close=plan(bars(List(5){100.0}+89.0,side),side)
            assertEquals(side,"TEETH_CLOSE_EXIT",close.exitReason)
        }
    }

    @Test fun previousCampaignLipsStateCannotForceFreshOwnerExit() {
        for(side in listOf("LONG","SHORT")) {
            val previous=bars(listOf(100.0,101.0,102.0,103.0,104.0,105.0),side)
            val selected=plan(previous,side).state
            assertEquals(previous.last().t,selected.getLong("lips_selected_at"))
            val current=bars(listOf(100.0,101.0,102.0,103.0,104.0,105.0,101.0,101.0),side)
            val fresh=plan(current,side,previous.last().t+WILLIAMS_H1_MS,selected)
            assertEquals(side,"",fresh.exitReason)
            assertFalse(side,fresh.state.has("lips_selected_at"))
        }
    }

    @Test fun equallySpacedButNonUtcAlignedH1CannotSupplyBookExitProof() {
        for(side in listOf("LONG","SHORT")) {
            val source=bars(listOf(100.0,101.0,102.0,103.0,104.0,105.0),side)
            val bad=source.map { it.copy(t=it.t+60_000L) }
            val observed=plan(bad,side)
            assertEquals(side,"",observed.exitReason)
            assertNull(observed.stop)
            assertEquals("NTD_H1_HISTORY_GAP",
                observed.state.getString("zone_book_evidence"))
        }
    }
}

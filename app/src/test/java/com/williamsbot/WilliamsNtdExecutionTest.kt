package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsNtdExecutionTest {
    @Test fun sharedAoSignalsSurviveUnhitBarsButNeverColourRecoveryOrConsumedLevels() {
        val cases=JSONObject(javaClass.getResourceAsStream("/williams_ntd_ao_lifetime_contract.json")!!
            .bufferedReader().use { it.readText() }).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val osc=c.getJSONArray("ao")
            val ao=(0 until osc.length()).map { osc.getDouble(it) };val short=c.getString("side")=="SHORT"
            val original=ao.indices.map { j ->
                val high=if(j==44 || j==45 && c.getBoolean("crossed")) (if(j==45) 111.0 else 110.0)
                    else if(j>44) 109.0 else 105.0
                WilliamsWaveBar((480000L+j)*WILLIAMS_H1_MS,100.0,high,if(j>=44) 90.0 else 95.0,100.0,10.0)
            }
            val bars=if(short) original.map { it.copy(o=200-it.o,h=200-it.l,l=200-it.h,c=200-it.c) } else original
            val found=williamsNtdSetups(c.getString("side"),bars,List(ao.size){if(short) 105.0 else 95.0},
                List(ao.size){if(short) 104.0 else 96.0},List(ao.size){if(short) 103.0 else 97.0},ao,List(ao.size){0.0},.1)
                .any { it.kind==c.getString("family") && it.knownIndex==44 }
            assertEquals(c.getString("id"),c.getBoolean("valid"),found)
        }
    }
    @Test fun sharedClosedEventContractChecksActualExecutionQuotesAndDeadline() {
        val resource=javaClass.getResourceAsStream("/williams_ntd_execution_contract.json")!!
        val cases=JSONObject(resource.bufferedReader().use { it.readText() }).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            val blocker=williamsNtdCloseEntryBlocker(c.getString("direction"),c.getLong("signal_at"),
                c.getDouble("reference"),c.getDouble("bound"),c.getLong("now"),c.getDouble("contract"),
                c.getDouble("mark"),c.getDouble("bid"),c.getDouble("ask"),c.getDouble("tick"))
            assertEquals(c.getString("name")+": "+blocker,c.getBoolean("allowed"),blocker==null)
        }
    }
    @Test fun dimensionsNeedAnOwnedFractalCycleOrAuthenticatedProfitableReentry() {
        for(side in listOf("LONG","SHORT")) for(family in williamsNtdFamilies) {
            val type=if(side=="SHORT") "SHORT_$family" else family
            assertFalse(williamsBookSignalRoleAllowed(williamsNtdProfile,type,"ADD_ON"))
            assertTrue(williamsBookSignalRoleAllowed(williamsNtdProfile,type,"ADD_ON","owned"))
            assertTrue(williamsBookSignalRoleAllowed(williamsNtdProfile,type,"ADD_ON",reentryOwner="profitable"))
            assertEquals(family=="FRACTAL",williamsBookSignalRoleAllowed(williamsNtdProfile,type,"ENTRY"))
        }
        assertFalse(williamsBookSignalRoleAllowed(williamsNtdProfile,"REVERSAL","ENTRY","owned"))
        assertNotNull(williamsNtdFirstEntryBlocker("{}","FRACTAL"))
        assertNotNull(williamsNtdFirstEntryBlocker("{}","AO_SAUCER"))
    }
    @Test fun eachNtdDimensionUsesTheProtectedCampaignStopWithoutImportingTc2Families() {
        val at=480000L*WILLIAMS_H1_MS;val now=at+WILLIAMS_H1_MS+1000
        for(side in listOf("LONG","SHORT")) for(family in williamsNtdFamilies) {
            val type=if(side=="SHORT") "SHORT_$family" else family
            val market=family=="ZONE_ADD_ON";val semantics=if(market) "MARKET_ON_CLOSE" else "STOP_ENTRY"
            val trigger=100.0;val stop=if(side=="LONG") 95.0 else 105.0
            val bound=if(market) williamsNtdCloseEntryBound(side,trigger,.1)!! else trigger
            val proof=williamsBookH1Proof("n",side,type,williamsNtdProfile,at,trigger,stop,.1,"owner",stop,
                semantics,if(market) trigger else 0.0)
            val setup=JSONObject().put("book_h1_entry",proof).toString()
            assertNull(type,williamsBookH1ProofBlocker(setup,side,"n",type,williamsNtdProfile,bound,stop,now,"owner",
                executionSemantics=semantics,referenceClose=if(market) trigger else 0.0))
            assertNotNull(williamsBookH1ProofBlocker(setup,side,"n",type,williamsNtdProfile,bound,stop,now,"foreign",
                executionSemantics=semantics,referenceClose=if(market) trigger else 0.0))
            assertNotNull(williamsBookH1ProofBlocker(setup,side,"n",type,williamsTc2Profile,bound,stop,now,"owner"))
        }
    }
    @Test fun preentryBarCannotSelectAnExitOrTighterStopForANewPosition() {
        val at=480000L*WILLIAMS_H1_MS
        for(short in listOf(false,true)) {
            val side=if(short) "SHORT" else "LONG"
            val bars=(0..5).map { i -> WilliamsWaveBar(at+i*WILLIAMS_H1_MS,100.0,101.0,99.0,100.0,1.0) }
            val line=List(6) { if(short) 90.0 else 110.0 }
            val opened=bars.last().t+WILLIAMS_H1_MS
            val p=williamsNtdManagementPlan(side,bars,line,line,List(6){0.0},List(6){0.0},.1,opened,JSONObject())
            assertEquals("",p.exitReason);assertNull(p.stop);assertEquals(0,p.state.length())
            val later=bars+bars.last().copy(t=opened)
            val next=williamsNtdManagementPlan(side,later,line+line.last(),line+line.last(),List(7){0.0},List(7){0.0},.1,opened,JSONObject())
            assertEquals("TEETH_CLOSE_EXIT",next.exitReason)
        }
    }
}

package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc2ReversalTest {
    private fun fixture()=JSONObject(javaClass.classLoader!!.getResource("williams_tc2_reversal_contract.json")!!.readText())

    @Test fun sharedTwoLegContractRejectsUnconfirmedFlatReplayAndPriceChasing() {
        val f=fixture();val examples=f.getJSONArray("examples");val cases=f.getJSONArray("cases")
        for(i in 0 until examples.length()) for(j in 0 until cases.length()) {
            val d=JSONObject(examples.getJSONObject(i).toString());val test=cases.getJSONObject(j)
            val ledger=d.getJSONArray("ledger");var now=d.getLong("now")
            val ownerId=d.getString("owner_id");val c=williamsTc2ReversalCandidate(ledger,ownerId,now)!!
            val owner=ledger.getJSONObject(0);val exit=ledger.getJSONObject(1)
            val proof=owner.getJSONObject("edition_management").getJSONObject("exit_evidence")
            var stop=c.getDouble("protective_stop");var signalId=c.getString("signal_id")
            var contract=d.getDouble("contract");var entry=d.getDouble("entry")
            val short=c.getString("direction")=="SHORT"
            when(test.getString("id")) {
                "partial-exit" -> owner.put("campaign_status","EXIT_PENDING")
                "close-reply-only" -> exit.put("exchange_status","FILLED")
                "partial-quantity" -> exit.put("executed_qty",".5")
                "future-flat" -> exit.put("confirmed_flat_at_ms",now+1)
                "stale" -> now+=121_000L
                "ambiguous" -> proof.getJSONObject("opposition_check").put("uncertain",JSONArray().put(JSONObject().put("state","AMBIGUOUS")))
                "wrong-source" -> proof.getJSONObject("reverse_candidate").put("strategy_profile",williamsNtdProfile)
                "stop-changed" -> stop+=1
                "trigger-recovered" -> contract+=if(short) 1 else -1
                "chasing" -> contract+=if(short) -1 else 1
                "unbounded-entry" -> entry=if(short) 110.1 else 89.9
                "wrong-signal" -> signalId="foreign"
                "unverified-owner" -> owner.put("strategy_ownership_verified",false)
                "foreign-campaign","already-admitted" -> ledger.put(JSONObject().put("intent_role","ENTRY").put("symbol","BTCUSDT")
                    .put("client_id","new-owner").put("reversal_owner_id",if(test.getString("id")=="already-admitted") ownerId else ""))
                "missing-fill" -> exit.remove("executed_qty")
                "mark-proof" -> proof.getJSONObject("opposition_check").put("trigger_price_basis","MARK_PRICE")
            }
            val result=williamsTc2ReversalEntryBlocker(ledger,ownerId,"BTCUSDT",c.getString("direction"),signalId,
                c.getString("signal_type"),c.getLong("signal_bar_time_ms"),c.getString("client_id"),entry,stop,now,
                contract,d.getDouble("mark"),d.getDouble("tick"))
            assertEquals(d.getString("id")+":"+test.getString("id"),if(test.isNull("blocker")) null else test.getString("blocker"),result)
        }
    }

    @Test fun lostCloseReplyCanResumeButNewIntentConsumesOwnerOnce() {
        val d=fixture().getJSONArray("examples").getJSONObject(0)
        val a=d.getJSONArray("ledger");a.getJSONObject(0).remove("close_reason")
        val id=d.getString("owner_id");val now=d.getLong("now")
        val c=williamsTc2ReversalCandidate(a,id,now)!!
        a.put(JSONObject().put("intent_role","ENTRY").put("symbol","BTCUSDT")
            .put("client_id",c.getString("client_id")).put("reversal_owner_id",id).put("order_status","SUBMISSION_UNKNOWN"))
        assertNull(williamsTc2ReversalCandidate(a,id,now))
        assertNotNull(williamsTc2ReversalCandidate(a,id,now,c.getString("client_id")))
    }
}

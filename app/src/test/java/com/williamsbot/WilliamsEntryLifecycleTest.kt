package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEntryLifecycleTest {
    private fun contract()=JSONObject(requireNotNull(javaClass.classLoader?.getResourceAsStream("williams_entry_lifecycle_contract.json")).bufferedReader().use { it.readText() })
    private class Journal:FuturesCampaignStateStore {
        val values=mutableMapOf<String,String>()
        override fun readState(key:String)=values[key]
        override fun writeState(key:String,value:String):Boolean { values[key]=value;return true }
    }
    @Test fun sharedPolicyBoundariesMatchPython() {
        val cases=contract().getJSONArray("time_cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            assertEquals(c.toString(),c.getBoolean("allowed"),WilliamsEntryTimePolicy.fromJson(c.getJSONObject("policy")).allows(c.getLong("now")))
        }
        assertFalse(WilliamsEntryTimePolicy().allows(0))
        assertFalse(WilliamsEntryTimePolicy().allows(-1))
    }
    @Test fun invalidPoliciesCannotWidenHours() {
        for(p in listOf(JSONObject().put("mode","AUTO"),JSONObject().put("start_utc","8:00"),JSONObject().put("end_utc","24:00"),
            JSONObject().put("mode","UTC_WINDOW").put("start_utc","08:00").put("end_utc","08:00"))) {
            try { WilliamsEntryTimePolicy.fromJson(p);fail("Invalid policy accepted: $p") } catch(expected:IllegalArgumentException) { }
        }
    }
    @Test fun sharedLifecycleDistinguishesFormationAndExchangeState() {
        val cases=contract().getJSONArray("lifecycle_cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            assertEquals(c.getString("name"),c.getString("state"),williamsSignalLifecycle(c.getJSONObject("signal"),c.optJSONObject("owner"),c.getLong("now"),c.getLong("snapshot_at_ms"),c.getBoolean("current")))
        }
    }
    @Test fun restartPreservesTimesTriggerAndReasonsWithoutOrderAuthority() {
        val store=Journal();val journal=WilliamsSignalJournal(store);val c=contract().getJSONArray("lifecycle_cases").getJSONObject(1)
        val now=c.getLong("now");val fact=c.getJSONObject("signal");val symbol="BTCUSDT"
        assertTrue(journal.observe(symbol,JSONArray().put(fact),now,WilliamsEntryTimePolicy("UTC_WINDOW","13:00","14:00")))
        journal.decision(symbol,"RUNTIME_PREFLIGHT","HTF confirmation gate blocked",now+1,fact.getString("signal_id"))
        journal.decision(symbol,"RUNTIME_PREFLIGHT","PORTFOLIO_RISK_EXHAUSTED",now+2,fact.getString("signal_id"))
        journal.observe(symbol,JSONArray().put(fact),now+30_000,WilliamsEntryTimePolicy())
        val restarted=WilliamsSignalJournal(store).view(symbol,JSONArray(),now+30_000)
        val saved=restarted.getJSONArray("signals").getJSONObject(0)
        assertEquals(now,saved.getLong("first_seen_at_ms"));assertEquals("VALID",saved.getString("state"))
        assertTrue(saved.getLong("formation_at_ms")<saved.getLong("known_at_ms"));assertEquals(101.0,saved.getDouble("trigger"),0.0)
        val events=restarted.getJSONArray("events");val categories=(0 until events.length()).map { events.getJSONObject(it).getString("reason_category") }.toSet()
        assertTrue(categories.containsAll(setOf("ENTRY_TIME","HTF_CONTEXT","RISK")))
        assertEquals(1,(0 until events.length()).count { events.getJSONObject(it).getString("stage")=="FORMATION" })
        assertFalse(saved.getBoolean("can_place_orders"));assertEquals("RUNTIME_GATE",williamsReasonCategory("RUNTIME_NOT_READY"))
        repeat(270) { journal.decision(symbol,"EXCHANGE","ORDER_REJECTED_$it api_key=secret-example",now+it) }
        val bounded=journal.view(symbol,JSONArray(),now+300)
        assertEquals(256,bounded.getJSONArray("events").length());assertFalse(bounded.toString().contains("secret-example"))
    }
    @Test fun chartNeverPromotesAFormationOrStaleCancelledOrCrossedOrderToAPlan() {
        val c=contract().getJSONArray("lifecycle_cases").getJSONObject(1);val now=c.getLong("now");val store=Journal()
        val journal=WilliamsSignalJournal(store);val fact=c.getJSONObject("signal");journal.observe("BTCUSDT",JSONArray().put(fact),now,WilliamsEntryTimePolicy())
        fun chart(owners:JSONArray,t:Long=now,price:Double=100.0)=williamsChartTrigger(JSONObject().put("BTCUSDT",JSONObject().put("signal_history",journal.view("BTCUSDT",owners,t))).toString(),"BTCUSDT",t,price)
        assertNull(chart(JSONArray()).trigger)
        val owner=JSONObject().put("symbol","BTCUSDT").put("signal_id",fact.getString("signal_id")).put("order_status","NEW")
            .put("campaign_status","ENTRY_PENDING").put("last_reconciled_ms",now).put("client_id","exchange-1").put("entry_price","101").put("protective_stop","95")
        assertEquals(101.0,requireNotNull(chart(JSONArray().put(owner)).trigger),0.0)
        assertNull(chart(JSONArray().put(owner),now+120_001).trigger)
        assertEquals("TRIGGER_CROSSED_AWAITING_EXCHANGE",chart(JSONArray().put(owner),price=102.0).state)
        owner.put("order_status","CANCELED").put("cancel_reason","OUTSIDE_CONFIGURED_ENTRY_TIME");assertNull(chart(JSONArray().put(owner)).trigger)
        journal.orders(JSONArray().put(owner),now+1)
        val cancelled=journal.view("BTCUSDT",JSONArray().put(owner),now+1).getJSONArray("events")
        val last=cancelled.getJSONObject(cancelled.length()-1)
        assertEquals("OUTSIDE_CONFIGURED_ENTRY_TIME",last.getString("reason"));assertEquals("ENTRY_TIME",last.getString("reason_category"))
        journal.observe("BTCUSDT",JSONArray(),now+1,WilliamsEntryTimePolicy())
        assertEquals("CANCELLED",journal.view("BTCUSDT",JSONArray().put(owner),now+1).getJSONArray("signals").getJSONObject(0).getString("state"))
    }
}

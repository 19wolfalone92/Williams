package com.williamsbot

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real coordinator + real signed transport; only persistence/network peers are modeled. */
class NativeFuturesCoordinatorLifecycleTest {
    private class Journal : FuturesCampaignStateStore {
        private val values = mutableMapOf<String, String>()
        var rejectWrites = false
        var rejectDecisionWrites = false
        @Synchronized override fun readState(key: String): String? = values[key]
        @Synchronized override fun writeState(key: String, value: String): Boolean {
            if (rejectWrites || rejectDecisionWrites && key.startsWith("decision_execution_")) return false
            values[key] = value
            return true
        }
    }

    private class Exchange(val direction: NativeFuturesCampaignCoordinator.Direction) {
        val requests = mutableListOf<Request>()
        val algos = linkedMapOf<String, JSONObject>()
        val orders = linkedMapOf<String, JSONObject>()
        var quantity = 0.0
        var entry = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) 101.0 else 99.0
        var mark = 100.0
        var liquidation = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) "50" else "200"
        var leverage = 2
        var badConfirmation = false
        var loseCloseReply = false
        var loseAdditionReply = false
        var loseCancelReply = false
        var afterLeverage:(()->Unit)?=null
        var additionFraction = 1.0
        var closeFraction = 1.0
        var closeStatus = "FILLED"
        var positionSide = "BOTH"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val p = params(request)
            var code = 200
            val response: Any = when (request.url.encodedPath) {
                "/fapi/v1/time" -> JSONObject().put("serverTime", System.currentTimeMillis())
                "/fapi/v1/positionSide/dual" -> JSONObject().put("dualSidePosition", false)
                "/fapi/v3/account" -> JSONObject().put("canTrade", true).put("totalWalletBalance", "10000")
                    .put("totalMarginBalance", "10000").put("totalUnrealizedProfit", "0")
                    .put("availableBalance", "10000").put("assets", JSONArray())
                "/fapi/v3/positionRisk" -> positions()
                "/fapi/v1/premiumIndex" -> JSONObject().put("symbol", "BTCUSDT").put("markPrice", mark.toString())
                "/fapi/v1/exchangeInfo" -> JSONObject("""{"symbols":[{"symbol":"BTCUSDT","status":"TRADING","filters":[
                    {"filterType":"LOT_SIZE","minQty":"0.001","maxQty":"1000","stepSize":"0.001"},
                    {"filterType":"MARKET_LOT_SIZE","minQty":"0.001","maxQty":"1000","stepSize":"0.001"},
                    {"filterType":"PRICE_FILTER","minPrice":"0.1","maxPrice":"1000000","tickSize":"0.1"},
                    {"filterType":"MIN_NOTIONAL","notional":"5"}]}]}""")
                "/fapi/v1/leverageBracket" -> JSONObject("""{"symbol":"BTCUSDT","brackets":[
                    {"notionalFloor":0,"notionalCap":1000000,"initialLeverage":125,"maintMarginRatio":0.005}]}""")
                "/fapi/v1/marginType" -> JSONObject().put("code", 200)
                "/fapi/v1/leverage" -> {
                    leverage = p.getValue("leverage").toInt()
                    afterLeverage?.invoke()
                    JSONObject().put("symbol", "BTCUSDT").put("leverage", if (badConfirmation) 1 else leverage)
                        .put("maxNotionalValue", "1000000")
                }
                "/fapi/v1/symbolConfig" -> JSONArray().put(JSONObject().put("symbol", "BTCUSDT")
                    .put("marginType", "ISOLATED").put("leverage", leverage).put("maxNotionalValue", "1000000"))
                "/fapi/v1/openOrders" -> JSONArray(orders.values.filter { it.optString("status") in setOf("NEW", "PARTIALLY_FILLED") })
                "/fapi/v1/openAlgoOrders" -> JSONArray(algos.values.filter { it.optString("algoStatus") == "NEW" })
                "/fapi/v1/algoOrder" -> when (request.method) {
                    "POST" -> {
                        val id = (algos.size + 1).toString()
                        val row = JSONObject(p).put("algoId", id).put("algoStatus", "NEW")
                            .put("orderType", p.getValue("type")).put("actualOrderId", "")
                        algos[id] = row
                        row
                    }
                    else -> {
                        val row = algos.values.singleOrNull { it.optString("algoId") == p["algoId"] ||
                            it.optString("clientAlgoId") == p["clientAlgoId"] }
                        if (row == null) { code = 400; JSONObject().put("code", -2013).put("msg", "Order does not exist.") }
                        else { if (request.method == "DELETE") row.put("algoStatus", "CANCELED")
                            if(request.method=="DELETE" && loseCancelReply) { code=503;JSONObject().put("code",-1000).put("msg","lost cancellation reply") } else row }
                    }
                }
                "/fapi/v1/order" -> if (request.method == "POST" && p["reduceOnly"]!="true") {
                    check(p["side"]==if(quantity>0 || quantity==0.0 && direction.name=="LONG") "BUY" else "SELL")
                    val added=p.getValue("quantity").toDouble()*additionFraction
                    val id=(100+orders.size).toString()
                    val old=kotlin.math.abs(quantity)
                    entry=(entry*old+mark*added)/(old+added)
                    quantity=(quantity.toString().toBigDecimal()+(if(quantity>0 || quantity==0.0 && direction.name=="LONG") added else -added).toString().toBigDecimal()).toDouble()
                    val row=JSONObject(p).put("orderId",id).put("status",if(additionFraction==1.0) "FILLED" else "PARTIALLY_FILLED")
                        .put("clientOrderId",p.getValue("newClientOrderId")).put("origQty",p.getValue("quantity"))
                        .put("executedQty",added.toString()).put("avgPrice",mark.toString())
                    orders[id]=row
                    if(loseAdditionReply) { code=503;JSONObject().put("code",-1000).put("msg","lost addition reply") } else row
                } else if (request.method == "POST") {
                    check(p["reduceOnly"] == "true")
                    check(p["side"] == if (quantity > 0) "SELL" else "BUY")
                    val requested=p.getValue("quantity").toDouble()
                    check(requested<=kotlin.math.abs(quantity))
                    val exitId=(90+orders.size).toString()
                    val row = JSONObject(p).put("orderId", exitId).put("status", closeStatus)
                        .put("clientOrderId", p.getValue("newClientOrderId"))
                        .put("origQty", requested.toString())
                        .put("executedQty", (requested * closeFraction).toString()).put("avgPrice", mark.toString())
                    orders[exitId] = row
                    quantity=(quantity.toString().toBigDecimal()-(if(quantity>0) 1 else -1).toBigDecimal()*requested.toString().toBigDecimal()*closeFraction.toString().toBigDecimal()).toDouble()
                    if (loseCloseReply) { code = 503; JSONObject().put("code", -1000).put("msg", "lost close reply") } else row
                } else {
                    orders.values.singleOrNull { it.optString("orderId") == p["orderId"] ||
                        it.optString("clientOrderId") == p["origClientOrderId"] }
                        ?.also { if (request.method == "DELETE") it.put("status", "CANCELED") }
                        ?: run { code = 400; JSONObject().put("code", -2013).put("msg", "Order does not exist.") }
                }
                else -> throw AssertionError("Unexpected exchange route ${request.method} ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("model")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()

        fun positions() = JSONArray().apply {
            if (quantity != 0.0) put(JSONObject().put("symbol", "BTCUSDT").put("positionSide", positionSide)
                .put("positionAmt", quantity.toString()).put("entryPrice", entry.toString())
                .put("markPrice", mark.toString()).put("liquidationPrice", liquidation))
        }
        fun fill(fraction: Double = 1.0, childClientId: String? = null) {
            val pending = algos.values.single { it.optString("closePosition") != "true" }
            entry = pending.getString("triggerPrice").toDouble()
            quantity = pending.getString("quantity").toDouble() * fraction *
                if (pending.getString("side") == "BUY") 1.0 else -1.0
            mark = if (quantity > 0) 102.0 else 98.0
            pending.put("algoStatus", "FINISHED").put("actualOrderId", "42")
            orders["42"] = JSONObject().put("symbol", "BTCUSDT").put("orderId", "42")
                .put("clientOrderId", childClientId ?: pending.getString("clientAlgoId"))
                .put("status", if (fraction == 1.0) "FILLED" else "PARTIALLY_FILLED")
                .put("origQty", pending.getString("quantity")).put("executedQty", kotlin.math.abs(quantity).toString())
                .put("side", pending.getString("side")).put("avgPrice", entry.toString())
        }
        fun fillAddition(clientId:String) {
            val parent=algos.values.single { it.optString("clientAlgoId")==clientId }
            val qty=parent.getString("quantity").toDouble();val price=parent.getString("triggerPrice").toDouble()
            val old=kotlin.math.abs(quantity);entry=(entry*old+price*qty)/(old+qty)
            quantity=(quantity.toString().toBigDecimal()+(if(quantity>0) qty else -qty).toString().toBigDecimal()).toDouble();mark=price+(if(quantity>0) 1 else -1)
            val id=(200+orders.size).toString()
            parent.put("algoStatus","FINISHED").put("actualOrderId",id)
            orders[id]=JSONObject().put("symbol","BTCUSDT").put("orderId",id).put("clientOrderId",clientId)
                .put("status","FILLED").put("origQty",qty.toString()).put("executedQty",qty.toString())
                .put("side",parent.getString("side")).put("avgPrice",price.toString())
        }
        fun posts(path: String) = requests.count { it.method == "POST" && it.url.encodedPath == path }
        fun adapter(admission: () -> Unit = {}) = BinanceUsdMFuturesTestnetAdapter("key", "secret", client,
            beforeExposureMutation = admission)
        companion object {
            fun params(r: Request): Map<String, String> = if (r.body is FormBody) {
                val body = r.body as FormBody
                (0 until body.size).associate { body.name(it) to body.value(it) }
            } else r.url.queryParameterNames.associateWith { r.url.queryParameter(it).orEmpty() }
        }
    }

    private fun plan(direction: NativeFuturesCampaignCoordinator.Direction, leverage: Int = 5, prefix: String = "W2E_") =
        NativeFuturesCampaignCoordinator.EntryPlan("BTCUSDT", direction, NativeFuturesCampaignCoordinator.EntryKind.STOP,
            "1", if (direction.name == "LONG") "101" else "99", if (direction.name == "LONG") "95" else "105",
            "${prefix}BTC_${direction.name}_$leverage", 0.002, leverage = leverage)

    private fun open(direction: NativeFuturesCampaignCoordinator.Direction, leverage: Int = 5, prefix: String = "W2E_"):
        Pair<NativeFuturesCampaignCoordinator, Exchange> {
        val exchange = Exchange(direction)
        val journal=Journal()
        val coordinator = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(coordinator.reconcile().toString(), coordinator.status().optBoolean("ready_for_new_entry"))
        val accepted = coordinator.submitPendingEntry(plan(direction, leverage, prefix))
        assertTrue(accepted.toString(), accepted.getBoolean("accepted"))
        val trace=JSONObject(requireNotNull(journal.readState("decision_execution_BTCUSDT")))
        assertEquals("EXECUTION_COORDINATOR",trace.getString("stage"))
        assertTrue(trace.getJSONObject("outcome").getBoolean("accepted"))
        assertEquals(plan(direction,leverage,prefix).clientId,trace.getString("client_id"))
        assertEquals(leverage, exchange.leverage)
        exchange.fill()
        val reconciled = coordinator.reconcile()
        assertTrue(reconciled.toString(), reconciled.getBoolean("ok"))
        assertEquals("OPEN_PROTECTED", coordinator.state.name)
        return coordinator to exchange
    }


    @Test fun passportPreparedCommitPrecedesAnyEntryMutationInBothDirections() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction);val journal=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val events=mutableListOf<JSONObject>()
            coordinator.setEntryPassportAuditSink { event->
                if(event.getString("phase")=="PREPARED") {
                    assertEquals(0,exchange.posts("/fapi/v1/marginType"))
                    assertEquals(0,exchange.posts("/fapi/v1/leverage"))
                    assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
                }
                events+=JSONObject(event.toString());events.size.toLong()
            }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val p=plan(direction).copy(signalId="signal",signalType="FRACTAL",signalReason="H1 fractal")
            assertTrue(coordinator.submitPendingEntry(p).getBoolean("accepted"))
            assertEquals(listOf("PREPARED","COORDINATOR_RETURNED"),events.map { it.getString("phase") })
            assertEquals(1,events.map { it.getString("attempt_id") }.distinct().size)
            assertEquals("signal",events.last().getString("signal_id"))
            assertFalse(events.last().getBoolean("m15_confirmation_required"))
            val trace=JSONObject(journal.readState("decision_execution_BTCUSDT")!!)
            assertEquals(2L,trace.getLong("audit_event_id"))
            assertEquals(p.signalReason,trace.getString("signal_reason"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            exchange.fill()
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val passport=williamsEntryPassport("BTCUSDT",trace,coordinator.intentsSnapshot(),null,
                System.currentTimeMillis()+1000L,true)
            assertTrue(passport.toString(),passport.getBoolean("identity_match"))
            assertEquals(passport.toString(),"EXCHANGE_PROTECTION_OBSERVED",
                passport.getString("protection_observation"))
        }
    }

    @Test fun passportAuditFailureBeforeSubmissionDoesNotReachAnyMutation() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            coordinator.setEntryPassportAuditSink { 0L }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val result=coordinator.submitPendingEntry(plan(direction))
            assertFalse(result.getBoolean("accepted"))
            assertTrue(result.getBoolean("safe_rejection"))
            assertFalse(result.getBoolean("mutation_attempted"))
            assertEquals(0,exchange.requests.count { it.method=="POST" })
            assertEquals("READY",coordinator.state.name)
        }
    }

    @Test fun passportOutcomeFailureAfterAcceptanceNeverRetriesTheFinancialEffect() {
        val direction=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(direction);val journal=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        coordinator.setEntryPassportAuditSink { event->if(event.getString("phase")=="PREPARED") 1L else 0L }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        val result=coordinator.submitPendingEntry(plan(direction))
        assertTrue(result.getBoolean("accepted"))
        assertEquals("OUTCOME_WRITE_FAILED_RECONCILE_REQUIRED",result.getString("passport_audit_status"))
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        exchange.fill()
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertEquals("OPEN_PROTECTED",coordinator.state.name)
    }

    @Test fun thrownAdmissionFailureRemainsUnknownInTheDurablePassport() {
        val direction=NativeFuturesCampaignCoordinator.Direction.SHORT
        val exchange=Exchange(direction);val journal=Journal()
        var failRead=false
        val coordinator=NativeFuturesCampaignCoordinator(journal) {
            check(!failRead) { "lost adapter response" };exchange.adapter()
        }
        val events=mutableListOf<JSONObject>()
        coordinator.setEntryPassportAuditSink { events+=JSONObject(it.toString());events.size.toLong() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        failRead=true
        val failure=runCatching { coordinator.submitPendingEntry(plan(direction)) }.exceptionOrNull()
        assertNotNull("An exception must reach the outer coordinator wrapper",failure)
        assertEquals(listOf("PREPARED","EXECUTION_EXCEPTION_UNVERIFIED"),
            events.map { it.getString("phase") })
        val trace=JSONObject(journal.readState("decision_execution_BTCUSDT")!!)
        assertTrue(trace.getJSONObject("outcome").getBoolean("outcome_unknown"))
        assertTrue(trace.getJSONObject("outcome").isNull("mutation_attempted"))
        assertEquals(0,exchange.requests.count { it.method=="POST" })
    }


    @Test fun triggeringAndTriggeredAreReconciledWithoutDuplicateOrdersAndRecoveredFillsAreProtected() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(status in listOf("TRIGGERING","TRIGGERED")) {
            val exchange=Exchange(direction);val journal=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.algos.values.single().put("algoStatus",status).put("actualOrderId","42")
            // The signed parent may expose its child id before GET /order can see it.
            val observed=coordinator.reconcile()
            assertTrue(observed.toString(),observed.getBoolean("ok"))
            assertEquals("ENTRY_PENDING",coordinator.state.name)
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
            assertEquals(status,coordinator.intentsSnapshot().getJSONObject(0).getString("order_status"))
            val recovered=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(recovered.reconcile().getBoolean("ok"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            exchange.fill(childClientId="exchange-child-42")
            assertTrue(recovered.reconcile().getBoolean("ok"))
            assertEquals("OPEN_PROTECTED",recovered.state.name)
            assertEquals("PROTECTED",recovered.intentsSnapshot().getJSONObject(0).getString("protection_status"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder")) // exactly one entry and one protective stop
        }
    }

    @Test fun unsupportedAlgoStatusStillFailsClosed() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.reconcile();assertTrue(coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG)).getBoolean("accepted"))
        exchange.algos.values.single().put("algoStatus","UNKNOWN_FUTURE_STATUS")
        assertFalse(coordinator.reconcile().getBoolean("ok"))
        assertEquals("RECONCILE_REQUIRED",coordinator.state.name)
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
    }

    @Test fun triggeringOnAnotherSymbolStillRefreshesAndTrailsTheExistingPositionProtection() {
        val side=NativeFuturesCampaignCoordinator.Direction.LONG
        var now=System.currentTimeMillis()
        val exchange=Exchange(side);val journal=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
        coordinator.reconcile();assertTrue(coordinator.submitPendingEntry(plan(side)).getBoolean("accepted"))
        exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
        val before=coordinator.intentsSnapshot().getJSONObject(0).getLong("last_reconciled_ms")
        val pending=JSONObject().put("intent_role","ENTRY").put("symbol","ETHUSDT")
            .put("direction","SHORT").put("kind","STOP").put("client_id","W2E_ETH_TEST_TRIGGERING")
            .put("quantity","0.1").put("entry_price","99").put("protective_stop","105")
            .put("initial_protective_stop","105").put("requested_leverage",5).put("risk_pct",.001)
            .put("order_status","NEW").put("exchange_status","NEW").put("campaign_status","ENTRY_PENDING")
            .put("strategy_profile",williamsTc2Profile).put("strategy_ownership_verified",true).put("created_at_ms",now)
        val rows=coordinator.intentsSnapshot().put(pending)
        journal.writeState("futures_campaign_intents_v2",rows.toString())
        exchange.algos["eth-trigger"]=JSONObject().put("symbol","ETHUSDT").put("algoId","eth-trigger")
            .put("clientAlgoId","W2E_ETH_TEST_TRIGGERING").put("side","SELL").put("algoStatus","TRIGGERING")
            .put("orderType","STOP_MARKET").put("triggerPrice","99").put("quantity","0.1").put("actualOrderId","")
        now+=10_000
        val result=coordinator.reconcile();assertTrue(result.toString(),result.getBoolean("ok"))
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
        val owner=coordinator.intentsSnapshot().getJSONObject(0)
        assertTrue(owner.getLong("last_reconciled_ms")>before)
        assertEquals("PROTECTED",owner.getString("protection_status"))
        val trail=coordinator.trailProtectiveStop("BTCUSDT",side,"96","W2PS_MTF_OTHER_SYMBOL")
        assertTrue(trail.toString(),trail.getBoolean("ok"))
    }

    @Test fun productionMtfGateRejectsH1OnlyPlansBeforeAccountOrOrderMutations() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.requireM15FractalForAutomatedEntries=true;coordinator.reconcile()
        val rejected=coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG))
        assertFalse(rejected.getBoolean("accepted"))
        assertTrue(rejected.getString("blocker").contains("M15_FRACTAL_PROOF_REQUIRED"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"));assertEquals(0,exchange.posts("/fapi/v1/leverage"))
    }

    @Test fun finalM15VerifierRunsAfterLeverageAndCanRejectWithoutPlacingAnEntry() {
        val now=java.time.Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val side=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(side);val journal=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
        coordinator.requireM15FractalForAutomatedEntries=true
        val parent=WilliamsH1Parent("BTCUSDT:1h:REVERSAL:parent","LONG",now-3*WILLIAMS_H1_MS,108.0,90.0)
        val child=WilliamsM15Entry(parent,now-3*WILLIAMS_M15_MS,now,101.0,95.0,94.0,.1)
        var configured=false;var verified=false
        exchange.afterLeverage={configured=true}
        coordinator.m15EntryVerifier={assertTrue(configured);verified=true;error("M15_CONTEXT_CHANGED_DURING_LEVERAGE")}
        coordinator.reconcile()
        val rejected=coordinator.submitPendingEntry(plan(side).copy(signalId=parent.signalId,signalBarTimeMs=parent.signalAt,
            setupContext=JSONObject().put("mtf_entry",child.toJson()).toString()))
        assertTrue(verified);assertFalse(rejected.getBoolean("accepted"))
        assertFalse(rejected.getBoolean("order_mutation_attempted"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals("REJECTED",coordinator.intentsSnapshot().getJSONObject(0).getString("order_status"))
    }

    @Test fun defaultPolicyAdmitsAtNightWhileCustomPolicyRejectsBeforeAnyOrder() {
        for(hour in listOf(1,21)) {
            val now=java.time.Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()+hour*3_600_000L
            val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
            var policy=WilliamsEntryTimePolicy()
            val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
            coordinator.setEntryTimePolicyProvider { policy };assertTrue(coordinator.reconcile().getBoolean("ok"))
            policy=WilliamsEntryTimePolicy("UTC_WINDOW","08:00","20:00")
            val rejected=coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG))
            assertFalse(rejected.getBoolean("accepted"));assertTrue(rejected.getString("blocker").contains("ENTRY_TIME_POLICY_BLOCKED"))
            assertEquals(0,exchange.posts("/fapi/v1/algoOrder"));assertEquals(0,exchange.posts("/fapi/v1/leverage"))
            policy=WilliamsEntryTimePolicy()
            assertTrue(coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG)).getBoolean("accepted"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    @Test fun aNewOpposingH1EventDuringLeverageRevokesM15BeforeAnyEntryPost() {
        val now=java.time.Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        for(side in NativeFuturesCampaignCoordinator.Direction.entries) {
            val short=side==NativeFuturesCampaignCoordinator.Direction.SHORT
            val exchange=Exchange(side)
            val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
            coordinator.requireM15FractalForAutomatedEntries=true
            val base=plan(side)
            val parent=WilliamsH1Parent("parent:"+side.name,side.name,now-3*WILLIAMS_H1_MS,
                if(short) 92.0 else 108.0,if(short) 110.0 else 90.0)
            val child=WilliamsM15Entry(parent,now-3*WILLIAMS_M15_MS,now,
                base.entryPrice.toDouble(),base.protectiveStop.toDouble(),if(short) 106.0 else 94.0,.1)
            var opposingAvailable=false
            exchange.afterLeverage={opposingAvailable=true}
            coordinator.m15EntryVerifier={
                val source=WilliamsScenarioSignal("later",if(short) "LONG" else "SHORT",williamsTc2Profile,
                    "STOP_ENTRY",now-WILLIAMS_H1_MS,if(short) 99.0 else 101.0,
                    if(short) 90.0 else 110.0,now+4*WILLIAMS_H1_MS)
                val assessment=assessWilliamsScenarioOpposition(side.name,parent.knownAt,
                    if(opposingAvailable) listOf(source) else emptyList(),emptyList(),100.0,now)
                check(assessment.blocker==null) { assessment.blocker.orEmpty() }
            }
            coordinator.reconcile()
            val rejected=coordinator.submitPendingEntry(base.copy(signalId=parent.signalId,signalBarTimeMs=parent.signalAt,
                setupContext=JSONObject().put("mtf_entry",child.toJson()).toString()))
            assertTrue(opposingAvailable)
            assertFalse(rejected.toString(),rejected.getBoolean("accepted"))
            assertTrue(rejected.toString(),rejected.getString("blocker").contains("H1_PARENT_OPPOSITE_TRIGGERED"))
            assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        }
    }
    @Test fun finalPolicyChangeDuringAccountConfigurationCannotSubmitAPhantomOrder() {
        val now=java.time.Instant.parse("2026-10-19T21:00:00Z").toEpochMilli()
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG);var policy=WilliamsEntryTimePolicy()
        val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
        coordinator.setEntryTimePolicyProvider { policy };coordinator.reconcile()
        exchange.afterLeverage={ policy=WilliamsEntryTimePolicy("UTC_WINDOW","08:00","20:00") }
        val rejected=coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG))
        assertFalse(rejected.getBoolean("accepted"));assertFalse(rejected.getBoolean("order_mutation_attempted"))
        assertTrue(rejected.getString("error").contains("ENTRY_TIME_POLICY_BLOCKED_BEFORE_SUBMISSION"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals("REJECTED",coordinator.intentsSnapshot().getJSONObject(0).getString("order_status"))
    }
    @Test fun closedPolicyStillAllowsCancellationRecoveryAndFillProtection() {
        for(filled in listOf(false,true)) {
            val now=java.time.Instant.parse("2026-10-19T21:00:00Z").toEpochMilli()
            val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG);var policy=WilliamsEntryTimePolicy()
            val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
            coordinator.setEntryTimePolicyProvider { policy };coordinator.reconcile()
            assertTrue(coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG)).getBoolean("accepted"))
            policy=WilliamsEntryTimePolicy("UTC_WINDOW","08:00","20:00")
            if(filled) {
                exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
                assertEquals("OPEN_PROTECTED",coordinator.state.name)
                assertTrue(exchange.algos.values.any { it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW" })
            } else {
                exchange.loseCancelReply=true
                assertFalse(coordinator.cancelPendingEntries("OUTSIDE_CONFIGURED_ENTRY_TIME").getBoolean("ok"))
                assertEquals("CANCEL_SUBMISSION_UNKNOWN",coordinator.intentsSnapshot().getJSONObject(0).getString("order_status"))
                val deletes=exchange.requests.count { it.method=="DELETE" }
                exchange.loseCancelReply=false
                assertTrue(coordinator.cancelPendingEntries("OUTSIDE_CONFIGURED_ENTRY_TIME").getBoolean("ok"))
                assertEquals(deletes,exchange.requests.count { it.method=="DELETE" })
                assertEquals("CANCELED",coordinator.intentsSnapshot().getJSONObject(0).getString("order_status"))
                assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            }
        }
    }

    @Test fun diagnosticPersistenceFailureDoesNotRetryAnAcceptedOrder() {
        val journal=Journal();val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        journal.rejectDecisionWrites=true
        val accepted=coordinator.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG))
        assertTrue(accepted.toString(),accepted.getBoolean("accepted"))
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        assertNull(journal.readState("decision_execution_BTCUSDT"))
        exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertEquals("OPEN_PROTECTED",coordinator.state.name)
    }

    @Test fun rejectedPlansHaveTheirOwnDecisionProofAndCannotReachTheExchange() {
        val journal=Journal();val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        val invalid=plan(NativeFuturesCampaignCoordinator.Direction.LONG).copy(riskPct=.5)
        val result=coordinator.submitPendingEntry(invalid)
        assertFalse(result.getBoolean("accepted"))
        val trace=JSONObject(requireNotNull(journal.readState("decision_execution_BTCUSDT")))
        assertFalse(trace.getJSONObject("outcome").getBoolean("mutation_attempted"))
        assertTrue(trace.getJSONObject("outcome").getString("blocker").contains("risk"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
    }

    /** Arrange a persisted pre-upgrade filled source; never enable new observer orders. */
    private fun markLegacySource(journal:Journal,coordinator:NativeFuturesCampaignCoordinator,source:String,type:String) {
        val rows=coordinator.intentsSnapshot()
        rows.getJSONObject(0).put("strategy_profile",source).put("signal_type",type)
        assertTrue(journal.writeState("futures_campaign_intents_v2",rows.toString()))
    }

    @Test fun confirmedUnfilledH1CancellationAllowsOneNewAttemptWithANewId() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val parent="BTCUSDT:1h:${direction.name}:FRACTAL"
            val first=plan(direction).copy(clientId=williamsBookH1ClientId("BTCUSDT",parent),signalId=parent)
            assertTrue(coordinator.submitPendingEntry(first).getBoolean("accepted"))
            assertTrue(coordinator.cancelPendingEntries("TEST_TEETH_INVALIDATED","BTCUSDT").getBoolean("ok"))
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val generation=williamsBookH1RetryGeneration(coordinator.intentsSnapshot(),parent)
            assertEquals(1,generation)
            val retry=first.copy(clientId=williamsBookH1ClientId("BTCUSDT",parent,requireNotNull(generation)))
            val admitted=coordinator.submitPendingEntry(retry)
            assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
            assertFalse(coordinator.submitPendingEntry(retry).getBoolean("accepted"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    @Test fun profileSelectionAndEntryAdmissionShareOneAuthorityAndPendingCampaignPreventsSwitching() {
        var selected=williamsTc2Profile
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.setEditionSelectionProvider { selected }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        val stale=plan(NativeFuturesCampaignCoordinator.Direction.LONG)
        coordinator.configureWhenFlat { selected=williamsNtdProfile }
        assertFalse(coordinator.submitPendingEntry(stale).getBoolean("accepted"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertFalse(coordinator.submitPendingEntry(stale.copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL")).getBoolean("accepted"))
        coordinator.configureWhenFlat { selected=williamsTc2Profile }
        assertTrue(coordinator.submitPendingEntry(stale).getBoolean("accepted"))
        try {
            coordinator.configureWhenFlat { selected=williamsCourseProfile }
            fail("A pending campaign must prevent edition changes")
        } catch(expected:IllegalStateException) { assertEquals(williamsTc2Profile,selected) }
    }

    @Test fun severalTc2AdditionsUseSeparateEntryCapsAndOneAggregatePortfolioCap() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val exchange=Exchange(direction);var now=System.currentTimeMillis()
            val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsTc2Profile,signalType="FRACTAL",signalBarTimeMs=now-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            for(i in 1..3) {
                now+=3_600_000L
                val addition=initial.copy(kind=NativeFuturesCampaignCoordinator.EntryKind.STOP,quantity="0.1",
                    entryPrice=(exchange.mark+if(direction.name=="LONG") 1 else -1).toString(),clientId="W2A_MULTI_${direction.name}_$i",riskPct=.001,
                    entryClientId=initial.clientId,signalType="SUPER_AO",executionSemantics="STOP_ENTRY",
                    referenceClose=exchange.mark,signalBarTimeMs=now-3_601_000L)
                val admitted=coordinator.submitPendingEntry(addition)
                assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
                exchange.fillAddition(addition.clientId)
                val verified=coordinator.reconcile()
                assertTrue(verified.toString(),verified.getBoolean("ok"))
            }
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals(.005,futuresCampaignReservedRisk(coordinator.intentsSnapshot().getJSONObject(0),coordinator.intentsSnapshot()),1e-9)
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
        }
    }

    @Test fun legacyProfitableNtdCycleCannotAuthorizeNewObserverRisk() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(market in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            var now=System.currentTimeMillis()
            val coordinator=NativeFuturesCampaignCoordinator(journal,clock={ now }) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL",signalBarTimeMs=now-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial.copy(strategyProfile=williamsTc2Profile)).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsNtdProfile,"FRACTAL")
            assertTrue(coordinator.closePosition("BTCUSDT",direction,"1","W2X_NTD_PROFIT_${direction.name}_$market","TEST_PROFIT").getBoolean("ok"))
            assertTrue(coordinator.recordVerifiedCampaignResult(initial.clientId,1.0,false,2))
            var previous=coordinator.intentsSnapshot().getJSONObject(0)
            now+=7_200_000L
            assertFalse(williamsNtdReentryEligible(previous,"BTCUSDT",direction.name,now-3_601_000L))
            assertTrue(coordinator.recordVerifiedCampaignResult(initial.clientId,1.0,true,2))
            previous=coordinator.intentsSnapshot().getJSONObject(0)
            assertTrue(williamsNtdReentryEligible(previous,"BTCUSDT",direction.name,now-3_601_000L))
            val reentry=initial.copy(clientId="W2E_NTD_RE_${direction.name}_$market",quantity="0.5",riskPct=.001,
                signalType=if(market) "ZONE_ADD_ON" else "AO_SAUCER",signalBarTimeMs=now-3_601_000L,
                reentryOwnerId=initial.clientId,kind=if(market) NativeFuturesCampaignCoordinator.EntryKind.MARKET else NativeFuturesCampaignCoordinator.EntryKind.STOP,
                executionSemantics=if(market) "MARKET_ON_CLOSE" else "STOP_ENTRY",
                referenceClose=if(market) exchange.mark else 0.0,
                entryPrice=(exchange.mark+if(market) 0 else if(direction.name=="LONG") 1 else -1).toString())
            val admitted=coordinator.submitPendingEntry(reentry)
            assertFalse(admitted.toString(),admitted.getBoolean("accepted"))
            assertTrue(admitted.toString().contains("OBSERVATION_ONLY"))
            assertEquals(0.0,exchange.quantity,0.0)
            assertTrue(williamsNtdReentryEligible(coordinator.intentsSnapshot().getJSONObject(0),"BTCUSDT",direction.name,now))
        }
    }

    @Test fun legacyCoursePartialReductionsKeepProtectionAndCannotReExpand() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(lost in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            var coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsCourseProfile,signalType="COURSE_HUMP",
                signalBarTimeMs=System.currentTimeMillis()-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial.copy(strategyProfile=williamsTc2Profile,signalType="FRACTAL")).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsCourseProfile,"COURSE_HUMP")
            val protection=exchange.algos.values.single { it.optString("closePosition")=="true" }.getString("clientAlgoId")
            exchange.loseCloseReply=lost
            val reduced=coordinator.closePosition("BTCUSDT",direction,"0.7","W2X_COURSE_TRIM_${direction.name}_$lost",
                "COURSE_STAGE_TRIM",partialReduction=true)
            if(!lost) assertTrue(reduced.toString(),reduced.getBoolean("ok"))
            coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(.3,kotlin.math.abs(exchange.quantity),1e-8)
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            val trigger=exchange.mark+if(direction.name=="LONG") 1 else -1
            val addition=initial.copy(quantity="0.4",entryPrice=trigger.toString(),riskPct=.001,
                clientId="W2A_COURSE_${direction.name}_$lost",entryClientId=initial.clientId,
                signalBarTimeMs=System.currentTimeMillis()-3_601_000L)
            val admitted=coordinator.submitPendingEntry(addition)
            assertFalse(admitted.toString(),admitted.getBoolean("accepted"))
            assertEquals(.3,kotlin.math.abs(exchange.quantity),1e-8)
            assertEquals(protection,exchange.algos.values.single { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" }.getString("clientAlgoId"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    @Test fun legacyNtdRemainsProtectedAcrossRestartAndRejectsObserverAdditions() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(lost in listOf(false,true)) for(fraction in listOf(.5,1.0)) {
            val journal=Journal();val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(coordinator.reconcile().toString(),coordinator.status().optBoolean("ready_for_new_entry"))
            val initial=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL",
                signalBarTimeMs=System.currentTimeMillis()-3_601_000L)
            assertTrue(coordinator.submitPendingEntry(initial.copy(strategyProfile=williamsTc2Profile)).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsNtdProfile,"FRACTAL")
            val oldProtection=exchange.algos.values.single { it.optString("closePosition")=="true" }.getString("clientAlgoId")
            val leverageCalls=exchange.posts("/fapi/v1/leverage")
            exchange.loseAdditionReply=lost;exchange.additionFraction=fraction
            val addition=initial.copy(kind=NativeFuturesCampaignCoordinator.EntryKind.MARKET,quantity="0.5",
                entryPrice=exchange.mark.toString(),clientId="W2A_NTD_${direction.name}_${lost}_$fraction",riskPct=.001,
                entryClientId=initial.clientId,signalType="ZONE_ADD_ON",executionSemantics="MARKET_ON_CLOSE",
                referenceClose=exchange.mark,signalBarTimeMs=initial.signalBarTimeMs)
            val submitted=coordinator.submitPendingEntry(addition)
            assertFalse(submitted.toString(),submitted.getBoolean("accepted"))
            // New coordinator represents process death after an unknown response.
            val recovered=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val reconciled=recovered.reconcile()
            assertTrue(reconciled.toString(),reconciled.getBoolean("ok"))
            assertEquals(reconciled.toString(),"OPEN_PROTECTED",recovered.state.name)
            assertEquals(1.0,kotlin.math.abs(exchange.quantity),1e-9)
            assertEquals(leverageCalls,exchange.posts("/fapi/v1/leverage"))
            val protection=exchange.algos.values.filter { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" }
            assertEquals(1,protection.size);assertEquals(oldProtection,protection.single().getString("clientAlgoId"))
            assertFalse(recovered.submitPendingEntry(addition).getBoolean("accepted"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            val closed=recovered.closePosition("BTCUSDT",direction,kotlin.math.abs(exchange.quantity).toString(),
                "W2X_NTD_${direction.name}_${lost}_$fraction","TEST_EXIT")
            assertTrue(closed.toString(),closed.getBoolean("ok"))
            assertEquals(0.0,exchange.quantity,0.0)
        }
    }

    @Test fun ntdCloseEventCannotUseAStaleFutureOrMovedPriceOrAnotherEdition() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val (coordinator,exchange)=open(direction)
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            val base=plan(direction).copy(kind=NativeFuturesCampaignCoordinator.EntryKind.MARKET,quantity="0.1",
                entryPrice=exchange.mark.toString(),clientId="W2A_BAD_CLOSE",riskPct=.001,
                entryClientId=owner.getString("client_id"),signalType="ZONE_ADD_ON",executionSemantics="MARKET_ON_CLOSE",
                referenceClose=exchange.mark,signalBarTimeMs=System.currentTimeMillis()-3_601_000L)
            assertFalse(coordinator.submitPendingEntry(base).getBoolean("accepted"))
            assertFalse(coordinator.submitPendingEntry(base.copy(strategyProfile=williamsNtdProfile)).getBoolean("accepted"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals(1.0,kotlin.math.abs(exchange.quantity),0.0)
        }
    }

    @Test fun manualQuantityCannotBeClaimedAsAnAdditionFill() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val (coordinator,exchange)=open(direction)
            exchange.quantity*=1.5
            val result=coordinator.reconcile()
            assertFalse(result.toString(),result.getBoolean("ok"))
            assertEquals("RECONCILE_REQUIRED",coordinator.state.name)
            assertTrue(exchange.algos.values.any { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" })
        }
    }

    @Test fun automaticAndForcedLongShortEntriesProtectTrailAndCloseAtSeveralLeverages() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            for (prefix in listOf("W2E_", "W2F_")) for (leverage in listOf(1, 2, 5, 10)) {
                val (coordinator, exchange) = open(direction, leverage, prefix)
                assertTrue(coordinator.isManagedOpenPosition("BTCUSDT", direction))
                val trailed = coordinator.trailProtectiveStop("BTCUSDT", direction,
                    if (direction.name == "LONG") "96" else "104", "W2PS_TRAIL_${direction.name}_$leverage")
                assertTrue(trailed.toString(), trailed.getBoolean("ok"))
                val closed = coordinator.closePosition("BTCUSDT", direction, "1", "W2X_CLOSE_${direction.name}_$leverage", "MANUAL_EXIT")
                assertTrue(closed.toString(), closed.getBoolean("ok"))
                assertEquals(0.0, exchange.quantity, 0.0)
                assertEquals(1, exchange.posts("/fapi/v1/order"))
                assertTrue(coordinator.reconcile().getBoolean("ok"))
            }
        }
    }

    @Test fun orphanRecoveryNeverPromotesStrategyOwnershipAndOnlyExplicitManualExitMayCloseIt() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val exchange = Exchange(direction)
            exchange.quantity = if (direction.name == "LONG") 1.0 else -1.0
            exchange.mark = if (direction.name == "LONG") 102.0 else 98.0
            val coordinator = NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            coordinator.setStructuralStopRecoveryProvider { _, _, _, _, _ ->
                RecoveredStructuralStop(if (direction.name == "LONG") "95" else "105", 0.002)
            }
            repeat(2) {
                assertFalse(coordinator.reconcile().getBoolean("ok"))
                assertFalse(coordinator.intentsSnapshot().getJSONObject(0).getBoolean("strategy_ownership_verified"))
                assertFalse(coordinator.isManagedOpenPosition("BTCUSDT", direction))
            }
            assertFalse(coordinator.closePosition("BTCUSDT", direction, "1", "W2X_KILL", "KILL_SWITCH").getBoolean("ok"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
            assertTrue(coordinator.closePosition("BTCUSDT", direction, "1", "W2X_MANUAL", "MANUAL_EXIT").getBoolean("ok"))
            assertEquals(1, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun actualLiquidationBreachProducesOneRevalidatedReduceOnlyExit() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val (coordinator, exchange) = open(direction)
            exchange.liquidation = if (direction.name == "LONG") "96" else "104"
            val blocked = coordinator.reconcile()
            assertFalse(blocked.getBoolean("ok"))
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
            val request = blocked.getJSONArray("required_safety_exits").getJSONObject(0)
            assertTrue(coordinator.executeRequiredSafetyExit(request).getBoolean("ok"))
            assertFalse(coordinator.executeRequiredSafetyExit(request).getBoolean("ok"))
            assertEquals(1, exchange.posts("/fapi/v1/order"))
            assertTrue(coordinator.reconcile().getBoolean("ok"))
        }
    }

    @Test fun trailingRevalidatesTheLivePositionBeforeAnyStopMutation() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            for (change in listOf("flat", "reversed", "increased")) {
                val (coordinator, exchange) = open(direction)
                exchange.quantity = when (change) {
                    "flat" -> 0.0
                    "reversed" -> -exchange.quantity
                    else -> exchange.quantity * 2
                }
                val before = exchange.posts("/fapi/v1/algoOrder")
                val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                    if (direction.name == "LONG") "96" else "104", "W2PS_STALE")
                assertFalse("$direction $change: $result", result.getBoolean("ok"))
                assertEquals(before, exchange.posts("/fapi/v1/algoOrder"))
                assertEquals("RECONCILE_REQUIRED", coordinator.state.name)
            }
        }
    }

    @Test fun reconciliationCannotAdoptAnExternalQuantityIncreaseAsAStrategyFill() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val (coordinator, exchange) = open(direction)
            exchange.quantity *= 2
            val result = coordinator.reconcile()
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
        }
    }

    @Test fun aTriggeredAlgoChildIsOwnedByItsExactOrderIdAndPartialRemainderIsCancelled() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val exchange = Exchange(direction)
            val coordinator = NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill(fraction = 0.4, childClientId = "EXCHANGE_GENERATED_CHILD")
            val result = coordinator.reconcile()
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("CANCELED", exchange.orders.getValue("42").getString("status"))
            assertTrue(coordinator.isManagedOpenPosition("BTCUSDT", direction))
        }
    }

    @Test fun anOperatorBlockAtTransportPreventsNewExposureButAllowsProtectionAndReduction() {
        val exchange = Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator = NativeFuturesCampaignCoordinator(Journal()) {
            exchange.adapter { error("operator paused during preflight") }
        }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertFalse(coordinator.submitPendingEntry(plan(exchange.direction)).getBoolean("accepted"))
        assertEquals(0, exchange.posts("/fapi/v1/algoOrder"))
        exchange.quantity = 1.0
        exchange.mark = 102.0
        val adapter = exchange.adapter { error("operator paused") }
        adapter.placeProtectiveStop("BTCUSDT", "LONG", "95", "W2PS_SAFE")
        adapter.closeMarketPosition("BTCUSDT", "LONG", "1", "W2X_SAFE")
        assertEquals(1, exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(1, exchange.posts("/fapi/v1/order"))
    }

    @Test fun emergencyExitCannotCloseAnExternallyIncreasedPosition() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity = 2.0
        val result = coordinator.closePosition("BTCUSDT", exchange.direction, "2", "W2X_FOREIGN", "KILL_SWITCH")
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals(0, exchange.posts("/fapi/v1/order"))
    }

    @Test fun entryFillAndLiveQuantityMayUseDifferentDecimalScales() {
        val exchange = Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator = NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertTrue(coordinator.submitPendingEntry(plan(exchange.direction)).getBoolean("accepted"))
        exchange.fill()
        exchange.orders.getValue("42").put("executedQty", "1.00000000")
        val result = coordinator.reconcile()
        assertTrue(result.toString(), result.getBoolean("ok"))
    }

    @Test fun aConfirmedPartialExitExplainsTheExactResidualCampaignQuantity() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.closeFraction = 0.4
        exchange.closeStatus = "EXPIRED_IN_MATCH"
        assertFalse(coordinator.closePosition("BTCUSDT", exchange.direction, "1", "W2X_PARTIAL", "MANUAL_EXIT").getBoolean("ok"))
        val result = coordinator.reconcile()
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(0.6, exchange.quantity, 1e-12)
        assertTrue(coordinator.isManagedOpenPosition("BTCUSDT", exchange.direction))
    }

    @Test fun hedgeRowsCannotBeAdoptedAfterAContradictoryOneWayModeReply() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.positionSide = "LONG"
        val result = coordinator.reconcile()
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
    }

    @Test fun unknownLiquidationDataBlocksAdmissionWithoutAuthorizingAnExit() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        for (value in listOf("0", "NaN", "malformed")) {
            exchange.liquidation = value
            val result = coordinator.reconcile()
            assertFalse(result.getBoolean("ok"))
            assertEquals(0, result.getJSONArray("required_safety_exits").length())
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun lostSafetyExitReplyIsReconciledWithoutASecondMarketOrder() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.liquidation = "96"
        val request = coordinator.reconcile().getJSONArray("required_safety_exits").getJSONObject(0)
        exchange.loseCloseReply = true
        assertFalse(coordinator.executeRequiredSafetyExit(request).getBoolean("ok"))
        assertFalse(coordinator.executeRequiredSafetyExit(request).getBoolean("ok"))
        assertEquals(1, exchange.posts("/fapi/v1/order"))
        val recovered = coordinator.reconcile()
        assertTrue(recovered.toString(), recovered.getBoolean("ok"))
        assertEquals(1, exchange.posts("/fapi/v1/order"))
    }

    @Test fun recoveredLiquidationBufferCancelsAStaleSafetyExitRequest() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.liquidation = "96"
        val request = coordinator.reconcile().getJSONArray("required_safety_exits").getJSONObject(0)
        exchange.liquidation = "50"
        assertFalse(coordinator.executeRequiredSafetyExit(request).getBoolean("ok"))
        assertEquals(0, exchange.posts("/fapi/v1/order"))
        assertTrue(coordinator.reconcile().getBoolean("ok"))
    }

    @Test fun wrongLeverageConfirmationDoesNotLeaveAPhantomEntry() {
        val exchange = Exchange(NativeFuturesCampaignCoordinator.Direction.LONG).apply { badConfirmation = true }
        val coordinator = NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        val result = coordinator.submitPendingEntry(plan(exchange.direction))
        assertFalse(result.getBoolean("accepted"))
        assertFalse(result.getBoolean("order_mutation_attempted"))
        assertEquals("CLOSED_OR_STOPPED", coordinator.intentsSnapshot().getJSONObject(0).getString("campaign_status"))
        assertEquals(0, exchange.posts("/fapi/v1/algoOrder"))
    }

    @Test fun rejectedJournalWritePreventsAllEntryMutations() {
        val exchange = Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val journal = Journal()
        val coordinator = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        journal.rejectWrites = true
        try { coordinator.submitPendingEntry(plan(exchange.direction)); fail("Journal failure must prevent mutation") }
        catch (_: IllegalStateException) { }
        assertEquals(0, exchange.posts("/fapi/v1/leverage"))
        assertEquals(0, exchange.posts("/fapi/v1/algoOrder"))
    }

    @Test fun actualReconciliationLifecycleIsVisibleAndAlwaysEndsOnFactoryFailure() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exchange = Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator = NativeFuturesCampaignCoordinator(Journal()) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            exchange.adapter()
        }
        val result = AtomicReference<JSONObject>()
        val thread = Thread { result.set(coordinator.reconcile()) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val during = coordinator.status()
            assertTrue(during.getBoolean("reconciliation_running"))
            assertFalse(during.getBoolean("ready_for_new_entry"))
        } finally { release.countDown(); thread.join(5000) }
        assertFalse(thread.isAlive)
        assertTrue(result.get().getBoolean("ok"))
        assertFalse(coordinator.status().getBoolean("reconciliation_running"))
        val failing = NativeFuturesCampaignCoordinator(Journal()) { error("credential store unavailable") }
        assertFalse(failing.reconcile().getBoolean("ok"))
        assertFalse(failing.status().getBoolean("reconciliation_running"))
        assertEquals("credential store unavailable", failing.status().getString("blocker"))
    }

    @Test fun tc2StrategyExitIsRejectedBeforeAnyExchangeRequestAndRetainsProtection() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val beforeRequests=exchange.requests.size
            val before=coordinator.intentsSnapshot().getJSONObject(0)
            val stopId=before.getString("protection_client_id")
            for(reason in listOf("STRATEGY_EXIT","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","CORRECTION_PARENT_PRICE_INVALIDATED","LIQUIDATION_GUARD")) {
                val result=coordinator.closePosition("BTCUSDT",direction,"1","W2X_BLOCK_"+direction.name,reason)
                assertFalse(result.getBoolean("ok"))
                assertEquals("TC2_PROTECTIVE_STOP_ONLY",result.getString("blocker"))
                assertFalse(result.getBoolean("mutation_attempted"))
                assertEquals(beforeRequests,exchange.requests.size)
                assertEquals("OPEN_PROTECTED",coordinator.state.name)
                assertEquals(stopId,coordinator.intentsSnapshot().getJSONObject(0).getString("protection_client_id"))
                assertTrue(CockpitDiagnostics.rows(coordinator.intentsSnapshot()).none {it.optString("intent_role")=="EXIT"})
            }
        }
    }

    @Test fun confirmedStopMoveRetainsItsBookReasonAndExactPrices() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val next=if(direction.name=="LONG") "96" else "104"
            val old=if(direction.name=="LONG") "95" else "105"
            val cid="W2PS_EXPLAIN_"+direction.name
            val result=coordinator.trailProtectiveStop("BTCUSDT",direction,next,cid,reason="TC2_LAST_5_CLOSED_BARS")
            assertTrue(result.toString(),result.getBoolean("ok"))
            val entry=coordinator.intentsSnapshot().getJSONObject(0)
            val move=entry.getJSONObject("last_confirmed_trail")
            assertEquals("TC2_LAST_5_CLOSED_BARS",move.getString("reason"))
            assertEquals(old,move.getString("old_stop"))
            assertEquals(next,move.getString("new_stop"))
            assertEquals(cid,move.getString("client_id"))
            assertEquals(williamsTc2Profile,move.getString("strategy_profile"))
            assertTrue(move.getLong("observed_at_ms")>0)
            assertEquals(1,exchange.algos.values.count {it.optString("clientAlgoId")==cid})
            val publicMove=coordinator.status().getJSONArray("pending_intent_details").getJSONObject(0)
                .getJSONObject("last_confirmed_trail")
            assertFalse(publicMove.has("client_id"))
            assertEquals(next,publicMove.getString("new_stop"))
        }
    }

    @Test fun explicitKillStillClosesAnOwnedTc2Campaign() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val result=coordinator.closePosition("BTCUSDT",direction,"1","W2X_KILL_ALLOWED_"+direction.name,"KILL_SWITCH")
            assertTrue(result.toString(),result.getBoolean("ok"))
            assertEquals(0.0,exchange.quantity,0.0)
            assertEquals("EMERGENCY_KILL",coordinator.intentsSnapshot().let { rows->
                (0 until rows.length()).map {rows.getJSONObject(it)}.last {it.optString("intent_role")=="EXIT"}
            }.getString("exit_authority"))
        }
    }
}

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
import org.junit.Ignore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real coordinator + real signed transport; only persistence/network peers are modeled. */
class NativeFuturesCoordinatorLifecycleTest {
    private class Journal : FuturesCampaignStateStore {
        private val values = mutableMapOf<String, String>()
        var rejectWrites = false
        var rejectDecisionWrites = false
        var afterWrite: ((String, String) -> Unit)? = null
        @Synchronized override fun readState(key: String): String? = values[key]
        @Synchronized override fun writeState(key: String, value: String): Boolean {
            if (rejectWrites || rejectDecisionWrites && key.startsWith("decision_execution_")) return false
            values[key] = value
            afterWrite?.invoke(key, value)
            return true
        }
    }

    private class Exchange(val direction: NativeFuturesCampaignCoordinator.Direction) {
        val requests = mutableListOf<Request>()
        val algos = linkedMapOf<String, JSONObject>()
        val orders = linkedMapOf<String, JSONObject>()
        val tradeFills = JSONArray()
        var quantity = 0.0
        var entry = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) 101.0 else 99.0
        var mark = 100.0
        var contract: Double? = null
        var liquidation = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) "50" else "200"
        var leverage = 2
        var badConfirmation = false
        var loseCloseReply = false
        var loseAdditionReply = false
        var loseCancelReply = false
        var afterLeverage:(()->Unit)?=null
        var afterSymbolConfig:(()->Unit)?=null
        var queriedStopTransform:((JSONObject)->JSONObject)?=null
        var beforeMarkRead:(()->Unit)?=null
        var loseProtectionReply=false
        var rejectProtectionCode:Int?=null
        var rejectSecondCloseAll=false
        var failPendingQueries=false
        var beforeRequest: ((Request, Map<String, String>) -> Unit)? = null
        var afterAlgoMutation: ((Request, JSONObject) -> Unit)? = null
        var additionFraction = 1.0
        var closeFraction = 1.0
        var closeStatus = "FILLED"
        var positionSide = "BOTH"
        var positionSnapshotTransform: ((JSONArray) -> JSONArray)? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val p = params(request)
            beforeRequest?.invoke(request, p)
            var code = 200
            val response: Any = when (request.url.encodedPath) {
                "/fapi/v1/time" -> JSONObject().put("serverTime", System.currentTimeMillis())
                "/fapi/v1/positionSide/dual" -> JSONObject().put("dualSidePosition", false)
                "/fapi/v3/account" -> JSONObject().put("canTrade", true).put("totalWalletBalance", "10000")
                    .put("totalMarginBalance", "10000").put("totalUnrealizedProfit", "0")
                    .put("availableBalance", "10000").put("assets", JSONArray())
                "/fapi/v3/positionRisk" -> positions()
                "/fapi/v1/premiumIndex" -> { beforeMarkRead?.invoke()
                    JSONObject().put("symbol", "BTCUSDT").put("markPrice", mark.toString()) }
                "/fapi/v2/ticker/price" -> JSONObject().put("symbol","BTCUSDT").put("price",(contract ?: mark).toString())
                "/fapi/v1/ticker/bookTicker" -> JSONObject().put("symbol","BTCUSDT")
                    .put("bidPrice",(contract ?: mark).toString()).put("askPrice",(contract ?: mark).toString())
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
                "/fapi/v1/symbolConfig" -> { afterSymbolConfig?.invoke(); JSONArray().put(JSONObject().put("symbol", "BTCUSDT")
                    .put("marginType", "ISOLATED").put("leverage", leverage).put("maxNotionalValue", "1000000"))
                }
                "/fapi/v1/openOrders" -> JSONArray(orders.values.filter {
                    it.optString("status") in setOf("NEW", "PARTIALLY_FILLED") &&
                        (p["symbol"] == null || it.optString("symbol") == p["symbol"])
                })
                "/fapi/v1/userTrades" -> JSONArray((0 until tradeFills.length()).map { tradeFills.getJSONObject(it) }
                    .filter { p["orderId"]==null || it.optString("orderId")==p["orderId"] })
                "/fapi/v1/openAlgoOrders" -> JSONArray(algos.values.filter {
                    it.optString("algoStatus") == "NEW" && (p["symbol"] == null || it.optString("symbol") == p["symbol"])
                })
                "/fapi/v1/algoOrder" -> when (request.method) {
                    "POST" -> {
                        val protective=p["closePosition"]=="true" || p["reduceOnly"]=="true"
                        val rejected=rejectProtectionCode?.takeIf { protective } ?: (-4130).takeIf {
                            rejectSecondCloseAll && p["closePosition"]=="true" && algos.values.any {
                                it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW"
                            }
                        }
                        if(rejected!=null) {
                            code=400;JSONObject().put("code",rejected).put("msg","simulated protective order rejection")
                        } else {
                            val id = (algos.size + 1).toString()
                            val row = JSONObject(p).put("algoId", id).put("algoStatus", "NEW")
                                .put("orderType", p.getValue("type")).put("actualOrderId", "")
                            algos[id] = row
                            afterAlgoMutation?.invoke(request, row)
                            if(loseProtectionReply && protective) {
                                code=503;JSONObject().put("code",-1000).put("msg","lost protection reply")
                            } else row
                        }
                    }
                    else -> {
                        val row = algos.values.singleOrNull { it.optString("algoId") == p["algoId"] ||
                            it.optString("clientAlgoId") == p["clientAlgoId"] }
                        if(request.method=="GET" && failPendingQueries && p["clientAlgoId"]?.startsWith("W2T_")==true) {
                            code=400;JSONObject().put("code",-1007).put("msg","query execution status unknown")
                        }
                        else if (row == null) { code = 400; JSONObject().put("code", -2013).put("msg", "Order does not exist.") }
                        else { if (request.method == "DELETE") {
                                row.put("algoStatus", "CANCELED")
                                afterAlgoMutation?.invoke(request, row)
                            }
                            if(request.method=="DELETE" && loseCancelReply) { code=503;JSONObject().put("code",-1000).put("msg","lost cancellation reply") }
                            else if(request.method=="GET" && row.optString("closePosition")=="true")
                                queriedStopTransform?.invoke(JSONObject(row.toString())) ?: row
                            else row }
                    }
                }
                "/fapi/v1/order" -> if (request.method == "POST" && p["reduceOnly"]!="true") {
                    check(quantity==0.0 || p["side"]==if(quantity>0) "BUY" else "SELL")
                    val added=p.getValue("quantity").toDouble()*additionFraction
                    val id=(100+orders.size).toString()
                    val old=kotlin.math.abs(quantity)
                    entry=(entry*old+mark*added)/(old+added)
                    quantity=(quantity.toString().toBigDecimal()+(if(p["side"]=="BUY") added else -added).toString().toBigDecimal()).toDouble()
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

        fun positions(): JSONArray = JSONArray().apply {
            if (quantity != 0.0) put(JSONObject().put("symbol", "BTCUSDT").put("positionSide", positionSide)
                .put("positionAmt", quantity.toString()).put("entryPrice", entry.toString())
                .put("markPrice", mark.toString()).put("liquidationPrice", liquidation))
        }.let { positionSnapshotTransform?.invoke(it) ?: it }
        fun fill(fraction: Double = 1.0, childClientId: String? = null) {
            val pending = algos.values.single { it.optString("closePosition") != "true" }
            entry = pending.getString("triggerPrice").toDouble()
            quantity = pending.getString("quantity").toBigDecimal().multiply(fraction.toBigDecimal()).toDouble() *
                if (pending.getString("side") == "BUY") 1.0 else -1.0
            mark = if (quantity > 0) 102.0 else 98.0
            pending.put("algoStatus", "FINISHED").put("actualOrderId", "42")
            orders["42"] = JSONObject().put("symbol", "BTCUSDT").put("orderId", "42")
                .put("clientOrderId", childClientId ?: pending.getString("clientAlgoId"))
                .put("status", if (fraction == 1.0) "FILLED" else "PARTIALLY_FILLED")
                .put("origQty", pending.getString("quantity")).put("executedQty", kotlin.math.abs(quantity).toString())
                .put("side", pending.getString("side")).put("avgPrice", entry.toString()).put("reduceOnly",false).put("positionSide","BOTH")
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
                .put("side",parent.getString("side")).put("avgPrice",price.toString()).put("reduceOnly",false).put("positionSide","BOTH")
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

    private fun plan(direction: NativeFuturesCampaignCoordinator.Direction, leverage: Int = 2, prefix: String = "W2E_") =
        NativeFuturesCampaignCoordinator.EntryPlan("BTCUSDT", direction, NativeFuturesCampaignCoordinator.EntryKind.STOP,
            "1", if (direction.name == "LONG") "101" else "99", if (direction.name == "LONG") "95" else "105",
            "${prefix}BTC_${direction.name}_$leverage", 0.002, leverage = leverage)

    private fun open(direction: NativeFuturesCampaignCoordinator.Direction, leverage: Int = 2, prefix: String = "W2E_",
        journal: Journal = Journal(), clock: () -> Long = System::currentTimeMillis):
        Pair<NativeFuturesCampaignCoordinator, Exchange> {
        val exchange = Exchange(direction)
        val coordinator = NativeFuturesCampaignCoordinator(journal, clock = clock) { exchange.adapter() }
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


    @Test fun productionBookDoorRejectsConfigured10xBeforeAnyExchangeEntryForBothDirections() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal=Journal()
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(journal) {exchange.adapter()}
            val recovered=coordinator.reconcile()
            assertTrue(recovered.toString(),recovered.optBoolean("ok"))
            val rejected=coordinator.submitPendingEntry(plan(direction,10))
            assertFalse(rejected.toString(),rejected.optBoolean("accepted"))
            assertTrue(rejected.toString(),rejected.toString().contains(
                "WILLIAMS_NEW_ENTRY_LEVERAGE_EXCEEDS_2X"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
            // It is an admission fence, not an exchange mutation or forced
            // leverage reset; existing exchange STOPs remain untouched.
        }
    }

    @Test fun bookH1W2HStopsMustBePlacedAndConfirmedAfterBothLongAndShortFills() {
        // Production book H1 client IDs start with W2H_ (not W2E_).
        // Before the regression fix the live Binance position was detected
        // but matchingIntent was null, despite the durable structural stop;
        // no protective algo was ever submitted for a filled WM3 entry.
        for (direction in listOf(NativeFuturesCampaignCoordinator.Direction.LONG,NativeFuturesCampaignCoordinator.Direction.SHORT)) {
            val exchange=Exchange(direction)
            val journal=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val first=coordinator.reconcile()
            assertTrue(first.toString(),first.optBoolean("ok"))
            val bookId=williamsBookH1ClientId(
                "BTCUSDT", "BTCUSDT:1h:"+(if(direction.name=="LONG") "FRACTAL" else "SHORT_FRACTAL")+":1792504800000"
            )
            assertTrue(bookId.startsWith("W2H_"))
            val intent=plan(direction).copy(clientId=bookId)
            val accepted=coordinator.submitPendingEntry(intent)
            assertTrue(accepted.toString(),accepted.getBoolean("accepted"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            exchange.fill()
            val protection=coordinator.reconcile()
            assertTrue(protection.toString(),protection.getBoolean("ok"))
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            assertEquals("PROTECTED",owner.getString("protection_status"))
            assertEquals(bookId,owner.getString("client_id"))
            assertTrue(owner.getString("protection_client_id").isNotBlank())
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(1,exchange.algos.values.count { it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW" })
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder")) // never duplicate protection
        }
    }

    @Test fun unknownManualClientPrefixNeverAuthorizesAutomaticProtectionMutation() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val journal=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertTrue(coordinator.submitPendingEntry(
            plan(NativeFuturesCampaignCoordinator.Direction.LONG,prefix="USER_MANUAL_")
        ).getBoolean("accepted"))
        exchange.fill()
        val result=coordinator.reconcile()
        assertFalse(result.toString(),result.optBoolean("ok"))
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.algos.values.count { it.optString("closePosition")=="true" })
        assertEquals("POSITION_UNPROTECTED",coordinator.state.name)
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

    @Test fun pinnedTc2CampaignUsesActualInitialFillAndExactlyFourBookAdditions() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(fraction in listOf(1.0,.4)) {
            val exchange=Exchange(direction);val journal=Journal();var now=System.currentTimeMillis()
            fun coordinator()=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            var c=coordinator();c.reconcile()
            val initial=plan(direction).copy(quantity="0.1",riskPct=.0004,signalType="FRACTAL",signalBarTimeMs=now-7_200_000L,
                setupContext=JSONObject().put("tc2_reverse_pyramid",JSONObject().put("policy",WILLIAMS_TC2_PYRAMID_POLICY)).toString())
            assertTrue(c.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill(fraction);assertTrue(c.reconcile().getBoolean("ok"))
            val unit=kotlin.math.abs(exchange.quantity).toString().toBigDecimal()
            for((index,weight) in listOf(5,4,3,2).withIndex()) {
                now+=3_600_000L
                c=coordinator();assertTrue(c.reconcile().getBoolean("ok"))
                val addition=initial.copy(quantity=unit.multiply(weight.toBigDecimal()).toPlainString(),riskPct=.001,
                    entryClientId=initial.clientId,clientId="W2A_BOOK_${direction.name}_${fraction}_$index",
                    signalType="SUPER_AO",signalBarTimeMs=now-3_601_000L,
                    entryPrice=(exchange.mark+if(direction.name=="LONG") 1 else -1).toString())
                val before=exchange.posts("/fapi/v1/algoOrder")
                val wrong=c.submitPendingEntry(addition.copy(quantity=unit.toPlainString(),clientId=addition.clientId+"_bad"))
                assertTrue(wrong.toString(),wrong.getBoolean("safe_rejection"))
                assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
                val accepted=c.submitPendingEntry(addition)
                assertTrue(accepted.toString(),accepted.getBoolean("accepted"))
                exchange.fillAddition(addition.clientId)
                assertTrue(c.reconcile().getBoolean("ok"))
            }
            assertEquals(unit.multiply(15.toBigDecimal()).toDouble(),kotlin.math.abs(exchange.quantity),1e-12)
            val owner=c.intentsSnapshot().getJSONObject(0)
            assertEquals("TC2_PYRAMID_COMPLETE_15_UNITS",williamsTc2NextTranche(owner,c.intentsSnapshot()).blocker)
            assertEquals("OPEN_PROTECTED",c.state.name)
        }
    }

    @Test fun tc2AdditionRechecksProfitAfterLeverageAndBeforePost() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(finalChange in listOf(false,true)) {
            val exchange=Exchange(direction);val c=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            c.reconcile();val first=plan(direction).copy(signalType="FRACTAL",signalBarTimeMs=System.currentTimeMillis()-7_200_000L)
            assertTrue(c.submitPendingEntry(first).getBoolean("accepted"));exchange.fill();assertTrue(c.reconcile().getBoolean("ok"))
            if(finalChange) exchange.afterSymbolConfig={ exchange.mark=exchange.entry }
            else exchange.mark=exchange.entry
            val addition=first.copy(quantity="0.1",riskPct=.001,entryClientId=first.clientId,clientId="W2A_PROFIT_${direction.name}_$finalChange",
                signalType="SUPER_AO",signalBarTimeMs=System.currentTimeMillis()-3_601_000L,
                entryPrice=(exchange.entry+if(direction.name=="LONG") 3 else -3).toString())
            val posts=exchange.posts("/fapi/v1/algoOrder")
            val result=c.submitPendingEntry(addition)
            assertFalse(result.toString(),result.getBoolean("accepted"))
            assertTrue(result.toString(),result.getBoolean("safe_rejection"))
            assertTrue(result.toString(),result.optString("blocker").contains("PROFITABLE_POSITION"))
            assertEquals(posts,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    private fun ntdBookPlan(base:NativeFuturesCampaignCoordinator.EntryPlan,now:Long):NativeFuturesCampaignCoordinator.EntryPlan {
        val at=(now/WILLIAMS_H1_MS-1)*WILLIAMS_H1_MS
        val type=if(base.direction.name=="SHORT" && !base.signalType.startsWith("SHORT_")) "SHORT_"+base.signalType else base.signalType
        val id="NTD:${base.symbol}:${base.direction}:$type:$at"
        val setup=JSONObject().put("ntd_initial",JSONObject().put("eligible",true).put("sleep_at_ms",at-10*WILLIAMS_H1_MS))
        val sourceTrigger=if(base.kind==NativeFuturesCampaignCoordinator.EntryKind.MARKET) base.referenceClose else base.entryPrice.toDouble()
        val p=base.copy(signalId=id,signalType=type,signalBarTimeMs=at,
            entryPrice=if(base.kind==NativeFuturesCampaignCoordinator.EntryKind.MARKET)
                williamsNtdCloseEntryBound(base.direction.name,base.referenceClose,.1)!!.toString() else base.entryPrice)
        setup.put("book_h1_entry",williamsBookH1Proof(id,p.direction.name,p.signalType,p.strategyProfile,at,
            sourceTrigger,p.protectiveStop.toDouble(),.1,p.entryClientId,p.protectiveStop.toDouble(),
            p.executionSemantics,p.referenceClose,p.reentryOwnerId))
        return p.copy(setupContext=setup.toString())
    }
    private fun requireNtdBookProof(c:NativeFuturesCampaignCoordinator) {
        c.requireBookH1ProofForAutomatedEntries=true
        c.bookH1EntryVerifier={ p ->
            check(williamsBookH1ProofBlocker(p.setupContext,p.direction.name,p.signalId,p.signalType,
                p.strategyProfile,p.entryPrice.toDouble(),p.protectiveStop.toDouble(),
                p.signalBarTimeMs+WILLIAMS_H1_MS+1000,p.entryClientId,p.reentryOwnerId,p.executionSemantics,p.referenceClose)==null)
        }
    }

    @Test fun finalSignedFuturesDoorRejectsNtdAndCourseNewOrdersForBothSides() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            for(profile in listOf(williamsNtdProfile,williamsCourseProfile)) {
                val journal=Journal();val exchange=Exchange(direction)
                val c=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
                assertTrue(c.reconcile().getBoolean("ok"))
                val plan=plan(direction).copy(
                    strategyProfile=profile,
                    signalType=if(profile==williamsNtdProfile) "FRACTAL" else "COURSE_THUMB")
                val beforeAlgo=exchange.posts("/fapi/v1/algoOrder")
                val beforeMarket=exchange.posts("/fapi/v1/order")
                val result=c.submitPendingEntry(plan)
                assertFalse(result.toString(),result.optBoolean("accepted"))
                assertTrue(result.toString(),
                    result.optString("blocker").contains("WILLIAMS_SOURCE_OBSERVATION_ONLY"))
                assertEquals(beforeAlgo,exchange.posts("/fapi/v1/algoOrder"))
                assertEquals(beforeMarket,exchange.posts("/fapi/v1/order"))
                assertEquals("READY",c.state.name)
            }
        }
    }

    @Ignore("NTD new exposure is disabled; historical transport fixture retained for independent future approval")
    @Test fun ntdInitialFractalAndEveryPendingDimensionUseTheRealProtectedOwner() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(family in williamsNtdFamilies-setOf("ZONE_ADD_ON")) {
            val now=System.currentTimeMillis()/WILLIAMS_H1_MS*WILLIAMS_H1_MS+1000
            val journal=Journal();val exchange=Exchange(direction)
            val c=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            c.reconcile();requireNtdBookProof(c)
            val first=ntdBookPlan(plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL"),now-WILLIAMS_H1_MS)
            val missing=c.submitPendingEntry(first.copy(setupContext="{}"))
            assertFalse(missing.getBoolean("accepted"));assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
            assertTrue(c.submitPendingEntry(first).getBoolean("accepted"))
            exchange.fill();assertTrue(c.reconcile().getBoolean("ok"))
            val stopId=c.intentsSnapshot().getJSONObject(0).getString("protection_client_id")
            val sign=if(direction.name=="LONG") 1 else -1
            val add=ntdBookPlan(first.copy(quantity="0.1",riskPct=.001,entryPrice=(exchange.mark+sign).toString(),
                clientId="W2A_NTD_${direction.name}_$family",entryClientId=first.clientId,signalType=family),now)
            assertTrue(c.submitPendingEntry(add).getBoolean("accepted"))
            exchange.fillAddition(add.clientId);assertTrue(c.reconcile().getBoolean("ok"))
            val restarted=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            assertTrue(restarted.reconcile().getBoolean("ok"))
            assertEquals(1.1,kotlin.math.abs(exchange.quantity),1e-9)
            assertEquals(stopId,restarted.intentsSnapshot().getJSONObject(0).getString("protection_client_id"))
            assertFalse(restarted.submitPendingEntry(add).getBoolean("accepted"))
        }
    }

    @Ignore("NTD new exposure is disabled; historical transport fixture retained for independent future approval")
    @Test fun ntdAdditionProfitAndBookExitProofAreRecheckedBeforeAnyNewMutation() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val now=System.currentTimeMillis()/WILLIAMS_H1_MS*WILLIAMS_H1_MS+1000
            val journal=Journal();val exchange=Exchange(direction)
            val c=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            c.reconcile();requireNtdBookProof(c)
            val initial=ntdBookPlan(plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL"),now-WILLIAMS_H1_MS)
            assertTrue(c.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(c.reconcile().getBoolean("ok"))
            requireNtdBookProof(c)
            val sign=if(direction.name=="LONG") 1 else -1
            val add=ntdBookPlan(initial.copy(strategyProfile=williamsNtdProfile,signalType="AO_SAUCER",quantity="0.1",riskPct=.001,
                entryPrice=(exchange.mark+sign).toString(),entryClientId=initial.clientId,clientId="W2A_NTD_RACE_${direction.name}"),now)
            exchange.afterSymbolConfig={ exchange.mark=exchange.entry }
            val before=exchange.posts("/fapi/v1/algoOrder")
            val blocked=c.submitPendingEntry(add)
            assertFalse(blocked.toString(),blocked.getBoolean("accepted"))
            assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
            exchange.afterSymbolConfig=null
            val noVerifier=c.closePosition("BTCUSDT",direction,"1","W2X_NTD_NO_PROOF","TEETH_CLOSE_EXIT")
            assertFalse(noVerifier.getBoolean("ok"));assertEquals(0,exchange.posts("/fapi/v1/order"))
            c.bookNtdExitVerifier={ _,_ -> error("NTD_CLOSED_LINE_EXIT_NO_LONGER_VALID") }
            assertFalse(c.closePosition("BTCUSDT",direction,"1","W2X_NTD_OLD_PROOF","TEETH_CLOSE_EXIT").getBoolean("ok"))
            assertEquals(1,exchange.algos.values.count { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" })
            assertEquals(0,exchange.posts("/fapi/v1/order"))
        }
    }

    @Ignore("NTD new exposure is disabled; historical transport fixture retained for independent future approval")
    @Test fun ntdCampaignCannotSpendTheUnusedPortfolioBudgetBeyondItsOwnCeiling() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val now=System.currentTimeMillis()/WILLIAMS_H1_MS*WILLIAMS_H1_MS+1000
            val journal=Journal();val exchange=Exchange(direction)
            val c=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            c.reconcile();requireNtdBookProof(c)
            val initial=ntdBookPlan(plan(direction,leverage=2).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL"),now-WILLIAMS_H1_MS)
            assertTrue(c.submitPendingEntry(initial).getBoolean("accepted"));exchange.fill();assertTrue(c.reconcile().getBoolean("ok"))
            val sign=if(direction.name=="LONG") 1 else -1
            for(family in listOf("AO_SAUCER","AO_ZERO_LINE","AC_SIGNAL","AO_TWIN_PEAKS")) {
                val add=ntdBookPlan(initial.copy(signalType=family,quantity="0.1",riskPct=.001,
                    entryPrice=(exchange.mark+sign).toString(),entryClientId=initial.clientId,clientId="W2A_CAP_${direction.name}_$family"),now)
                val admitted=c.submitPendingEntry(add);assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
                exchange.fillAddition(add.clientId);assertTrue(c.reconcile().getBoolean("ok"))
            }
            val ledger=c.intentsSnapshot()
            assertEquals(.006,futuresCampaignReservedRisk(ledger.getJSONObject(0),ledger),1e-9)
            val last=ntdBookPlan(initial.copy(signalType="BALANCE_LINE",quantity="0.1",riskPct=.001,
                entryPrice=(exchange.mark+sign).toString(),entryClientId=initial.clientId,clientId="W2A_CAP_LAST_${direction.name}"),now)
            val before=exchange.posts("/fapi/v1/algoOrder");val blocked=c.submitPendingEntry(last)
            assertFalse(blocked.toString(),blocked.getBoolean("accepted"))
            assertTrue(blocked.toString(),blocked.getString("blocker").startsWith("CAMPAIGN_RISK_LIMIT:"))
            assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals("OPEN_PROTECTED",c.state.name)
        }
    }

    @Test fun oldNtdSourceLabelAloneCannotAcquireAdditionAuthority() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal=Journal();val exchange=Exchange(direction)
            val c=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            c.reconcile()
            val initial=plan(direction).copy(signalBarTimeMs=System.currentTimeMillis()-7_200_000L)
            assertTrue(c.submitPendingEntry(initial).getBoolean("accepted"));exchange.fill()
            assertTrue(c.reconcile().getBoolean("ok"))
            markLegacySource(journal,c,williamsNtdProfile,"FRACTAL")
            val base=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="AO_SAUCER",quantity="0.1",riskPct=.001,
                entryPrice=(exchange.mark+if(direction.name=="LONG") 1 else -1).toString(),
                entryClientId=plan(direction).clientId,clientId="W2A_OLD_NTD_${direction.name}",signalBarTimeMs=(System.currentTimeMillis()/WILLIAMS_H1_MS-1)*WILLIAMS_H1_MS)
            val before=exchange.posts("/fapi/v1/algoOrder");val blocked=c.submitPendingEntry(base)
            assertFalse(blocked.getBoolean("accepted"))
            assertTrue(blocked.getString("blocker").contains("CYCLE_NOT_PROVEN"))
            assertEquals(before,exchange.posts("/fapi/v1/algoOrder"));assertEquals("OPEN_PROTECTED",c.state.name)
        }
    }

    @Ignore("NTD new exposure is disabled; historical transport fixture retained for independent future approval")
    @Test fun profitableNtdCycleReentersOnlyOnceWithOwnedStopOrCloseEvent() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(market in listOf(false,true)) for(lost in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            var now=System.currentTimeMillis()/WILLIAMS_H1_MS*WILLIAMS_H1_MS+1000
            val coordinator=NativeFuturesCampaignCoordinator(journal,clock={ now }) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL",signalBarTimeMs=now-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial.copy(strategyProfile=williamsTc2Profile)).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsNtdProfile,"FRACTAL")
            assertTrue(coordinator.closePosition("BTCUSDT",direction,"1","W2X_NTD_PROFIT_${direction.name}_$market","MANUAL_EXIT").getBoolean("ok"))
            assertTrue(coordinator.recordVerifiedCampaignResult(initial.clientId,1.0,false,2))
            var previous=coordinator.intentsSnapshot().getJSONObject(0)
            now+=7_200_000L
            assertFalse(williamsNtdReentryEligible(previous,"BTCUSDT",direction.name,now-3_601_000L))
            assertTrue(coordinator.recordVerifiedCampaignResult(initial.clientId,1.0,true,2))
            previous=coordinator.intentsSnapshot().getJSONObject(0)
            assertTrue(williamsNtdReentryEligible(previous,"BTCUSDT",direction.name,now-3_601_000L))
            val reentry=ntdBookPlan(initial.copy(clientId="W2E_NTD_RE_${direction.name}_$market",quantity="0.5",riskPct=.001,
                signalType=if(market) "ZONE_ADD_ON" else "AO_SAUCER",signalBarTimeMs=now-3_601_000L,
                reentryOwnerId=initial.clientId,kind=if(market) NativeFuturesCampaignCoordinator.EntryKind.MARKET else NativeFuturesCampaignCoordinator.EntryKind.STOP,
                executionSemantics=if(market) "MARKET_ON_CLOSE" else "STOP_ENTRY",
                referenceClose=if(market) exchange.mark else 0.0,
                entryPrice=(exchange.mark+if(market) 0 else if(direction.name=="LONG") 1 else -1).toString()),now)
            requireNtdBookProof(coordinator)
            exchange.loseAdditionReply=lost
            val admitted=coordinator.submitPendingEntry(reentry)
            assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
            assertFalse(williamsNtdReentryEligible(coordinator.intentsSnapshot().getJSONObject(0),"BTCUSDT",direction.name,now))
            if(market) {
                assertEquals(.5,kotlin.math.abs(exchange.quantity),1e-9)
                assertEquals("PROTECTED",admitted.getString("protection"))
            }
            assertFalse(coordinator.submitPendingEntry(reentry).getBoolean("accepted"))
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
            val closeId="W2X_COURSE_TRIM_${direction.name}_$lost"
            coordinator.saveManagementState("BTCUSDT",initial.clientId,JSONObject().put("course_stage","W3_EXTENSION")
                .put("course_unit_quantity","0.1").put("course_unit_basis","CONFIRMED_INITIAL_FILL").put("course_w3_to_w1_ratio",2.0)
                .put("course_pending_client_id",closeId).put("course_pending_stage","W4")
                .put("course_pending_quantity",williamsCourseStageQuantity("0.1","W3_EXTENSION","W4","1","0.001",w3ToW1Ratio=2.0)))
            val protection=exchange.algos.values.single { it.optString("closePosition")=="true" }.getString("clientAlgoId")
            // This test isolates signed reduction/recovery. The production
            // source gate has its own causal parent/terminal fixture tests.
            coordinator.bookCourseTrimVerifier={ _,_ -> }
            exchange.loseCloseReply=lost
            val reduced=coordinator.closePosition("BTCUSDT",direction,"0.7",closeId,
                "COURSE_STAGE_TRIM",partialReduction=true)
            if(!lost) assertTrue(reduced.toString(),reduced.getBoolean("ok"))
            coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(.3,kotlin.math.abs(exchange.quantity),1e-8)
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            val management=coordinator.intentsSnapshot().getJSONObject(0).getJSONObject("edition_management")
            assertEquals("W4",management.getString("course_stage"))
            assertEquals("0.1",management.getString("course_unit_quantity"))
            assertEquals(1,management.getJSONArray("course_stage_fills").length())
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

    @Test fun courseTrimRequiresASourceVerifierBeforeAnyMutationAndImmediatelyBeforePost() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) {
            val journal=Journal();val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.reconcile()
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsCourseProfile,"COURSE_HUMP")
            val before=exchange.posts("/fapi/v1/order")
            val missing=coordinator.closePosition("BTCUSDT",direction,"0.7","W2X_CRS_MISSING_${direction.name}","COURSE_STAGE_TRIM",true)
            assertFalse(missing.getBoolean("ok"))
            assertTrue(missing.getString("blocker").contains("COURSE_FINAL_TRIM_VERIFIER_REQUIRED"))
            assertEquals(before,exchange.posts("/fapi/v1/order"))
            var checks=0
            coordinator.bookCourseTrimVerifier={ _,_ -> checks++;check(checks==1) { "COURSE_SOURCE_CHANGED_BEFORE_POST" } }
            val raced=coordinator.closePosition("BTCUSDT",direction,"0.7","W2X_CRS_RACE_${direction.name}","COURSE_STAGE_TRIM",true)
            assertFalse(raced.getBoolean("ok"));assertEquals(2,checks)
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            assertEquals(before,exchange.posts("/fapi/v1/order"))
            assertEquals(1.0,kotlin.math.abs(exchange.quantity),1e-9)
            assertTrue(exchange.algos.values.any { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" })
        }
    }

    @Test fun courseInitialUnitRequiresTheExactSignedFillAndDoesNotRescaleAfterRestart() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(partial in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.reconcile()
            val proposal=williamsCourseInitialQuantity("0.9","0.001")
            val initial=plan(direction).copy(quantity="0.9")
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            val rows=coordinator.intentsSnapshot()
            // Legacy filled intent fixture. Course new-risk execution remains
            // disabled; source/degree admission is not claimed by this test.
            rows.getJSONObject(0).put("strategy_profile",williamsCourseProfile).put("signal_type","COURSE_HUMP")
                .put("setup_context",JSONObject().put("course_exact_quantity",proposal).put("course_initial_stage","W1")
                    .put("course_origin_at",10L).toString())
            journal.writeState("futures_campaign_intents_v2",rows.toString())
            exchange.fill(if(partial) .5 else 1.0,childClientId="SIGNED_ACTUAL_CHILD")
            if(partial) exchange.orders.getValue("42").put("status","CANCELED")
            val reconciled=coordinator.reconcile()
            assertTrue(reconciled.toString(),reconciled.getBoolean("ok"))
            val owner=coordinator.intentsSnapshot().getJSONObject(0);val management=owner.getJSONObject("edition_management")
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            if(partial) {
                assertFalse(management.has("course_unit_quantity"))
                assertEquals("COURSE_STAGE_REQUIRES_FULL_TERMINAL_FILL",management.getString("course_quantity_blocker"))
            } else {
                assertEquals("0.3",management.getString("course_unit_quantity"))
                assertEquals("W1",management.getString("course_stage"))
                assertEquals(1,management.getJSONArray("course_stage_fills").length())
            }
            val posts=exchange.posts("/fapi/v1/algoOrder")
            val restarted=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(restarted.reconcile().toString(),restarted.reconcile().getBoolean("ok"))
            val after=restarted.intentsSnapshot().getJSONObject(0).getJSONObject("edition_management")
            assertEquals(management.toString(),after.toString())
            assertEquals(posts,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    @Test fun courseTerminalExitPreservesExactlyOnceAndRequiresCleanSignedFlatForHandoff() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(change in listOf("NORMAL","LOST_REPLY","PARTIAL","SOURCE_RACE","PROFIT_RACE")) {
            val hour=3_600_000L
            val base=System.currentTimeMillis()/hour*hour-153*hour
            val source=courseExitCase(JSONObject().put("kind","W5"),direction.name,base,200.0)
            val journal=Journal();val exchange=Exchange(direction)
            var coordinator=NativeFuturesCampaignCoordinator(journal,clock={ source.now }) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(signalBarTimeMs=source.now-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            markLegacySource(journal,coordinator,williamsCourseProfile,"COURSE_HUMP")
            val rows=coordinator.intentsSnapshot();val owner=rows.getJSONObject(0)
            val evidence=source.observe().put("owner_id",initial.clientId).put("symbol","BTCUSDT").put("side",direction.name)
                .put("observed_at_ms",source.now)
            assertEquals("OBSERVED_COURSE_SOURCE_RULE",evidence.getString("status"))
            owner.put("position_open_observed_at_ms",source.opened).put("edition_management",JSONObject()
                .put("course_origin_at",source.origin).put("course_stage","W5").put("course_unit_quantity",1.0/6)
                .put("course_unit_basis","CONFIRMED_INITIAL_FILL").put("course_w3_to_w1_ratio",2.0).put("exit_evidence",evidence))
            assertTrue(journal.writeState("futures_campaign_intents_v2",rows.toString()))
            exchange.mark=source.contract
            var checks=0
            coordinator.bookCourseExitVerifier={ current,reason ->
                checks++
                check(williamsCourseExitProofBlocker(current.getJSONObject("edition_management").getJSONObject("exit_evidence"),
                    current.getString("client_id"),"BTCUSDT",direction.name,reason,source.opened,source.now)==null)
                val proof=if(change=="SOURCE_RACE" && checks>1) JSONObject().put("status","BLOCKED") else source.observe()
                check(proof.optString("status")=="OBSERVED_COURSE_SOURCE_RULE" && proof.optString("reason")==reason) { "COURSE_SOURCE_CHANGED" }
                if(change=="PROFIT_RACE" && checks>1) exchange.mark=100.0
                check(williamsTc2OppositeExitProfitBlocker(direction.name,current.getString("actual_entry_price").toDouble(),exchange.mark)==null) { "COURSE_PROFIT_CHANGED" }
            }
            if(change=="LOST_REPLY") exchange.loseCloseReply=true
            if(change=="PARTIAL") exchange.closeFraction=.5
            val before=exchange.posts("/fapi/v1/order")
            val result=coordinator.closePosition("BTCUSDT",direction,"1","W2X_CRSEND_${direction.name}_$change","COURSE_W5_TERMINAL_CONFIRMED")
            if(change=="NORMAL") assertTrue(result.toString(),result.getBoolean("ok"))
            else assertFalse(result.getBoolean("ok"))
            coordinator=NativeFuturesCampaignCoordinator(journal,clock={ source.now }) { exchange.adapter() }
            if(change in setOf("NORMAL","LOST_REPLY")) {
                assertTrue(coordinator.reconcile().toString(),coordinator.status().optBoolean("ready_for_new_entry"))
                val closed=coordinator.intentsSnapshot().getJSONObject(0)
                val handoff=closed.getJSONObject("edition_management").getJSONObject("course_flat_handoff")
                assertEquals(initial.clientId,handoff.getString("motive_campaign_id"));assertFalse(handoff.getBoolean("can_place_orders"))
                assertEquals(0.0,kotlin.math.abs(exchange.quantity),1e-9)
                assertEquals(before+1,exchange.posts("/fapi/v1/order"))
            } else if(change=="PARTIAL") {
                coordinator.reconcile()
                assertTrue(kotlin.math.abs(exchange.quantity)>0)
                assertFalse(coordinator.intentsSnapshot().getJSONObject(0).getJSONObject("edition_management").has("course_flat_handoff"))
            } else {
                assertEquals(1.0,kotlin.math.abs(exchange.quantity),1e-9)
                assertEquals(before,exchange.posts("/fapi/v1/order"))
            }
            if(change !in setOf("NORMAL","LOST_REPLY")) assertTrue(exchange.algos.values.any {
                it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" })
        }
    }

    @Test fun courseTerminalDoorRejectsMissingVerifierAndArbitraryReasonsBeforeMutation() {
        val journal=Journal();val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        coordinator.reconcile();assertTrue(coordinator.submitPendingEntry(plan(exchange.direction)).getBoolean("accepted"))
        exchange.fill();coordinator.reconcile();markLegacySource(journal,coordinator,williamsCourseProfile,"COURSE_HUMP")
        val before=exchange.posts("/fapi/v1/order")
        for(reason in listOf("TAKE_PROFIT","CORRECTION_PARENT_PRICE_INVALIDATED","COURSE_W5_TERMINAL_CONFIRMED")) {
            val result=coordinator.closePosition("BTCUSDT",exchange.direction,"1","W2X_CRS_BAD_${reason.take(15)}",reason)
            assertFalse(result.getBoolean("ok"));assertTrue(result.optBoolean("safe_rejection"))
        }
        assertEquals(before,exchange.posts("/fapi/v1/order"));assertEquals("OPEN_PROTECTED",coordinator.state.name)
    }

    @Ignore("NTD new exposure is disabled; historical transport fixture retained for independent future approval")
    @Test fun ntdZoneAdditionRetainsProtectionAcrossPartialFillLostReplyAndRestart() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(lost in listOf(false,true)) for(fraction in listOf(.5,1.0)) {
            val journal=Journal();val exchange=Exchange(direction)
            val now=System.currentTimeMillis()/WILLIAMS_H1_MS*WILLIAMS_H1_MS+1000
            val coordinator=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            assertTrue(coordinator.reconcile().toString(),coordinator.status().optBoolean("ready_for_new_entry"))
            val initial=ntdBookPlan(plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL"),now)
            requireNtdBookProof(coordinator)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            val oldProtection=exchange.algos.values.single { it.optString("closePosition")=="true" }.getString("clientAlgoId")
            val leverageCalls=exchange.posts("/fapi/v1/leverage")
            exchange.loseAdditionReply=lost;exchange.additionFraction=fraction
            val addition=ntdBookPlan(initial.copy(kind=NativeFuturesCampaignCoordinator.EntryKind.MARKET,quantity="0.5",
                entryPrice=exchange.mark.toString(),clientId="W2A_NTD_${direction.name}_${lost}_$fraction",riskPct=.001,
                entryClientId=initial.clientId,signalType="ZONE_ADD_ON",executionSemantics="MARKET_ON_CLOSE",
                referenceClose=exchange.mark,signalBarTimeMs=initial.signalBarTimeMs),now)
            requireNtdBookProof(coordinator)
            val submitted=coordinator.submitPendingEntry(addition)
            assertTrue(submitted.toString(),submitted.getBoolean("accepted"))
            assertEquals("PROTECTED",submitted.getString("protection"))
            // New coordinator represents process death after an unknown response.
            val recovered=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val reconciled=recovered.reconcile()
            assertTrue(reconciled.toString(),reconciled.getBoolean("ok"))
            assertEquals(reconciled.toString(),"OPEN_PROTECTED",recovered.state.name)
            assertEquals(1.0+.5*fraction,kotlin.math.abs(exchange.quantity),1e-9)
            assertEquals(leverageCalls,exchange.posts("/fapi/v1/leverage"))
            val protection=exchange.algos.values.filter { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" }
            assertEquals(1,protection.size);assertEquals(oldProtection,protection.single().getString("clientAlgoId"))
            assertFalse(recovered.submitPendingEntry(addition).getBoolean("accepted"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            val closed=recovered.closePosition("BTCUSDT",direction,kotlin.math.abs(exchange.quantity).toString(),
                "W2X_NTD_${direction.name}_${lost}_$fraction","MANUAL_EXIT")
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
            for (prefix in listOf("W2E_", "W2F_")) for (leverage in listOf(1, 2)) {
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

    @Test fun tc2RejectsArbitraryExitAndForgedOppositionBeforeAnyOrderMutation() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val beforeRequests=exchange.requests.size
            val before=coordinator.intentsSnapshot().getJSONObject(0)
            val stopId=before.getString("protection_client_id")
            for(reason in listOf("STRATEGY_EXIT","CORRECTION_PARENT_PRICE_INVALIDATED","LIQUIDATION_GUARD","TAKE_PROFIT")) {
                val result=coordinator.closePosition("BTCUSDT",direction,"1","W2X_BLOCK_"+direction.name,reason)
                assertFalse(result.getBoolean("ok"))
                assertEquals("TC2_EXIT_REQUIRES_BOOK_OPPOSITE_TRIGGER",result.getString("blocker"))
                assertFalse(result.getBoolean("mutation_attempted"))
                assertEquals(beforeRequests,exchange.requests.size)
                assertEquals("OPEN_PROTECTED",coordinator.state.name)
            }
            // A label claiming book opposition is not an authorization token.
            // The signed STOP/mark may be read but no reduce-only POST is sent
            // without the matching durable closed-H1 decision and owner evidence.
            val postsBefore=exchange.posts("/fapi/v1/order")
            val forged=coordinator.closePosition("BTCUSDT",direction,"1","W2X_FORGED_"+direction.name,
                "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            assertFalse(forged.getBoolean("ok"))
            assertTrue(forged.getString("blocker").startsWith("TC2_OPPOSITE_EXIT_"))
            assertEquals(postsBefore,exchange.posts("/fapi/v1/order"))
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            assertEquals(stopId,coordinator.intentsSnapshot().getJSONObject(0).getString("protection_client_id"))
            assertTrue(CockpitDiagnostics.rows(coordinator.intentsSnapshot()).none {it.optString("intent_role")=="EXIT"})
        }
    }

    @Test fun confirmedBookOppositeExitFlattensOnlyOwnedPositionWithStopHeldUntilFill() {
        val h=3_600_000L
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var time=System.currentTimeMillis()-4*h
            val journal=Journal()
            val (coordinator,exchange)=open(direction,journal=journal,clock={time})
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            val opened=owner.getLong("position_open_observed_at_ms")
            val clientId=owner.getString("client_id")
            val protectionId=owner.getString("protection_client_id")
            // The mock exchange retains its authenticated open stop until
            // the reduce-only close is confirmed FLAT.
            time+=4*h
            val signalAt=time-2*h
            val observed=time-1_000L
            val opposite=if(direction.name=="LONG") "SHORT" else "LONG"
            // Book-opposite signal can flatten only once old campaign is
            // in genuine positive mark-to-entry territory after cost reserves.
            val trigger=if(direction.name=="LONG") 110.0 else 90.0
            exchange.mark=if(direction.name=="LONG") 99.0 else 101.0
            val signalId="BOOK:OPPOSITE:"+direction.name
            val event=JSONObject().put("state","TRIGGERED").put("known_at_ms",time-60_000L)
                .put("basis","CLOSED_BAR_EXTREME")
            val detected=JSONObject().put("signal_id",signalId).put("direction",opposite)
                .put("strategy_profile",williamsTc2Profile)
                .put("execution_semantics","STOP_ENTRY")
                .put("signal_bar_time_ms",signalAt).put("trigger",trigger).put("event",event)
            val decision=JSONObject().put("observed_at_ms",observed)
                .put("trigger_price_basis","CONTRACT_PRICE")
                .put("scope_started_at_ms",opened).put("direction",direction.name)
                .put("opposition",detected).put("uncertain",JSONArray())
            assertTrue(journal.writeState("decision_h1_opposition_BTCUSDT",decision.toString()))
            val reason="OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
            val management=JSONObject().put("exit_evidence",JSONObject()
                .put("reason",reason).put("signal_id",signalId)
                .put("observed_at_ms",observed).put("opposition_check",decision))
            coordinator.saveManagementState("BTCUSDT",clientId,management)
            val postsBefore=exchange.posts("/fapi/v1/order")
            // Even a fully confirmed opposite Wise-Man breakout cannot
            // cause an early loss-taking market exit. Keep the exchange SL.
            val premature=coordinator.closePosition("BTCUSDT",direction,"1",
                "W2X_PREMATURE_"+direction.name,reason)
            assertFalse(premature.toString(),premature.optBoolean("ok",false))
            assertEquals("TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE",
                premature.optString("blocker"))
            assertEquals(postsBefore,exchange.posts("/fapi/v1/order"))
            assertEquals("OPEN_PROTECTED",coordinator.state.name)
            assertTrue(exchange.algos.values.any {
                it.optString("clientAlgoId")==protectionId && it.optString("algoStatus")=="NEW"
            })
            exchange.mark=if(direction.name=="LONG") 108.0 else 92.0
            exchange.contract=if(direction.name=="LONG") 111.0 else 89.0
            val markOnly=coordinator.closePosition("BTCUSDT",direction,"1",
                "W2X_MARK_ONLY_"+direction.name,reason)
            assertFalse(markOnly.toString(),markOnly.optBoolean("ok",false))
            assertEquals(postsBefore,exchange.posts("/fapi/v1/order"))
            exchange.contract=if(direction.name=="LONG") 108.0 else 92.0
            val result=coordinator.closePosition("BTCUSDT",direction,"1",
                "W2X_BOOK_"+direction.name,reason)
            assertTrue(result.toString(),result.optBoolean("ok",false))
            assertEquals(postsBefore+1,exchange.posts("/fapi/v1/order"))
            assertEquals(0.0,exchange.quantity,0.0)
            assertFalse(exchange.algos.values.any {
                it.optString("clientAlgoId")==protectionId && it.optString("algoStatus")=="NEW"
            })
            assertTrue(coordinator.intentsSnapshot().let { rows->
                (0 until rows.length()).map { rows.getJSONObject(it) }.any {
                    it.optString("intent_role")=="EXIT" &&
                    it.optString("reason")==reason && it.optString("exit_authority")=="PINNED_EDITION_RULE"
                }
            })
        }
    }

    private fun persistReversalSignal(coordinator:NativeFuturesCampaignCoordinator,journal:Journal,
        direction:NativeFuturesCampaignCoordinator.Direction,now:Long) {
        val owner=coordinator.intentsSnapshot().getJSONObject(0)
        val opposite=if(direction.name=="LONG") "SHORT" else "LONG"
        val trigger=if(opposite=="LONG") 90.0 else 110.0
        val signalId="REVERSAL_BOOK_"+opposite
        val signalAt=now-2*3_600_000L
        val decision=JSONObject().put("direction",direction.name)
            .put("scope_started_at_ms",owner.getLong("position_open_observed_at_ms"))
            .put("observed_at_ms",now-1000).put("trigger_price_basis","CONTRACT_PRICE")
            .put("uncertain",JSONArray()).put("opposition",JSONObject().put("signal_id",signalId)
                .put("direction",opposite).put("strategy_profile",williamsTc2Profile).put("execution_semantics","STOP_ENTRY")
                .put("signal_bar_time_ms",signalAt).put("trigger",trigger)
                .put("event",JSONObject().put("state","TRIGGERED").put("known_at_ms",now-2000)))
        assertTrue(journal.writeState("decision_h1_opposition_BTCUSDT",decision.toString()))
        val candidate=JSONObject().put("symbol","BTCUSDT").put("direction",opposite)
            .put("strategy_profile",williamsTc2Profile).put("execution_semantics","STOP_ENTRY")
            .put("signal_id",signalId).put("signal_type",if(opposite=="LONG") "REVERSAL" else "SHORT_REVERSAL")
            .put("signal_bar_time_ms",signalAt).put("trigger",trigger).put("protective_stop",if(opposite=="LONG") 85.0 else 115.0)
        coordinator.saveManagementState("BTCUSDT",owner.getString("client_id"),JSONObject().put("exit_evidence",JSONObject()
            .put("reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED").put("signal_id",signalId).put("observed_at_ms",now-1000)
            .put("opposition_check",decision).put("reverse_candidate",candidate)))
    }

    private fun reversalPlan(c:JSONObject)=NativeFuturesCampaignCoordinator.EntryPlan("BTCUSDT",
        NativeFuturesCampaignCoordinator.Direction.valueOf(c.getString("direction")),
        NativeFuturesCampaignCoordinator.EntryKind.MARKET,"0.1",
        if(c.getString("direction")=="LONG") "90.1" else "109.9",c.getDouble("protective_stop").toString(),
        c.getString("client_id"),.002,signalType=c.getString("signal_type"),signalId=c.getString("signal_id"),
        signalBarTimeMs=c.getLong("signal_bar_time_ms"),reversalOwnerId=c.getString("owner_id"),
        executionSemantics=WILLIAMS_TC2_REVERSAL)

    @Test fun bookReversalUsesTwoDistinctFilledCampaignsAndNewProtectionWithoutDuplicateEntry() {
        val h=3_600_000L
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(lostReply in listOf(false,true)) {
            var now=System.currentTimeMillis()-4*h
            val journal=Journal();val (coordinator,exchange)=open(direction,journal=journal,clock={now})
            now+=4*h;persistReversalSignal(coordinator,journal,direction,now)
            val ownerId=coordinator.intentsSnapshot().getJSONObject(0).getString("client_id")
            val oldStop=coordinator.intentsSnapshot().getJSONObject(0).getString("protection_client_id")
            exchange.mark=if(direction.name=="LONG") 110.0 else 90.0
            exchange.loseCloseReply=lostReply
            val closed=coordinator.closePosition("BTCUSDT",direction,"1","REV_EXIT_${direction.name}_$lostReply",
                "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            assertEquals(!lostReply,closed.optBoolean("ok"))
            val resumed=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            val recovered=resumed.reconcile()
            assertTrue(recovered.toString(),recovered.optBoolean("ok"))
            val c=williamsTc2ReversalCandidate(resumed.intentsSnapshot(),ownerId,now)!!
            assertFalse(exchange.algos.values.any { it.optString("clientAlgoId")==oldStop && it.optString("algoStatus")=="NEW" })
            val p=reversalPlan(c)
            var verified=false
            resumed.bookH1EntryVerifier={ checked -> assertEquals(p.signalId,checked.signalId);verified=true }
            exchange.liquidation=if(p.direction.name=="LONG") "50" else "200"
            exchange.loseAdditionReply=lostReply
            val before=exchange.posts("/fapi/v1/order")
            val reversed=resumed.submitPendingEntry(p)
            assertTrue(verified)
            assertTrue(reversed.toString(),reversed.optBoolean("accepted"))
            assertEquals("PROTECTED",reversed.optString("protection"))
            assertFalse(reversed.has("blocker"));assertFalse(reversed.has("error"))
            assertEquals("OPEN_PROTECTED",resumed.state.name)
            assertEquals(before+1,exchange.posts("/fapi/v1/order"))
            assertEquals(if(p.direction.name=="LONG") .1 else -.1,exchange.quantity,1e-10)
            now+=6*h // Expired entry proof never disables recovery of an existing fill/STOP.
            val restarted=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            val protectedResult=restarted.reconcile()
            assertTrue(protectedResult.toString(),protectedResult.optBoolean("ok"))
            assertEquals("OPEN_PROTECTED",restarted.state.name)
            assertNull(williamsTc2ReversalCandidate(restarted.intentsSnapshot(),ownerId,now))
            assertFalse(restarted.submitPendingEntry(p).getBoolean("accepted"))
            assertEquals(before+1,exchange.posts("/fapi/v1/order"))
            val reverse=restarted.intentsSnapshot().let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .single { it.optString("reversal_owner_id")==ownerId } }
            assertEquals("OPEN_PROTECTED",campaignLifecycleStatus(reverse))
            assertNotEquals(oldStop,reverse.getString("protection_client_id"))
            assertTrue(exchange.algos.values.any { it.optString("clientAlgoId")==reverse.getString("protection_client_id") &&
                it.optString("workingType")=="MARK_PRICE" && it.optString("closePosition")=="true" })
        }
    }

    @Test fun partialOppositeExitCannotAuthorizeReverseRiskAfterRestart() {
        val h=3_600_000L
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var now=System.currentTimeMillis()-4*h
            val journal=Journal();val (coordinator,exchange)=open(direction,journal=journal,clock={now})
            now+=4*h;persistReversalSignal(coordinator,journal,direction,now)
            val ownerId=coordinator.intentsSnapshot().getJSONObject(0).getString("client_id")
            exchange.mark=if(direction.name=="LONG") 110.0 else 90.0
            exchange.closeStatus="PARTIALLY_FILLED";exchange.closeFraction=.5
            val result=coordinator.closePosition("BTCUSDT",direction,"1","PARTIAL_REVERSE_"+direction.name,
                "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            assertFalse(result.getBoolean("ok"));assertEquals(.5,kotlin.math.abs(exchange.quantity),1e-10)
            assertNull(williamsTc2ReversalCandidate(coordinator.intentsSnapshot(),ownerId,now))
            val restarted=NativeFuturesCampaignCoordinator(journal,clock={now}) { exchange.adapter() }
            restarted.reconcile()
            assertNull(williamsTc2ReversalCandidate(restarted.intentsSnapshot(),ownerId,now))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            assertTrue(exchange.algos.values.any { it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW" })
        }
    }

    @Test fun newlySubmittedBookEntryCannotReconcileAgainstAnotherPriceBasis() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            assertEquals("CONTRACT_PRICE",owner.getString("entry_trigger_basis"))
            exchange.algos.values.single().put("workingType","MARK_PRICE")
            assertFalse(coordinator.reconcile().getBoolean("ok"))
            assertEquals("RECONCILE_REQUIRED",coordinator.state.name)
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
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

    @Test fun canceledPreviouslyConfirmedStopTriggersExactlyOneOwnedEmergencyExitLongAndShort() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction, prefix="W2H_")
            val stop = exchange.algos.values.single {
                it.optString("closePosition") == "true" && it.optString("algoStatus") == "NEW"
            }
            stop.put("algoStatus", "CANCELED")
            val failed = coordinator.reconcile()
            assertFalse(failed.toString(), failed.getBoolean("ok"))
            assertEquals("POSITION_UNPROTECTED", coordinator.state.name)
            assertEquals(0, exchange.posts("/fapi/v1/order"))
            val requests = failed.getJSONArray("required_safety_exits")
            assertEquals(failed.toString(), 1, requests.length())
            val request = requests.getJSONObject(0)
            assertEquals("UNPROTECTED_POSITION_GUARD", request.getString("reason"))
            assertEquals(stop.getString("clientAlgoId"), request.getString("missing_protection_client_id"))
            // A fake/stale caller cannot bypass binding of current source/side/qty.
            val foreign = JSONObject(request.toString()).put("entry_client_id", "FOREIGN")
            assertFalse(coordinator.executeRequiredUnprotectedSafetyExit(foreign).getBoolean("ok"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
            // A blocked attempt must not lose the already proven hazard on
            // the next reconciliation tick.
            val retry=coordinator.reconcile()
            assertEquals(retry.toString(),1,retry.getJSONArray("required_safety_exits").length())
            val closed = coordinator.executeRequiredUnprotectedSafetyExit(request)
            assertTrue(closed.toString(), closed.getBoolean("ok"))
            assertEquals(0.0, exchange.quantity, 0.0)
            assertEquals(1, exchange.posts("/fapi/v1/order"))
            assertFalse(coordinator.executeRequiredUnprotectedSafetyExit(request).getBoolean("ok"))
            assertEquals(1, exchange.posts("/fapi/v1/order"))
            assertEquals("VERIFIED_RISK_REDUCTION", coordinator.intentsSnapshot().let { intents ->
                (0 until intents.length()).map { intents.getJSONObject(it) }
                    .last { it.optString("intent_role")=="EXIT" }
            }.getString("exit_authority"))
        }
    }

    @Test fun unknownStopResponseNeverAuthorizesAnEmergencyMarketClose() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            // The order disappeared from the exchange mock, and querying
            // its clientAlgoId returns -2013/unknown, NOT a terminal response.
            val stopKey = exchange.algos.keys.single { key ->
                exchange.algos.getValue(key).optString("closePosition") == "true"
            }
            exchange.algos.remove(stopKey)
            val result = coordinator.reconcile()
            assertFalse(result.getBoolean("ok"))
            assertEquals(0, result.getJSONArray("required_safety_exits").length())
            assertEquals(0, exchange.posts("/fapi/v1/order"))
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
        }
    }

    @Test fun foreignOrIncreasedPositionCannotUseMissingStopEmergencyCapability() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            exchange.quantity *= 2
            val stop = exchange.algos.values.single { it.optString("closePosition")=="true" }
            stop.put("algoStatus", "CANCELED")
            val result = coordinator.reconcile()
            assertFalse(result.getBoolean("ok"))
            assertEquals(0, result.optJSONArray("required_safety_exits")?.length() ?: 0)
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun neverSubmittedInvalidInitialStopIsSafelyReducedButUnknownAckIsNot() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill()
            // Fill occurred while price crossed the planned initial SL.
            // No STOP_MARKET was ever POSTed, which is much stronger proof
            // than an HTTP timeout or an unrecognized clientAlgoId.
            exchange.mark=if(direction.name=="LONG") 94.0 else 106.0
            val rec=coordinator.reconcile()
            assertFalse(rec.getBoolean("ok"))
            val requests=rec.getJSONArray("required_safety_exits")
            assertEquals(rec.toString(),1,requests.length())
            val req=requests.getJSONObject(0)
            assertEquals("NEVER_SUBMITTED_PROVEN",req.getString("guard_origin"))
            assertEquals(0,exchange.algos.values.count {
                it.optString("closePosition")=="true"
            })
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            val retry=coordinator.reconcile()
            assertEquals(retry.toString(),1,retry.getJSONArray("required_safety_exits").length())
            val result=coordinator.executeRequiredUnprotectedSafetyExit(req)
            assertTrue(result.toString(),result.getBoolean("ok"))
            assertEquals(0.0,exchange.quantity,0.0)
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            assertFalse(coordinator.executeRequiredUnprotectedSafetyExit(req).getBoolean("ok"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun lostEmergencyCloseAcknowledgementCannotCauseDuplicateReduceOnly() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val stop=exchange.algos.values.single { it.optString("closePosition")=="true" }
            stop.put("algoStatus","CANCELED")
            val req=coordinator.reconcile().getJSONArray("required_safety_exits").getJSONObject(0)
            exchange.loseCloseReply=true
            val uncertain=coordinator.executeRequiredUnprotectedSafetyExit(req)
            assertFalse(uncertain.toString(),uncertain.getBoolean("ok"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            assertFalse(coordinator.executeRequiredUnprotectedSafetyExit(req).getBoolean("ok"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            exchange.loseCloseReply=false
            // Reconciliation must resolve the first exchange reduction,
            // not send a second order with a new client ID.
            coordinator.reconcile()
            assertEquals(1,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun canceledConfirmedStopStillEmitsEmergencyExitAfterMarkAlreadyCrossesItsLevel() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val stop=exchange.algos.values.single { it.optString("closePosition")=="true" }
            stop.put("algoStatus","CANCELED")
            exchange.mark=if(direction.name=="LONG") 94.0 else 106.0
            val first=coordinator.reconcile()
            assertFalse(first.getBoolean("ok"))
            assertEquals("POSITION_UNPROTECTED",coordinator.state.name)
            val requests=first.getJSONArray("required_safety_exits")
            assertEquals(first.toString(),1,requests.length())
            val again=coordinator.reconcile()
            assertEquals(again.toString(),1,again.getJSONArray("required_safety_exits").length())
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            val reduction=coordinator.executeRequiredUnprotectedSafetyExit(requests.getJSONObject(0))
            assertTrue(reduction.toString(),reduction.getBoolean("ok"))
            assertEquals(0.0,exchange.quantity,0.0)
            assertEquals(1,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun terminalStopMustMatchExactIdentityAndHaveNoTriggeredChild() {
        val corruptions:List<(JSONObject)->Unit> = listOf(
            { it.put("symbol","ETHUSDT") }, { it.put("clientAlgoId","OTHER_CAMPAIGN") },
            { it.put("side","INVALID") }, { it.put("orderType","TAKE_PROFIT_MARKET") },
            { it.put("triggerPrice","1") }, { it.put("closePosition",false) },
            { it.put("positionSide","LONG") }, { it.put("workingType","CONTRACT_PRICE") },
            { it.put("actualOrderId","87") }, { it.remove("actualOrderId") }
        )
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(crossed in listOf(false,true))
            for(corrupt in corruptions) {
                val (coordinator,exchange)=open(direction)
                exchange.algos.values.single { it.optString("closePosition")=="true" }.put("algoStatus","CANCELED")
                if(crossed) exchange.mark=if(direction.name=="LONG") 94.0 else 106.0
                exchange.queriedStopTransform={ row -> row.also(corrupt) }
                val result=coordinator.reconcile()
                assertFalse(result.getBoolean("ok"))
                assertEquals(result.toString(),0,result.getJSONArray("required_safety_exits").length())
                assertEquals(0,exchange.posts("/fapi/v1/order"))
            }
    }

    @Test fun emergencyExitRechecksTheChildAtBothMutationBoundaries() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(changeAt in listOf(1,2)) {
            val (coordinator,exchange)=open(direction)
            exchange.algos.values.single { it.optString("closePosition")=="true" }.put("algoStatus","CANCELED")
            val request=coordinator.reconcile().getJSONArray("required_safety_exits").getJSONObject(0)
            var queries=0
            exchange.queriedStopTransform={ row ->
                queries++
                if(queries>=changeAt) row.put("actualOrderId","87") else row
            }
            val result=coordinator.executeRequiredUnprotectedSafetyExit(request)
            assertFalse(result.toString(),result.getBoolean("ok"))
            assertEquals(changeAt,queries)
            assertEquals(0,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun additionsRequireOwnerBoundBookProofAndFreshFinalVerifier() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(invalidate in listOf(false,true)) {
            val exchange=Exchange(direction)
            var now=java.time.Instant.parse("2026-10-21T10:00:10Z").toEpochMilli()
            val coordinator=NativeFuturesCampaignCoordinator(Journal(),clock={now}) { exchange.adapter() }
            val first=plan(direction).copy(signalType=if(direction.name=="LONG") "REVERSAL" else "SHORT_REVERSAL",
                signalBarTimeMs=now/WILLIAMS_H1_MS*WILLIAMS_H1_MS-2*WILLIAMS_H1_MS,signalId="first")
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(first).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            now+=WILLIAMS_H1_MS
            val price=exchange.mark+if(direction.name=="LONG") 1 else -1
            val bookStop=if(direction.name=="LONG") price-2 else price+2
            val signalAt=now/WILLIAMS_H1_MS*WILLIAMS_H1_MS-WILLIAMS_H1_MS
            val type=if(direction.name=="LONG") "FRACTAL" else "SHORT_FRACTAL"
            val proof=williamsBookH1Proof("addition",direction.name,type,williamsTc2Profile,signalAt,
                price,bookStop,.1,first.clientId,first.protectiveStop.toDouble())
            val addition=first.copy(clientId="W2A_VERIFIED_${direction.name}",entryClientId=first.clientId,
                signalType=type,signalId="addition",signalBarTimeMs=signalAt,entryPrice=price.toString(),
                quantity="0.1",riskPct=.001,setupContext=JSONObject().put("book_h1_entry",proof).toString())
            coordinator.requireBookH1ProofForAutomatedEntries=true
            val missing=coordinator.submitPendingEntry(addition.copy(setupContext="{}"))
            assertFalse(missing.getBoolean("accepted"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder")) // initial entry + protective SL
            var configurationChecked=false;var verified=false
            exchange.afterSymbolConfig={configurationChecked=true}
            coordinator.bookH1EntryVerifier={ checked ->
                assertTrue(configurationChecked);assertEquals(first.clientId,checked.entryClientId)
                verified=true
                if(invalidate) error("H1_ADDITION_TEETH_CHANGED_AFTER_PREFLIGHT")
            }
            val result=coordinator.submitPendingEntry(addition)
            assertTrue(verified)
            assertEquals(result.toString(),!invalidate,result.getBoolean("accepted"))
            assertEquals(if(invalidate) 2 else 3,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals(1,exchange.algos.values.count { it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW" })
        }
    }

    @Test fun markCrossingBeforeStopPostDoesNotLeaveAnUnknownPhantomStop() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill()
            var reads=0
            exchange.beforeMarkRead={
                if(++reads==2) exchange.mark=if(direction.name=="LONG") 94.0 else 106.0
            }
            assertFalse(coordinator.reconcile().getBoolean("ok"))
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            // Updated P0 recovery persists proof immediately after the
            // crossed-mark hazard; it must NOT create an unproven stop POST.
            assertEquals("NEVER_SUBMITTED_PROVEN",owner.getString("protection_status"))
            assertEquals("NEVER_SUBMITTED_PROVEN",owner.getString("unprotected_safety_guard_origin"))
            assertTrue(owner.optString("protection_client_id").isBlank())
            assertFalse(owner.optBoolean("protection_post_attempted",false))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder")) // Only the initial entry was posted.
            val next=coordinator.reconcile()
            val request=next.getJSONArray("required_safety_exits").getJSONObject(0)
            assertEquals("NEVER_SUBMITTED_PROVEN",request.getString("guard_origin"))
            assertTrue(coordinator.executeRequiredUnprotectedSafetyExit(request).getBoolean("ok"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
            assertEquals(0.0,exchange.quantity,0.0)
        }
    }

    @Test fun actualLostProtectivePostReplyKeepsItsIdAndIsAdoptedWithoutRetry() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertTrue(coordinator.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill();exchange.loseProtectionReply=true
            assertFalse(coordinator.reconcile().getBoolean("ok"))
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            assertEquals("SUBMISSION_UNKNOWN",owner.getString("protection_status"))
            assertTrue(owner.getString("protection_client_id").isNotBlank())
            val next=coordinator.reconcile()
            assertTrue(next.toString(),next.getBoolean("ok"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder")) // Entry + original STOP, never a duplicate.
            assertEquals(0,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun previousInstallWilliamsStopIsObservedNotCanceledOrSilentlyAdoptedLongAndShort() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            exchange.quantity=if(direction.name=="LONG") 1.0 else -1.0
            exchange.entry=if(direction.name=="LONG") 101.0 else 99.0
            exchange.mark=if(direction.name=="LONG") 100.0 else 100.0
            val stopId="W2PS_BTCUSDT_1234567890"
            val stop=JSONObject().put("symbol","BTCUSDT")
                .put("clientAlgoId",stopId).put("algoId","5001")
                .put("algoStatus","NEW").put("orderType","STOP_MARKET")
                .put("side",if(direction.name=="LONG") "SELL" else "BUY")
                .put("positionSide","BOTH").put("workingType","MARK_PRICE")
                .put("closePosition","true")
                .put("triggerPrice",if(direction.name=="LONG") "95" else "105")
            exchange.algos["5001"]=stop
            val newInstall=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            val inspected=newInstall.reconcile()
            assertFalse(inspected.toString(),inspected.getBoolean("ok"))
            val observations=inspected.getJSONArray("exchange_protection_observations")
            assertEquals(inspected.toString(),1,observations.length())
            assertEquals("EXTERNAL_PROTECTED",observations.getJSONObject(0).getString("stop_proof"))
            assertEquals(stopId,observations.getJSONObject(0).getString("protection_client_id"))
            assertFalse(observations.getJSONObject(0).getBoolean("automatic_trailing_authorized"))
            assertEquals("RECONCILE_REQUIRED",newInstall.state.name)
            assertEquals(0,newInstall.intentsSnapshot().length())
            assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals("NEW",stop.getString("algoStatus"))
            assertEquals(1,inspected.getJSONArray("external_open_orders").length())
            assertTrue(newInstall.status().getJSONArray("exchange_protection_observations").length()>0)
            assertFalse(newInstall.status().getBoolean("ready_for_new_entry"))
        }
    }

    @Test fun malformedPreviousInstallStopIsNotMisreportedAsProtectedOrReplaced() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity=1.0
        exchange.entry=101.0
        exchange.mark=100.0
        val foreign=JSONObject().put("symbol","BTCUSDT")
            .put("clientAlgoId","W2PS_BTCUSDT_1234567890").put("algoId","5001")
            .put("algoStatus","NEW").put("orderType","STOP_MARKET")
            .put("side","BUY").put("positionSide","BOTH")
            .put("workingType","MARK_PRICE").put("closePosition","true")
            .put("triggerPrice","95")
        exchange.algos["5001"]=foreign
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        val result=coordinator.reconcile()
        assertFalse(result.getBoolean("ok"))
        assertEquals("STOP_UNCONFIRMED",
            result.getJSONArray("exchange_protection_observations")
                .getJSONObject(0).getString("stop_proof"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
    }

    @Test fun resetProtectionBindingOnUpgradeRecoversOnlySameAuthenticatedFillAndStop() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal=Journal()
            val exchange=Exchange(direction)
            val old=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(old.reconcile().getBoolean("ok"))
            assertTrue(old.submitPendingEntry(plan(direction)).getBoolean("accepted"))
            exchange.fill()
            assertTrue(old.reconcile().toString(),old.reconcile().getBoolean("ok"))
            val before=old.intentsSnapshot().getJSONObject(0)
            val legacyStopId=before.getString("protection_client_id")
            val postsBefore=exchange.posts("/fapi/v1/algoOrder")
            val rows=JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
            rows.getJSONObject(0).remove("protection_client_id")
            rows.getJSONObject(0).put("protection_status","NOT_SUBMITTED")
            journal.writeState("futures_campaign_intents_v2",rows.toString())
            val upgraded=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val result=upgraded.reconcile()
            assertTrue(result.toString(),result.getBoolean("ok"))
            assertEquals(legacyStopId,upgraded.intentsSnapshot().getJSONObject(0).getString("protection_client_id"))
            assertEquals("EXACT_DERIVED_W2PS_AND_AUTHENTICATED_FILL",
                upgraded.intentsSnapshot().getJSONObject(0).getString("protection_rebound_proof"))
            assertEquals("PROTECTED",upgraded.intentsSnapshot().getJSONObject(0).getString("protection_status"))
            assertEquals(postsBefore,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun changedQuantityPreventsRebindingEvenWhenOldW2PSExists() {
        val journal=Journal()
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        val old=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        assertTrue(old.reconcile().getBoolean("ok"))
        assertTrue(old.submitPendingEntry(plan(NativeFuturesCampaignCoordinator.Direction.LONG)).getBoolean("accepted"))
        exchange.fill()
        assertTrue(old.reconcile().getBoolean("ok"))
        val rows=JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
        rows.getJSONObject(0).remove("protection_client_id")
        rows.getJSONObject(0).put("protection_status","NOT_SUBMITTED")
        journal.writeState("futures_campaign_intents_v2",rows.toString())
        exchange.quantity*=2
        val revived=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        val r=revived.reconcile()
        assertFalse(r.getBoolean("ok"))
        assertFalse(revived.intentsSnapshot().getJSONObject(0).has("protection_rebound_proof"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
    }

    @Test fun unrelatedManualOrderCannotFreezeOwnedTc2StructuralStopTrail() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange)=open(direction)
            val foreign=JSONObject().put("symbol","SOLUSDT")
                .put("orderId","900").put("clientOrderId","USER_OTHER_PAIR")
                .put("side","BUY").put("status","NEW")
                .put("type","LIMIT").put("positionSide","BOTH")
            exchange.orders["900"]=foreign
            val inspected=coordinator.reconcile()
            assertFalse(inspected.toString(),inspected.getBoolean("ok"))
            assertEquals("RECONCILE_REQUIRED",coordinator.state.name)
            val eligible=inspected.getJSONArray("verified_external_stop_management_symbols")
            assertEquals(inspected.toString(),1,eligible.length())
            assertEquals("BTCUSDT",eligible.getString(0))
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
            val oldProtection=exchange.algos.values.single { it.optString("closePosition")=="true" }
            val newStop=if(direction.name=="LONG") "96" else "104"
            val moved=coordinator.trailProtectiveStop(
                "BTCUSDT",direction,newStop,
                "W2T_BTC_EXTPROOF_"+direction.name,
                reason="TC2_LAST_5_CLOSED_BARS"
            )
            assertTrue(moved.toString(),moved.getBoolean("ok"))
            assertEquals("RECONCILE_REQUIRED",coordinator.state.name)
            assertEquals("CANCELED",oldProtection.getString("algoStatus"))
            assertEquals(1,exchange.algos.values.count {
                it.optString("closePosition")=="true" && it.optString("algoStatus")=="NEW"
            })
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            // Foreign SOL limit order is never canceled or edited by BTC trailing.
            assertEquals("NEW",foreign.getString("status"))
        }
    }

    private fun existingFullStop(exchange:Exchange, direction:NativeFuturesCampaignCoordinator.Direction): JSONObject {
        val stop=JSONObject()
            .put("symbol","BTCUSDT").put("algoId","5001")
            .put("clientAlgoId","W2PS_BTCUSDT_LEGACY")
            .put("algoStatus","NEW").put("orderType","STOP_MARKET")
            .put("side",if(direction==NativeFuturesCampaignCoordinator.Direction.LONG) "SELL" else "BUY")
            .put("positionSide","BOTH").put("workingType","MARK_PRICE")
            .put("closePosition","true")
            .put("triggerPrice",if(direction==NativeFuturesCampaignCoordinator.Direction.LONG) "95" else "105")
        exchange.algos["5001"]=stop
        return stop
    }

    @Test fun operatorApprovedExistingPositionsCanOnlyTightenConfirmedStopsInBothDirections() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            exchange.rejectSecondCloseAll=true
            exchange.quantity=if(direction==NativeFuturesCampaignCoordinator.Direction.LONG) 1.0 else -1.0
            exchange.entry=if(exchange.quantity>0) 101.0 else 99.0
            exchange.mark=100.0
            val old=existingFullStop(exchange,direction)
            val store=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(store) { exchange.adapter() }
            assertFalse(coordinator.reconcile().getBoolean("ok"))
            val approved=coordinator.approveOperatorStopSupervision(
                "BTCUSDT","1",exchange.entry.toString(),old.getString("triggerPrice"),
                "СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
            assertTrue(approved.toString(),approved.getBoolean("ok"))
            assertFalse(approved.getBoolean("strategy_ownership_verified"))
            assertEquals(0,coordinator.intentsSnapshot().length())
            val restart=NativeFuturesCampaignCoordinator(store) { exchange.adapter() }
            assertEquals("ACTIVE",restart.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
            val moved=restart.trailOperatorSupervisedStop(
                "BTCUSDT",direction,
                if(exchange.quantity>0) "96" else "104","W2T_BTC_SUPERVISED")
            assertTrue(moved.toString(),moved.getBoolean("ok"))
            assertEquals("CANCELED",old.getString("algoStatus"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            val submitted=Exchange.params(exchange.requests.single {
                it.method=="POST" && it.url.encodedPath=="/fapi/v1/algoOrder"
            })
            assertEquals("1",submitted["quantity"])
            assertEquals("true",submitted["reduceOnly"])
            assertFalse(submitted.containsKey("closePosition"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
            assertEquals("ACTIVE",restart.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
            assertFalse(restart.status().optBoolean("ready_for_new_entry"))
        }
    }

    @Test fun operatorTakeoverRequiresExactFreshExchangeProofAndDurableConsent() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        existingFullStop(exchange,NativeFuturesCampaignCoordinator.Direction.LONG)
        val store=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(store) { exchange.adapter() }
        for ((qty,stop,consent) in listOf(
            Triple("2","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП"),
            Triple("1","94","СОПРОВОЖДАТЬ ТОЛЬКО СТОП"),
            Triple("1","95","")
        )) {
            assertThrows(Exception::class.java) {
                coordinator.approveOperatorStopSupervision("BTCUSDT",qty,"101",stop,consent)
            }
        }
        assertEquals(0,coordinator.operatorSupervisionsSnapshot().length())
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        store.rejectWrites=true
        assertThrows(Exception::class.java) {
            coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        }
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
    }

    @Test fun supervisedStopFailsClosedOnPositionIncreaseAndLostBinanceReply() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision(
            "BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        exchange.quantity=2.0
        assertThrows(Exception::class.java) {
            coordinator.trailOperatorSupervisedStop("BTCUSDT",
                NativeFuturesCampaignCoordinator.Direction.LONG,"96","W2T_VOLUME_CHANGED")
        }
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        exchange.quantity=1.0
        exchange.loseProtectionReply=true
        val ambiguous=coordinator.trailOperatorSupervisedStop("BTCUSDT",
            NativeFuturesCampaignCoordinator.Direction.LONG,"96","W2T_LOST_REPLY")
        assertFalse(ambiguous.toString(),ambiguous.getBoolean("ok"))
        assertEquals("REPLACEMENT_UNRESOLVED",
            coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
        assertEquals("NEW",old.getString("algoStatus"))
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        assertThrows(Exception::class.java) {
            coordinator.trailOperatorSupervisedStop("BTCUSDT",
                NativeFuturesCampaignCoordinator.Direction.LONG,"97","W2T_SECOND_ATTEMPT")
        }
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        val recovered=coordinator.recoverOperatorSupervisedStop("BTCUSDT")
        assertTrue(recovered.toString(),recovered.getBoolean("ok"))
        assertEquals("ACTIVE",coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
        assertEquals("CANCELED",old.getString("algoStatus"))
        assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
    }

    @Test fun supervisedStopRejectsReusedClientIdentityAndNewForeignOrdersBeforePost() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,NativeFuturesCampaignCoordinator.Direction.LONG)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        assertThrows(Exception::class.java) {
            coordinator.trailOperatorSupervisedStop("BTCUSDT",
                NativeFuturesCampaignCoordinator.Direction.LONG,"96",old.getString("clientAlgoId"))
        }
        exchange.orders["foreign"]=JSONObject().put("symbol","BTCUSDT").put("status","NEW")
        assertThrows(Exception::class.java) {
            coordinator.trailOperatorSupervisedStop("BTCUSDT",
                NativeFuturesCampaignCoordinator.Direction.LONG,"96","W2T_FOREIGN_ORDER")
        }
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals("NEW",old.getString("algoStatus"))
        assertEquals("ACTIVE",coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
    }

    @Test fun uncertainSupervisedReplacementRechecksExposureImmediatelyBeforeOldStopCancellation() {
        for (foreignOrder in listOf(false,true)) {
            val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
            exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
            val old=existingFullStop(exchange,NativeFuturesCampaignCoordinator.Direction.LONG)
            val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
            coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
            exchange.loseProtectionReply=true
            assertFalse(coordinator.trailOperatorSupervisedStop("BTCUSDT",
                NativeFuturesCampaignCoordinator.Direction.LONG,"96","W2T_LOST_RECHECK").getBoolean("ok"))
            exchange.beforeMarkRead={
                if(foreignOrder) exchange.orders["racing"]=JSONObject().put("symbol","BTCUSDT").put("status","NEW")
                else exchange.quantity=2.0
            }
            assertThrows(Exception::class.java) { coordinator.recoverOperatorSupervisedStop("BTCUSDT") }
            assertEquals("NEW",old.getString("algoStatus"))
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.requests.count { it.method=="DELETE" })
            assertEquals("REPLACEMENT_UNRESOLVED",
                coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
        }
    }

    @Test fun approvedSupervisionHasIndependentObservableAuthorityWithoutInventingEntryOwnership() {
        val exchange=Exchange(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        existingFullStop(exchange,NativeFuturesCampaignCoordinator.Direction.LONG)
        val store=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(store) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        assertFalse(coordinator.reconcile().getBoolean("ok"))
        val observation=coordinator.status().getJSONArray("exchange_protection_observations").getJSONObject(0)
        assertEquals("EXTERNAL_PROTECTED",observation.getString("stop_proof"))
        assertTrue(observation.getBoolean("automatic_trailing_authorized"))
        assertEquals("OPERATOR_APPROVED_STOP_ONLY",observation.getString("management_mode"))
        assertFalse(observation.getBoolean("strategy_ownership_verified"))
        coordinator.recordOperatorSupervisionObservation("BTCUSDT","WAITING_FOR_STRUCTURE","No improvement",1234L)
        val restored=NativeFuturesCampaignCoordinator(store) { exchange.adapter() }
            .operatorSupervisionsSnapshot().getJSONObject(0)
        assertEquals("WAITING_FOR_STRUCTURE",restored.getString("last_evaluation_status"))
        assertEquals(1234L,restored.getLong("last_evaluated_closed_bar_at_ms"))
        assertEquals("ACTIVE",restored.getString("state"))
        assertEquals(0,coordinator.intentsSnapshot().length())
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
    }

    @Test fun definiteSupervisedStopRejectionsPersistTheCauseAndResumeOnlyAfterSignedOriginalProof() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) for(code in listOf(-4130,-2021,-1111)) {
            val exchange=Exchange(direction)
            exchange.quantity=if(direction.name=="LONG") 1.0 else -1.0
            exchange.entry=101.0;exchange.mark=100.0
            val old=existingFullStop(exchange,direction)
            val journal=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.approveOperatorStopSupervision("BTCUSDT","1","101",old.getString("triggerPrice"),
                "СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
            val proposed=if(direction.name=="LONG") "96" else "104"
            coordinator.recordOperatorSupervisionObservation("BTCUSDT","STOP_PROPOSED","Closed H1",1234,proposed.toDouble())
            exchange.rejectProtectionCode=code
            assertFalse(coordinator.trailOperatorSupervisedStop("BTCUSDT",direction,proposed,"W2T_REJECTED").getBoolean("ok"))
            coordinator.recordOperatorSupervisionObservation("BTCUSDT","BLOCKED","Recovering")
            val restarted=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val saved=restarted.operatorSupervisionsSnapshot().getJSONObject(0)
            assertEquals("REJECTED",saved.getString("pending_submission_outcome"))
            assertEquals(code,saved.getJSONObject("last_replacement_failure").getInt("exchange_code"))
            assertEquals(1234L,saved.getLong("last_evaluated_closed_bar_at_ms"))
            assertEquals(proposed.toDouble(),saved.getDouble("last_proposed_stop"),0.0)
            val recovered=restarted.recoverOperatorSupervisedStop("BTCUSDT")
            assertTrue(recovered.toString(),recovered.getBoolean("ok"))
            assertEquals("ORIGINAL_RETAINED",recovered.getString("recovery_status"))
            assertEquals("NEW",old.getString("algoStatus"))
            assertEquals(0,exchange.requests.count { it.method=="DELETE" })
            assertEquals(1,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals("ACTIVE",restarted.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
            exchange.rejectProtectionCode=null
            assertTrue(restarted.trailOperatorSupervisedStop("BTCUSDT",direction,proposed,"W2T_AFTER_REJECTION").getBoolean("ok"))
            assertEquals(2,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun failedSupervisedStopPreflightIsNeverTreatedAsAnAcceptedPost() {
        val direction=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(direction)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,direction)
        val coordinator=NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        var markReads=0
        exchange.beforeMarkRead={ if(++markReads==2) exchange.mark=95.5 }
        assertFalse(coordinator.trailOperatorSupervisedStop("BTCUSDT",direction,"96","W2T_PREFLIGHT").getBoolean("ok"))
        assertEquals("NOT_SUBMITTED",coordinator.operatorSupervisionsSnapshot().getJSONObject(0)
            .getString("pending_submission_outcome"))
        assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
        exchange.beforeMarkRead=null;exchange.mark=100.0
        assertTrue(coordinator.recoverOperatorSupervisedStop("BTCUSDT").getBoolean("ok"))
        assertEquals("NEW",old.getString("algoStatus"))
        assertEquals(0,exchange.requests.count { it.method=="DELETE" })
    }

    private fun legacyUncertainSupervision(journal:Journal, old:JSONObject) {
        val rows=JSONArray(requireNotNull(journal.readState("futures_operator_stop_supervision_v1")))
        rows.getJSONObject(0).put("state","REPLACEMENT_UNRESOLVED")
            .put("previous_protection_client_id",old.getString("clientAlgoId"))
            .put("pending_protection_client_id","W2T_LEGACY_UNKNOWN")
            .put("pending_stop",if(old.getString("side")=="SELL") "96" else "104")
        journal.writeState("futures_operator_stop_supervision_v1",rows.toString())
    }

    @Test fun legacyUnknownAbsenceNeedsSeparatedSignedQueriesAndExactPendingCancellationBeforeResume() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange=Exchange(direction)
            exchange.quantity=if(direction.name=="LONG") 1.0 else -1.0
            exchange.entry=101.0;exchange.mark=100.0
            val old=existingFullStop(exchange,direction)
            val journal=Journal()
            var now=100_000L
            val coordinator=NativeFuturesCampaignCoordinator(journal,{now}) { exchange.adapter() }
            coordinator.approveOperatorStopSupervision("BTCUSDT","1","101",old.getString("triggerPrice"),
                "СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
            legacyUncertainSupervision(journal,old)
            assertFalse(coordinator.recoverOperatorSupervisedStop("BTCUSDT").getBoolean("ok"))
            assertEquals(0,exchange.requests.count { it.method=="DELETE" })
            val restarted=NativeFuturesCampaignCoordinator(journal,{now}) { exchange.adapter() }
            now+=59_999L
            assertFalse(restarted.recoverOperatorSupervisedStop("BTCUSDT").getBoolean("ok"))
            now++
            val recovered=restarted.recoverOperatorSupervisedStop("BTCUSDT")
            assertTrue(recovered.toString(),recovered.getBoolean("ok"))
            assertEquals("ORIGINAL_RETAINED",recovered.getString("recovery_status"))
            val cancellation=exchange.requests.single { it.method=="DELETE" }
            assertEquals("W2T_LEGACY_UNKNOWN",Exchange.params(cancellation)["clientAlgoId"])
            assertEquals("NEW",old.getString("algoStatus"))
            assertEquals(0,exchange.posts("/fapi/v1/algoOrder"))
            val saved=restarted.operatorSupervisionsSnapshot().getJSONObject(0)
            assertEquals("ACTIVE",saved.getString("state"))
            assertFalse(saved.has("pending_protection_client_id"))
            assertFalse(saved.has("last_replacement_failure")) // Original failure was never recorded by 4.53.2.
            assertEquals("SEPARATED_SIGNED_NOT_FOUND_AND_EXACT_CANCEL_FENCE",
                saved.getJSONObject("last_replacement_resolution").getString("proof"))
        }
    }

    @Test fun pendingQueryTransportFailureCannotBeUsedAsProofOfAbsence() {
        val direction=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(direction)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,direction)
        val journal=Journal()
        val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        legacyUncertainSupervision(journal,old)
        exchange.failPendingQueries=true
        assertThrows(FuturesHttpException::class.java) { coordinator.recoverOperatorSupervisedStop("BTCUSDT") }
        val saved=coordinator.operatorSupervisionsSnapshot().getJSONObject(0)
        assertEquals("REPLACEMENT_UNRESOLVED",saved.getString("state"))
        assertFalse(saved.has("pending_absent_since_ms"))
        assertEquals("NEW",old.getString("algoStatus"))
        assertEquals(0,exchange.requests.count { it.method=="DELETE" || it.method=="POST" })
    }

    @Test fun terminalPendingStopsWithChildrenOrWrongIdentityNeverResumeSupervision() {
        for(child in listOf(false,true)) for(wrongTrigger in listOf(false,true)) {
            val direction=NativeFuturesCampaignCoordinator.Direction.LONG
            val exchange=Exchange(direction)
            exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
            val old=existingFullStop(exchange,direction)
            val journal=Journal()
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
            legacyUncertainSupervision(journal,old)
            exchange.algos["6002"]=JSONObject(old.toString()).put("algoId","6002")
                .put("clientAlgoId","W2T_LEGACY_UNKNOWN").put("algoStatus","CANCELED")
                .put("triggerPrice",if(wrongTrigger) "97" else "96")
                .put("actualOrderId",if(child) "9001" else "").put("actualQty",if(child) "0.1" else "0")
            if(child || wrongTrigger) {
                assertThrows(Exception::class.java) { coordinator.recoverOperatorSupervisedStop("BTCUSDT") }
                assertEquals("REPLACEMENT_UNRESOLVED",coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
            } else assertTrue(coordinator.recoverOperatorSupervisedStop("BTCUSDT").getBoolean("ok"))
            assertEquals("NEW",old.getString("algoStatus"))
            assertEquals(0,exchange.requests.count { it.method=="DELETE" || it.method=="POST" })
        }
    }

    @Test fun formerSupervisedPositionClosureSurvivesRestartWithExactExchangeHistoryAndNoMutations() {
        val direction=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(direction)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,direction)
        val journal=Journal();var now=100_000L
        val coordinator=NativeFuturesCampaignCoordinator(journal,{now}) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        old.put("algoStatus","FINISHED").put("actualOrderId",42L)
        exchange.quantity=0.0;now=102_000L
        exchange.orders["42"]=JSONObject().put("symbol","BTCUSDT").put("orderId",42L)
            .put("side","SELL").put("positionSide","BOTH").put("status","FILLED").put("executedQty","1")
        exchange.tradeFills.put(JSONObject().put("symbol","BTCUSDT").put("orderId",42L).put("id",777L)
            .put("side","SELL").put("positionSide","BOTH").put("qty","1").put("price","95.1")
            .put("realizedPnl","-5.9").put("commission","0.04").put("commissionAsset","USDT").put("time",101_000L))
        assertTrue(coordinator.reconcile().getBoolean("ok"))
        assertEquals("CLOSED_PENDING_EVIDENCE",coordinator.operatorSupervisionsSnapshot().getJSONObject(0).getString("state"))
        coordinator.refreshOperatorClosedTradeHistory()
        val restored=NativeFuturesCampaignCoordinator(journal,{now}) { exchange.adapter() }
        val r=restored.operatorSupervisionsSnapshot().getJSONObject(0)
        assertEquals("CLOSED_EXCHANGE_CONFIRMED",r.getString("state"))
        assertEquals(95.1,r.getJSONObject("confirmed_closure").getDouble("exit_price"),0.0)
        assertEquals(101_000L,r.getJSONObject("confirmed_closure").getLong("closed_at"))
        val requestsBefore=exchange.requests.size
        restored.refreshOperatorClosedTradeHistory()
        assertEquals(requestsBefore,exchange.requests.size)
        assertEquals(0,exchange.requests.count { it.method!="GET" })
        assertEquals(0,restored.intentsSnapshot().length())
    }

    @Test fun missingClosureEvidenceRemainsVisibleAndDoesNotHammerTheExchangeOrCancelTheStop() {
        val direction=NativeFuturesCampaignCoordinator.Direction.LONG
        val exchange=Exchange(direction)
        exchange.quantity=1.0;exchange.entry=101.0;exchange.mark=100.0
        val old=existingFullStop(exchange,direction)
        val journal=Journal();var now=100_000L
        val coordinator=NativeFuturesCampaignCoordinator(journal,{now}) { exchange.adapter() }
        coordinator.approveOperatorStopSupervision("BTCUSDT","1","101","95","СОПРОВОЖДАТЬ ТОЛЬКО СТОП")
        exchange.quantity=0.0;now=102_000L
        coordinator.reconcile()
        coordinator.refreshOperatorClosedTradeHistory()
        val checked=exchange.requests.size
        coordinator.refreshOperatorClosedTradeHistory()
        assertEquals(checked,exchange.requests.size)
        val r=coordinator.operatorSupervisionsSnapshot().getJSONObject(0)
        assertEquals("CLOSED_PENDING_EVIDENCE",r.getString("state"))
        assertTrue(r.getString("closure_history_detail").isNotBlank())
        val rows=futuresVisibleTradeHistory(JSONArray(),coordinator.operatorSupervisionsSnapshot(),JSONArray())
        assertEquals(1,rows.length());assertTrue(rows.getJSONObject(0).isNull("exit_price"))
        assertEquals("NEW",old.getString("algoStatus"))
        assertEquals(0,exchange.requests.count { it.method!="GET" })
    }

    private class SimulatedProcessDeath : Error("simulated process death")

    @Test fun nativeSwapBridgesProtectionAndNeverPostsTwoCloseAllStopsLongAndShort() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            exchange.rejectSecondCloseAll = true
            val operations = mutableListOf<String>()
            exchange.afterAlgoMutation = { request, row ->
                operations += "${request.method}:${row.optString("clientAlgoId")}"
                val active = exchange.algos.values.filter { it.optString("algoStatus") == "NEW" }
                assertTrue("Protection gap at $operations", active.any {
                    isFullPositionFuturesStop(it, "BTCUSDT", direction.name,
                        kotlin.math.abs(exchange.quantity).toString().toBigDecimal(), exchange.mark)
                })
                assertTrue(active.count { it.optString("closePosition") == "true" } <= 1)
            }
            val oldId = coordinator.intentsSnapshot().getJSONObject(0).getString("protection_client_id")
            val next = if (direction.name == "LONG") "96" else "104"
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction, next, "W2T_NATIVE_${direction.name}")
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(4, operations.size)
            assertTrue(operations[0].startsWith("POST:W2B_"))
            assertEquals("DELETE:$oldId", operations[1])
            assertEquals("POST:W2T_NATIVE_${direction.name}", operations[2])
            assertTrue(operations[3].startsWith("DELETE:W2B_"))
            val active = exchange.algos.values.single { it.optString("algoStatus") == "NEW" }
            assertEquals("true", active.getString("closePosition"))
            assertFalse(active.has("quantity"))
            assertFalse(coordinator.intentsSnapshot().getJSONObject(0).has("native_stop_replacement"))
            // No second account-wide reconciliation is needed to expose the
            // exchange-confirmed NEW stop; the old stop must not remain in
            // the cockpit's signed observations (Diagnostics 9).
            val stopProof = coordinator.status().getJSONArray("exchange_protection_observations")
            assertEquals(1, stopProof.length())
            val proof = stopProof.getJSONObject(0)
            assertEquals("OWNED_STOP_SEEN", proof.getString("stop_proof"))
            assertEquals("W2T_NATIVE_${direction.name}", proof.getString("protection_client_id"))
            assertEquals(0, proof.getString("stop").toBigDecimal().compareTo(next.toBigDecimal()))
            assertEquals(0, proof.getString("quantity").toBigDecimal()
                .compareTo(exchange.quantity.toString().toBigDecimal()))
            assertTrue(proof.getLong("observed_at_ms") > 0L)
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun nativeSwapRecoversProcessDeathAfterEveryMutationWithoutDuplicatePosts() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) for (deathAfter in 1..4) {
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal)
            exchange.rejectSecondCloseAll = true
            var mutations = 0
            exchange.afterAlgoMutation = { _, _ -> if (++mutations == deathAfter) throw SimulatedProcessDeath() }
            val cid = "W2T_CRASH_${direction.name}_$deathAfter"
            assertThrows(SimulatedProcessDeath::class.java) {
                coordinator.trailProtectiveStop("BTCUSDT", direction,
                    if (direction.name == "LONG") "96" else "104", cid)
            }
            assertTrue(coordinator.intentsSnapshot().getJSONObject(0).has("native_stop_replacement"))
            exchange.afterAlgoMutation = null
            val restarted = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val recovered = restarted.reconcile()
            assertTrue("$direction/$deathAfter: $recovered", recovered.getBoolean("ok"))
            val posts = exchange.requests.filter { it.method == "POST" && it.url.encodedPath == "/fapi/v1/algoOrder" }
                .map { Exchange.params(it) }
            assertEquals(1, posts.count { it["clientAlgoId"] == cid })
            assertEquals(1, posts.count { it["clientAlgoId"]?.startsWith("W2B_") == true })
            assertEquals(1, exchange.algos.values.count { it.optString("algoStatus") == "NEW" })
            assertEquals(cid, restarted.intentsSnapshot().getJSONObject(0).getString("protection_client_id"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun nativeSwapLostRepliesAtAllFourStagesAreResolvedByExactQueries() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) for (lostStage in 1..4) {
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal)
            val oldId = coordinator.intentsSnapshot().getJSONObject(0).getString("protection_client_id")
            val cid = "W2T_LOST_${direction.name}_$lostStage"
            exchange.rejectSecondCloseAll = true
            exchange.beforeRequest = { request, params ->
                val id = params["clientAlgoId"].orEmpty()
                exchange.loseProtectionReply = request.method == "POST" &&
                    (lostStage == 1 && id.startsWith("W2B_") || lostStage == 3 && id == cid)
                exchange.loseCancelReply = request.method == "DELETE" &&
                    (lostStage == 2 && id == oldId || lostStage == 4 && id.startsWith("W2B_"))
            }
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", cid)
            assertFalse("$direction/$lostStage: $result", result.getBoolean("ok"))
            assertTrue(exchange.algos.values.any { it.optString("algoStatus") == "NEW" })
            val failure = coordinator.status().getJSONArray("pending_intent_details").getJSONObject(0)
                .getJSONObject("last_native_stop_failure")
            assertEquals(503, failure.getInt("http_status"))
            exchange.beforeRequest = null; exchange.loseProtectionReply = false; exchange.loseCancelReply = false
            val restarted = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val recovered = restarted.reconcile()
            assertTrue("$direction/$lostStage: $recovered", recovered.getBoolean("ok"))
            assertEquals(1, exchange.requests.count { it.method == "POST" && Exchange.params(it)["clientAlgoId"] == cid })
            assertEquals(1, exchange.requests.count { it.method == "POST" &&
                Exchange.params(it)["clientAlgoId"]?.startsWith("W2B_") == true })
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun nativeBridgeRejectionRetainsOldStopAndExportsFailureWithoutPostLoop() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var now = System.currentTimeMillis()
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal, clock = { now })
            val old = exchange.algos.values.single { it.optString("closePosition") == "true" }
            exchange.rejectProtectionCode = -2022
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_REJECT_NATIVE")
            assertFalse(result.getBoolean("ok"))
            assertEquals("NEW", old.getString("algoStatus"))
            val tx = coordinator.intentsSnapshot().getJSONObject(0).getJSONObject("native_stop_replacement")
            assertEquals("REJECTED", tx.getString("bridge_outcome"))
            assertEquals(-2022, tx.getJSONObject("first_failure").getInt("exchange_code"))
            val posts = exchange.posts("/fapi/v1/algoOrder")
            exchange.rejectProtectionCode = null
            val restarted = NativeFuturesCampaignCoordinator(journal, clock = { now }) { exchange.adapter() }
            val retained = restarted.reconcile()
            assertTrue(retained.toString(), retained.getBoolean("ok"))
            assertFalse(restarted.intentsSnapshot().getJSONObject(0).has("native_stop_replacement"))
            assertFalse(restarted.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_COOLDOWN").getBoolean("ok"))
            assertEquals(posts, exchange.posts("/fapi/v1/algoOrder"))
            now += 61_000L
            assertTrue(restarted.reconcile().getBoolean("ok"))
            assertTrue(restarted.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_AFTER_COOLDOWN").getBoolean("ok"))
        }
    }

    @Test fun nativeFinalRejectionKeepsBridgeAndOnlyRetriesDefiniteFailureAfterCooldown() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var now = System.currentTimeMillis()
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal, clock = { now })
            val cid = "W2T_FINAL_REJECT_${direction.name}"
            exchange.beforeRequest = { request, params ->
                exchange.rejectProtectionCode = if (request.method == "POST" && params["clientAlgoId"] == cid) -2022 else null
            }
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", cid)
            assertFalse(result.getBoolean("ok"))
            // After the old close-all has been canceled, its earlier signed
            // proof must not be advertised as current during bridge recovery.
            assertEquals(0, coordinator.status()
                .getJSONArray("exchange_protection_observations").length())
            val bridge = exchange.algos.values.single { it.optString("algoStatus") == "NEW" }
            assertEquals("true", bridge.getString("reduceOnly"))
            exchange.beforeRequest = null; exchange.rejectProtectionCode = null
            val restarted = NativeFuturesCampaignCoordinator(journal, clock = { now }) { exchange.adapter() }
            val posts = exchange.posts("/fapi/v1/algoOrder")
            assertFalse(restarted.reconcile().getBoolean("ok"))
            assertEquals(posts, exchange.posts("/fapi/v1/algoOrder"))
            assertFalse(restarted.status().getBoolean("ready_for_new_entry"))
            now += 61_000L
            val recovered = restarted.reconcile()
            assertTrue(recovered.toString(), recovered.getBoolean("ok"))
            assertEquals("CANCELED", bridge.getString("algoStatus"))
            assertEquals(1, exchange.algos.values.count { it.optString("algoStatus") == "NEW" })
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun nativeSwapNeverReplaysUnknownPostThatHasNoExchangeRow() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) for (stage in listOf("bridge", "final")) {
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal)
            journal.afterWrite = { key, value ->
                if (key == "futures_campaign_intents_v2" && JSONArray(value).getJSONObject(0)
                        .optJSONObject("native_stop_replacement")?.optString("phase") ==
                    if (stage == "bridge") "SUBMIT_BRIDGE" else "SUBMIT_FINAL") throw SimulatedProcessDeath()
            }
            assertThrows(SimulatedProcessDeath::class.java) {
                coordinator.trailProtectiveStop("BTCUSDT", direction,
                    if (direction.name == "LONG") "96" else "104", "W2T_NOT_SENT_$stage")
            }
            journal.afterWrite = null
            val posts = exchange.posts("/fapi/v1/algoOrder")
            val restarted = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            repeat(2) { assertFalse(restarted.reconcile().getBoolean("ok")) }
            assertEquals(posts, exchange.posts("/fapi/v1/algoOrder"))
            assertFalse(restarted.submitPendingEntry(plan(direction).copy(clientId = "W2H_BLOCKED_BY_UNKNOWN")).getBoolean("accepted"))
            assertTrue(exchange.algos.values.any { it.optString("algoStatus") == "NEW" })
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun nativeSwapRechecksQuantityEntryAndForeignOrdersBeforeDeletingOriginal() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) for (change in listOf("quantity", "entry", "ordinary", "algo")) {
            val (coordinator, exchange) = open(direction)
            val old = exchange.algos.values.single { it.optString("closePosition") == "true" }
            exchange.afterAlgoMutation = { request, row ->
                if (request.method == "POST" && row.optString("reduceOnly") == "true") when (change) {
                    "quantity" -> exchange.quantity *= 2
                    "entry" -> exchange.entry += 1
                    "ordinary" -> exchange.orders["foreign"] = JSONObject().put("symbol", "BTCUSDT")
                        .put("status", "NEW").put("clientOrderId", "USER_PENDING")
                    "algo" -> exchange.algos["foreign"] = JSONObject().put("symbol", "BTCUSDT")
                        .put("algoStatus", "NEW").put("clientAlgoId", "USER_PENDING_ALGO")
                }
            }
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_EXPOSURE_CHANGED")
            assertFalse("$direction/$change: $result", result.getBoolean("ok"))
            assertEquals("NEW", old.getString("algoStatus"))
            assertEquals(0, exchange.requests.count { it.method == "DELETE" })
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun legacyUnknownNativeReplacementRequiresSpacedSignedQueriesAndCancellationFence() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var now = System.currentTimeMillis()
            val journal = Journal()
            val (_, exchange) = open(direction, journal = journal, clock = { now })
            val rows = JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
            rows.getJSONObject(0).put("previous_protection_client_id", "W2T_LEGACY_UNKNOWN")
                .put("previous_protective_stop", if (direction.name == "LONG") "96" else "104")
            journal.writeState("futures_campaign_intents_v2", rows.toString())
            val restarted = NativeFuturesCampaignCoordinator(journal, clock = { now }) { exchange.adapter() }
            val posts = exchange.posts("/fapi/v1/algoOrder")
            assertFalse(restarted.reconcile().getBoolean("ok"))
            assertTrue(restarted.intentsSnapshot().getJSONObject(0).has("previous_protection_client_id"))
            now += 59_999L
            assertFalse(restarted.reconcile().getBoolean("ok"))
            assertEquals(0, exchange.requests.count { it.method == "DELETE" })
            now += 1L
            val recovered = restarted.reconcile()
            assertTrue(recovered.toString(), recovered.getBoolean("ok"))
            assertFalse(restarted.intentsSnapshot().getJSONObject(0).has("previous_protection_client_id"))
            assertEquals(1, exchange.requests.count { it.method == "DELETE" && Exchange.params(it)["clientAlgoId"] == "W2T_LEGACY_UNKNOWN" })
            assertEquals(posts, exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }


    @Test fun nativeBridgeCannotBeConfirmedWithChangedQuantitySideTriggerOrWorkingType() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) for (field in listOf("quantity", "side", "triggerPrice", "workingType", "algoStatus")) {
            val (coordinator, exchange) = open(direction)
            val old = exchange.algos.values.single { it.optString("closePosition") == "true" }
            exchange.afterAlgoMutation = { request, row ->
                if (request.method == "POST" && row.optString("reduceOnly") == "true") row.put(field, when (field) {
                    "quantity" -> "0.1"
                    "side" -> if (direction.name == "LONG") "BUY" else "SELL"
                    "triggerPrice" -> if (direction.name == "LONG") "95.9" else "104.1"
                    "workingType" -> "CONTRACT_PRICE"
                    else -> "TRIGGERING"
                })
            }
            val result = coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_BAD_BRIDGE")
            assertFalse("$direction/$field: $result", result.getBoolean("ok"))
            assertEquals("NEW", old.getString("algoStatus"))
            assertEquals(0, exchange.requests.count { it.method == "DELETE" })
        }
    }

    @Test fun nativeSwapRejectsAnUnresolvedDurableAdditionEvenWhenNoOpenRowIsVisible() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal)
            val rows = JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
            rows.put(JSONObject().put("intent_role", "ADD_ON").put("symbol", "BTCUSDT")
                .put("entry_client_id", rows.getJSONObject(0).getString("client_id"))
                .put("client_id", "W2A_UNCERTAIN").put("exchange_status", "SUBMISSION_UNKNOWN")
                .put("order_status", "SUBMISSION_UNKNOWN"))
            journal.writeState("futures_campaign_intents_v2", rows.toString())
            val before = exchange.posts("/fapi/v1/algoOrder")
            assertFalse(coordinator.trailProtectiveStop("BTCUSDT", direction,
                if (direction.name == "LONG") "96" else "104", "W2T_BLOCK_PENDING_ADD").getBoolean("ok"))
            assertEquals(before, exchange.posts("/fapi/v1/algoOrder"))
            assertFalse(coordinator.intentsSnapshot().getJSONObject(0).has("native_stop_replacement"))
        }
    }

    @Test fun finalNativeCloseAllStillCoversANewlyFilledTc2PyramidAddition() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val exchange = Exchange(direction)
            var now = System.currentTimeMillis()
            val coordinator = NativeFuturesCampaignCoordinator(Journal(), clock = { now }) { exchange.adapter() }
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            val initial = plan(direction).copy(strategyProfile = williamsTc2Profile,
                signalType = "FRACTAL", signalBarTimeMs = now - 7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill(); assertTrue(coordinator.reconcile().getBoolean("ok"))
            val next = if (direction.name == "LONG") "96" else "104"
            exchange.rejectSecondCloseAll = true
            assertTrue(coordinator.trailProtectiveStop("BTCUSDT", direction, next, "W2T_BEFORE_ADD").getBoolean("ok"))
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            now += 3_600_000L
            val addition = initial.copy(quantity = "0.1", protectiveStop = next,
                entryPrice = (exchange.mark + if (direction.name == "LONG") 1 else -1).toString(),
                clientId = "W2A_AFTER_SWAP", riskPct = .001, entryClientId = initial.clientId,
                signalType = "SUPER_AO", executionSemantics = "STOP_ENTRY",
                referenceClose = exchange.mark, signalBarTimeMs = now - 3_601_000L)
            val accepted = coordinator.submitPendingEntry(addition)
            assertTrue(accepted.toString(), accepted.getBoolean("accepted"))
            exchange.fillAddition(addition.clientId)
            val reconciled = coordinator.reconcile()
            assertTrue(reconciled.toString(), reconciled.getBoolean("ok"))
            val stop = exchange.algos.values.single { it.optString("algoStatus") == "NEW" }
            assertEquals("true", stop.getString("closePosition"))
            assertTrue(isFullPositionFuturesStop(stop, "BTCUSDT", direction.name, "1.1".toBigDecimal(), exchange.mark))
            assertEquals("1.1", coordinator.intentsSnapshot().getJSONObject(0).getString("confirmed_position_qty"))
        }
    }

    @Test fun aTriggeredTemporaryStopClosesThroughExchangeAndLeavesNoArmedOrphan() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            var now = System.currentTimeMillis()
            val journal = Journal()
            val (coordinator, exchange) = open(direction, journal = journal, clock = { now })
            exchange.afterAlgoMutation = { request, row ->
                if (request.method == "POST" && row.optString("reduceOnly") == "true") throw SimulatedProcessDeath()
            }
            assertThrows(SimulatedProcessDeath::class.java) {
                coordinator.trailProtectiveStop("BTCUSDT", direction,
                    if (direction.name == "LONG") "96" else "104", "W2T_STOP_TRIGGERED")
            }
            exchange.afterAlgoMutation = null
            val bridge = exchange.algos.values.single { it.optString("reduceOnly") == "true" }
            bridge.put("algoStatus", "FINISHED").put("actualOrderId", "701")
            exchange.orders["701"] = JSONObject().put("symbol", "BTCUSDT").put("orderId", "701")
                .put("status", "FILLED").put("side", bridge.getString("side"))
                .put("executedQty", "1").put("origQty", "1").put("avgPrice", bridge.getString("triggerPrice"))
            exchange.quantity = 0.0
            val before = exchange.posts("/fapi/v1/algoOrder")
            val restarted = NativeFuturesCampaignCoordinator(journal, clock = { now }) { exchange.adapter() }
            restarted.reconcile(); now += 61_000L
            val closed = restarted.reconcile()
            assertTrue(closed.toString(), closed.getBoolean("ok"))
            assertEquals("CLOSED_OR_STOPPED", restarted.intentsSnapshot().getJSONObject(0).getString("campaign_status"))
            assertFalse(restarted.intentsSnapshot().getJSONObject(0).has("native_stop_replacement"))
            assertTrue(exchange.algos.values.none { it.optString("algoStatus") == "NEW" })
            assertEquals(before, exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
            assertEquals(1, futuresVisibleTradeHistory(JSONArray(), JSONArray(), restarted.intentsSnapshot()).length())
        }
    }

    @Test fun orphanStopCleanupOnFlatCancelsOnlyExactVerifiedProtectiveIdentity() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            val original = exchange.algos.values.single { it.optString("closePosition") == "true" }
            exchange.quantity = 0.0
            val result = coordinator.reconcile()
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("CANCELED", original.getString("algoStatus"))
            assertEquals(1, exchange.requests.count {
                it.method == "DELETE" && it.url.encodedPath == "/fapi/v1/algoOrder"
            })
        }
    }

    @Test fun orphanStopCleanupNeverCancelsAnUnexpectedSideOrTriggerOnAFlatSymbol() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            val original = exchange.algos.values.single { it.optString("closePosition") == "true" }
            original.put("side", if (direction.name == "LONG") "BUY" else "SELL")
            exchange.quantity = 0.0
            coordinator.reconcile()
            assertEquals("NEW", original.getString("algoStatus"))
            assertEquals(0, exchange.requests.count { it.method == "DELETE" &&
                it.url.encodedPath == "/fapi/v1/algoOrder" })
        }
    }

    @Test fun orphanStopCleanupCannotCancelAfterPositionReopensBetweenSignedReads() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator, exchange) = open(direction)
            val original = exchange.algos.values.single { it.optString("closePosition") == "true" }
            exchange.quantity = 0.0
            var flatSnapshotWasRead = false
            var positionReopened = false
            exchange.beforeRequest = { request, _ ->
                when (request.url.encodedPath) {
                    "/fapi/v3/positionRisk" -> if (!positionReopened) flatSnapshotWasRead = true
                    "/fapi/v1/openAlgoOrders" -> if (flatSnapshotWasRead && !positionReopened) {
                        exchange.quantity = if (direction.name == "LONG") 1.0 else -1.0
                        positionReopened = true
                    }
                }
            }
            coordinator.reconcile()
            assertTrue("Expected a race after the first signed flat snapshot", positionReopened)
            assertEquals("NEW", original.getString("algoStatus"))
            assertEquals(0, exchange.requests.count {
                it.method == "DELETE" && it.url.encodedPath == "/fapi/v1/algoOrder"
            })
        }
    }

    @Test fun legacyNativeUnknownQueryTimeoutCannotBecomeProofOfOrderAbsence() {
        var now = System.currentTimeMillis()
        val journal = Journal()
        val (_, exchange) = open(NativeFuturesCampaignCoordinator.Direction.SHORT, journal = journal, clock = { now })
        val rows = JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
        rows.getJSONObject(0).put("previous_protection_client_id", "W2T_TIMEOUT_UNKNOWN")
            .put("previous_protective_stop", "104")
        journal.writeState("futures_campaign_intents_v2", rows.toString())
        val restarted = NativeFuturesCampaignCoordinator(journal, clock = { now }) { exchange.adapter() }
        assertFalse(restarted.reconcile().getBoolean("ok"))
        now += 61_000L; exchange.failPendingQueries = true
        assertFalse(restarted.reconcile().getBoolean("ok"))
        assertTrue(restarted.intentsSnapshot().getJSONObject(0).has("previous_protection_client_id"))
        assertEquals(0, exchange.requests.count { it.method == "DELETE" })
        exchange.failPendingQueries = false
        assertTrue(restarted.reconcile().getBoolean("ok"))
    }


    @Test fun aDelayedAcceptedTighterLegacyStopIsPromotedBeforeOnlyTheWiderStopIsCancelled() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal = Journal()
            val (_, exchange) = open(direction, journal = journal)
            val old = exchange.algos.values.single { it.optString("closePosition") == "true" }
            val next = if (direction.name == "LONG") "96" else "104"
            val accepted = JSONObject(old.toString()).put("algoId", "901").put("clientAlgoId", "W2T_DELAYED_ACCEPTED")
                .put("triggerPrice", next)
            exchange.algos["901"] = accepted
            val rows = JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2")))
            rows.getJSONObject(0).put("previous_protection_client_id", "W2T_DELAYED_ACCEPTED")
                .put("previous_protective_stop", next).put("trail_attempt_reason", "TC2_LAST_5_CLOSED_BARS")
            journal.writeState("futures_campaign_intents_v2", rows.toString())
            val posts = exchange.posts("/fapi/v1/algoOrder")
            exchange.afterAlgoMutation = { _, _ ->
                assertEquals("Never remove the tighter accepted stop", "NEW", accepted.getString("algoStatus"))
                val persisted = JSONArray(requireNotNull(journal.readState("futures_campaign_intents_v2"))).getJSONObject(0)
                assertEquals("W2T_DELAYED_ACCEPTED", persisted.getString("protection_client_id"))
            }
            val restarted = NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val recovered = restarted.reconcile()
            assertTrue(recovered.toString(), recovered.getBoolean("ok"))
            assertEquals("CANCELED", old.getString("algoStatus"))
            assertEquals("NEW", accepted.getString("algoStatus"))
            val owner = restarted.intentsSnapshot().getJSONObject(0)
            assertEquals("W2T_DELAYED_ACCEPTED", owner.getString("protection_client_id"))
            assertEquals(next, owner.getString("protective_stop"))
            assertEquals(next, owner.getJSONObject("last_confirmed_trail").getString("new_stop"))
            assertFalse(owner.has("previous_protection_client_id"))
            val proof = recovered.getJSONArray("exchange_protection_observations").getJSONObject(0)
            assertEquals(next, proof.getString("stop"))
            assertEquals("OWNED_STOP_SEEN", proof.getString("stop_proof"))
            assertEquals(posts, exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(0, exchange.posts("/fapi/v1/order"))
        }
    }


    @Test fun diagnostics19RecoveredStopIsPublishedInTheSameSignedAudit() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val journal=Journal()
            val (coordinator,exchange)=open(direction,journal=journal)
            // open() performs the first fill reconciliation and STOP POST.
            // Its proof must already reflect that newly confirmed STOP.
            val proof=coordinator.status().getJSONArray("exchange_protection_observations").getJSONObject(0)
            val owner=coordinator.intentsSnapshot().getJSONObject(0)
            assertEquals("OWNED_STOP_SEEN",proof.getString("stop_proof"))
            assertEquals(owner.getString("protection_client_id"),proof.getString("protection_client_id"))
            assertEquals(owner.getString("protective_stop"),proof.getString("stop"))
            assertTrue(proof.getBoolean("automatic_trailing_authorized"))
            val posts=exchange.posts("/fapi/v1/algoOrder")
            assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(posts,exchange.posts("/fapi/v1/algoOrder"))
        }
    }

    @Test fun diagnostics19UnrelatedReversedCampaignCannotContaminateOwnedPositionRiskOrProof() {
        val journal=Journal()
        val (coordinator,exchange)=open(NativeFuturesCampaignCoordinator.Direction.LONG,journal=journal)
        val rows=coordinator.intentsSnapshot()
        val foreign=JSONObject(rows.getJSONObject(0).toString())
            .put("symbol","ETHUSDT").put("client_id","W2E_ETH_OLD_LONG")
        rows.put(foreign)
        journal.writeState("futures_campaign_intents_v2",rows.toString())
        exchange.positionSnapshotTransform={ positions -> positions.put(JSONObject()
            .put("symbol","ETHUSDT").put("positionSide","BOTH").put("positionAmt","-1")
            .put("entryPrice","99").put("markPrice","100").put("liquidationPrice","200")) }
        val before=exchange.posts("/fapi/v1/algoOrder")
        val result=coordinator.reconcile()
        assertFalse(result.getBoolean("ok"))
        assertEquals("POSITION_UNPROTECTED",result.getString("state"))
        val owned=coordinator.intentsSnapshot().getJSONObject(0)
        assertEquals("PASS",owned.getString("actual_risk_audit_status"))
        assertFalse(owned.has("actual_risk_audit_blocker"))
        val proofs=result.getJSONArray("exchange_protection_observations")
        assertEquals(2,proofs.length())
        assertEquals("OWNED_STOP_SEEN",proofs.getJSONObject(0).getString("stop_proof"))
        assertEquals("REVERSED_CAMPAIGN_QUARANTINE",proofs.getJSONObject(1).getString("management_mode"))
        assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
        val scope=result.getJSONArray("verified_external_stop_management_symbols")
        assertEquals(1,scope.length())
        assertEquals("BTCUSDT",scope.getString(0))
        val trail=coordinator.trailProtectiveStop("BTCUSDT",NativeFuturesCampaignCoordinator.Direction.LONG,
            "96","W2T_ISOLATED_BTC","TC2_LAST_5_CLOSED_BARS")
        assertTrue(trail.toString(),trail.getBoolean("ok"))
        assertEquals("POSITION_UNPROTECTED",coordinator.state.name)
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
    }

    @Test fun diagnostics19DuplicateSignedPositionRowsNeverOverwriteRealExposure() {
        val (coordinator,exchange)=open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.positionSnapshotTransform={ rows -> rows.put(JSONObject(rows.getJSONObject(0).toString())
            .put("positionAmt","0")) }
        val before=exchange.posts("/fapi/v1/algoOrder")
        val result=coordinator.reconcile()
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.toString(),result.getString("blocker").contains("duplicate",ignoreCase=true))
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
        assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(0,exchange.posts("/fapi/v1/order"))
    }

    @Test fun diagnostics18ReversedLivePositionNeverBelongsToOldCampaignAndDoesNotMutateOrders() {
        for (direction in NativeFuturesCampaignCoordinator.Direction.entries) {
            val (coordinator,exchange) = open(direction)
            val oldJournal=coordinator.intentsSnapshot().toString()
            val postsBefore=exchange.posts("/fapi/v1/algoOrder")
            val otherBefore=exchange.posts("/fapi/v1/order")
            val existingProtection=exchange.algos.values.single {
                it.optString("closePosition")=="true"
            }
            val oldSide=existingProtection.getString("side")
            exchange.quantity = -exchange.quantity
            exchange.mark = if (exchange.quantity>0.0) 102.0 else 98.0
            val first=coordinator.reconcile()
            assertFalse(first.toString(),first.getBoolean("ok"))
            assertEquals("POSITION_UNPROTECTED",first.getString("state"))
            assertEquals(1,first.getInt("positions_checked"))
            assertEquals(1,first.getJSONArray("exchange_protection_observations").length())
            val proof=first.getJSONArray("exchange_protection_observations").getJSONObject(0)
            assertEquals("STOP_UNCONFIRMED",proof.getString("stop_proof"))
            assertEquals("REVERSED_CAMPAIGN_QUARANTINE",proof.getString("management_mode"))
            assertFalse(proof.getBoolean("strategy_ownership_verified"))
            assertFalse(proof.getBoolean("automatic_trailing_authorized"))
            assertEquals(oldSide,existingProtection.getString("side"))
            assertEquals(oldJournal,coordinator.intentsSnapshot().toString())
            assertEquals(postsBefore,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(otherBefore,exchange.posts("/fapi/v1/order"))
            assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
            val again=coordinator.reconcile()
            assertFalse(again.getBoolean("ok"))
            assertEquals(0,again.getJSONArray("required_safety_exits").length())
            assertEquals(postsBefore,exchange.posts("/fapi/v1/algoOrder"))
            assertEquals(otherBefore,exchange.posts("/fapi/v1/order"))
        }
    }

    @Test fun diagnostics18GenuineExchangeStopForReversedPositionIsVisibleButNeverOwned() {
        val (coordinator,exchange)=open(NativeFuturesCampaignCoordinator.Direction.LONG)
        val old=coordinator.intentsSnapshot().toString()
        exchange.quantity=-exchange.quantity
        exchange.mark=98.0
        exchange.adapter().placeProtectiveStop("BTCUSDT","SHORT","105","MANUAL_SHORT_STOP")
        val before=exchange.posts("/fapi/v1/algoOrder")
        val result=coordinator.reconcile()
        assertFalse(result.toString(),result.getBoolean("ok"))
        assertEquals("RECONCILE_REQUIRED",result.getString("state"))
        val p=result.getJSONArray("exchange_protection_observations").getJSONObject(0)
        assertEquals("SHORT",p.getString("direction"))
        assertEquals("EXTERNAL_PROTECTED",p.getString("stop_proof"))
        assertEquals("MANUAL_SHORT_STOP",p.getString("protection_client_id"))
        assertEquals("REVERSED_CAMPAIGN_QUARANTINE",p.getString("management_mode"))
        assertFalse(p.getBoolean("automatic_trailing_authorized"))
        assertEquals(before,exchange.posts("/fapi/v1/algoOrder"))
        assertEquals(old,coordinator.intentsSnapshot().toString())
        assertFalse(coordinator.status().getBoolean("ready_for_new_entry"))
    }
}

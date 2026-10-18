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
        @Synchronized override fun readState(key: String): String? = values[key]
        @Synchronized override fun writeState(key: String, value: String): Boolean {
            if (rejectWrites) return false
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
                        else { if (request.method == "DELETE") row.put("algoStatus", "CANCELED"); row }
                    }
                }
                "/fapi/v1/order" -> if (request.method == "POST" && p["reduceOnly"]!="true") {
                    check(p["side"]==if(quantity>0 || quantity==0.0 && direction.name=="LONG") "BUY" else "SELL")
                    val added=p.getValue("quantity").toDouble()*additionFraction
                    val id=(100+orders.size).toString()
                    val old=kotlin.math.abs(quantity)
                    entry=(entry*old+mark*added)/(old+added)
                    quantity+=(if(quantity>0 || quantity==0.0 && direction.name=="LONG") added else -added)
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
            quantity+=(if(quantity>0) qty else -qty);mark=price+(if(quantity>0) 1 else -1)
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
        val coordinator = NativeFuturesCampaignCoordinator(Journal()) { exchange.adapter() }
        assertTrue(coordinator.reconcile().toString(), coordinator.status().optBoolean("ready_for_new_entry"))
        val accepted = coordinator.submitPendingEntry(plan(direction, leverage, prefix))
        assertTrue(accepted.toString(), accepted.getBoolean("accepted"))
        assertEquals(leverage, exchange.leverage)
        exchange.fill()
        val reconciled = coordinator.reconcile()
        assertTrue(reconciled.toString(), reconciled.getBoolean("ok"))
        assertEquals("OPEN_PROTECTED", coordinator.state.name)
        return coordinator to exchange
    }

    @Test fun profitableNtdCycleReentersAnyDimensionOnceOnlyAfterVerifiedFeesAndActualClose() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(market in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            var now=System.currentTimeMillis()
            val coordinator=NativeFuturesCampaignCoordinator(journal,clock={ now }) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL",signalBarTimeMs=now-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
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
            assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
            assertFalse(coordinator.submitPendingEntry(reentry.copy(clientId=reentry.clientId+"B")).getBoolean("accepted"))
            assertFalse(williamsNtdReentryEligible(coordinator.intentsSnapshot().getJSONObject(0),"BTCUSDT",direction.name,now))
            if(market) assertTrue(coordinator.reconcile().getBoolean("ok"))
            assertEquals(if(market) "OPEN_PROTECTED" else "ENTRY_PENDING",coordinator.state.name)
        }
    }

    @Test fun coursePartialReductionsKeepProtectionAndReExpansionUsesTheRemainingAverage() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(lost in listOf(false,true)) {
            val journal=Journal();val exchange=Exchange(direction)
            var coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            coordinator.reconcile()
            val initial=plan(direction).copy(strategyProfile=williamsCourseProfile,signalType="COURSE_HUMP",
                signalBarTimeMs=System.currentTimeMillis()-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
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
            assertTrue(admitted.toString(),admitted.getBoolean("accepted"))
            exchange.fillAddition(addition.clientId)
            val verified=coordinator.reconcile()
            assertTrue(verified.toString(),verified.getBoolean("ok"))
            assertEquals(.7,kotlin.math.abs(exchange.quantity),1e-8)
            assertEquals(protection,exchange.algos.values.single { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" }.getString("clientAlgoId"))
            assertEquals(3,exchange.posts("/fapi/v1/algoOrder"))

        }
    }

    @Test fun ntdMarketAdditionsAreOwnedProtectedAndRecoverLostRepliesForBothSides() {
        for(direction in NativeFuturesCampaignCoordinator.Direction.values()) for(lost in listOf(false,true)) for(fraction in listOf(.5,1.0)) {
            val journal=Journal();val exchange=Exchange(direction)
            val coordinator=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            assertTrue(coordinator.reconcile().toString(),coordinator.status().optBoolean("ready_for_new_entry"))
            val initial=plan(direction).copy(strategyProfile=williamsNtdProfile,signalType="FRACTAL",
                signalBarTimeMs=System.currentTimeMillis()-7_200_000L)
            assertTrue(coordinator.submitPendingEntry(initial).getBoolean("accepted"))
            exchange.fill();assertTrue(coordinator.reconcile().getBoolean("ok"))
            val oldProtection=exchange.algos.values.single { it.optString("closePosition")=="true" }.getString("clientAlgoId")
            val leverageCalls=exchange.posts("/fapi/v1/leverage")
            exchange.loseAdditionReply=lost;exchange.additionFraction=fraction
            val addition=initial.copy(kind=NativeFuturesCampaignCoordinator.EntryKind.MARKET,quantity="0.5",
                entryPrice=exchange.mark.toString(),clientId="W2A_NTD_${direction.name}_${lost}_$fraction",riskPct=.001,
                entryClientId=initial.clientId,signalType="ZONE_ADD_ON",executionSemantics="MARKET_ON_CLOSE",
                referenceClose=exchange.mark,signalBarTimeMs=System.currentTimeMillis()-3_601_000L)
            val submitted=coordinator.submitPendingEntry(addition)
            assertEquals(submitted.toString(),!lost,submitted.getBoolean("accepted"))
            // New coordinator represents process death after an unknown response.
            val recovered=NativeFuturesCampaignCoordinator(journal) { exchange.adapter() }
            val reconciled=recovered.reconcile()
            assertTrue(reconciled.toString(),reconciled.getBoolean("ok"))
            assertEquals(reconciled.toString(),"OPEN_PROTECTED",recovered.state.name)
            assertEquals(1+.5*fraction,kotlin.math.abs(exchange.quantity),1e-9)
            assertEquals(leverageCalls,exchange.posts("/fapi/v1/leverage"))
            val protection=exchange.algos.values.filter { it.optString("algoStatus")=="NEW" && it.optString("closePosition")=="true" }
            assertEquals(1,protection.size);assertEquals(oldProtection,protection.single().getString("clientAlgoId"))
            assertFalse(recovered.submitPendingEntry(addition).getBoolean("accepted"))
            assertEquals(1,exchange.posts("/fapi/v1/order"))
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
                val closed = coordinator.closePosition("BTCUSDT", direction, "1", "W2X_CLOSE_${direction.name}_$leverage", "STRATEGY_EXIT")
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

    @Test fun strategyExitCannotCloseAnExternallyIncreasedPosition() {
        val (coordinator, exchange) = open(NativeFuturesCampaignCoordinator.Direction.LONG)
        exchange.quantity = 2.0
        val result = coordinator.closePosition("BTCUSDT", exchange.direction, "2", "W2X_FOREIGN", "STRATEGY_EXIT")
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
        assertFalse(coordinator.closePosition("BTCUSDT", exchange.direction, "1", "W2X_PARTIAL", "STRATEGY_EXIT").getBoolean("ok"))
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
}

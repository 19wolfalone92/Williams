package com.williamsbot

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesDailyRiskModelBTest {
    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("utc-risk-comparison-21.json")
        assertNotNull("Shared 21-case UTC risk fixture must be on the Android test classpath", stream)
        return JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    private fun decimal(row: JSONObject, name: String): BigDecimal =
        row.get(name).toString().toBigDecimal()

    @Test
    fun replaysTheExactPreviousTwentyOneCasesAgainstModelB() {
        val fixture = vectors()
        assertEquals(21, fixture.getInt("scenarios"))
        val rows = fixture.getJSONArray("results")
        assertEquals(21, rows.length())
        var blockedForMissingEvidence = 0
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.isNull("independent_midnight_equity_B")) {
                blockedForMissingEvidence++
                continue
            }
            val expected = row.getJSONObject("independent_midnight_equity_B")
            val inputs = row.getJSONObject("inputs")
            val start = decimal(inputs, "w0").add(decimal(inputs, "u0"))
            val current = decimal(inputs, "w0")
                .add(decimal(inputs, "realized"))
                .add(decimal(inputs, "commission"))
                .add(decimal(inputs, "funding"))
                .add(decimal(inputs, "external"))
                .add(decimal(inputs, "u1"))
            val actual = calculateFuturesDailyRiskModelB(
                start, current, decimal(inputs, "external")
            )
            assertNotNull(row.optString("case"), actual)
            assertEquals(0, start.compareTo(decimal(expected, "baseline")))
            assertEquals(0, actual!!.dailyPnlUsdt.compareTo(decimal(expected, "pnl")))
            val tolerance = BigDecimal("1e-24")
            assertTrue(
                actual.lossPct.subtract(decimal(expected, "loss_pct")).abs() < tolerance
            )
            assertEquals(!expected.getBoolean("allow_entry"), actual.entryBlocked)
            assertEquals(
                expected.getBoolean("emergency_5pct_threshold_reached"),
                actual.emergencyBreakerTripped
            )
        }
        assertEquals(4, blockedForMissingEvidence)
    }

    @Test
    fun rawAccountPreservesDecimalThresholdAndRejectsMissingOrContradictoryEvidence() {
        val account = JSONObject().put("multiAssetsMargin", false)
            .put("totalWalletBalance", "1000.00000000000001")
            .put("totalUnrealizedProfit", "-30.00000000000001")
            .put("totalMarginBalance", "970")
        val parsed = checkNotNull(parseFuturesDailyRiskAccountB(account))
        assertTrue(checkNotNull(calculateFuturesDailyRiskModelB(
            parsed.walletUsdt, parsed.equityUsdt, BigDecimal.ZERO)).entryBlocked)
        for (key in listOf("multiAssetsMargin", "totalWalletBalance", "totalUnrealizedProfit", "totalMarginBalance")) {
            val incomplete = JSONObject(account.toString())
            incomplete.remove(key)
            assertNull(parseFuturesDailyRiskAccountB(incomplete))
        }
        assertNull(parseFuturesDailyRiskAccountB(JSONObject(account.toString()).put("totalMarginBalance", "971")))
    }

    @Test
    fun exactThreeAndFivePercentBoundariesAndFullDepletionAreHandled() {
        assertFalse(checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal("970.1"), BigDecimal.ZERO
        )).entryBlocked)
        assertTrue(checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal("970"), BigDecimal.ZERO
        )).entryBlocked)
        assertTrue(checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal("969.9"), BigDecimal.ZERO
        )).entryBlocked)
        assertTrue(checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal("950"), BigDecimal.ZERO
        )).emergencyBreakerTripped)
        val depleted = checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO
        ))
        assertEquals(0, depleted.lossPct.compareTo(BigDecimal("100")))
        assertTrue(depleted.entryBlocked)
        assertTrue(depleted.emergencyBreakerTripped)
        assertNull(calculateFuturesDailyRiskModelB(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
    }

    @Test
    fun externalFlowsAreSeparatedAndFeesRemainInEquityExactlyOnce() {
        val rows = JSONArray()
            .put(JSONObject().put("incomeType", "TRANSFER").put("asset", "USDT")
                .put("income", "100").put("time", 10).put("tranId", "1"))
            .put(JSONObject().put("incomeType", "WITHDRAW").put("asset", "USDT")
                .put("income", "-50").put("time", 11).put("tranId", "2"))
            .put(JSONObject().put("incomeType", "COMMISSION").put("asset", "USDT")
                .put("income", "-1").put("time", 12).put("tranId", "3"))
        val summary = summarizeFuturesDailyIncomeModelB(rows, 0, 90, 95)
        assertNotNull(summary)
        assertEquals(0, summary!!.capitalFlowUsdt.compareTo(BigDecimal("50")))
        assertEquals(0, summary.totalIncomeUsdt.compareTo(BigDecimal("49")))
        val result = checkNotNull(calculateFuturesDailyRiskModelB(
            BigDecimal("1000"), BigDecimal("1049"), summary.capitalFlowUsdt
        ))
        assertEquals(0, result.dailyPnlUsdt.compareTo(BigDecimal("-1")))
    }

    @Test
    fun unknownIncomeAndSnapshotWindowEventsFailClosed() {
        val unknown = JSONArray().put(JSONObject()
            .put("incomeType", "UNRECOGNIZED").put("asset", "USDT")
            .put("income", "1").put("time", 10).put("tranId", "1"))
        assertNull(summarizeFuturesDailyIncomeModelB(unknown, 0, 90, 95))
        val overlapping = JSONArray().put(JSONObject()
            .put("incomeType", "COMMISSION").put("asset", "USDT")
            .put("income", "-1").put("time", 92).put("tranId", "2"))
        assertNull(summarizeFuturesDailyIncomeModelB(overlapping, 0, 90, 95))
        val nonUsdt = JSONArray().put(JSONObject()
            .put("incomeType", "REALIZED_PNL").put("asset", "BTC")
            .put("income", "1").put("time", 10).put("tranId", "3"))
        assertNull(summarizeFuturesDailyIncomeModelB(nonUsdt, 0, 90, 95))
    }

    @Test
    fun positionReverseRecoversOpeningPartialCloseFullCloseAndFlip() {
        fun trades(side: String, qty: String, price: String, pnl: String, id: Int = 1) =
            JSONArray().put(JSONObject().put("id", id).put("time", 100)
                .put("symbol", "BTCUSDT").put("side", side).put("qty", qty)
                .put("price", price).put("realizedPnl", pnl).put("positionSide", "BOTH"))
        val open = reverseFuturesPositionAtMidnightB(
            "BTCUSDT", BigDecimal.ONE, BigDecimal("101"), trades("BUY", "1", "101", "0"),
            0, 900, 950
        )
        assertNotNull(open)
        assertEquals(0, open!!.quantity.compareTo(BigDecimal.ZERO))
        val partial = reverseFuturesPositionAtMidnightB(
            "BTCUSDT", BigDecimal("0.5"), BigDecimal("100"), trades("SELL", "0.5", "110", "5"),
            0, 900, 950
        )
        assertNotNull(partial)
        assertEquals(0, partial!!.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, partial.entryPrice!!.compareTo(BigDecimal("100")))
        val closed = reverseFuturesPositionAtMidnightB(
            "BTCUSDT", BigDecimal.ZERO, BigDecimal.ZERO, trades("SELL", "1", "110", "10"),
            0, 900, 950
        )
        assertNotNull(closed)
        assertEquals(0, closed!!.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, closed.entryPrice!!.compareTo(BigDecimal("100")))
        val flipped = reverseFuturesPositionAtMidnightB(
            "BTCUSDT", BigDecimal("-0.5"), BigDecimal("110"), trades("SELL", "1.5", "110", "10"),
            0, 900, 950
        )
        assertNotNull(flipped)
        assertEquals(0, flipped!!.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, flipped.entryPrice!!.compareTo(BigDecimal("100")))
    }

    @Test
    fun modelBSnapshotMustMatchAccountAndUtcDay() {
        val snapshot = JSONObject()
            .put("schema_version", 2)
            .put("utc_day_start_ms", 0)
            .put("account_fingerprint", "account-hash")
            .put("wallet_balance_at_midnight_usdt", "1000")
            .put("unrealized_pnl_at_midnight_usdt", "10")
            .put("equity_at_midnight_usdt", "1010")
            .put("source", "BINANCE_INCOME_USER_TRADES_MARK_PRICE_1M")
            .put("complete", true)
            .put("observed_window_start_ms", 1)
            .put("observed_window_end_ms", 2)
            .put("positions_at_midnight", JSONArray())
        assertTrue(futuresDailyRiskModelBSnapshotEstablished(snapshot.toString(), "account-hash", 0))
        assertFalse(futuresDailyRiskModelBSnapshotEstablished(snapshot.toString(), "other", 0))
        assertFalse(futuresDailyRiskModelBSnapshotEstablished(snapshot.toString(), "account-hash", 86_400_000L))
    }
}

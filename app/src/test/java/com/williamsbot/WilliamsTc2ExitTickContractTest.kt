package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The same JSON test cases are executed by Python. Book chapter 12 PDF165.
 * A passing fixture is not evidence that a Binance order was accepted. */
class WilliamsTc2ExitTickContractTest {
    private val h1=3_600_000L
    private fun contract() = JSONObject(
        requireNotNull(javaClass.getResource("/williams_tc2_exit_tick_contract.json")).readText()
    )

    @Test fun directSourceTicksMatchSharedBookContractForBothSides() {
        val cases=contract().getJSONArray("direct")
        for (i in 0 until cases.length()) {
            val row=cases.getJSONObject(i)
            val actual=tc2ExactStructuralStop(
                row.getString("side"),row.getString("extreme").toDouble(),
                row.getString("tick").toDouble()
            )
            if (row.isNull("expected")) assertNull(row.toString(),actual)
            else assertEquals(row.toString(),row.getString("expected").toDouble(),actual!!,0.0)
        }
    }

    @Test fun fiveClosedBarBookTrailMatchesPythonOnExactExchangeTicks() {
        val cases=contract().getJSONArray("five_bar")
        for (i in 0 until cases.length()) {
            val row=cases.getJSONObject(i)
            val highs=row.getJSONArray("highs")
            val lows=row.getJSONArray("lows")
            assertEquals(highs.length(),lows.length())
            val candles=(0 until highs.length()).map { j ->
                FuturesTrailBar((j+1L)*h1,
                    highs.getString(j).toDouble(),lows.getString(j).toDouble())
            }
            val result=planWilliamsCampaignStop(
                direction=row.getString("side"),
                oldStop=row.getString("old_stop").toDouble(),
                actualEntry=row.getString("entry").toDouble(),
                initialStop=null,
                markPrice=row.getString("mark").toDouble(),
                tickSize=row.getString("tick").toDouble(),
                openedObservedAtMs=1L,bars=candles,window=5
            )
            assertNotNull(row.toString(),result)
            assertEquals(row.toString(), "TC2_LAST_5_CLOSED_BARS",result!!.reason)
            assertEquals(row.toString(),row.getString("expected").toDouble(),result.stop,0.0)
        }
    }
}

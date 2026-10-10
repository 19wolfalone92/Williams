package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsStrategyGoldenVectorsTest {
    private val vectors by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("williams_strategy_golden_vectors.json")
            ?: error("shared golden-vector fixture is missing from test resources")
        stream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun jsonDouble(value: org.json.JSONArray, index: Int): Double =
        value.getDouble(index)

    @Test
    fun alligatorUsesMedianPriceCanonicalPeriodsSmaSeedAndDisplayShifts() {
        val mediansJson = vectors.getJSONObject("alligator").getJSONArray("medianPrices")
        val medians = (0 until mediansJson.length()).map { jsonDouble(mediansJson, it) }
        val lines = WilliamsAlligatorMath.calculateFromMedianPrices(medians)
        val expected = vectors.getJSONObject("alligator").getJSONArray("expected")
        for (i in 0 until expected.length()) {
            val row = expected.getJSONObject(i)
            val line = when (row.getString("series")) {
                "jaw" -> lines.jaw
                "teeth" -> lines.teeth
                "lips" -> lines.lips
                else -> error("unknown Alligator series")
            }
            val index = row.getInt("index")
            if (row.isNull("value")) {
                assertFalse("Expected an unavailable pre-shift value", line[index].isFinite())
            } else {
                assertEquals(row.getDouble("value"), line[index], 1e-9)
            }
        }
    }

    @Test
    fun aoAndAcMatchSharedFormulaVectorsAndUseExactly34SlowSamples() {
        val data = vectors.getJSONObject("aoAc")
        val pricesJson = data.getJSONArray("medianPrices")
        val prices = (0 until pricesJson.length()).map { jsonDouble(pricesJson, it) }
        val ao = WilliamsOscillatorMath.aoSeries(prices)
        val aoExpected = data.getJSONArray("aoExpected")
        for (i in 0 until aoExpected.length()) {
            val row = aoExpected.getJSONObject(i)
            assertEquals(row.getDouble("value"), ao[row.getInt("index")], 1e-9)
        }
        val ac = WilliamsOscillatorMath.acSeries(prices)
        val acExpected = data.getJSONArray("acExpected")
        for (i in 0 until acExpected.length()) {
            val row = acExpected.getJSONObject(i)
            assertEquals(row.getDouble("value"), ac[row.getInt("index")], 1e-9)
        }
        val colors = data.getJSONObject("colors").getJSONArray("ao")
        val aoColors = (0 until colors.length()).map { colors.getDouble(it) }
        val expectedThird = data.getJSONObject("colors").getInt("thirdRisingIndex")
        assertTrue(WilliamsOscillatorMath.isThirdSameColorAoBar(aoColors, expectedThird, "LONG"))
        assertFalse(WilliamsOscillatorMath.isThirdSameColorAoBar(aoColors, expectedThird - 1, "LONG"))
    }

    @Test
    fun fractalConfirmationHandlesTiesAndDoesNotLookAhead() {
        val cases = vectors.getJSONObject("fractals")
        val strict = cases.getJSONObject("strictUp")
        val strictHighs = (0 until strict.getJSONArray("highs").length()).map {
            strict.getJSONArray("highs").getDouble(it)
        }
        assertNull(WilliamsFractalMath.confirmUp(
            strictHighs, strict.getInt("centerIndex"),
            strict.getInt("throughIndexBeforeConfirmation")
        ))
        val confirmedStrict = WilliamsFractalMath.confirmUp(strictHighs, strict.getInt("centerIndex"))
        assertNotNull(confirmedStrict)
        assertEquals(strict.getInt("expectedConfirmationIndex"), confirmedStrict!!.confirmationIndex)
        assertEquals(strict.getDouble("expectedLevel"), confirmedStrict.level, 0.0)

        val tied = cases.getJSONObject("equalRightHighExtendsConfirmation")
        val tiedHighs = (0 until tied.getJSONArray("highs").length()).map {
            tied.getJSONArray("highs").getDouble(it)
        }
        assertNull(WilliamsFractalMath.confirmUp(
            tiedHighs, tied.getInt("centerIndex"), tied.getInt("throughIndexBeforeConfirmation")
        ))
        val confirmedTied = WilliamsFractalMath.confirmUp(tiedHighs, tied.getInt("centerIndex"))
        assertNotNull(confirmedTied)
        assertEquals(tied.getInt("expectedConfirmationIndex"), confirmedTied!!.confirmationIndex)
        assertEquals(tied.getDouble("expectedLevel"), confirmedTied.level, 0.0)

        val cancelled = cases.getJSONObject("strictNewHighCancelsCandidate")
        val cancelledHighs = (0 until cancelled.getJSONArray("highs").length()).map {
            cancelled.getJSONArray("highs").getDouble(it)
        }
        assertNull(WilliamsFractalMath.confirmUp(cancelledHighs, cancelled.getInt("centerIndex")))

        val low = cases.getJSONObject("equalRightLowExtendsConfirmation")
        val lows = (0 until low.getJSONArray("lows").length()).map { low.getJSONArray("lows").getDouble(it) }
        assertNull(WilliamsFractalMath.confirmDown(lows, low.getInt("centerIndex"), low.getInt("throughIndexBeforeConfirmation")))
        assertEquals(low.getInt("expectedConfirmationIndex"),
            WilliamsFractalMath.confirmDown(lows, low.getInt("centerIndex"))!!.confirmationIndex)
    }

    @Test
    fun pendingEntryCannotBeRearmedAfterTriggerOrStructuralInvalidation() {
        val safe = vectors.getJSONObject("causalEntry").getJSONObject("long")
        val highs = listOf(10.0, 10.05, 10.05)
        val lows = listOf(9.0, 9.5, 9.4)
        assertTrue(WilliamsSignalMath.isLongSignalStillActionable(
            safe.getInt("signalIndex"), 2, highs, lows, safe.getDouble("triggerPrice")
        ))

        val crossed = vectors.getJSONObject("causalEntry").getJSONObject("triggerAlreadyCrossed")
        assertFalse(WilliamsSignalMath.isLongSignalStillActionable(
            0, 1, listOf(10.0, 10.2), listOf(9.0, 9.5), crossed.getDouble("triggerPrice")
        ))

        val invalidated = vectors.getJSONObject("causalEntry").getJSONObject("structuralInvalidationBreached")
        assertFalse(WilliamsSignalMath.isLongSignalStillActionable(
            0, 1, listOf(10.0, 10.05), listOf(9.0, 8.9), invalidated.getDouble("triggerPrice")
        ))
        val expired = vectors.getJSONObject("causalEntry").getJSONObject("expired")
        assertTrue(WilliamsSignalMath.isExpired(expired.getInt("currentBar").toLong(), expired.getInt("expiresAtBar").toLong()))
    }

    @Test
    fun onlyH1CanCreateCanonicalCampaignSignals() {
        val contract = vectors.getJSONObject("timeframeContract")
        assertEquals(WilliamsTimeframeContract.CANONICAL_DECISION, contract.getString("canonicalDecision"))
        assertTrue(WilliamsTimeframeContract.canCreateCanonicalSignal("1h"))
        assertFalse(WilliamsTimeframeContract.canCreateCanonicalSignal("15m"))
        assertFalse(WilliamsTimeframeContract.canCreateCanonicalSignal("5m"))
    }


    @Test
    fun entryProvenanceFailsClosedAcrossTimeframesAndInvalidTimestamps() {
        val now = 10_000_000L
        fun blocker(
            timeframe: String = "1h",
            bar: Long = now - 7_200_000L,
            confirmation: Long = now - 3_600_000L,
            expiry: Long = now + 60_000L,
            symbolMatches: Boolean = true,
            signalId: Boolean = true,
            campaignId: Boolean = true,
            trigger: Double = 101.0,
            invalidation: Double = 97.0
        ) = WilliamsTimeframeContract.entryProvenanceBlocker(
            timeframe, bar, confirmation, expiry, now,
            symbolMatches, signalId, campaignId, trigger, invalidation
        )

        assertNull(blocker())
        assertEquals("ENTRY_TIMEFRAME_BLOCKED", blocker(timeframe = "5m"))
        assertEquals("ENTRY_TIMEFRAME_BLOCKED", blocker(timeframe = "15m"))
        assertEquals("ENTRY_TIMEFRAME_BLOCKED", blocker(timeframe = ""))
        assertEquals("ENTRY_TIMEFRAME_BLOCKED", blocker(timeframe = "unknown"))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(expiry = now))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(confirmation = now + 1L))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(confirmation = now - 7_200_000L))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(bar = 0L))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(symbolMatches = false))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(signalId = false))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(campaignId = false))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(trigger = Double.NaN))
        assertEquals("ENTRY_PROVENANCE_BLOCKED", blocker(invalidation = 0.0))
    }
}

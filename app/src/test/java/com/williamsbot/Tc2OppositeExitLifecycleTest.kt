package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class Tc2OppositeExitLifecycleTest {
    private val hour=3_600_000L

    private fun signal(side:String,expiresAt:Long=40*hour):WilliamsScenarioSignal =
        WilliamsScenarioSignal(
            "BOOK_OPPOSITE_TEST", side, williamsTc2Profile, "STOP_ENTRY",
            19*hour,
            if(side=="SHORT") 95.0 else 105.0,
            if(side=="SHORT") 105.0 else 95.0,
            expiresAt
        )

    private fun bars(side:String,invalidates:Boolean):List<WilliamsWaveBar> =
        if(side=="SHORT") listOf(
            WilliamsWaveBar(20*hour,100.0,101.0,94.0,96.0),
            WilliamsWaveBar(21*hour,96.0,if(invalidates) 106.0 else 103.0,96.0,103.0),
            WilliamsWaveBar(22*hour,103.0,104.0,94.0,95.0))
        else listOf(
            WilliamsWaveBar(20*hour,100.0,106.0,99.0,104.0),
            WilliamsWaveBar(21*hour,104.0,104.0,if(invalidates) 94.0 else 97.0,97.0),
            WilliamsWaveBar(22*hour,97.0,106.0,96.0,105.0))

    @Test fun alreadyFailedOppositeH1BreakoutMustNeverResurrectAfterLaterRecross() {
        // The old generic level-event detector intentionally knows the
        // FIRST trigger, not whether the trend subsequently invalidated it.
        for(side in listOf("LONG","SHORT")) {
            val owner=if(side=="LONG") "SHORT" else "LONG"
            val source=signal(side)
            val candles=bars(side,invalidates=true)
            val contract=if(side=="SHORT") 94.0 else 106.0
            val assessed=assessWilliamsScenarioOpposition(owner,18*hour,
                listOf(source),candles,contract,23*hour)
            val first=requireNotNull(assessed.opposition)
            assertEquals("TRIGGERED",first.event.state)
            assertEquals(21*hour,first.event.knownAt)
            val proof=tc2OppositeExitLifecycle(first.signal,first.event,candles,23*hour)
            assertFalse(side,proof.valid)
            assertEquals(side,"OPPOSITE_SIGNAL_INVALIDATED_AFTER_FIRST_TRIGGER",proof.reason)
            assertFalse(proof.toJson().getBoolean("may_override_exchange_stop"))
        }
    }

    @Test fun validFreshOppositeH1BreakoutIsNotBlockedByProfitabilityOrPullbackWithoutInvalidation() {
        for(side in listOf("LONG","SHORT")) {
            val owner=if(side=="LONG") "SHORT" else "LONG"
            val source=signal(side)
            val candles=bars(side,invalidates=false)
            val contract=if(side=="SHORT") 94.0 else 106.0
            val assessed=assessWilliamsScenarioOpposition(owner,18*hour,
                listOf(source),candles,contract,23*hour)
            val first=requireNotNull(assessed.opposition)
            val proof=tc2OppositeExitLifecycle(first.signal,first.event,candles,23*hour)
            assertTrue(side,proof.valid)
            assertEquals("CLOSED_H1_SIGNAL_REMAINS_VALID",proof.reason)
            assertEquals(2,proof.observedAfterTrigger)
        }
    }

    @Test fun oldOppositeSignalExpiresEvenIfOriginallyTriggered() {
        val source=signal("SHORT",expiresAt=22*hour)
        val proof=tc2OppositeExitLifecycle(source,
            WilliamsLevelEvent("TRIGGERED",21*hour,"CLOSED_BAR_EXTREME"),
            bars("SHORT",invalidates=false),23*hour)
        assertFalse(proof.valid)
        assertEquals("OPPOSITE_SOURCE_TIME_OR_EVENT_UNVERIFIED",proof.reason)
    }

    @Test fun missingClosedH1BarsNeverAuthorizeResurrectedOppositeExit() {
        val source=signal("SHORT")
        val bars=bars("SHORT",invalidates=false)
        val proof=tc2OppositeExitLifecycle(source,
            WilliamsLevelEvent("TRIGGERED",21*hour,"CLOSED_BAR_EXTREME"),
            bars.filter { it.t!=21*hour },23*hour)
        assertFalse(proof.valid)
        assertEquals("OPPOSITE_POST_TRIGGER_CLOSED_H1_GAP_OR_INVALID",proof.reason)
    }

    @Test fun freshLivePriceCrossingStillPassesWithNoPostTriggerBars() {
        val now=23*hour+15_000L
        val fresh=tc2OppositeExitLifecycle(signal("SHORT"),
            WilliamsLevelEvent("TRIGGERED",now,"LIVE_MARK"),
            bars("SHORT",invalidates=false),now)
        assertTrue(fresh.valid)
        assertEquals("CURRENT_CONTRACT_TRIGGER",fresh.reason)
        assertFalse(tc2OppositeExitLifecycle(signal("SHORT"),
            WilliamsLevelEvent("TRIGGERED",now-1,"LIVE_MARK"),
            bars("SHORT",invalidates=false),now).valid)
    }

    @Test fun aSourceWithNoValidOppositeBoundaryNeverAuthorizesMarketExit() {
        val signal=signal("SHORT").copy(invalidation=null)
        assertEquals("OPPOSITE_PRICE_INVALIDATION_BOUNDARY_UNVERIFIED",
            tc2OppositeExitLifecycle(signal,
                WilliamsLevelEvent("TRIGGERED",21*hour,"CLOSED_BAR_EXTREME"),
                bars("SHORT",false),23*hour).reason)
    }
}

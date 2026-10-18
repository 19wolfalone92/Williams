package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsComplexWaveTest {
    private fun pivots(prices:List<Double>,firstLow:Boolean=true):List<WilliamsWaveAnchor> =
        prices.mapIndexed { i,v ->
            val low=(i%2==0)==firstLow
            WilliamsWaveAnchor(if(low) "LOW" else "HIGH",v,i,(i+1)*1000L,(i+1)*1000L+100)
        }
    private fun labels(prices:List<Double>,direction:String="UP",firstLow:Boolean=true):Set<String> =
        detectWilliamsComplexWave(pivots(prices,firstLow),direction,100000L).map { it.pattern }.toSet()

    @Test fun ordinaryAndTruncatedFifthsAreDistinctAndNotTradingSignals() {
        assertTrue("ORDINARY_IMPULSE_CANDIDATE" in labels(listOf(100.0,120.0,110.0,145.0,125.0,155.0)))
        assertTrue("TRUNCATED_FIFTH_CANDIDATE" in labels(listOf(100.0,120.0,110.0,145.0,125.0,142.0)))
        assertTrue(detectWilliamsComplexWave(pivots(listOf(100.0,120.0,110.0,145.0,125.0,155.0)),
            "UP",100000L).all { !it.toJson().getBoolean("can_place_orders") })
    }
    @Test fun overlappingDiagonalIsAnAlternativeButBrokenW3OriginIsNot() {
        assertTrue("ENDING_OR_LEADING_DIAGONAL_CANDIDATE" in labels(
            listOf(100.0,120.0,110.0,145.0,115.0,160.0)))
        assertFalse("ENDING_OR_LEADING_DIAGONAL_CANDIDATE" in labels(
            listOf(100.0,120.0,110.0,145.0,105.0,160.0)))
    }
    @Test fun zigzagFlatTriangleAndCombinationsAreResearchOnly() {
        assertTrue("ZIGZAG_ABC_CANDIDATE" in labels(
            listOf(100.0,120.0,110.0,145.0,125.0,155.0,135.0,143.0,120.0)))
        assertTrue("EXPANDED_FLAT_ABC_CANDIDATE" in labels(
            listOf(100.0,120.0,110.0,145.0,125.0,155.0,135.0,158.0,128.0)))
        assertTrue("RUNNING_FLAT_ABC_CANDIDATE" in labels(
            listOf(100.0,120.0,110.0,145.0,125.0,155.0,135.0,158.0,140.0)))
        assertTrue("CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" in labels(
            listOf(130.0,100.0,125.0,105.0,120.0,110.0),firstLow=false))
        val combination=labels(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,143.0,120.0,132.0,116.0,125.0))
        assertTrue("W_X_Y_COMBINATION_UNRESOLVED" in combination)
        assertTrue("W_X_Y_X_Z_COMBINATION_UNRESOLVED" in combination)
    }
    @Test fun noFuturePivotsAndMirroredDownside() {
        val prices=listOf(100.0,120.0,110.0,145.0,125.0,155.0)
        assertTrue(detectWilliamsComplexWave(pivots(prices),"UP",6000L).isEmpty())
        assertTrue(detectWilliamsComplexWave(pivots(prices),"UP",6200L).isNotEmpty())
        assertTrue("ORDINARY_IMPULSE_CANDIDATE" in labels(prices.map { 1000.0-it },
            direction="DOWN",firstLow=false))
    }
}

package com.williamsbot

/** TC2 ch9: a divergent bar piercing the Alligator mouth is invalid (PDF128).
 * Angulation remains a declared numerical approximation of the visual rule. */
internal fun validWilliamsReversalBar(
    direction:String,bar:WilliamsWaveBar,previousExtreme:Double,mouth:List<Double>,angulation:Double
):Boolean {
    if (direction !in listOf("LONG","SHORT") || mouth.size!=3 ||
        (mouth+listOf(bar.o,bar.h,bar.l,bar.c,previousExtreme,angulation)).any { !it.isFinite() } ||
        minOf(bar.o,bar.l,bar.c)<=0 || bar.h<=bar.l || bar.h<maxOf(bar.o,bar.c) ||
        bar.l>minOf(bar.o,bar.c) || angulation<=0) return false
    return if (direction=="LONG") bar.l<previousExtreme && bar.c>=(bar.h+bar.l)/2 && bar.h<mouth.min()
        else bar.h>previousExtreme && bar.c<=(bar.h+bar.l)/2 && bar.l>mouth.max()
}

internal data class WilliamsPendingFractal(
    val center:Int,val groupStart:Int,val confirmation:Int,val trigger:Double,val protective:Double,val currentTeeth:Double
)

/** Latest eligible confirmed fractal, rather than an ineligible newer one
 * masking an older still-pending level. Each plateau is one extremum group. */
internal fun selectWilliamsPendingFractal(
    direction:String,bars:List<WilliamsWaveBar>,teeth:List<Double>,tick:Double
):WilliamsPendingFractal? {
    if (direction !in listOf("LONG","SHORT") || bars.size<5 || teeth.size!=bars.size ||
        !tick.isFinite() || tick<=0 || !teeth.last().isFinite() || teeth.last()<=0 ||
        !validWilliamsEditionBars(bars) ||
        // The same selector also serves the legacy read-only M15
        // refinement. Demand consecutive REAL bars at their genuine
        // resolution (1h, 15m, 5m, ...), never assume a missing H1/M15
        // candle counts toward the two right-side fractal confirmations.
        bars[1].t-bars[0].t !in williamsWaveFrames.values ||
        bars.any { it.t<0L || it.t%(bars[1].t-bars[0].t)!=0L } ||
        bars.zipWithNext().any { (a,b) -> b.t-a.t!=bars[1].t-bars[0].t }) return null
    val long=direction=="LONG"
    fun extreme(i:Int)=if (long) bars[i].h else bars[i].l
    for (i in bars.lastIndex-2 downTo 2) {
        val p=extreme(i)
        if (!p.isFinite() || extreme(i+1)==p) continue
        var start=i
        var count=0
        var j=i-1
        while (j>=0 && count<2) {
            val value=extreme(j)
            if (value==p) start=j
            else if (if (long) value<p else value>p) count++
            else break
            j--
        }
        if (count<2 || !(i+1..i+2).all { if (long) extreme(it)<p else extreme(it)>p }) continue
        val trigger=if (long) p+tick else p-tick
        // TC2 ch11 PDF pp.147-149 and NTD ch4: the live BREAKOUT level,
        // not the historic pivot extreme, must be beyond Teeth. Evaluate
        // the still-untriggered order against the current displaced red line.
        if (trigger<=0 || (if (long) trigger<=teeth.last() || bars.last().c>=trigger
                else trigger>=teeth.last() || bars.last().c<=trigger)) continue
        if ((i+2..bars.lastIndex).any { if (long) bars[it].h>=trigger else bars[it].l<=trigger }) continue
        val stopStart=minOf(start,maxOf(0,i+2-4))
        val protective=if (long) (stopStart..i+2).minOf { bars[it].l }-tick
            else (stopStart..i+2).maxOf { bars[it].h }+tick
        if (!protective.isFinite() || protective<=0) continue
        return WilliamsPendingFractal(i,start,i+2,trigger,protective,teeth.last())
    }
    return null
}


/**
 * Trading Chaos 2nd ed., chapter 12 (PDF pp. 156-161):
 * when Super AO is the first presenting Wise Man, the initial protective
 * stop may be at the reversal base or, more conservatively, just beyond
 * the signal price bar. The three-to-five-bar rule describes SUBSEQUENT
 * trailing, not a mandatory initial stop window (PDF p.156, printed p.141).
 *
 * Choose the explicitly described signal-bar variant. Retain the third AO
 * bar as the original risk anchor even after later bars become available.
 */
internal fun williamsTc2SuperAoInitialStop(
    direction:String,bars:List<WilliamsWaveBar>,thirdAoBarIndex:Int,tick:Double
):Double? {
    if(direction !in setOf("LONG","SHORT") ||
        thirdAoBarIndex !in bars.indices || !tick.isFinite() || tick<=0)
        return null
    val b=bars[thirdAoBarIndex]
    if(listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() } ||
            minOf(b.o,b.l,b.c)<=0 ||
                b.h<b.l || b.h<maxOf(b.o,b.c) || b.l>minOf(b.o,b.c)
        ) return null
    val price=if(direction=="LONG") b.l-tick else b.h+tick
    if(!price.isFinite() || price<=0) return null
    return price
}


/** The SHORT WM3 candidate is detected on mirrored OHLC, but executable
 * prices must be derived from the ORIGINAL exchange candles. Floating-point
 * reflection can shift a perfectly valid tick by ~1 ulp, and a later floor()
 * then loses an entire tick (observed on BTCUSDT/ETHUSDT 2026-10-26).
 *
 * The mirrored series has NO price-setting authority. All inputs are known
 * closed H1 source bars, including two confirmed right-hand fractal bars.
 * TC2 ch.11: sell at the source pivot LOW minus exactly one exchange tick;
 * protective stop is beyond the highest HIGH in the selector's 3-5-bar
 * confirmation window (or the complete extended same-price plateau).
 */
internal data class Tc2ShortFractalSourceLevels(
    val trigger: Double,
    val protective: Double,
    val pivotLow: Double,
    val stopWindowHigh: Double
)

internal fun exactTc2ShortFractalLevels(
    bars: List<WilliamsWaveBar>,
    centerAtMs: Long,
    groupStartAtMs: Long,
    confirmationAtMs: Long,
    tick: Double
): Tc2ShortFractalSourceLevels? {
    if (bars.size < 5 || !tick.isFinite() || tick <= 0.0 ||
        centerAtMs < 0L || groupStartAtMs < 0L || confirmationAtMs < 0L) return null
    fun uniqueIndex(at: Long): Int {
        val matches = bars.indices.filter { bars[it].t == at }
        return if (matches.size == 1) matches.single() else -1
    }
    val center = uniqueIndex(centerAtMs)
    val groupStart = uniqueIndex(groupStartAtMs)
    val confirmation = uniqueIndex(confirmationAtMs)
    if (center < 2 || groupStart < 0 || groupStart > center ||
        confirmation != center + 2 || confirmation !in bars.indices) return null
    if (bars.take(confirmation + 1).zipWithNext().any { (a, b) -> b.t <= a.t }) return null
    // Identical to selectWilliamsPendingFractal: preserve a long plateau
    // when it stretches further left than the ordinary five-bar window.
    val stopStart = minOf(groupStart, maxOf(0, confirmation - 4))
    val window = bars.subList(stopStart, confirmation + 1)
    if (window.any { !it.h.isFinite() || !it.l.isFinite() ||
            it.h < it.l || it.l <= 0.0 }) return null
    val pivot = bars[center].l
    val highest = window.maxOf { it.h }
    // If the original exchange prices are not on the tick grid, do NOT
    // silently fabricate a nearby rule-compliant execution price.
    if (!ExecutionMath.isMultiple(pivot, tick) ||
        !ExecutionMath.isMultiple(highest, tick)) return null
    val step = ExecutionMath.decimal(tick)
    val trigger = ExecutionMath.decimal(pivot).subtract(step).toDouble()
    val protective = ExecutionMath.decimal(highest).add(step).toDouble()
    if (!trigger.isFinite() || !protective.isFinite() ||
        trigger <= 0.0 || protective <= trigger ||
        !ExecutionMath.isMultiple(trigger, tick) ||
        !ExecutionMath.isMultiple(protective, tick)) return null
    return Tc2ShortFractalSourceLevels(trigger, protective, pivot, highest)
}

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
        !validWilliamsEditionBars(bars)) return null
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
 * stop is behind the LOWEST low (LONG) / HIGHEST high (SHORT) of the last
 * three-to-five price bars, not merely the low/high of the third AO bar.
 *
 * Choose five CLOSED H1 price bars ending with that third AO bar, retaining
 * the original time and series causality; this is a documented, consistent
 * choice inside the book's 3–5 bar range. Never examine subsequent bars.
 */
internal fun williamsTc2SuperAoInitialStop(
    direction:String,bars:List<WilliamsWaveBar>,thirdAoBarIndex:Int,tick:Double,
    window:Int=5
):Double? {
    if(direction !in setOf("LONG","SHORT") || window !in 3..5 ||
        thirdAoBarIndex !in (window-1)..bars.lastIndex || !tick.isFinite() || tick<=0)
        return null
    val history=bars.subList(thirdAoBarIndex-window+1,thirdAoBarIndex+1)
    if(history.any { b ->
            !b.h.isFinite() || !b.l.isFinite() || b.l<=0 ||
                b.h<b.l || b.h<maxOf(b.o,b.c) || b.l>minOf(b.o,b.c)
        }) return null
    val price=if(direction=="LONG") history.minOf { it.l }-tick
        else history.maxOf { it.h }+tick
    if(!price.isFinite() || price<=0) return null
    return price
}

package com.williamsbot

/**
 * NTD, chapter 8 (PDF 99-114): fifth-dimension Balance Line trade FORMATION.
 *
 * Read HIGHs for buys and LOWs for sells from right to left. The current
 * Blue Jaw is the Balance Line, not the Red Teeth used by fractal entries.
 * The 1/2 reference-extreme rule doubles for BUY in RED zone and SELL in
 * GREEN zone. This is diagnostic only: no orders and no automatic profile
 * switching from the TC2 Three Wise Men system.
 */
internal data class WilliamsNtdBar(
    val h: Double, val l: Double,
    val jaw: Double, val teeth: Double, val lips: Double,
    val ao: Double, val ac: Double
)

internal data class WilliamsNtdBalanceEvidence(
    val side: String, val status: String,
    val baseIndex: Int = -1, val referenceIndex: Int = -1,
    val requiredSteps: Int = 0, val completedSteps: Int = 0,
    val triggerPrice: Double = 0.0, val protectiveReference: Double = 0.0,
    val zone: String = "UNKNOWN", val relation: String = "UNKNOWN",
    val reason: String = "",
    val canPlaceOrders: Boolean = false
)

/** All bars passed here MUST be actual exchange-closed candles. */
internal fun williamsNtdBalanceLine(
    bars: List<WilliamsNtdBar>, side: String, tick: Double,
    lookback: Int = 20
): WilliamsNtdBalanceEvidence {
    fun blocked(reason: String) = WilliamsNtdBalanceEvidence(side,"BLOCKED",reason=reason)
    if (side !in listOf("LONG","SHORT")) return blocked("INVALID_SIDE")
    if (!tick.isFinite() || tick <= 0.0) return blocked("INVALID_TICK")
    if (bars.size < 5 || lookback < 3) return blocked("INSUFFICIENT_BARS")
    val last=bars.last()
    val prev=bars[bars.lastIndex-1]
    if (!listOf(last.ao,last.ac,prev.ao,prev.ac).all { it.isFinite() })
        return blocked("ZONE_NOT_READY")
    val zone = when {
        last.ao > prev.ao && last.ac > prev.ac -> "GREEN"
        last.ao < prev.ao && last.ac < prev.ac -> "RED"
        else -> "GRAY"
    }
    val long = side == "LONG"
    fun selected(b: WilliamsNtdBar) = if (long) b.h else b.l
    val start = maxOf(1,bars.size-lookback)
    val candidates = (start..bars.lastIndex).filter { i ->
        val b=bars[i]
        listOf(b.h,b.l,b.jaw,b.teeth,b.lips).all { it.isFinite() } &&
            b.l>0 && b.h>=b.l && minOf(b.jaw,b.teeth,b.lips)>0
    }
    if(candidates.isEmpty()) return blocked("NO_VALID_BASE")
    val base = if (long) candidates.minWithOrNull(compareBy<Int> { selected(bars[it]) }.thenByDescending { it })!!
        else candidates.maxWithOrNull(compareBy<Int> { selected(bars[it]) }.thenBy { it })!!
    val basePrice=selected(bars[base])
    // NTD PDF p104 Fig 8-9(D) / p108 Fig 8-20(D): it is the
    // SIGNAL reference bar's position relative to Jaw, not the base
    // bar's historic Jaw, that decides 1 vs 2 extremes.
    var relation="UNKNOWN"
    var needed=0
    var count=0
    var prior=basePrice
    var ref=-1
    val left=maxOf(0,base-2*lookback)
    for (i in base-1 downTo left) {
        val price=selected(bars[i])
        if (!price.isFinite() || price<=0) return blocked("INVALID_PRICE_IN_FORMATION")
        if ((long && price>prior) || (!long && price<prior)) {
            if(count==0) {
                val jaw=bars[i].jaw
                if(!jaw.isFinite() || jaw<=0) return blocked("SIGNAL_JAW_NOT_READY")
                relation=when {
                    price>jaw -> "ABOVE"
                    price<jaw -> "BELOW"
                    else -> "ON"
                }
                val uphill=(long && relation!="ABOVE") || (!long && relation!="BELOW")
                needed=(if(uphill) 2 else 1)*
                    (if((long && zone=="RED") || (!long && zone=="GREEN")) 2 else 1)
            }
            count++
            prior=price
            ref=i
            if (count==needed) break
        }
    }
    if (needed==0) return blocked("NO_NEW_REFERENCE_EXTREME")
    if (count<needed) return WilliamsNtdBalanceEvidence(
        side,"INCOMPLETE",base,ref,needed,count,zone=zone,
        relation=relation,reason="MISSING_REQUIRED_REFERENCE_EXTREMES"
    )
    val trigger=if (long) prior+tick else prior-tick
    val protective=if(long) bars[base].l else bars[base].h
    if (!trigger.isFinite() || trigger<=0 || !protective.isFinite() || protective<=0 ||
        (long && protective>=trigger) || (!long && protective<=trigger))
        return blocked("INVALID_ORDER_GEOMETRY")
    val mouth=if(long) maxOf(last.jaw,last.teeth,last.lips) else minOf(last.jaw,last.teeth,last.lips)
    if(!mouth.isFinite() || (long && trigger<=mouth) || (!long && trigger>=mouth))
        return WilliamsNtdBalanceEvidence(side,"BLOCKED",base,ref,needed,count,trigger,protective,
            zone,relation,"INSIDE_ALLIGATOR_MOUTH")
    val touched=(base+1..bars.lastIndex).any { i ->
        if(long) bars[i].h>=trigger else bars[i].l<=trigger
    }
    if(touched) return WilliamsNtdBalanceEvidence(side,"BLOCKED",base,ref,needed,count,
        trigger,protective,zone,relation,"TRIGGER_ALREADY_TOUCHED")
    return WilliamsNtdBalanceEvidence(side,"CANDIDATE",base,ref,needed,count,
        trigger,protective,zone,relation,"NTD_CH8_RESEARCH_ONLY")
}

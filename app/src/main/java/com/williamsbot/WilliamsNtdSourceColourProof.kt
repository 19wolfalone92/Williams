package com.williamsbot

/** NTD, chapter 5, printed pp.57-66 (PDF images 57-66).
 * Original *closed* AO values, not precomputed colour bits.
 * All signals are READ-ONLY book evidence, never order authorization.
 */
internal fun williamsNtdLastClosedAoFormation(
    original:List<Double>,direction:String,maxAgeBars:Int=30
):String? {
    if(direction !in setOf("LONG","SHORT") || original.size<3) return null
    val ao=if(direction=="SHORT") original.map { -it } else original
    val last=ao.lastIndex
    if(ao.subList(last-2,last+1).any { !it.isFinite() }) return null
    val saucer=ao[last-2]>0 && ao[last-1]>0 && ao[last]>0 &&
        ao[last-2]>ao[last-1] && ao[last]>ao[last-1]
    val zeroCross=ao[last-1]<0 && ao[last]>0
    // A confirmed trough requires a value at both neighbours; the second
    // trough must precede the green signal bar exactly (not look-ahead).
    val twinPeaks=if(ao[last]>ao[last-1]) {
        val lo=maxOf(1,last-maxOf(3,maxAgeBars))
        val troughs=(lo until last).filter { i ->
            ao[i-1].isFinite() && ao[i].isFinite() && ao[i+1].isFinite() &&
                ao[i]<0 && ao[i]<ao[i-1] && ao[i]<ao[i+1]
        }
        if(troughs.size<2 || troughs.last()!=last-1) false
        else {
            val first=troughs[troughs.lastIndex-1]
            val second=troughs.last()
            ao[first]<ao[second] && (first..last).all { j ->
                ao[j].isFinite() && ao[j]<0
            }
        }
    } else false
    return when {
        twinPeaks->"AO_TWIN_PEAKS"
        saucer->"AO_SAUCER"
        zeroCross->"AO_ZERO_LINE"
        else->null
    }
}

/** NTD chapter 6, pp.74-82. Crossing zero changes the number of confirming
 * AC bars, it NEVER creates a standalone AC entry. All signals observer-only.
 */
internal fun williamsNtdLastClosedAcFormation(
    original:List<Double>,direction:String
):String? {
    if(direction !in setOf("LONG","SHORT") || original.size<4) return null
    val ac=if(direction=="SHORT") original.map { -it } else original
    val last=ac.lastIndex
    if(!ac[last].isFinite()) return null
    var run=0
    var i=last
    while(i>0 && ac[i].isFinite() && ac[i-1].isFinite() && ac[i]>ac[i-1]) {
        run++;i--
    }
    val precedingOpposite=i>0 && ac[i].isFinite() && ac[i-1].isFinite() &&
        ac[i]<ac[i-1]
    val withMomentum=ac[last]>0
    val needed=if(withMomentum) 2 else 3
    val crossingAtLast=ac[last-1].isFinite() && ac[last-1]<=0 && ac[last]>0
    val complete=run==needed || (withMomentum && run in 2..3 && crossingAtLast)
    return if(complete && precedingOpposite) "AC_SIGNAL" else null
}

package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.abs

internal const val williamsTc2Profile="TC2_THREE_WISE_MEN"
internal const val williamsNtdProfile="NTD_FIVE_DIMENSIONS"
internal const val williamsCourseProfile="COURSE_PROFITUNITY"
internal const val williamsUnifiedProfile="WILLIAMS_UNIFIED"

/** TC1/NTD/TC2/Course: decimal-exact single Binance tick beyond a SOURCE OHLC
 * extremum. Never let binary Double addition shift a STOP/trigger by a tick.
 * Off-grid market data cannot create executable book evidence; keep existing
 * exchange protection instead of guessing a corrected price. */
internal fun williamsBookExactPriceTick(extreme:Double,tick:Double,above:Boolean):Double? {
    if(!extreme.isFinite() || extreme<=0.0 || !tick.isFinite() || tick<=0.0) return null
    val p=BigDecimal.valueOf(extreme)
    val step=BigDecimal.valueOf(tick)
    if(p.remainder(step).signum()!=0) return null
    return p.add(if(above) step else step.negate()).toDouble()
        .takeIf { it.isFinite() && it>0.0 }
}
internal val williamsSourceProfiles=listOf(williamsTc2Profile,williamsNtdProfile,williamsCourseProfile)
internal val williamsEditionProfiles=williamsSourceProfiles+williamsUnifiedProfile

/** Unified selection scans all three sources but journals the real source for
 * each individual campaign. Never reinterpret a live legacy campaign. */
internal fun williamsProfileAllowsSource(selected:String,source:String):Boolean =
    source in williamsSourceProfiles && (selected==williamsUnifiedProfile || selected==source)

/** Live new exposure is deliberately narrower than signal-source visibility.
 * TC2 and NTD retain independent source lifecycles. Course remains OBSERVE;
 * an observation cannot acquire order authority through the unified selector.
 */
internal fun williamsVerifiedNewRiskSource(source:String):Boolean =
    williamsNewExposureBlocker(source)==null

/** Contradictory fresh H1 entry hypotheses must not choose a side by chance. */
internal fun williamsUnifiedDirectionsConflict(directions:Iterable<String>):Boolean =
    directions.filter { it=="LONG" || it=="SHORT" }.toSet().size>1

internal data class WilliamsEditionSetup(
    val kind:String,val side:String,val knownIndex:Int,val trigger:Double,val stop:Double,
    val invalidation:Double=0.0,val context:JSONObject=JSONObject(),
    val role:String="ENTRY",val executionSemantics:String="STOP_ENTRY"
)
internal data class WilliamsEditionFractal(
    val kind:String,val price:Double,val center:Int,val groupStart:Int,val leftStart:Int,val confirmation:Int
)
internal fun validWilliamsEditionBars(bars:List<WilliamsWaveBar>):Boolean = bars.isNotEmpty() &&
    bars.all { b -> b.t>=0 && listOf(b.o,b.h,b.l,b.c,b.v).all { it.isFinite() } &&
        minOf(b.o,b.l,b.c)>0 && b.h>=maxOf(b.o,b.l,b.c) && b.l<=minOf(b.o,b.c) && b.v>=0 } &&
    bars.zipWithNext().all { (a,b) -> a.t<b.t }

/** No book permits treating candles separated by an unobserved H1 interval
 * as consecutive confirmation bars, AO bars or an Alligator path. In the past
 * the TC2 generator had no local gap check (unlike NTD/Course and the final
 * execution door). Keep the scanner itself causally truthful, too.
 */
internal fun williamsEditionSignalSeriesBlocker(
    bars: List<WilliamsWaveBar>, frame: String
): String? {
    val step = williamsWaveFrames[frame] ?: return "UNSUPPORTED_BOOK_SIGNAL_FRAME"
    if (bars.size < 45 || !validWilliamsEditionBars(bars))
        return "BOOK_SIGNAL_OHLCV_UNVERIFIED"
    if (bars.any { it.t <= 0L || it.t % step != 0L })
        return "BOOK_SIGNAL_BAR_TIME_MISALIGNED"
    if (bars.zipWithNext().any { (a, b) -> b.t - a.t != step })
        return "BOOK_SIGNAL_CANDLE_GAP"
    return null
}

/** NTD PDF42 and Course PDF61-72. Outside bars keep both genuine fractals;
 * simultaneous extremes cannot establish an ordered Hump start/signal. */
internal fun confirmedWilliamsEditionFractals(bars:List<WilliamsWaveBar>):List<WilliamsEditionFractal> {
    if (!validWilliamsEditionBars(bars)) return emptyList()
    val out=mutableListOf<WilliamsEditionFractal>()
    for (i in 2 until bars.size-2) for (kind in listOf("LOW","HIGH")) {
        fun value(j:Int)=if (kind=="LOW") bars[j].l else bars[j].h
        val p=value(i)
        if (value(i+1)==p) continue
        var start=i;var leftStart=i;var confirmation:Int?=null;var ok=true
        for (step in listOf(-1,1)) {
            var count=0;var j=i+step
            while (j in bars.indices && count<2) {
                val v=value(j)
                if (v==p) {
                    if (step==1) { ok=false;break }
                    start=j
                } else if ((v>p)==(kind=="LOW")) count++
                else { ok=false;break }
                if (count==2) { if (step<0) leftStart=j else confirmation=j }
                j+=step
            }
            ok=ok && count==2
        }
        if (ok && confirmation!=null) out+=WilliamsEditionFractal(kind,p,i,start,leftStart,confirmation)
    }
    return out.sortedWith(compareBy<WilliamsEditionFractal> { it.center }.thenBy { it.kind })
}

/** NTD PDF103-111: right-to-left base, successive record extrema, +1/+2,
 * adverse AO+AC zone doubles the count. Later higher highs do not move a buy. */
internal fun williamsNtdBalanceLineSetup(
    side:String,bars:List<WilliamsWaveBar>,jaw:List<Double>,teeth:List<Double>,lips:List<Double>,
    ao:List<Double>,ac:List<Double>,tick:Double,requireMouth:Boolean=true
):WilliamsEditionSetup? {
    val n=bars.size
    if (side !in listOf("LONG","SHORT") || n<3 || !validWilliamsEditionBars(bars) ||
        listOf(jaw,teeth,lips,ao,ac).any { it.size!=n } || !tick.isFinite() || tick<=0) return null
    if (listOf(jaw.last(),teeth.last(),lips.last(),ao[n-2],ao.last(),ac[n-2],ac.last()).any { !it.isFinite() }) return null
    val sign=if (side=="LONG") 1 else -1
    fun extreme(i:Int)=if (sign==1) bars[i].h else bars[i].l
    var base=n-1;val records=mutableListOf<Int>();var last=sign*extreme(base);var needed:Int?=null
    val adverse=sign*(ao.last()-ao[n-2])<0 && sign*(ac.last()-ac[n-2])<0
    for (j in n-2 downTo 0) {
        val p=sign*extreme(j)
        if (records.isEmpty() && p<last) { base=j;last=p }
        else if (p>last) {
            records+=j;last=p
            if (needed==null) {
                if (!jaw[j].isFinite() || jaw[j]<=0) return null
                val away=sign*(extreme(j)-jaw[j])>0
                needed=(if (away) 1 else 2)*(if (adverse) 2 else 1)
            }
            if (records.size==needed) break
        }
    }
    val required=needed?:return null
    if (records.size<required) return null
    val target=records.last();val trigger=extreme(target)+sign*tick
    val mouth=if (sign==1) maxOf(jaw.last(),teeth.last(),lips.last()) else minOf(jaw.last(),teeth.last(),lips.last())
    if (trigger<=0 || (requireMouth && (mouth<=0 || sign*(trigger-mouth)<=0)) || (base until n).any { sign*(extreme(it)-trigger)>=0 }) return null
    val window=bars.subList(maxOf(0,base-4),base+1)
    val stop=if (sign==1) window.minOf { it.l }-tick else window.maxOf { it.h }+tick
    if (stop<=0 || sign*(trigger-stop)<=0) return null
    return WilliamsEditionSetup("BALANCE_LINE",side,n-1,trigger,stop,stop,JSONObject()
        .put("formation_key","${bars[base].t}:${bars[target].t}:$required").put("base_index",base).put("record_indices",JSONArray(records)).put("required_extremes",required)
        .put("adverse_zone",adverse).put("signal_index",target),role="ADD_ON")
}

internal fun williamsCourseHumpSetup(side:String,bars:List<WilliamsWaveBar>,tick:Double):WilliamsEditionSetup? {
    if (side !in listOf("LONG","SHORT") || !tick.isFinite() || tick<=0) return null
    val pivots=confirmedWilliamsEditionFractals(bars)
    val targetKind=if (side=="LONG") "HIGH" else "LOW"
    val sign=if (side=="LONG") 1 else -1
    for (signal in pivots.asReversed()) {
        if (signal.kind!=targetKind) continue
        val start=pivots.lastOrNull { it.center<signal.center && it.kind!=targetKind }?:continue
        if (start.confirmation>=signal.leftStart) continue
        val trigger=signal.price+sign*tick;val invalidation=start.price+sign*tick
        val later=bars.drop(signal.confirmation)
        if (later.any { if (sign==1) it.h>=trigger else it.l<=trigger } ||
            later.any { if (sign==1) it.l<=invalidation else it.h>=invalidation }) continue
        val opposite=pivots.filter { it.kind!=targetKind && it.confirmation<=signal.confirmation }.takeLast(2)
        if (opposite.size<2) continue
        val stop=if (sign==1) opposite.minOf { it.price }-tick else opposite.maxOf { it.price }+tick
        if (trigger<=0 || stop<=0 || sign*(trigger-stop)<=0) continue
        return WilliamsEditionSetup("COURSE_HUMP",side,signal.confirmation,trigger,stop,invalidation,JSONObject()
            .put("start_index",start.center).put("signal_index",signal.center)
            .put("start_confirmed_at_index",start.confirmation).put("stop_fractal_indices",JSONArray(opposite.map { it.center })))
    }
    return null
}

internal fun williamsCourseThumbSetup(side:String,bars:List<WilliamsWaveBar>,tick:Double):WilliamsEditionSetup? {
    if (side !in listOf("LONG","SHORT") || !validWilliamsEditionBars(bars) || !tick.isFinite() || tick<=0) return null
    val sign=if (side=="SHORT") 1 else -1
    var fingers=listOf<Int>()
    for ((i,b) in bars.withIndex()) {
        if (fingers.isEmpty()) { fingers=listOf(i);continue }
        val p=bars[fingers.last()]
        if (sign*(b.h-p.h)>0 && sign*(b.l-p.l)>0) fingers=(fingers+i).takeLast(3)
        else if (b.h<=p.h && b.l>=p.l) continue
        else fingers=listOf(i)
    }
    if (fingers.size<3 || fingers.count { it>0 && bars[it].v>bars[it-1].v && bars[it-1].v>0 }<2) return null
    val colours=fingers.map { i ->
        if(i<=0 || bars[i].v<=bars[i-1].v || bars[i-1].v<=0) "OTHER"
        else {
            val current=(bars[i].h-bars[i].l)/bars[i].v
            val previous=(bars[i-1].h-bars[i-1].l)/bars[i-1].v
            when { current<previous->"SQUAT";current>previous->"GREEN";else->"MFI_UNCHANGED" }
        }
    }
    // PDF69: any two or increasing-volume shortcut. PDF71: mixed is ideal,
    // otherwise weak; do not invent a mandatory mixed-colour execution veto.
    val strength=when {
        "SQUAT" in colours && "GREEN" in colours->"IDEAL_MIXED"
        colours.count { it in listOf("SQUAT","GREEN") }>=2->"WEAK"
        else->"UNCONFIRMED_FLAT_MFI"
    }
    val thumb=fingers.first();val middle=fingers.last()
    val trigger=if (sign==1) bars[thumb].l-tick else bars[thumb].h+tick
    val stop=if (sign==1) bars[middle].h+tick else bars[middle].l-tick
    if (trigger<=0 || stop<=0 || bars.drop(middle).any { if (sign==1) it.l<=trigger else it.h>=trigger }) return null
    return WilliamsEditionSetup("COURSE_THUMB",side,middle,trigger,stop,stop,JSONObject()
        .put("finger_indices",JSONArray(fingers)).put("thumb_index",thumb).put("reverse_level",stop)
        .put("finger_profitunity_colours",JSONArray(colours)).put("thumb_strength",strength)
        .put("thumb_admission_basis","COURSE_PDF69_VOLUME_SHORTCUT")
        .put("volume_input","KLINE_VOLUME_PROXY"))
}

internal fun williamsCourseSquatSetup(side:String,bars:List<WilliamsWaveBar>,tick:Double):WilliamsEditionSetup? {
    if (side !in listOf("LONG","SHORT") || bars.size<2 || !validWilliamsEditionBars(bars) || !tick.isFinite() || tick<=0) return null
    val a=bars[bars.lastIndex-1];val b=bars.last()
    if (!(b.v>a.v && a.v>0 && (b.h-b.l)/b.v<(a.h-a.l)/a.v)) return null
    val trigger=williamsBookExactPriceTick(if(side=="LONG") b.h else b.l,
        tick,above=side=="LONG") ?: return null
    val stop=williamsBookExactPriceTick(if(side=="LONG") b.l else b.h,
        tick,above=side=="SHORT") ?: return null
    if (minOf(trigger,stop)<=0) return null
    return WilliamsEditionSetup("COURSE_SQUAT",side,bars.lastIndex,trigger,stop,stop,JSONObject().put("volume_input","KLINE_VOLUME_PROXY"))
}

internal fun williamsCourseTwoFractalStop(side:String,bars:List<WilliamsWaveBar>,tick:Double):Double? {
    if (side !in listOf("LONG","SHORT") || !tick.isFinite() || tick<=0) return null
    val kind=if (side=="LONG") "LOW" else "HIGH"
    val pivots=confirmedWilliamsEditionFractals(bars).filter { it.kind==kind }.takeLast(2)
    if (pivots.size<2) return null
    return if (side=="LONG") pivots.minOf { it.price }-tick else pivots.maxOf { it.price }+tick
}

internal fun williamsCourseAirbagStop(side:String,higher:List<WilliamsWaveBar>,tick:Double):Double? {
    if (side !in listOf("LONG","SHORT") || higher.size<2 || !validWilliamsEditionBars(higher) || !tick.isFinite() || tick<=0) return null
    val sign=if (side=="LONG") 1 else -1;val current=higher.last()
    val bar=higher.dropLast(1).lastOrNull { sign*(current.h-it.h)>0 && sign*(current.l-it.l)>0 }?:return null
    // Course Level III p.11 specifies the lower low PLUS one tick for LONG.
    // The SHORT buy stop is symmetrically the higher high MINUS one tick.
    val stop=if (sign==1) bar.l+tick else bar.h-tick
    return stop.takeIf { it>0 }
}

/** Preserve BOTH the formation invalidation and higher-frame Air Bag.
 * A crossed Air Bag blocks new exposure instead of widening the local stop. */
internal fun williamsCourseInitialProtection(side:String,formation:Double,airbag:Double,entry:Double):Double? {
    if(side !in listOf("LONG","SHORT") || listOf(formation,airbag,entry).any { !it.isFinite() || it<=0 }) return null
    return if(side=="LONG") maxOf(formation,airbag).takeIf { it<entry }
        else minOf(formation,airbag).takeIf { it>entry }
}

internal fun williamsCourseRhythm(bars:List<WilliamsWaveBar>):Pair<String,String> {
    if (bars.size<34 || !validWilliamsEditionBars(bars)) return "UNKNOWN" to "UNKNOWN"
    val mids=bars.map { (it.h+it.l)/2 }
    fun mean(n:Int,previous:Boolean=false)=(if (previous) mids.dropLast(1) else mids).takeLast(n).average()
    val fast=mean(5);val medium=mean(13);val slow=mean(34)
    val twr=if (fast>maxOf(medium,slow)) "UP" else if (fast<minOf(medium,slow)) "DOWN" else "NEUTRAL"
    // Course Level II p14/PDF45: compare oscillator with its signal SMA(5),
    // independently of whether the latest AO bar grew or declined.
    val oscillator=fast-slow
    val signal=if(bars.size>=38) (bars.size-5 until bars.size).map { end ->
        mids.subList(end-4,end+1).average()-mids.subList(end-33,end+1).average()
    }.average() else Double.NaN
    val momentum=if(!signal.isFinite()) "UNKNOWN" else if(oscillator>signal) "UP" else if(oscillator<signal) "DOWN" else "NEUTRAL"
    return twr to momentum
}

internal fun williamsOpposingCloseEventTriggered(signalAt:Long,frameMs:Long,openedAt:Long,now:Long):Boolean {
    if(minOf(signalAt,frameMs,openedAt,now)<=0L || signalAt>Long.MAX_VALUE-frameMs) return false
    val knownAt=signalAt+frameMs
    return openedAt<knownAt && knownAt<=now && (now-knownAt)/frameMs<5L
}

internal fun williamsAdditionTimeEligible(profile:String,signalAt:Long,family:String,originAt:Long,originFamily:String,latestAt:Long):Boolean =
    if(profile==williamsNtdProfile) originAt>0 && signalAt>=originAt &&
        (signalAt>originAt || family.removePrefix("SHORT_")!=originFamily.removePrefix("SHORT_"))
    else signalAt>latestAt

internal fun williamsMarketOnCloseReason(
    signalAt:Long,frameMs:Long,now:Long,reference:Double,mark:Double,
    maximumAgeMs:Long=90_000L,maximumDeviation:Double=.0015
):String? {
    if (frameMs<=0 || signalAt<=0 || signalAt>Long.MAX_VALUE-frameMs || maximumAgeMs<0) return "INVALID_CLOSE_EVENT_TIME"
    val closed=signalAt+frameMs
    if (now<closed) return "CLOSE_EVENT_NOT_CLOSED"
    if (now-closed>maximumAgeMs) return "CLOSE_EVENT_EXPIRED"
    if (listOf(reference,mark).any { !it.isFinite() || it<=0 } || !maximumDeviation.isFinite() || maximumDeviation<0) return "INVALID_CLOSE_EVENT_PRICE"
    if (abs(mark/reference-1)>maximumDeviation) return "CLOSE_EVENT_SLIPPAGE_LIMIT"
    return null
}

/** NTD ch.5 PDF pp.64-66, ch.6 pp.86-89: "special blue light".
 * A still-unfilled AO/AC stop may move to a MORE favourable subsequent
 * closed price-bar level while the oscillator remains the SAME colour.
 * Every intervening bar must be checked against the then-active stop BEFORE
 * any replacement; a crossed order must not be resurrected from history.
 *
 * This is read-only SETUP evidence, NOT permission to amend a Binance order.
 * The execution door must separately cancel/reconcile any existing order.
 */
internal data class WilliamsNtdBlueLight(val trigger:Double,val sourceIndex:Int)
internal fun williamsNtdBlueLight(
    side:String,bars:List<WilliamsWaveBar>,osc:List<Double>,
    signalIndex:Int,originalTrigger:Double,tick:Double
):WilliamsNtdBlueLight? {
    if(side !in listOf("LONG","SHORT") || signalIndex !in bars.indices ||
        bars.size!=osc.size || !validWilliamsEditionBars(bars) ||
        !originalTrigger.isFinite() || originalTrigger<=0.0 ||
        !tick.isFinite() || tick<=0.0) return null
    val buy=side=="LONG"
    var trigger=originalTrigger
    var sourceIndex=signalIndex
    for(j in signalIndex+1 until bars.size) {
        val b=bars[j]
        if(!osc[j].isFinite() || !osc[j-1].isFinite() ||
            (if(buy) osc[j]<=osc[j-1] else osc[j]>=osc[j-1])) return null
        // Intrabar ordering is unknown: a historical trigger touch always
        // wins over a close-time improvement. Never fabricate an unfilled order.
        if(if(buy) b.h>=trigger else b.l<=trigger) return null
        val candidate=williamsBookExactPriceTick(if(buy)b.h else b.l,tick,above=buy)
            ?:return null
        if(if(buy) candidate<trigger else candidate>trigger) {
            trigger=candidate
            sourceIndex=j
        }
    }
    return WilliamsNtdBlueLight(trigger,sourceIndex)
}

/** Five independent NTD dimensions; only the external fractal initiates a cycle. */
internal fun williamsNtdSetups(side:String,bars:List<WilliamsWaveBar>,jaw:List<Double>,teeth:List<Double>,
    lips:List<Double>,ao:List<Double>,ac:List<Double>,tick:Double,exitMode:Boolean=false,
    includePendingAo:Boolean=true):List<WilliamsEditionSetup> {
    val n=bars.size
    if (side !in listOf("LONG","SHORT") || n<45 || !validWilliamsEditionBars(bars) || !tick.isFinite() || tick<=0 ||
        listOf(jaw,teeth,lips,ao,ac).any { it.size!=n }) return emptyList()
    val sign=if (side=="LONG") 1 else -1
    fun high(i:Int)=if (sign==1) bars[i].h else -bars[i].l
    fun low(i:Int)=if (sign==1) bars[i].l else -bars[i].h
    fun pending(i:Int,trigger:Double)=((i+1) until n).none { sign*(if (sign==1) bars[it].h else bars[it].l)>=sign*trigger }
    val out=mutableListOf<WilliamsEditionSetup>()
    fun add(kind:String,i:Int,role:String="ADD_ON",execution:String="STOP_ENTRY",reference:Double?=null) {
        val trigger=reference ?: williamsBookExactPriceTick(
            if(sign==1) bars[i].h else bars[i].l,tick,above=sign==1) ?: return
        if (!trigger.isFinite() || trigger<=0 || (execution=="STOP_ENTRY" && !pending(i,trigger))) return
        val stop=williamsBookExactPriceTick(
            if(sign==1) bars[i].l else bars[i].h,tick,above=sign!=1) ?: return
        if (sign*(trigger-stop)<=0) return
        out+=WilliamsEditionSetup(kind,side,i,trigger,stop,role=role,executionSemantics=execution)
    }
    val fractalKind=if (sign==1) "HIGH" else "LOW"
    confirmedWilliamsEditionFractals(bars).asReversed().firstOrNull { f ->
        f.kind==fractalKind &&
            // NTD ch4 / TC2 ch11: qualify the actual +1/-1 tick
            // BREAKOUT beyond red Teeth, not the earlier pivot price.
            (williamsBookExactPriceTick(f.price,tick,above=sign==1)?.let {
                sign*(it-teeth.last())>0 && pending(f.confirmation,it)
            } ?: false)
    }?.let { f ->
        val stop=williamsBookExactPriceTick(
            if(sign==1) bars.subList(f.groupStart,f.center+1).minOf { it.l }
            else bars.subList(f.groupStart,f.center+1).maxOf { it.h },
            tick,above=sign!=1) ?: return@let
        val trigger=williamsBookExactPriceTick(f.price,tick,above=sign==1) ?: return@let
        val initial=williamsNtdInitialContext(bars,jaw,teeth,lips,f.center,tick)
        out+=WilliamsEditionSetup("FRACTAL",side,f.confirmation,trigger,stop,
            role=if(exitMode || initial.optBoolean("eligible")) "ENTRY" else "ADD_ON",
            context=JSONObject().put("formation_index",f.center).put("ntd_initial",initial))
    }
    val a=ao.map { sign*it }; val c=ac.map { sign*it };val i=n-1
    if (a.takeLast(3).all { it.isFinite() }) {
        val peaks=(maxOf(1,i-30) until i).filter { a[it]<0 && a[it]<a[it-1] && a[it]<a[it+1] }
        val second=peaks.lastOrNull()
        // Do not skip an intervening unsuitable AO trough: the two peaks
        // must be consecutive confirmed extremes of this same negative wave.
        val first=if(second==i-1) peaks.dropLast(1).lastOrNull() else null
        when {
            first!=null && second!=null && a[first]<a[second] && a[i]>a[i-1] &&
                (first..i).all { a[it].isFinite() && a[it]<0 } -> add("AO_TWIN_PEAKS",i)
            a[i-2]>0 && a[i-1]>0 && a[i]>0 && a[i-2]>a[i-1] && a[i]>a[i-1] -> add("AO_SAUCER",i)
            a[i-1]<0 && a[i]>0 -> add("AO_ZERO_LINE",i)
        }
    }
    // NTD PDF63-67: failure to break the signal bar on the very next
    // candle does not delete the AO stop. Preserve the original causal
    // formation while its AO colour continues and its level is unconsumed.
    // A recovered colour must never resurrect a previously cancelled signal.
    if(includePendingAo) for(end in n-1 downTo maxOf(45,n-4)) {
        val prior=williamsNtdSetups(side,bars.take(end),jaw.take(end),teeth.take(end),lips.take(end),
            ao.take(end),ac.take(end),tick,exitMode,includePendingAo=false)
        for(setup in prior.filter { it.kind in setOf("AO_SAUCER","AO_ZERO_LINE","AO_TWIN_PEAKS") }) {
            val blue=williamsNtdBlueLight(side,bars,ao,setup.knownIndex,setup.trigger,tick)
                ?:continue
            out+=setup.copy(trigger=blue.trigger,context=JSONObject(setup.context.toString())
                .put("book_blue_light","NTD_AO_SPECIAL_BLUE_LIGHT")
                .put("formation_index",setup.knownIndex)
                .put("level_source_index",blue.sourceIndex))
        }
    }
    for (j in i downTo maxOf(1,n-10)) {
        if (!c[j].isFinite() || c[j]<=c[j-1]) continue
        // NTD PDF89: an unhit AC signal is cancelled by an opposite colour.
        // A later recovery cannot resurrect that old pending level.
        if((j+1 until n).any { !c[it].isFinite() || !c[it-1].isFinite() || c[it]<=c[it-1] }) continue
        var start=j
        while(start>0 && c[start]>c[start-1]) start--
        val run=j-start;val needed=if(c[j]>0) 2 else 3
        val crossed=c[j]>0 && c[j-1]<=0
        if ((run==needed || c[j]>0 && run in 2..3 && crossed) && start>0 && c[start]<c[start-1]) {
            val before=out.size;add("AC_SIGNAL",j)
            if(out.size>before) {
                val original=out.removeAt(out.lastIndex)
                val blue=williamsNtdBlueLight(side,bars,ac,j,original.trigger,tick)
                if(blue!=null) {
                    out+=original.copy(trigger=blue.trigger,
                        context=JSONObject(original.context.toString())
                            .put("book_blue_light","NTD_AC_SPECIAL_BLUE_LIGHT")
                            .put("formation_index",j)
                            .put("level_source_index",blue.sourceIndex))
                    break
                }
            }
        }
    }
    var zoneRun=0
    for(j in i downTo 1) {
        if(a[j]>a[j-1] && c[j]>c[j-1]) zoneRun++ else break
    }
    // First-fractal activation is durable campaign ownership at execution, not
    // an arbitrary indicator boolean. A flat cycle may not use these additions.
    if(zoneRun in 2..5 && sign*(bars[i].c-bars[i-1].c)>0)
        add("ZONE_ADD_ON",i,execution="MARKET_ON_CLOSE",reference=bars[i].c)
    williamsNtdBalanceLineSetup(side,bars,jaw,teeth,lips,ao,ac,tick,requireMouth=!exitMode)?.let { out+=it }
    val mouth=if(sign==1) maxOf(jaw.last(),teeth.last(),lips.last()) else minOf(jaw.last(),teeth.last(),lips.last())
    return out.filter { (it.kind=="FRACTAL" || bars.last().t-bars[it.knownIndex].t<18_000_000L) &&
        (exitMode || it.role=="ENTRY" || mouth.isFinite() && sign*(it.trigger-mouth)>0) }
}

internal data class WilliamsNtdManagement(val state:JSONObject,val exitReason:String="",val stop:Double?=null)
/** NTD PDF115-120; slopes are an explicit numerical chart-angle policy. */
internal fun williamsNtdManagementPlan(side:String,bars:List<WilliamsWaveBar>,teeth:List<Double>,lips:List<Double>,
    ao:List<Double>,ac:List<Double>,tick:Double,openedAt:Long,state:JSONObject):WilliamsNtdManagement {
    val s=JSONObject(state.toString());val n=bars.size;val sign=if(side=="LONG") 1 else -1
    if(side !in listOf("LONG","SHORT") || n<6 || !validWilliamsEditionBars(bars) ||
        !tick.isFinite() || tick<=0 || openedAt<=0 ||
        listOf(teeth,lips,ao,ac).any { it.size!=n }) return WilliamsNtdManagement(s)
    // A missing candle must not become an invented fifth consecutive zone bar.
    // The runtime checks this too, but the book planner is independently safe.
    if(bars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_H1_MS })
        return WilliamsNtdManagement(s.put("zone_book_evidence","NTD_H1_HISTORY_GAP"))
    if(bars.last().t+WILLIAMS_H1_MS<=openedAt) return WilliamsNtdManagement(s)
    // A persisted exit state from an older position cannot arm a freshly
    // opened campaign, even if a local journal was accidentally reused.
    if(s.optLong("zone_active_at",0L)>0L &&
        s.optLong("zone_active_at")+WILLIAMS_H1_MS<=openedAt) {
        for(key in listOf("zone_active_at","zone_book_stop","zone_book_stop_source_time_ms",
            "zone_historical_stop_touch_at_ms","zone_book_evidence")) s.remove(key)
    }
    var run=0
    for(i in 1 until n) {
        if(bars[i].t+3_600_000L<=openedAt) continue
        run=if(listOf(ao[i],ao[i-1],ac[i],ac[i-1]).all { it.isFinite() } &&
            sign*(ao[i]-ao[i-1])>0 && sign*(ac[i]-ac[i-1])>0) run+1 else 0
        if(run>=5 && s.optLong("zone_active_at",0L)<=0) s.put("zone_active_at",bars[i].t)
    }
    if(s.optLong("zone_active_at",0L)>0L) {
        // NTD PDF 117-119, fig.9-5. Reconstruct the entire CLOSED-BAR
        // stop path from the fifth coloured bar, not just the last candle.
        // The same proof must survive process loss or lost cached book state.
        val activation=s.optLong("zone_active_at")
        val start=bars.indexOfFirst { it.t==activation }
        if(start<0) return WilliamsNtdManagement(s.put(
            "zone_book_evidence","NTD_ZONE_ACTIVATION_NOT_IN_CLOSED_HISTORY"))
        var replayStop:Double?=null
        var replaySource=activation
        for(i in start until n) {
            val bar=bars[i]
            // A STOP touched on an earlier candle makes any later profitable
            // price an invalid continuation of this *book* campaign. We
            // cannot infer whether a real Binance order filled, so quarantine
            // this advisory trail and retain exchange-confirmed protection.
            if(i>start && replayStop!=null &&
                (if(sign==1) bar.l<=replayStop else bar.h>=replayStop)) {
                s.put("zone_historical_stop_touch_at_ms",bar.t)
                    .put("zone_book_evidence","HISTORICAL_BOOK_STOP_TOUCHED_RECONCILE_EXCHANGE")
                    .put("zone_book_stop_authorized_order",false)
                return WilliamsNtdManagement(s)
            }
            val extreme=if(sign==1) bar.l else bar.h
            val next=williamsBookExactPriceTick(extreme,tick,above=sign!=1)
                ?:return WilliamsNtdManagement(s.put(
                    "zone_book_evidence","NTD_BOOK_PRICE_OFF_EXCHANGE_TICK_GRID"))
            if(replayStop==null || sign*(next-replayStop)>0) {
                replayStop=next
                replaySource=bar.t
            }
        }
        // A contradictory persisted stop is not silently overwritten by a
        // weaker replay. The account STOP remains the exchange authority.
        val previous=s.optDouble("zone_book_stop",Double.NaN)
        if(previous.isFinite() && previous>0 &&
            sign*(replayStop!!-previous)<0)
            return WilliamsNtdManagement(s.put(
                "zone_book_evidence","NTD_REPLAY_CONFLICTS_WITH_PERSISTED_STOP"))
        s.put("zone_book_stop",replayStop)
            .put("zone_book_stop_source_time_ms",replaySource)
            .put("zone_book_evidence","REPLAYED_COMPLETE_CLOSED_H1_PATH")
            .put("zone_book_rule","NTD_PDF117_119_FIVE_SAME_COLOUR_STICKY_CLOSED_BAR")
            .put("zone_book_stop_authorized_order",false)
        return WilliamsNtdManagement(s,stop=replayStop)
    }
    val activated=s.optLong("lips_selected_at",0L);val useLips=activated>0 && activated<bars.last().t
    val line=if(useLips) lips.last() else teeth.last()
    val reason=if(line.isFinite() && line>0 && sign*(bars.last().c-line)<0)
        (if(useLips) "LIPS_CLOSE_EXIT" else "TEETH_CLOSE_EXIT") else ""
    if(activated<=0 && lips[n-6].isFinite() && lips.last().isFinite() && lips[n-6]>0) {
        val priceSlope=sign*(bars.last().c/bars[n-6].c-1)
        val lipsSlope=sign*(lips.last()/lips[n-6]-1)
        if(priceSlope>maxOf(0.0,lipsSlope)) s.put("lips_selected_at",bars.last().t)
    }
    return WilliamsNtdManagement(s,reason)
}

/** NTD PDF118: after a proved profitable cycle, any dimension may re-enter.
 * Fees and authenticated fills must be known; a price estimate is insufficient. */
internal fun williamsNtdReentryEligible(owner:JSONObject?,symbol:String,direction:String,signalAt:Long):Boolean =
    owner!=null && owner.optString("symbol")==symbol && owner.optString("direction")==direction &&
    owner.optString("strategy_profile")==williamsNtdProfile && campaignLifecycleStatus(owner)=="CLOSED_OR_STOPPED" &&
    owner.optString("net_result_source")=="BINANCE_USER_TRADES" && owner.optBoolean("net_result_fees_known",false) &&
    owner.optDouble("verified_net_pnl_usdt",Double.NaN).let { it.isFinite() && it>0 } &&
    owner.optString("ntd_reentry_consumed_by").isBlank() && owner.optLong("closed_observed_at_ms",0L).let {
        it>0L && signalAt>it
    }

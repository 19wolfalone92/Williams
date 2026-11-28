package com.williamsbot

import java.util.ArrayDeque
import java.util.Collections

internal const val WILLIAMS_ALLIGATOR_WARMUP_BARS = 500
internal fun canonicalAlligatorWarmupReady(closedBars: Int): Boolean =
    closedBars >= WILLIAMS_ALLIGATOR_WARMUP_BARS

internal data class ClosedMedianBar(val openMs:Long,val median:Double)
internal data class WilliamsMathPoint(val ao:Double,val ac:Double,
    val jaw:Double?,val teeth:Double?,val lips:Double?)

/** Constant-size sliding windows. Resum the 5/34/5 values in their canonical
 * order, rather than accumulating subtraction rounding drift across years.
 * That preserves the existing exact AO zero/colour comparison semantics. */
private class ExactRollingWindow(private val length:Int) {
    private val values=ArrayDeque<Double>(length)
    fun push(value:Double) {
        if(values.size==length) values.removeFirst()
        values.addLast(value)
    }
    fun mean():Double {
        if(values.size!=length) return Double.NaN
        var sum=0.0
        for(value in values) {
            if(!value.isFinite()) return Double.NaN
            sum+=value
        }
        return (sum/length).takeIf { it.isFinite() } ?: Double.NaN
    }
}

private class ShiftedSmma(private val period:Int,private val displacement:Int) {
    private var count=0
    private var seed=0.0
    private var raw=Double.NaN
    private val tail=ArrayDeque<Double>(displacement+1)
    fun push(median:Double):Double? {
        count++
        if(count<=period) {
            seed+=median
            if(count==period && seed.isFinite()) raw=seed/period
        } else if(raw.isFinite()) raw=(raw*(period-1)+median)/period
        tail.addLast(raw)
        return if(tail.size>displacement) tail.removeFirst().takeIf { it.isFinite() } else null
    }
}

/** One new confirmed candle advances bounded indicator state; no historical
 * look-ahead and no previous point is changed. The seed is the first input
 * bar; a shifted window/correction must explicitly rebuild from its own seed. */
internal class WilliamsMathAccumulator {
    private val fast=ExactRollingWindow(5)
    private val slow=ExactRollingWindow(34)
    private val acceleration=ExactRollingWindow(5)
    private val jaw=ShiftedSmma(13,8)
    private val teeth=ShiftedSmma(8,5)
    private val lips=ShiftedSmma(5,3)
    private var lastOpen=Long.MIN_VALUE
    fun append(bar:ClosedMedianBar):WilliamsMathPoint {
        require(bar.openMs>lastOpen && bar.median.isFinite() && bar.median>0.0)
        lastOpen=bar.openMs
        fast.push(bar.median); slow.push(bar.median)
        val ao=(fast.mean()-slow.mean()).takeIf { it.isFinite() } ?: Double.NaN
        acceleration.push(ao)
        val ac=ao-acceleration.mean()
        return WilliamsMathPoint(ao,ac,jaw.push(bar.median),teeth.push(bar.median),lips.push(bar.median))
    }
}

/** Bounded current chart window. An exact overlapping suffix plus new closed
 * candles advances the existing accumulator; corrections/prepends/key changes
 * rebuild from the supplied seed. A shorter historical viewport does not
 * rewind the live recursive state. */
internal class IncrementalWilliamsMath {
    private var key=""
    private var input:List<ClosedMedianBar> = emptyList()
    private var output:List<WilliamsMathPoint> = emptyList()
    private var accumulator=WilliamsMathAccumulator()
    var seedOpenMs:Long?=null
        private set
    var appendedPoints:Long=0L
        private set
    var rebuilds:Long=0L
        private set
    @Synchronized fun update(nextKey:String,bars:List<ClosedMedianBar>):List<WilliamsMathPoint> {
        val next=bars.toList()
        require(next.all { it.openMs>0L && it.median.isFinite() && it.median>0.0 })
        require(next.zipWithNext().all { (a,b)->b.openMs>a.openMs })
        if(next.isEmpty()) return emptyList()
        if(nextKey==key && next==input) return output

        // The chart is a moving bounded window. When its first candle rolls
        // forward, retain the recursive SMMA/rolling-window state if the new
        // input is an exact suffix of the previous snapshot plus new bars.
        // Re-seeding at the new first visible candle changes Alligator values
        // and needlessly recalculates every point on each candle close.
        val overlapStart=if(nextKey==key && input.isNotEmpty())
            input.binarySearchBy(next.first().openMs) { it.openMs }.takeIf { it>=0 }
        else null
        if(overlapStart!=null) {
            val overlapCount=minOf(input.size-overlapStart,next.size)
            val matches=(0 until overlapCount).all { offset ->
                input[overlapStart+offset]==next[offset]
            }
            if(matches) {
                // A historical chart viewport may request only a subrange.
                // Return its existing projection without rewinding the live
                // accumulator or changing the latest processed candle.
                if(overlapCount==next.size) {
                    return Collections.unmodifiableList(
                        ArrayList(output.subList(overlapStart,overlapStart+overlapCount))
                    )
                }
                val result=ArrayList<WilliamsMathPoint>(next.size)
                result.addAll(output.subList(overlapStart,input.size))
                for(i in overlapCount until next.size) result.add(accumulator.append(next[i]))
                appendedPoints+=(next.size-overlapCount).toLong()
                input=next
                output=Collections.unmodifiableList(result)
                return output
            }
        }

        // A correction, prepend, symbol/frame change or non-overlapping range
        // invalidates the recursive seed. Rebuild once from the supplied
        // closed snapshot; later appends/slides remain incremental.
        val state=WilliamsMathAccumulator()
        val result=ArrayList<WilliamsMathPoint>(next.size)
        next.forEach { result+=state.append(it) }
        key=nextKey
        input=next
        accumulator=state
        seedOpenMs=next.first().openMs
        output=Collections.unmodifiableList(result)
        rebuilds++
        appendedPoints+=next.size.toLong()
        return output
    }
}

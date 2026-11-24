package com.williamsbot

import java.util.ArrayDeque
import java.util.Collections

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

/** Bounded single chart window. Same anchor + identical prefix -> append only.
 * Sliding the seed or correcting an old candle deliberately rebuilds; changing
 * the seed silently would alter the current Williams SMMA reference. */
internal class IncrementalWilliamsMath {
    private var key=""
    private var input:List<ClosedMedianBar> = emptyList()
    private var output:List<WilliamsMathPoint> = emptyList()
    private var accumulator=WilliamsMathAccumulator()
    var appendedPoints:Long=0L
        private set
    var rebuilds:Long=0L
        private set
    @Synchronized fun update(nextKey:String,bars:List<ClosedMedianBar>):List<WilliamsMathPoint> {
        val next=bars.toList()
        val samePrefix=nextKey==key && next.size>=input.size &&
            input.indices.all { input[it]==next[it] }
        if(samePrefix && next.size==input.size) return output
        val state=if(samePrefix) accumulator else WilliamsMathAccumulator()
        // Validate the complete series before mutating a reusable accumulator.
        require(next.all { it.median.isFinite() && it.median>0.0 } &&
            next.zipWithNext().all { (a,b)-> b.openMs>a.openMs })
        val result=ArrayList<WilliamsMathPoint>(next.size)
        val from=if(samePrefix) input.size else 0
        if(samePrefix) result.addAll(output)
        for(i in from until next.size) result.add(state.append(next[i]))
        if(!samePrefix) rebuilds++
        appendedPoints+=(next.size-from).toLong()
        key=nextKey
        input=next
        accumulator=state
        output=Collections.unmodifiableList(result)
        return output
    }
}

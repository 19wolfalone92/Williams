package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

/** Pixel geometry shared by candles, line plots, annotations and tap selection. */
internal data class ChartPlotBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right-left).coerceAtLeast(1f)
    val height get() = (bottom-top).coerceAtLeast(1f)
    fun candleX(index: Int, count: Int): Float = left+(index+.5f)*width/max(1,count)
    fun candleAt(x: Float, count: Int): Int? =
        if (count<=0 || !x.isFinite() || x<left || x>=right) null
        else ((x-left)/width*count).toInt().coerceIn(0,count-1)
}

internal fun chartPlotBounds(width: Float, height: Float, axisWidth: Float, textHeight: Float): ChartPlotBounds {
    val w=width.coerceAtLeast(1f);val h=height.coerceAtLeast(1f)
    val gutter=axisWidth.coerceAtLeast(0f).coerceAtMost(w*.45f)
    val inset=textHeight.coerceAtLeast(1f).coerceAtMost(h*.2f)
    return ChartPlotBounds(0f,inset,max(1f,w-gutter),max(inset+1f,h-inset))
}

internal fun boundedChartLabelX(anchor: Float, textWidth: Float, left: Float, right: Float): Float =
    anchor.coerceIn(left,max(left,right-textWidth.coerceAtLeast(0f)))

internal fun formatChartPrice(value: Double?): String =
    value?.takeIf { it.isFinite() && it>0 }?.let {
        val raw=BigDecimal.valueOf(it)
        val rounded=raw.setScale(8,RoundingMode.HALF_UP)
        (if(rounded.signum()==0) raw else rounded).stripTrailingZeros().toPlainString()
    } ?: "—"

internal data class ChartViewport(val start: Int, val count: Int)
internal fun chartViewport(total: Int, requested: Float, offset: Float): ChartViewport {
    if(total<=0) return ChartViewport(0,0)
    val count=(if(requested.isFinite()) requested.toInt() else 100).coerceIn(min(20,total),total)
    val back=(if(offset.isFinite()) offset.toInt() else 0).coerceIn(0,total-count)
    return ChartViewport(total-count-back,count)
}

/** Exact view bucket; monthly bars use the calendar rather than thirty days. */
internal fun chartIntervalEnd(openAt: Long, interval: String): Long? = runCatching {
    val match=Regex("([1-9][0-9]*)([mhdwM])").matchEntire(interval) ?: return null
    val amount=match.groupValues[1].toLong()
    if(match.groupValues[2]=="M") java.time.Instant.ofEpochMilli(openAt)
        .atZone(java.time.ZoneOffset.UTC).plusMonths(amount).toInstant().toEpochMilli()
    else Math.addExact(openAt,Math.multiplyExact(amount,when(match.groupValues[2]) {
        "m" -> 60_000L; "h" -> 3_600_000L; "d" -> 86_400_000L; "w" -> 604_800_000L
        else -> return null
    }))
}.getOrNull()

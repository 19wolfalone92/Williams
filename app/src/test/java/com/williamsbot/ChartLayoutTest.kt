package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class ChartLayoutTest {
    @Test fun firstAndLastCandlesHaveRoomAndAxisDoesNotSelectTheLastBar() {
        val plot=chartPlotBounds(320f,360f,76f,16f)
        assertEquals(244f,plot.right,0f)
        assertTrue(plot.candleX(0,100)>plot.left)
        assertTrue(plot.candleX(99,100)<plot.right)
        assertEquals(0,plot.candleAt(plot.candleX(0,100),100))
        assertEquals(99,plot.candleAt(plot.candleX(99,100),100))
        assertNull(plot.candleAt(plot.right,100))
        assertNull(plot.candleAt(-1f,100))
    }
    @Test fun annotationsAtLastCandleRemainInsidePlot() {
        val plot=chartPlotBounds(320f,360f,80f,16f)
        val x=boundedChartLabelX(plot.candleX(99,100),72f,plot.left,plot.right)
        assertTrue(x>=plot.left)
        assertTrue(x+72f<=plot.right)
    }
    @Test fun widePriceLabelsLeavePositiveCandleSpace() {
        val plot=chartPlotBounds(280f,160f,400f,80f)
        assertTrue(plot.width>=280*.55f)
        assertTrue(plot.height>0)
        assertTrue(plot.top>0 && plot.bottom<160)
        assertEquals(plot.left+plot.width/2,plot.candleX(0,1),0f)
    }
    @Test fun viewportCannotOverrunHistoryOnZoomOrPan() {
        for(total in listOf(1,10,20,180)) {
            for(count in listOf(1f,20f,100f,500f)) {
                for(back in listOf(-100f,0f,50f,1000f)) {
                    val view=chartViewport(total,count,back)
                    assertTrue(view.start>=0 && view.count>0)
                    assertTrue(view.start+view.count<=total)
                }
            }
        }
        assertEquals(ChartViewport(80,100),chartViewport(180,100f,0f))
        assertEquals(ChartViewport(30,100),chartViewport(180,100f,50f))
    }
    @Test fun lowPriceStopsRetainDecimalsAndMissingPricesStayUnknown() {
        assertEquals("0.00012345",formatChartPrice(.00012345))
        assertEquals("123456.78",formatChartPrice(123456.78))
        assertEquals("1.32",formatChartPrice(1.32))
        assertEquals("0.0000000001",formatChartPrice(.0000000001))
        assertEquals("—",formatChartPrice(null))
        assertEquals("—",formatChartPrice(Double.NaN))
        assertEquals("—",formatChartPrice(0.0))
    }
    @Test fun historyMarkersUseActualViewIntervalIncludingCalendarMonths() {
        val jan=java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        assertEquals(jan+900_000,chartIntervalEnd(jan,"15m"))
        assertEquals(jan+21_600_000,chartIntervalEnd(jan,"6h"))
        val feb=java.time.Instant.parse("2024-02-01T00:00:00Z").toEpochMilli()
        val mar=java.time.Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()
        assertEquals(feb,chartIntervalEnd(jan,"1M"))
        assertEquals(mar,chartIntervalEnd(feb,"1M"))
        assertNull(chartIntervalEnd(jan,"bad"))
    }
}

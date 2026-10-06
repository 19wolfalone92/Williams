package com.williamsbot

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Test

class TradingEventLoopTest {
    @Test
    fun serializes_events_in_submission_order() {
        val loop = TradingEventLoop()
        val values = Collections.synchronizedList(mutableListOf<Int>())
        repeat(100) { i -> loop.post { values += i } }
        loop.call { Unit }
        assertEquals((0 until 100).toList(), values)
        loop.close()
    }
}

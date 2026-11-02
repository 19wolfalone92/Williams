package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

/** TC2 PDF137–139 third changing AO bar (LONG and mirrored SHORT). */
class WilliamsTc2AoSourceProofTest {
    @Test fun thirdActualChangeNotThirdPositiveAoAndNoZeroLineRequirement() {
        val ao=MutableList(46) { 0.0 }
        // Previous 3 red or flat values cannot produce a new green streak.
        ao[41]=-6.0;ao[42]=-5.0;ao[43]=-4.0;ao[44]=-3.0;ao[45]=-2.0
        assertEquals(4,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
        assertEquals(44,45-(4-3)) // the original third positive increment
        ao[43]=ao[42]
        assertEquals(2,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
        ao[43]=-4.0
        ao[45]=0.0 // AO zero touch does NOT break a green colour
        assertEquals(4,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
    }

    @Test fun mirroredShortGreenStreakUsesSameThirdBar() {
        val source=MutableList(46) { 0.0 }
        source[41]=6.0;source[42]=5.0;source[43]=4.0;source[44]=3.0;source[45]=2.0
        val reflected=source.map { -it }
        assertEquals(4,williamsTc2ConsecutiveAoRise(45,35){reflected[it]})
        source[43]=source[42]
        val wrong=source.map { -it }
        assertEquals(2,williamsTc2ConsecutiveAoRise(45,35){wrong[it]})
    }

    @Test fun invalidAosAreNotBookSignalsAndUnknownHistoryDoesNotInventThirdBar() {
        val ao=MutableList(46) { 1.0 }
        ao[41]=0.0;ao[42]=1.0;ao[43]=2.0;ao[44]=3.0;ao[45]=4.0
        assertEquals(4,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
        ao[44]=Double.NaN
        assertEquals(0,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
        ao[44]=3.0;ao[45]=Double.POSITIVE_INFINITY
        assertEquals(0,williamsTc2ConsecutiveAoRise(45,35){ao[it]})
        assertEquals(0,williamsTc2ConsecutiveAoRise(34,35){0.0})
        assertEquals(0,williamsTc2ConsecutiveAoRise(45,0){0.0})
    }
}

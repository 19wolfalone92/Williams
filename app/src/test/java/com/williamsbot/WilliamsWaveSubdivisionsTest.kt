package com.williamsbot
import org.junit.Assert.*
import org.junit.Test
class WilliamsWaveSubdivisionsTest {
    @Test fun zigzagRequiresConfirmedFiveThreeFiveActualChildEndpointsInEitherDirection() {
        val h=3_600_000L
        for(mirror in listOf(false,true)) {
            val prices=listOf(150.0,135.0,142.0,120.0,130.0,110.0,123.0,118.0,130.0,110.0,120.0,90.0,100.0,80.0)
            val times=listOf(30,31,32,34,35,36,38,40,42,43,44,46,47,48)
            val children=prices.indices.map { i ->
                val high=(i%2==0)!=mirror
                WilliamsWaveAnchor(if(high) "HIGH" else "LOW",if(mirror) 300-prices[i] else prices[i],i,times[i]*h,(times[i]+3)*h)
            }
            val parent=listOf(0,5,8,13).map { children[it].copy(knownAt=children[it].extremeAt+18*h) }
            val proof=proveWilliamsWaveSubdivisions(parent,children,listOf(5,3,5),6*h,80*h)
            assertEquals("PRICE_SUBDIVISIONS_CONFIRMED",proof.status)
            assertEquals(listOf(5,3,5),proof.observed)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children,listOf(3,3,5),6*h,80*h).status)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children,listOf(5,3,5),6*h,60*h).status)
            assertEquals("UNRESOLVED",proveWilliamsWaveSubdivisions(parent,children.dropLast(1),listOf(5,3,5),6*h,80*h).status)
            assertEquals(proof,proveWilliamsWaveSubdivisions(parent,children+children.last().copy(price=Double.NaN,extremeAt=81*h,knownAt=84*h),listOf(5,3,5),6*h,80*h))
        }
    }
}

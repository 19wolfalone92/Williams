package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class R51PendingStopHeadTest {
    data class Candidate(val side:String,val level:Double,val id:String)
    @Test fun nearestSameSideBoundaryIsIndependentOfFormationAndIterationOrder() {
        for(side in listOf("LONG","SHORT")) {
            val levels=if(side=="LONG") listOf(120.0,110.0,130.0) else listOf(80.0,90.0,70.0)
            val rows=levels.mapIndexed { i,v -> Candidate(side,v,"WM"+(i+1)) }
            for(i in 0..2) {
                val rotated=rows.drop(i)+rows.take(i)
                for(order in listOf(rotated,rotated.reversed()))
                    assertEquals("WM2",williamsPendingStopHeads(order,{it.side},{it.level},{it.id}).single().id)
            }
        }
    }
    @Test fun oppositeDirectionHeadsAreBothRetainedWithoutInventingTriggerOrder() {
        val rows=listOf(Candidate("LONG",110.0,"L1"),Candidate("LONG",105.0,"L2"),
            Candidate("SHORT",90.0,"S1"),Candidate("SHORT",95.0,"S2"))
        assertEquals(setOf("L2","S2"),williamsPendingStopHeads(rows,{it.side},{it.level},{it.id}).map { it.id }.toSet())
    }
}

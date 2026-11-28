package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionDoorLeaseTest {
    @Test fun durableFencePrecedesLeaseClearAcrossEveryInjectedWriteOutcome() {
        for (needsFence in listOf(false, true)) for (fence in 0..2)
            for (sqlite in 0..2) for (mirror in 0..2) {
                val calls = mutableListOf<String>()
                fun write(name: String, outcome: Int): Boolean {
                    calls.add(name)
                    if (outcome == 2) error("injected disk failure")
                    return outcome == 1
                }
                val result = finalizeExecutionDoorLease(needsFence,
                    { write("fence", fence) }, { write("sqlite", sqlite) }, { write("mirror", mirror) })
                if (needsFence && fence != 1) {
                    assertEquals(ExecutionLeaseFinalization.RECONCILE_FENCE_FAILED, result)
                    assertEquals(listOf("fence"), calls)
                } else {
                    assertEquals(if (needsFence) listOf("fence", "sqlite", "mirror") else listOf("sqlite", "mirror"), calls)
                    assertEquals(if (sqlite == 1 && mirror == 1) ExecutionLeaseFinalization.RELEASE_ALLOWED
                        else ExecutionLeaseFinalization.LEASE_CLEAR_FAILED, result)
                }
            }
    }
}

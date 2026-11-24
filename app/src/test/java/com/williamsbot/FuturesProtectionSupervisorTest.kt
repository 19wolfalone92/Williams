package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesProtectionSupervisorTest {
    @Test fun classifiesEveryRequiredProtectionStateWithoutGrantingMutationAuthority() {
        assertEquals(FuturesProtectionSupervisorState.PROTECTED,
            futuresProtectionSupervisorState(true,true,1))
        assertEquals(FuturesProtectionSupervisorState.UNPROTECTED,
            futuresProtectionSupervisorState(true,true,0))
        assertEquals(FuturesProtectionSupervisorState.PROTECTION_PENDING,
            futuresProtectionSupervisorState(true,true,0,mutationPending=true))
        assertEquals(FuturesProtectionSupervisorState.PROTECTION_UNKNOWN,
            futuresProtectionSupervisorState(false,true,0))
        assertEquals(FuturesProtectionSupervisorState.PROTECTION_UNKNOWN,
            futuresProtectionSupervisorState(true,true,2))
        assertEquals(FuturesProtectionSupervisorState.RECOVERY_REQUIRED,
            futuresProtectionSupervisorState(true,true,0,recoveryRequired=true))
        assertEquals(FuturesProtectionSupervisorState.CLOSED,
            futuresProtectionSupervisorState(true,false,0))
    }

    @Test(expected=IllegalArgumentException::class)
    fun negativeStopCountIsRejected() {
        futuresProtectionSupervisorState(true,true,-1)
    }
}

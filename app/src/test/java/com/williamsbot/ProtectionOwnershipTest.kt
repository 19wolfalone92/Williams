package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionOwnershipTest {
    @Test
    fun onlyCurrentOrExplicitlyTrackedPredecessorProtectionIsBotOwned() {
        assertTrue(isOwnedProtectionClientId("W2PS_current", "W2PS_current", ""))
        assertTrue(isOwnedProtectionClientId("W2PS_previous", "W2PS_current", "W2PS_previous"))
        assertFalse(isOwnedProtectionClientId("manual-user-stop", "W2PS_current", "W2PS_previous"))
        assertFalse(isOwnedProtectionClientId("", "W2PS_current", "W2PS_previous"))
    }
}

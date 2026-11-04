package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCrashEvidenceTest {
    @Test fun fatalFingerprintNeverCopiesMessageOrUntrustedStackFrames() {
        val failure = IllegalStateException("secret listenKey=THIS_MUST_NEVER_BE_LOGGED")
        failure.stackTrace = arrayOf(
            StackTraceElement("okhttp3.WebSocket", "privateUrl?listenKey=LEAK", "Private.kt", 5),
            StackTraceElement("com.williamsbot.TradingForegroundService", "requestRuntimeStart", "TradingForegroundService.kt", 219)
        )
        val result = AndroidCrashEvidence.fingerprint(failure)
        assertEquals("IllegalStateException", result.exceptionType)
        assertEquals("TradingForegroundService.requestRuntimeStart:219", result.appFrame)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("LEAK"))
    }

    @Test fun wrappedRuntimeCrashReportsDeepestCause() {
        val root = OutOfMemoryError("payload-cannot-leak")
        root.stackTrace = arrayOf(
            StackTraceElement("com.williamsbot.NativeFuturesCampaignCoordinator", "restore", "Coordinator.kt", 93)
        )
        val wrapped = RuntimeException("outer secret", root)
        val result = AndroidCrashEvidence.fingerprint(wrapped)
        assertEquals("OutOfMemoryError", result.exceptionType)
        assertEquals("NativeFuturesCampaignCoordinator.restore:93", result.appFrame)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun nonApplicationCrashDisclosesNoThirdPartyLocations() {
        val failure = java.io.IOException("apiKey=HIDDEN")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.external.privateAccount", "sendPassword", "Secrets.kt", 1)
        )
        val result = AndroidCrashEvidence.fingerprint(failure)
        assertEquals("IOException", result.exceptionType)
        assertEquals("NO_APPLICATION_FRAME", result.appFrame)
        assertTrue(result.toString().length < 190)
    }
}

package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiAuthorizationTest {
    private val expected = "per-install-random-token-with-enough-entropy"

    @Test
    fun acceptsExactBearerToken() {
        assertTrue(LocalApiAuthorization.isAuthorized(expected, "Bearer $expected"))
    }

    @Test
    fun bearerSchemeIsCaseInsensitive() {
        assertTrue(LocalApiAuthorization.isAuthorized(expected, "bEaReR $expected"))
    }

    @Test
    fun rejectsMissingOrBlankAuthorization() {
        assertFalse(LocalApiAuthorization.isAuthorized(expected, null))
        assertFalse(LocalApiAuthorization.isAuthorized(expected, ""))
        assertFalse(LocalApiAuthorization.isAuthorized(expected, "Bearer "))
    }

    @Test
    fun rejectsBlankExpectedToken() {
        assertFalse(LocalApiAuthorization.isAuthorized("", "Bearer $expected"))
        assertFalse(LocalApiAuthorization.isAuthorized("   ", "Bearer $expected"))
    }

    @Test
    fun rejectsWrongToken() {
        assertFalse(LocalApiAuthorization.isAuthorized(expected, "Bearer not-the-token"))
    }

    @Test
    fun rejectsMalformedAuthorizationHeaders() {
        assertFalse(LocalApiAuthorization.isAuthorized(expected, expected))
        assertFalse(LocalApiAuthorization.isAuthorized(expected, "Basic $expected"))
        assertFalse(LocalApiAuthorization.isAuthorized(expected, "Bearer $expected extra"))
        assertFalse(LocalApiAuthorization.isAuthorized(expected, "Bearer\t$expected"))
    }
}

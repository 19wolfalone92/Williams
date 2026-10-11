package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlContractTest {
    @Test
    fun acceptsOnlyExactLoopbackRuntimeUrls() {
        assertTrue(BackendUrlContract.isLocalRuntimeUrl("http://127.0.0.1:18080"))
        assertTrue(BackendUrlContract.isLocalRuntimeUrl("http://localhost:18080/"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("http://127.0.0.1:18081"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("http://127.0.0.1:18080.evil.test"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("http://127.0.0.1:18080@evil.test"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("http://user@127.0.0.1:18080"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("http://127.0.0.1:18080/api"))
        assertFalse(BackendUrlContract.isLocalRuntimeUrl("https://127.0.0.1:18080"))
    }

    @Test
    fun acceptsStructurallyValidRemoteHttpsUrlsOnly() {
        assertTrue(BackendUrlContract.isSecureRemoteUrl("https://api.example.test"))
        assertTrue(BackendUrlContract.isSecureRemoteUrl("https://api.example.test/v1"))
        assertFalse(BackendUrlContract.isSecureRemoteUrl("http://api.example.test"))
        assertFalse(BackendUrlContract.isSecureRemoteUrl("https://"))
        assertFalse(BackendUrlContract.isSecureRemoteUrl("https://user@api.example.test"))
        assertFalse(BackendUrlContract.isSecureRemoteUrl("https://api.example.test?next=http://evil.test"))
        assertFalse(BackendUrlContract.isSecureRemoteUrl("https://api.example.test#fragment"))
    }
}

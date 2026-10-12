package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationTransportPolicyTest {
    @Test
    fun timestampRejectionMayRetryOnlyReadOnlyGet() {
        assertTrue(
            MutationTransportPolicy.mayRetryTimestampRejection(
                "GET", 0, "{\"code\":-1021,\"msg\":\"timestamp outside recvWindow\"}"
            )
        )
        assertFalse(
            MutationTransportPolicy.mayRetryTimestampRejection(
                "POST", 0, "{\"code\":-1021,\"msg\":\"timestamp outside recvWindow\"}"
            )
        )
        assertFalse(
            MutationTransportPolicy.mayRetryTimestampRejection(
                "DELETE", 0, "{\"code\":-1021,\"msg\":\"timestamp outside recvWindow\"}"
            )
        )
        assertFalse(
            MutationTransportPolicy.mayRetryTimestampRejection(
                "GET", 1, "{\"code\":-1021}"
            )
        )
        assertFalse(
            MutationTransportPolicy.mayRetryTimestampRejection(
                "POST", 0, "{\"code\":-2010,\"msg\":\"insufficient balance\"}"
            )
        )
    }
}

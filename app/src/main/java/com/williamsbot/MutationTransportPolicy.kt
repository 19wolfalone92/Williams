package com.williamsbot

/**
 * Transport retry policy for signed requests.
 *
 * Mutating requests are never retransmitted by the transport layer. Even when
 * a response appears to reject a timestamp, the durable mutation remains
 * unresolved until the recovery path records authoritative evidence.
 */
internal object MutationTransportPolicy {
    fun mayRetryTimestampRejection(method: String, attempt: Int, responseBody: String): Boolean =
        method.equals("GET", ignoreCase = true) &&
            attempt == 0 &&
            responseBody.contains("-1021")
}

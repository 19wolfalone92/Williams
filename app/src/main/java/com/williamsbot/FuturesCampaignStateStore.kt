package com.williamsbot

/** The same durable journal contract is exercised by coordinator integration tests. */
internal interface FuturesCampaignStateStore {
    fun readState(key: String): String?
    fun writeState(key: String, value: String): Boolean
}

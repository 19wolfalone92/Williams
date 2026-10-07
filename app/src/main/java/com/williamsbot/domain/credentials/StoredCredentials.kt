package com.williamsbot.domain.credentials

data class StoredCredentials(
    val apiKey: String,
    val apiSecret: String,
    val testnet: Boolean
)

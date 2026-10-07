package com.williamsbot.domain.credentials

interface CredentialsProvider {
    fun state(): CredentialState
    fun read(): StoredCredentials?
    val isConfigured: Boolean
        get() = state() != CredentialState.MISSING
}

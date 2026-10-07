package com.williamsbot.domain.credentials

interface CredentialsStore : CredentialsProvider {
    fun save(credentials: StoredCredentials): Boolean
    fun clear(): Boolean
}

package com.williamsbot.domain.credentials

interface CredentialsStore : CredentialsProvider {
    fun save(credentials: StoredCredentials): Boolean
    fun clear(): Boolean

    /**
     * Persist the credential state as explicitly unverified and confirm the
     * persisted marker through a read-back. A false result means the desired
     * state was not proven durable.
     */
    fun markUnverified(): Boolean
}

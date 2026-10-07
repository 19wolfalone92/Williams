package com.williamsbot

import android.content.SharedPreferences
import com.williamsbot.data.credentials.EncryptedCredentialsStore
import com.williamsbot.domain.credentials.CredentialState
import com.williamsbot.domain.credentials.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CredentialsStoreTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: EncryptedCredentialsStore

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        store = EncryptedCredentialsStore(fakePrefs)
    }

    @Test
    fun save_successful_when_write_read_compare_matches() {
        val credentials = StoredCredentials("key", "secret", true)

        assertTrue(store.save(credentials))
        assertEquals(CredentialState.VERIFIED, store.state())
        assertEquals(credentials, store.read())
    }

    @Test
    fun save_rolls_back_to_previous_credentials_on_mismatch() {
        val oldCredentials = StoredCredentials("old_key", "old_secret", true)
        val newCredentials = StoredCredentials("new_key", "new_secret", false)

        assertTrue(store.save(oldCredentials))

        fakePrefs.corruptNextApiKeyRead = true

        assertFalse(store.save(newCredentials))
        assertEquals(CredentialState.VERIFIED, store.state())
        assertEquals(oldCredentials, store.read())
    }

    @Test
    fun commit_failure_does_not_report_success_and_preserves_previous_credentials() {
        val oldCredentials = StoredCredentials("old_key", "old_secret", true)
        val newCredentials = StoredCredentials("new_key", "new_secret", true)

        assertTrue(store.save(oldCredentials))

        fakePrefs.shouldCommitSucceed = false

        assertFalse(store.save(newCredentials))
        assertEquals(CredentialState.VERIFIED, store.state())
        assertEquals(oldCredentials, store.read())
    }

    @Test
    fun markUnverified_writes_and_read_back_confirms_unverified() {
        val credentials = StoredCredentials("key", "secret", true)
        assertTrue(store.save(credentials))

        assertTrue(store.markUnverified())
        assertEquals(CredentialState.SAVED_UNVERIFIED, store.state())
        assertFalse(fakePrefs.getBoolean(EncryptedCredentialsStore.KEY_VERIFIED_MARKER, true))
    }

    @Test
    fun markUnverified_returns_false_when_commit_fails() {
        val credentials = StoredCredentials("key", "secret", true)
        assertTrue(store.save(credentials))

        fakePrefs.shouldCommitSucceed = false

        assertFalse(store.markUnverified())
        assertEquals(CredentialState.VERIFIED, store.state())
    }

    @Test
    fun markUnverified_returns_false_when_read_back_still_reports_verified() {
        val credentials = StoredCredentials("key", "secret", true)
        assertTrue(store.save(credentials))

        fakePrefs.forceVerifiedMarkerTrueOnRead = true

        assertFalse(store.markUnverified())
    }

    @Test
    fun markUnverified_returns_false_when_read_back_throws() {
        val credentials = StoredCredentials("key", "secret", true)
        assertTrue(store.save(credentials))

        fakePrefs.throwOnVerifiedMarkerRead = true

        assertFalse(store.markUnverified())
    }

    @Test
    fun clear_removes_all_keys_and_sets_missing_state() {
        val credentials = StoredCredentials("key", "secret", false)
        assertTrue(store.save(credentials))

        assertTrue(store.clear())
        assertEquals(CredentialState.MISSING, store.state())
        assertEquals(null, store.read())
        assertFalse(fakePrefs.contains(EncryptedCredentialsStore.KEY_API_KEY))
        assertFalse(fakePrefs.contains(EncryptedCredentialsStore.KEY_API_SECRET))
        assertFalse(fakePrefs.contains(EncryptedCredentialsStore.KEY_TESTNET))
        assertFalse(fakePrefs.contains(EncryptedCredentialsStore.KEY_VERIFIED_MARKER))
    }
}

private class FakeSharedPreferences : SharedPreferences {

    private object Removed
    private val values = mutableMapOf<String, Any?>()

    var shouldCommitSucceed: Boolean = true
    var corruptNextApiKeyRead: Boolean = false
    var forceVerifiedMarkerTrueOnRead: Boolean = false
    var throwOnVerifiedMarkerRead: Boolean = false

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? {
        if (corruptNextApiKeyRead &&
            key == EncryptedCredentialsStore.KEY_API_KEY
        ) {
            corruptNextApiKeyRead = false
            return "corrupted_key"
        }
        return values[key] as? String ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
            ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (values[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (values[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (values[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        if (
            throwOnVerifiedMarkerRead &&
            key == EncryptedCredentialsStore.KEY_VERIFIED_MARKER
        ) {
            throw IllegalStateException("simulated read-back failure")
        }
        if (
            forceVerifiedMarkerTrueOnRead &&
            key == EncryptedCredentialsStore.KEY_VERIFIED_MARKER
        ) {
            return true
        }
        return (values[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value ?: Removed
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            pending[key] = values?.toSet() ?: Removed
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = Removed
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (!shouldCommitSucceed) return false

            if (clearAll) values.clear()
            pending.forEach { (key, value) ->
                if (value === Removed) values.remove(key)
                else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

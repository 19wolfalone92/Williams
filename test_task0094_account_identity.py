from __future__ import annotations

import hashlib
import json

import pytest

from db import Database
from execution_account_identity import (
    AccountIdentityBlocked,
    credential_fingerprint,
    verify_execution_account_identity,
)


class SignedAccountClient:
    def __init__(self, api_key="test-key-A", *, testnet=True, account=None, error=None):
        self.api_key = api_key
        self.api_secret = "DO-NOT-PERSIST-THIS-SECRET"
        self.testnet = testnet
        self._account = account or {
            "canTrade": True,
            "accountType": "SPOT",
            "uid": 123456,
            "balances": [],
        }
        self._error = error
        self.account_calls = 0

    def account(self):
        self.account_calls += 1
        if self._error:
            raise self._error
        return dict(self._account)


def test_account_identity_pins_only_after_authenticated_account_response(tmp_path, monkeypatch):
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"test-key-A").hexdigest())
    db = Database(str(tmp_path / "identity.sqlite"))
    client = SignedAccountClient()
    result = verify_execution_account_identity(client, db, execution_scope="spot", account_scope="spot-a")
    assert result["verified"] is True
    assert result["pinned"] is True
    assert result["account_uid_present"] is True
    row = db.conn.execute("SELECT * FROM execution_account_bindings").fetchone()
    assert row["credential_fingerprint"] == hashlib.sha256(b"test-key-A").hexdigest()
    assert "DO-NOT-PERSIST-THIS-SECRET" not in json.dumps(dict(row))
    db.conn.close()


def test_changed_api_key_is_blocked_even_when_account_scope_is_unchanged(tmp_path, monkeypatch):
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"key-A").hexdigest())
    db = Database(str(tmp_path / "identity.sqlite"))
    verify_execution_account_identity(SignedAccountClient("key-A"), db, account_scope="same-label")
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"key-B").hexdigest())
    with pytest.raises(AccountIdentityBlocked, match="credential identity changed"):
        verify_execution_account_identity(SignedAccountClient("key-B"), db, account_scope="same-label")
    db.conn.close()


def test_changed_testnet_live_domain_is_blocked(tmp_path, monkeypatch):
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"key-A").hexdigest())
    db = Database(str(tmp_path / "identity.sqlite"))
    verify_execution_account_identity(SignedAccountClient("key-A", testnet=True), db, account_scope="same-label")
    with pytest.raises(AccountIdentityBlocked, match="Testnet/live execution domain changed"):
        verify_execution_account_identity(SignedAccountClient("key-A", testnet=False), db, account_scope="same-label")
    db.conn.close()


def test_changed_exchange_uid_is_blocked(tmp_path, monkeypatch):
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"test-key-A").hexdigest())
    db = Database(str(tmp_path / "identity.sqlite"))
    verify_execution_account_identity(SignedAccountClient(), db, account_scope="same-label")
    changed = SignedAccountClient(account={
        "canTrade": True, "accountType": "SPOT", "uid": 987654, "balances": []
    })
    with pytest.raises(AccountIdentityBlocked, match="account UID changed"):
        verify_execution_account_identity(changed, db, account_scope="same-label")
    db.conn.close()


def test_identity_api_failure_does_not_pin_or_allow_mutation(tmp_path, monkeypatch):
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", hashlib.sha256(b"test-key-A").hexdigest())
    db = Database(str(tmp_path / "identity.sqlite"))
    client = SignedAccountClient(error=TimeoutError("network unavailable"))
    with pytest.raises(AccountIdentityBlocked, match="identity request failed"):
        verify_execution_account_identity(client, db, account_scope="spot-a")
    count = db.conn.execute("SELECT COUNT(*) FROM execution_account_bindings").fetchone()[0]
    assert count == 0
    db.conn.close()


def test_explicit_expected_fingerprint_is_enforced(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "identity.sqlite"))
    monkeypatch.setenv("WILLIAMS_EXPECTED_API_KEY_SHA256", "0" * 64)
    with pytest.raises(AccountIdentityBlocked, match="fingerprint does not match"):
        verify_execution_account_identity(SignedAccountClient("actual-key"), db, account_scope="spot-a")
    assert db.conn.execute("SELECT COUNT(*) FROM execution_account_bindings").fetchone()[0] == 0
    db.conn.close()


def test_fingerprint_helper_never_accepts_empty_key():
    with pytest.raises(AccountIdentityBlocked, match="API key is missing"):
        credential_fingerprint("")

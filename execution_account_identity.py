"""Fail-closed Binance Spot account identity binding.

This module never logs or persists the API secret. The SHA-256 API-key
fingerprint is a credential identity, not a claim that Binance exposes an
immutable account UID on every Spot account response. If Binance returns UID,
it is persisted and checked as an additional identity signal.
"""
from __future__ import annotations

import hashlib
import hmac
import os
from typing import Any


class AccountIdentityBlocked(PermissionError):
    """Execution must remain blocked until the account binding is verified."""


def credential_fingerprint(api_key: str) -> str:
    key = str(api_key or "").strip()
    if not key:
        raise AccountIdentityBlocked("Binance API key is missing; account identity cannot be verified")
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def verify_execution_account_identity(
    client: Any,
    db: Any,
    *,
    execution_scope: str = "spot",
    account_scope: str | None = None,
) -> dict[str, Any]:
    """Authenticate to Binance and pin/check the local execution identity.

    A key change, Testnet/live change, account UID change, failed signed request,
    or unavailable DB binding is fail-closed. A key rotation requires an explicit
    offline rebind while trading is stopped; merely keeping account_scope constant
    does not bypass the check.
    """
    scope = str(execution_scope or "spot").strip().lower()
    if scope != "spot":
        raise AccountIdentityBlocked(
            f"execution scope {scope!r} has no verified account identity adapter"
        )
    api_key = str(getattr(client, "api_key", "") or "")
    fingerprint = credential_fingerprint(api_key)
    expected = os.getenv("WILLIAMS_EXPECTED_API_KEY_SHA256", "").strip().lower()
    if len(expected) != 64 or any(ch not in "0123456789abcdef" for ch in expected):
        raise AccountIdentityBlocked(
            "WILLIAMS_EXPECTED_API_KEY_SHA256 must be explicitly configured before execution"
        )
    if not hmac.compare_digest(expected, fingerprint):
        raise AccountIdentityBlocked(
            "configured Binance API key fingerprint does not match WILLIAMS_EXPECTED_API_KEY_SHA256"
        )

    account_method = getattr(client, "account", None)
    if not callable(account_method):
        raise AccountIdentityBlocked("signed Binance account endpoint unavailable")
    try:
        account = account_method()
    except Exception as exc:
        raise AccountIdentityBlocked(
            f"authenticated Binance account identity request failed: {type(exc).__name__}"
        ) from exc
    if not isinstance(account, dict):
        raise AccountIdentityBlocked("authenticated Binance account response is not an object")
    if account.get("canTrade") is not True:
        raise AccountIdentityBlocked("authenticated Binance account response lacks canTrade=true")
    if not isinstance(account.get("balances"), list):
        raise AccountIdentityBlocked("authenticated Binance account response lacks a balances array")

    uid_value = account.get("uid")
    uid = None if uid_value in (None, "") else str(uid_value)
    account_type_value = account.get("accountType")
    account_type = None if account_type_value in (None, "") else str(account_type_value)
    configured_scope = str(
        account_scope
        or os.getenv("WILLIAMS_ACCOUNT_SCOPE", "").strip()
        or "spot-default"
    ).strip()
    try:
        result = db.verify_or_pin_execution_account(
            exchange="BINANCE",
            execution_scope=scope,
            account_scope=configured_scope,
            credential_fingerprint=fingerprint,
            account_uid=uid,
            account_type=account_type,
            testnet=bool(getattr(client, "testnet", False)),
        )
    except AccountIdentityBlocked:
        raise
    except PermissionError as exc:
        raise AccountIdentityBlocked(str(exc)) from exc
    except Exception as exc:
        raise AccountIdentityBlocked(
            f"durable Binance account binding failed: {type(exc).__name__}"
        ) from exc
    return {
        "verified": True,
        "pinned": bool(result.get("pinned")),
        "exchange": "BINANCE",
        "execution_scope": scope,
        "account_scope": configured_scope,
        "account_uid_present": uid is not None,
        "account_type": result.get("account_type"),
        "testnet": bool(getattr(client, "testnet", False)),
        "credential_fingerprint_prefix": fingerprint[:12],
    }

"""Internal capability contract for exchange-backed reconciliation.

Only ExecutionRecoveryCoordinator issues sealed evidence objects. Database resolution
rejects arbitrary status/evidence strings. This is an in-process trust contract, not
a security boundary against arbitrary hostile Python code running in the same process.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


_EVIDENCE_SEAL = object()


@dataclass(frozen=True)
class VerifiedReconciliationEvidence:
    exchange: str
    account_scope: str
    execution_scope: str
    symbol: str
    client_order_id: str
    order_id: str
    status: str
    executed_qty: str
    history_qty: str
    trade_ids: tuple[str, ...]
    observed_at: str
    evidence_id: str
    order_verified: bool
    history_complete: bool
    fill_totals_match: bool
    _seal: Any = None

    def __post_init__(self):
        if self._seal is not _EVIDENCE_SEAL:
            raise TypeError("VerifiedReconciliationEvidence may only be issued by the recovery coordinator")


def _issue_verified_reconciliation_evidence(**kwargs):
    """Private issuer used after the coordinator verifies authoritative exchange data."""
    return VerifiedReconciliationEvidence(**kwargs, _seal=_EVIDENCE_SEAL)


def is_verified_reconciliation_evidence(value) -> bool:
    return (
        type(value) is VerifiedReconciliationEvidence
        and getattr(value, "_seal", None) is _EVIDENCE_SEAL
    )

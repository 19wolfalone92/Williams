"""Fail-closed runtime freshness evidence contracts.

This module validates component measurement timestamps. It does not manufacture
measurements: callers must supply evidence produced by the component that was
actually observed. Report-generation time is never accepted as component time.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping
import time


# No operational freshness budget has been approved as a project contract.
# Callers must pass an explicitly approved max_age_ms; absence remains UNKNOWN.
DEFAULT_MAX_AGE_MS: dict[str, int] = {}

FRESHNESS_COMPONENTS = (
    "binance_rest", "server_time", "account", "balances", "candles",
    "market_websocket", "user_data_stream", "scanner_heartbeat",
    "market_context", "sqlite_integrity", "reconciliation", "diagnostics",
    "android_runtime",
)

EXPECTED_SOURCES = {
    "binance_rest": "binance_rest_response",
    "server_time": "binance_server_time_sync",
    "account": "authenticated_account_response",
    "balances": "authenticated_account_response",
    "candles": "binance_klines_response",
    "market_websocket": "market_ws_event",
    "user_data_stream": "authenticated_user_stream_event",
    "scanner_heartbeat": "scanner_completed_cycle",
    "market_context": "market_context_published_snapshot",
    "sqlite_integrity": "sqlite_integrity_check",
    "reconciliation": "exchange_local_reconciliation",
    "diagnostics": "diagnostic_component_probe",
    "android_runtime": "android_runtime_heartbeat",
}


@dataclass(frozen=True)
class FreshnessEvidence:
    component: str
    source: str
    observed_at_ms: int
    valid: bool
    generation: str
    details: Mapping[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class FreshnessResult:
    component: str
    status: str
    reason: str
    age_ms: int | None = None
    source: str = ""


def evaluate_freshness(
    component: str,
    evidence: FreshnessEvidence | None,
    *,
    now_ms: int | None = None,
    max_age_ms: int | None = None,
    future_tolerance_ms: int = 1_000,
) -> FreshnessResult:
    """Return PASS only for valid, source-bound, fresh, non-future evidence."""
    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    if component not in EXPECTED_SOURCES:
        return FreshnessResult(component, "UNKNOWN", "component_has_no_freshness_contract")
    if evidence is None:
        return FreshnessResult(component, "UNKNOWN", "measurement_missing")
    if evidence.component != component:
        return FreshnessResult(component, "UNKNOWN", "component_identity_mismatch", source=evidence.source)
    expected_source = EXPECTED_SOURCES[component]
    if evidence.source != expected_source:
        return FreshnessResult(component, "UNKNOWN", "measurement_source_mismatch", source=evidence.source)
    if not evidence.generation:
        return FreshnessResult(component, "UNKNOWN", "measurement_generation_missing", source=evidence.source)
    try:
        observed = int(evidence.observed_at_ms)
    except (TypeError, ValueError, OverflowError):
        return FreshnessResult(component, "UNKNOWN", "measurement_timestamp_invalid", source=evidence.source)
    age = now - observed
    if age < -max(0, int(future_tolerance_ms)):
        return FreshnessResult(component, "UNKNOWN", "measurement_timestamp_in_future", age, evidence.source)
    if age < 0:
        # Even a small future timestamp is suspicious for an execution gate.
        return FreshnessResult(component, "UNKNOWN", "measurement_clock_skew", age, evidence.source)
    if not evidence.valid:
        return FreshnessResult(component, "FAIL", "component_measurement_invalid", age, evidence.source)
    if max_age_ms is None:
        return FreshnessResult(component, "UNKNOWN", "freshness_budget_unapproved", age, evidence.source)
    budget = max(1, int(max_age_ms))
    if age > budget:
        return FreshnessResult(component, "FAIL", "measurement_stale", age, evidence.source)
    return FreshnessResult(component, "PASS", "fresh_measurement", age, evidence.source)


def evaluate_freshness_set(
    required_components: tuple[str, ...],
    evidence_by_component: Mapping[str, FreshnessEvidence],
    *,
    now_ms: int | None = None,
    max_ages_ms: Mapping[str, int] | None = None,
) -> dict[str, FreshnessResult]:
    """Evaluate a full component set; missing evidence remains UNKNOWN."""
    results: dict[str, FreshnessResult] = {}
    for component in required_components:
        evidence = evidence_by_component.get(component)
        results[component] = evaluate_freshness(
            component,
            evidence,
            now_ms=now_ms,
            max_age_ms=(max_ages_ms or {}).get(component),
        )
    return results

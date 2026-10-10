import time

import pytest

from runtime_freshness import (
    DEFAULT_MAX_AGE_MS,
    FRESHNESS_COMPONENTS,
    EXPECTED_SOURCES,
    FreshnessEvidence,
    evaluate_freshness,
    evaluate_freshness_set,
)


def evidence(component, *, at=100_000, source=None, valid=True, generation="g1"):
    return FreshnessEvidence(
        component=component,
        source=source or EXPECTED_SOURCES[component],
        observed_at_ms=at,
        valid=valid,
        generation=generation,
    )


@pytest.mark.parametrize("component", list(FRESHNESS_COMPONENTS))
def test_fresh_component_measurement_passes(component):
    result = evaluate_freshness(component, evidence(component), now_ms=100_000, max_age_ms=1_000)
    assert result.status == "PASS"
    assert result.age_ms == 0


def test_stale_measurement_fails_closed():
    result = evaluate_freshness(
        "market_websocket", evidence("market_websocket", at=90_000),
        now_ms=100_000,
        max_age_ms=1_000,
    )
    assert result.status == "FAIL"
    assert result.reason == "measurement_stale"


def test_missing_measurement_remains_unknown():
    result = evaluate_freshness("scanner_heartbeat", None, now_ms=100_000)
    assert result.status == "UNKNOWN"
    assert result.reason == "measurement_missing"


def test_future_timestamp_remains_unknown():
    result = evaluate_freshness(
        "balances", evidence("balances", at=100_001),
        now_ms=100_000,
    )
    assert result.status == "UNKNOWN"
    assert result.reason == "measurement_clock_skew"


def test_wrong_source_and_wrong_component_remain_unknown():
    wrong_source = evaluate_freshness(
        "market_websocket",
        evidence("market_websocket", source="diagnostic_report_created_at"),
        now_ms=100_000,
    )
    wrong_component = evaluate_freshness(
        "market_websocket", evidence("balances"),
        now_ms=100_000,
    )
    assert wrong_source.status == "UNKNOWN"
    assert wrong_source.reason == "measurement_source_mismatch"
    assert wrong_component.status == "UNKNOWN"
    assert wrong_component.reason == "component_identity_mismatch"


def test_invalid_component_measurement_fails():
    result = evaluate_freshness(
        "reconciliation", evidence("reconciliation", valid=False),
        now_ms=100_000,
    )
    assert result.status == "FAIL"
    assert result.reason == "component_measurement_invalid"


def test_missing_required_component_is_not_inferred_from_report_timestamp():
    results = evaluate_freshness_set(
        ("account", "market_websocket", "android_runtime"),
        {"account": evidence("account", at=100_000)},
        now_ms=100_000,
        max_ages_ms={"account": 1_000},
    )
    assert results["account"].status == "PASS"
    assert results["market_websocket"].status == "UNKNOWN"
    assert results["android_runtime"].status == "UNKNOWN"


def test_generation_is_required_to_detect_measurement_identity():
    result = evaluate_freshness(
        "candles", evidence("candles", generation=""),
        now_ms=100_000,
    )
    assert result.status == "UNKNOWN"
    assert result.reason == "measurement_generation_missing"


def test_missing_approved_budget_remains_unknown_even_with_fresh_measurement():
    result = evaluate_freshness(
        "market_websocket", evidence("market_websocket"),
        now_ms=100_000,
    )
    assert result.status == "UNKNOWN"
    assert result.reason == "freshness_budget_unapproved"


def test_unapproved_budget_cannot_be_inferred_from_component_defaults():
    assert DEFAULT_MAX_AGE_MS == {}

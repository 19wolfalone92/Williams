"""Strict offline acceptance-contract tests. They are NOT live Binance proofs."""
from williams_demo_evidence_gate import validate_demo_both_directions
from test_williams_demo_evidence_gate import example


def sample():
    result = {"environment": "BINANCE_USDM_FUTURES_DEMO", "apk_sha256": "c"*64, "runs": []}
    for side, prefix in (("LONG", "L"), ("SHORT", "S")):
        run = example()
        run["direction"] = side
        run["journal"] = {
            "executed_qty": 0.1, "commission_usdt": 0.03,
            "realized_pnl_usdt": -0.4,
            "source": "SIGNED_DEMO_TRADE_AND_INCOME_SNAPSHOT",
        }
        owner = "CAMP_" + prefix
        for e in run["events"]:
            typ = e["type"]
            if typ in ("ENTRY_FILL_REPORTED", "STOP_ACK_REPORTED",
                       "POSITION_RECONCILED", "STOP_RECONFIRMED"):
                e["campaign_id"] = owner
            if typ == "ENTRY_FILL_REPORTED":
                e["exchange_order_id"] = prefix + "_ORDER_001"
                e["side"] = "BUY" if side == "LONG" else "SELL"
                e["avg_fill_price"] = 100.0
            if typ in ("STOP_ACK_REPORTED", "STOP_RECONFIRMED"):
                e["order_id"] = prefix + "_STOP_001"
                e["side"] = "SELL" if side == "LONG" else "BUY"
                e["stop_price"] = 95.0 if side == "LONG" else 105.0
            if typ == "POSITION_FLAT":
                e["symbol"] = "BTCUSDT"
                e["position_qty"] = 0
                e["source"] = "SIGNED_DEMO_ACCOUNT_SNAPSHOT"
                e["exit_side"] = "SELL" if side == "LONG" else "BUY"
        result["runs"].append(run)
    return result


def test_two_artificial_timelines_only_pass_structure_and_not_a_real_release():
    report = validate_demo_both_directions(sample())
    assert report["errors"] == [], report
    assert report["status"] == "STRUCTURAL_PASS_REQUIRES_INDEPENDENT_PHYSICAL_BINANCE_ATTESTATION"
    assert report["release_approved"] is False
    assert report["exchange_authenticity_verified"] is False
    assert report["physical_device_verified"] is False
    assert report["can_enable_live"] is False


def test_only_long_is_not_full_book_trading_acceptance():
    payload = sample()
    payload["runs"] = payload["runs"][:1]
    errors = validate_demo_both_directions(payload)["errors"]
    assert "REQUIRE_EXACTLY_TWO_INDEPENDENT_DEMO_CYCLES" in errors
    assert "LONG_AND_SHORT_BOTH_REQUIRED" in errors


def test_synthetic_matching_order_id_for_two_directions_never_counts():
    payload = sample()
    payload["runs"][1]["events"][4]["exchange_order_id"] = "L_ORDER_001"
    assert "DUPLICATE_OR_UNKNOWN_DEMO_ENTRY_ORDER" in validate_demo_both_directions(payload)["errors"]


def test_wrong_stop_side_missing_signed_flat_and_pnl_are_blockers():
    payload = sample()
    short = payload["runs"][1]
    next(e for e in short["events"] if e["type"] == "STOP_ACK_REPORTED")["side"] = "SELL"
    flat = next(e for e in short["events"] if e["type"] == "POSITION_FLAT")
    flat["source"] = "LOCAL_SQLITE"
    short["journal"].pop("realized_pnl_usdt")
    err = validate_demo_both_directions(payload)["errors"]
    assert "SHORT_ENTRY_OR_STOP_DIRECTION_MISMATCH" in err
    assert "SHORT_POSITION_FLAT_NOT_SIGNED_OR_DIRECTION_MISSING" in err
    assert "SHORT_JOURNAL_REALIZED_PNL_USDT_MISSING" in err


def test_recovery_cannot_replace_protection_silently():
    payload = sample()
    recovered = next(e for e in payload["runs"][0]["events"]
                     if e["type"] == "STOP_RECONFIRMED")
    recovered["order_id"] = "L_SECOND_STOP"
    assert "LONG_STOP_REPLACEMENT_UNPROVED" in validate_demo_both_directions(payload)["errors"]
    recovered["replaces_order_id"] = "L_STOP_001"
    recovered["replacement_receipt_id"] = "SIGNED_ORDER_UPDATE_001"
    assert "LONG_STOP_REPLACEMENT_UNPROVED" not in validate_demo_both_directions(payload)["errors"]


def test_position_from_another_campaign_cannot_be_adopted():
    payload = sample()
    restored = next(e for e in payload["runs"][0]["events"]
                    if e["type"] == "POSITION_RECONCILED")
    restored["campaign_id"] = "OTHER_CAMP"
    assert "LONG_POSITION_CAMPAIGN_OWNERSHIP_UNPROVED" in validate_demo_both_directions(payload)["errors"]


def test_corrupted_stop_qty_environment_and_secret_remain_fail_closed():
    payload = sample()
    stop = next(e for e in payload["runs"][0]["events"]
                if e["type"] == "STOP_ACK_REPORTED")
    stop["qty"] = 0.001
    payload["runs"][0]["events"][1]["api_key"] = "SHOULD_NOT_BE_EXPORTED"
    payload["runs"][1]["environment"] = "LIVE"
    err = validate_demo_both_directions(payload)["errors"]
    assert "LONG_CYCLE_INCOMPLETE" in err
    assert "SHORT_CYCLE_INCOMPLETE" in err
    assert "SHORT_DIFFERENT_BUILD_OR_EXCHANGE" in err


def test_nested_journal_credentials_are_rejected_without_printing_them():
    payload = sample()
    payload["runs"][0]["journal"]["credentials"] = {
        "api_secret": "MUST_NOT_BE_IN_EXPORTED_DIAGNOSTICS"
    }
    report = validate_demo_both_directions(payload)
    assert "SECRET_FIELD_FOUND_IN_DIAGNOSTIC" in report["errors"]
    assert "MUST_NOT_BE_IN_EXPORTED_DIAGNOSTICS" not in repr(report)


def test_wrong_side_initial_stop_or_same_campaign_cannot_pass_dual_contract():
    payload = sample()
    stop = next(e for e in payload["runs"][0]["events"]
                if e["type"] == "STOP_ACK_REPORTED")
    stop["stop_price"] = 105.0  # LONG cannot use stop above entry as initial risk
    err = validate_demo_both_directions(payload)["errors"]
    assert "LONG_INITIAL_STRUCTURAL_STOP_WRONG_SIDE" in err
    payload = sample()
    for e in payload["runs"][1]["events"]:
        if "campaign_id" in e:
            e["campaign_id"] = "CAMP_L"
    assert "DUPLICATE_CAMPAIGN_OWNERSHIP_ACROSS_DIRECTIONS" in (
        validate_demo_both_directions(payload)["errors"]
    )

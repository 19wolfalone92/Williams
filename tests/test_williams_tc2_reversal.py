import json
from copy import deepcopy
from pathlib import Path

import pytest

from williams_tc2_reversal import reversal_candidate, reversal_entry_blocker

FIXTURE = json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_tc2_reversal_contract.json").read_text())


@pytest.mark.parametrize("example", FIXTURE["examples"], ids=lambda x:x["id"])
@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda x:x["id"])
def test_shared_two_leg_contract_rejects_unconfirmed_flat_replay_and_price_chasing(example, case):
    d = deepcopy(example)
    c = reversal_candidate(d["ledger"], d["owner_id"], d["now"])
    assert c
    owner, exit_ = d["ledger"]
    proof = owner["edition_management"]["exit_evidence"]
    kind = case["id"]
    stop, signal_id = c["protective_stop"], c["signal_id"]
    if kind == "partial-exit":
        owner["campaign_status"] = "EXIT_PENDING"
    elif kind == "close-reply-only":
        exit_["exchange_status"] = "FILLED"
    elif kind == "partial-quantity":
        exit_["executed_qty"] = ".5"
    elif kind == "future-flat":
        exit_["confirmed_flat_at_ms"] = d["now"]+1
    elif kind == "stale":
        d["now"] += 121_000
    elif kind == "ambiguous":
        proof["opposition_check"]["uncertain"] = [{"state":"AMBIGUOUS"}]
    elif kind == "wrong-source":
        proof["reverse_candidate"]["strategy_profile"] = "NTD_FIVE_DIMENSIONS"
    elif kind == "stop-changed":
        stop += 1
    elif kind == "trigger-recovered":
        d["contract"] += 1 if c["direction"] == "SHORT" else -1
    elif kind == "chasing":
        d["contract"] += -1 if c["direction"] == "SHORT" else 1
    elif kind == "unbounded-entry":
        d["entry"] = 110.1 if c["direction"] == "SHORT" else 89.9
    elif kind == "wrong-signal":
        signal_id = "foreign"
    elif kind == "unverified-owner":
        owner["strategy_ownership_verified"] = False
    elif kind in {"foreign-campaign", "already-admitted"}:
        d["ledger"].append({"intent_role":"ENTRY","symbol":"BTCUSDT","client_id":"new-owner",
                           "reversal_owner_id":d["owner_id"] if kind == "already-admitted" else ""})
    elif kind == "missing-fill":
        del exit_["executed_qty"]
    elif kind == "mark-proof":
        proof["opposition_check"]["trigger_price_basis"] = "MARK_PRICE"
    result = reversal_entry_blocker(d["ledger"], d["owner_id"], "BTCUSDT", c["direction"],
        signal_id, c["signal_type"], c["signal_bar_time_ms"], c["client_id"], d["entry"],
        stop, d["now"], d["contract"], d["mark"], d["tick"])
    assert result == case["blocker"]


def test_confirmed_lost_close_reply_can_resume_but_new_intent_consumes_owner_once():
    d = deepcopy(FIXTURE["examples"][0])
    del d["ledger"][0]["close_reason"]  # The reply was lost before local closure bookkeeping.
    c = reversal_candidate(d["ledger"], d["owner_id"], d["now"])
    assert c
    d["ledger"].append({"intent_role":"ENTRY","symbol":"BTCUSDT","client_id":c["client_id"],
                       "reversal_owner_id":d["owner_id"],"order_status":"SUBMISSION_UNKNOWN"})
    assert reversal_candidate(d["ledger"],d["owner_id"],d["now"]) is None
    # Only the in-progress SAME submission can revalidate itself before POST.
    assert reversal_candidate(d["ledger"],d["owner_id"],d["now"],c["client_id"])

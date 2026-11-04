"""NTD original pp.115–120, figs 9-2 to 9-6, source-exit contract.

Teeth is close-only, not a wick, and the Lips speed regime must be selected
on a CLOSED candle and applied to the NEXT candle of the *same* campaign.
These are paper/source checks: they never prove a Binance STOP exists.
"""
from dataclasses import replace

import pytest

from williams_edition_rules import ntd_management_plan
from williams_wave_hierarchy import WaveBar

H1 = 3_600_000


def bars_from_closes(closes, side="LONG"):
    rows = []
    for i, close in enumerate(closes):
        open_price = close
        rows.append(WaveBar(
            (i + 1) * H1, open_price, close + 1, close - 1, close, 100.0))
    if side == "SHORT":
        rows = [replace(b, o=300-b.o, h=300-b.l,
                        l=300-b.h, c=300-b.c) for b in rows]
    return rows


def plan(series, side="LONG", opened_at=H1, state=None, *, lips=None, teeth=None):
    n = len(series)
    return ntd_management_plan(
        side, series, teeth or [210.0 if side=="SHORT" else 90.0] * n,
        lips or [198.0 if side=="SHORT" else 102.0] * n,
        [0.0]*n, [0.0]*n, .1, opened_at, state)


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_ntd_fast_lips_is_selected_only_from_completed_source_and_acts_next_bar(side):
    source=bars_from_closes([100,101,102,103,104,105],side)
    fast=plan(source,side)
    assert fast["exit_reason"]==""
    assert fast["stop"] is None
    assert fast["state"]["lips_selected_at"]==source[-1].t

    same_bar=plan(source,side,state=fast["state"])
    assert same_bar["exit_reason"]==""

    with_next=source+bars_from_closes([101],side)[0:1]
    # The next closed H1 has the correct consecutive actual timestamp.
    with_next[-1]=replace(with_next[-1], t=source[-1].t+H1)
    result=plan(with_next,side,state=fast["state"])
    assert result["exit_reason"]=="LIPS_CLOSE_EXIT"
    assert result["stop"] is None


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_ntd_teeth_is_close_only_and_not_intrabar_crossing(side):
    # A non-fast trend has no Lips exit selected. Wicks can cross Teeth but
    # the closing price alone decides the book's red-line close-only exit.
    steady=bars_from_closes([100]*6,side)
    steady[-1]=(replace(steady[-1],l=80.0) if side=="LONG"
                else replace(steady[-1],h=220.0))
    on_wick=plan(steady,side)
    assert on_wick["exit_reason"]==""
    assert "lips_selected_at" not in on_wick["state"]

    closing=bars_from_closes([100]*5+[89],side)
    exit_result=plan(closing,side)
    assert exit_result["exit_reason"]=="TEETH_CLOSE_EXIT"
    assert exit_result["stop"] is None


@pytest.mark.parametrize("side", ["LONG","SHORT"])
def test_ntd_fast_lips_does_not_transfer_into_new_position_with_old_journal(side):
    old=bars_from_closes([100,101,102,103,104,105],side)
    selected=plan(old,side)["state"]
    assert selected["lips_selected_at"]==old[-1].t
    fresh=bars_from_closes([100,101,102,103,104,105,101,101],side)
    # A different position opens at the first full candle after the old
    # steep-market decision, then its next candle is complete.
    new_owner_open=old[-1].t+H1
    result=plan(fresh,side,opened_at=new_owner_open,state=selected)
    assert result["exit_reason"]==""
    assert "lips_selected_at" not in result["state"]


@pytest.mark.parametrize("side", ["LONG","SHORT"])
def test_ntd_source_rejects_equally_spaced_but_utc_misaligned_h1(side):
    source=bars_from_closes([100,101,102,103,104,105],side)
    bad=[replace(b,t=b.t+60_000) for b in source]
    outcome=plan(bad,side)
    assert outcome["exit_reason"]==""
    assert outcome["stop"] is None
    assert outcome["state"]["zone_book_evidence"]=="NTD_H1_HISTORY_GAP"

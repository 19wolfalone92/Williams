"""15-asset frozen Binance H1 history is real data, not human-labeled Elliott truth."""
from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
import pytest

from tools import replay_frozen_h1_five_symbol as frozen


def test_all_fifteen_frozen_exchange_histories_are_causal_and_checksum_tracked():
    assert len(frozen.ASSUMED_TICKS_2026_10_25)==15
    hashes = set()
    for symbol in frozen.ASSUMED_TICKS_2026_10_25:
        candles, digest = frozen.load_sealed_h1(symbol)
        assert len(candles) == 480
        assert candles.index.tz is not None
        assert candles.index[0] == pd.Timestamp("2024-11-09T00:00:00Z")
        assert candles.index[-1] == pd.Timestamp("2024-11-28T23:00:00Z")
        assert (candles.index.to_series().diff().iloc[1:] == pd.Timedelta(hours=1)).all()
        assert len(digest) == 64 and digest not in hashes
        hashes.add(digest)


def test_gapped_or_forged_frozen_exchange_source_fails_closed(monkeypatch, tmp_path):
    symbol="BTCUSDT"
    fixture=frozen.SOURCE_DIR/f"blind_wave_20241109_29_{symbol}.json"
    original=json.loads(fixture.read_text())
    monkeypatch.setattr(frozen,"SOURCE_DIR",tmp_path)
    output=tmp_path/fixture.name
    for mutation in ("wrong_source","missing_bar","duplicate","misaligned","future","nan"):
        payload=json.loads(json.dumps(original))
        if mutation=="wrong_source":
            payload["source"]="FAKE_BROKER"
        elif mutation=="missing_bar":
            payload["rows"].pop()
        elif mutation=="duplicate":
            payload["rows"][1][0]=payload["rows"][0][0]
        elif mutation=="misaligned":
            payload["rows"][1][0]+=1
        elif mutation=="future":
            payload["rows"][-1][0]+=3_600_000
        elif mutation=="nan":
            payload["rows"][5][2]="NaN"
        output.write_text(json.dumps(payload))
        with pytest.raises(ValueError):
            frozen.load_sealed_h1(symbol)


def test_results_retain_unverified_status_even_with_positive_backtest(monkeypatch,tmp_path):
    tick_checks={}
    def mocked(symbol,candles,*,tick_size,output_dir,config,window_bars):
        tick_checks[symbol]=tick_size
        return {"trade_metrics":{"trades":2,"net_pnl_usdt":123.45},
            "max_drawdown_pct":-2.0,
            "read_only_chronological_quality":{
                "status":"RETROSPECTIVE_HOLDOUT_NOT_INDEPENDENTLY_CERTIFIED",
                "profits_certified":False,"execution_authority":False}}
    monkeypatch.setattr(frozen,"run_symbol_replay",mocked)
    r=frozen.run_all(tmp_path/"report")
    assert len(tick_checks)==15 and tick_checks["XRPUSDT"]==0.0001
    assert r["total_trades_not_portfolio"]==30
    assert r["independent_expert_wave_annotations"]==0
    assert r["human_wave_accuracy"] is None
    assert not r["profits_certified"] and not r["book_fidelity_certified"]
    assert not r["execution_authority"] and not r["demo_device_certified"]
    assert json.loads((tmp_path/"report"/"frozen_fifteen_symbol_summary.json").read_text())["execution_authority"] is False

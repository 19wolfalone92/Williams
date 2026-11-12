"""Check diagnostic-ZIP bridge never invents candles or trading authority."""
import json
import zipfile

import pytest

from tc2_time_machine_from_diagnostics import research_archive
from tc2_time_machine_research import HOUR_MS


def make_archive(tmp_path, frame="1h", contiguous=True):
    path = tmp_path / "diagnostic.zip"
    start = 1_780_000_000_000
    candles = []
    price = 100.0
    for i in range(150):
        nxt = price * (1.005 if i % 9 < 5 else 0.993)
        candles.append([start + i * HOUR_MS, price,
                        max(price, nxt) * 1.001,
                        min(price, nxt) * 0.999, nxt, 100.0])
        price = nxt
    doc = {"BNBUSDT": {"frame": frame, "history_contiguous": contiguous,
                       "as_of_close_ms": start + 150 * HOUR_MS,
                       "candles": candles}}
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("signal_decision_history.json", json.dumps(doc))
    return path


def test_diagnostics_bridge_read_only_and_reproducible(tmp_path):
    archive = make_archive(tmp_path)
    kwargs = dict(symbol="BNBUSDT", side="LONG", trigger=100.0,
                  stop=95.0, seed=42, replays=35)
    first = research_archive(archive, **kwargs)
    assert first == research_archive(archive, **kwargs)
    assert first["training_bars"] == 126
    assert first["data_origin"] == "ANDROID_DIAGNOSTIC_CLOSED_H1_AS_OF_SNAPSHOT"
    assert first["exchange_orders_placed"] == 0


def test_missing_or_unverified_h1_rejected(tmp_path):
    path = make_archive(tmp_path, frame="5m")
    with pytest.raises(ValueError, match="contiguous H1"):
        research_archive(path, symbol="BNBUSDT", side="LONG", trigger=100,
                         stop=95, replays=3)
    path = make_archive(tmp_path, contiguous=False)
    with pytest.raises(ValueError, match="contiguous H1"):
        research_archive(path, symbol="BNBUSDT", side="LONG", trigger=100,
                         stop=95, replays=3)
    with pytest.raises(ValueError, match="symbol not found"):
        research_archive(path, symbol="ETHUSDT", side="LONG", trigger=100,
                         stop=95, replays=3)

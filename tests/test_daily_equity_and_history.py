from datetime import datetime, timezone
from pathlib import Path

import pytest
import pandas as pd

from data import fetch_klines_cached_history, fetch_klines_history
from db import Database
from equity_breaker import EquityCircuitBreaker


@pytest.fixture
def database(tmp_path):
    db = Database(str(tmp_path / "daily.sqlite3"))
    yield db
    db.conn.close()


def closed_trade(db, pnl, fees=0, symbol="BTCUSDT"):
    now = datetime.now(timezone.utc).isoformat()
    db.save_trade(symbol=symbol, entry_time=now, exit_time=now, pnl=pnl, fees=fees)


def test_global_daily_loss_uses_realized_losses_across_all_symbols(database):
    closed_trade(database, -250, 60)
    closed_trade(database, 20, 20, "ETHUSDT")
    assert database.pnl_today_all() == -310
    allowed, reason = EquityCircuitBreaker().check(database, 10000)
    assert not allowed, reason


@pytest.mark.parametrize("field,value", [("unrealized_pnl_quote", float("nan")),
    ("unrealized_pnl_quote", float("inf")), ("fees_quote", float("nan"))])
def test_unknown_pnl_cannot_disable_daily_risk_comparisons(database, field, value):
    assert not EquityCircuitBreaker().check(database, 10000, **{field: value})[0]


def test_non_finite_persisted_daily_baseline_is_not_a_valid_risk_source(database):
    database.state_set("daily_risk_day", datetime.now(timezone.utc).date().isoformat())
    database.state_set("daily_start_equity", "nan")
    closed_trade(database, 1)
    assert not EquityCircuitBreaker().check(database, 10000)[0]


@pytest.mark.parametrize("failed_key", ["daily_start_equity", "daily_risk_day"])
def test_daily_baseline_day_and_value_roll_back_together_on_storage_failure(database, monkeypatch, failed_key):
    database.state_set("daily_risk_day", "2000-01-01")
    database.state_set("daily_start_equity", "9000")
    write = database.state_set
    def fail_value(key, value):
        if key == failed_key:
            raise OSError("model disk full")
        return write(key, value)
    monkeypatch.setattr(database, "state_set", fail_value)
    with pytest.raises(OSError):
        EquityCircuitBreaker().check(database, 10000)
    assert database.state_get("daily_risk_day") == "2000-01-01"
    assert database.state_get("daily_start_equity") == "9000"


def test_daily_loss_latch_survives_a_new_breaker_and_later_pnl_recovery(database):
    breaker = EquityCircuitBreaker()
    assert breaker.check(database, 10000)[0]
    closed_trade(database, -400)
    assert not breaker.check(database, 9600)[0]
    closed_trade(database, 500)
    assert not EquityCircuitBreaker().check(database, 10100)[0]


class HistoricalClient:
    def __init__(self, futures=True, price=100):
        self.is_futures = futures
        self.testnet = True
        self.price = price
        self.open_time = 1_700_000_000_000
        self.calls = []

    def _request(self, method, path, params):
        self.calls.append((path, params.copy()))
        if params["startTime"] > self.open_time:
            return []
        return [[self.open_time, self.price, self.price + 1, self.price - 1, self.price, 10,
                 self.open_time + 3_599_999, 100, 10, 1, 1, 0]]


def test_full_futures_history_uses_only_the_futures_kline_route():
    client = HistoricalClient()
    assert len(fetch_klines_history(client, "BTCUSDT", "1h")) == 1
    assert client.calls[0][0] == "/fapi/v1/klines"


def test_history_cache_refreshes_the_last_still_forming_candle(tmp_path):
    client = HistoricalClient()
    fetch_klines_cached_history(client, "BTCUSDT", "1h", str(tmp_path))
    client.price = 105
    refreshed = fetch_klines_cached_history(client, "BTCUSDT", "1h", str(tmp_path))
    assert len(refreshed) == 1 and refreshed.iloc[-1]["close"] == 105
    assert client.calls[-1][1]["startTime"] == client.open_time


def test_spot_and_futures_history_cannot_share_a_cache_file(tmp_path):
    spot = HistoricalClient(futures=False, price=100)
    futures = HistoricalClient(futures=True, price=200)
    fetch_klines_cached_history(spot, "BTCUSDT", "1h", str(tmp_path))
    result = fetch_klines_cached_history(futures, "BTCUSDT", "1h", str(tmp_path))
    assert result.iloc[-1]["close"] == 200
    assert (tmp_path / "spot_testnet" / "BTCUSDT_1h.csv").is_file()
    assert (tmp_path / "futures_testnet" / "BTCUSDT_1h.csv").is_file()


def test_a_failed_cache_write_retains_the_previous_complete_history(tmp_path, monkeypatch):
    client = HistoricalClient()
    fetch_klines_cached_history(client, "BTCUSDT", "1h", str(tmp_path))
    cache = tmp_path / "futures_testnet" / "BTCUSDT_1h.csv"
    previous = cache.read_bytes()
    def fail_write(_, path, **kwargs):
        Path(path).write_text("partial CSV")
        raise OSError("model disk full")
    monkeypatch.setattr(pd.DataFrame, "to_csv", fail_write)
    with pytest.raises(OSError):
        fetch_klines_cached_history(client, "BTCUSDT", "1h", str(tmp_path))
    assert cache.read_bytes() == previous
    assert not list(cache.parent.glob("*.tmp"))

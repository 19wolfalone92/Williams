import os
import tempfile
from dotenv import load_dotenv
from test_db_guard import assert_test_db_safe
load_dotenv(".env")

# TEST SAFETY: never use production DB
_test_db_fd, _test_db_path = tempfile.mkstemp(
    prefix="williams_test_",
    suffix=".sqlite3"
)
os.close(_test_db_fd)
os.environ["WILLIAMS_TEST_DB"] = _test_db_path
os.environ["WILLIAMS_DB_PATH"] = _test_db_path
assert_test_db_safe()

from trader import Trader
from portfolio_controller import PortfolioController

print()
print("=" * 72)
print("WILLIAMS SELECTED SYMBOL PREPARATION TEST")
print("BINANCE SPOT TESTNET")
print("NO ORDERS")
print("=" * 72)

trader = Trader(testnet=True)

assert trader.state() == "FLAT"

balance = trader.available_quote()

controller = PortfolioController(
    trader.client,
    balance_quote=balance,
    symbols=[
        "BTCUSDT",
        "ETHUSDT",
        "SOLUSDT",
        "BNBUSDT",
        "XRPUSDT",
        "ADAUSDT",
        "DOGEUSDT",
        "AVAXUSDT",
        "LINKUSDT",
        "DOTUSDT",
    ],
)

selection = controller.select(has_open_position=False)

if selection is None:
    print()
    print("NO STRICT SIGNAL CURRENTLY.")
    print("Using BTCUSDT as a preparation-only validation symbol.")
    selected_symbol = "BTCUSDT"
else:
    candidate = selection.candidate

    assert candidate.signal is True
    assert candidate.htf_confirmed is True

    selected_symbol = candidate.symbol

    print()
    print("SCANNER SELECTED:")
    print("  symbol:", selected_symbol)
    print("  score:", round(candidate.score, 2))
    print("  HTF:", candidate.htf_confirmed)

print()
print("PREPARING SYMBOL:", selected_symbol)

result = trader.switch_symbol(selected_symbol)

assert trader.symbol == selected_symbol
assert trader.active_symbol == selected_symbol
assert trader.base_asset
assert trader.quote_asset == "USDT"
assert result["status"] == "TRADING"

assert "PRICE_FILTER" in trader.filters
assert (
    "LOT_SIZE" in trader.filters
    or "MARKET_LOT_SIZE" in trader.filters
)
assert (
    "NOTIONAL" in trader.filters
    or "MIN_NOTIONAL" in trader.filters
)

print("[PASS] exchange info loaded")
print("[PASS] symbol status TRADING")
print("[PASS] base/quote assets loaded")
print("[PASS] price filter loaded")
print("[PASS] quantity filter loaded")
print("[PASS] notional filter loaded")

print()
print("RUNNING BASELINE CHECK...")

trader.ensure_foreign_base_balance_baseline()

print("[PASS] foreign balance baseline ready")

print()
print("RUNNING RECOVERY CHECK...")

trader.recover_state()

assert trader.state() in {
    "FLAT",
    "RECONCILE_REQUIRED",
}

print("[PASS] recovery completed")
print("  state:", trader.state())

# Для чистого безопасного теста ожидаем FLAT.
if trader.state() != "FLAT":
    raise RuntimeError(
        "Preparation resulted in RECONCILE_REQUIRED. "
        "Do NOT integrate automatic trading until this is investigated."
    )

print()
print("=" * 72)
print("FINAL")
print("=" * 72)

print("SYMBOL:", trader.symbol)
print("ACTIVE SYMBOL:", trader.active_symbol)
print("BASE ASSET:", trader.base_asset)
print("QUOTE ASSET:", trader.quote_asset)
print("STATE:", trader.state())

print()
print("ORDERS CREATED: NO")
print("BUY EXECUTED:   NO")
print("SELL EXECUTED:  NO")
print("OCO CREATED:    NO")
print("LIVE TRADING:   NO")

print()
print("SELECTED SYMBOL PREPARATION TEST: PASS")

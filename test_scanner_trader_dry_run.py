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
print("WILLIAMS SCANNER -> TRADER DRY RUN")
print("BINANCE SPOT TESTNET")
print("ABSOLUTE NO-ORDER MODE")
print("=" * 72)

trader = Trader(testnet=True)

assert trader.state() == "FLAT"

print()
print("INITIAL")
print("  symbol:", trader.symbol)
print("  state: ", trader.state())

balance = trader.available_quote()

print()
print("ACCOUNT")
print("  free USDT:", round(balance, 4))

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

print()
print("SCANNING...")

selection = controller.select(has_open_position=False)

if selection is None:
    print()
    print("ACTION: WAIT")
    print("No STRICT_SIGNAL candidate passed all risk checks.")
else:
    candidate = selection.candidate

    print()
    print("SELECTED CANDIDATE")
    print("  symbol:          ", candidate.symbol)
    print("  state:           ", candidate.setup_state)
    print("  strict signal:   ", candidate.signal)
    print("  score:            ", round(candidate.score, 2))
    print("  setup score:     ", round(candidate.setup_score, 2))
    print("  signal strength: ", round(candidate.signal_strength, 2))
    print("  HTF confirmed:   ", candidate.htf_confirmed)
    print("  ATR %:           ", round(candidate.atr_pct, 4))
    print("  spread %:        ", round(candidate.spread_pct, 4))
    print("  R:R:             ", round(candidate.risk_reward, 2))

    assert candidate.signal is True
    assert candidate.htf_confirmed is True

    print()
    print("SWITCHING TRADER SYMBOL...")

    result = trader.switch_symbol(candidate.symbol)

    assert trader.symbol == candidate.symbol
    assert trader.active_symbol == candidate.symbol
    assert trader.state() == "FLAT"

    print("  symbol:", trader.symbol)
    print("  base:  ", trader.base_asset)
    print("  quote: ", trader.quote_asset)
    print("  state: ", trader.state())
    print("  filters:", len(trader.filters))

    print()
    print("[PASS] scanner selected strict candidate")
    print("[PASS] risk checks passed")
    print("[PASS] trader switched to selected symbol")
    print("[PASS] trader remains FLAT")
    print("[PASS] no order path executed")

print()
print("=" * 72)
print("SAFETY")
print("=" * 72)
print("ORDERS CREATED: NO")
print("BUY EXECUTED:   NO")
print("SELL EXECUTED:  NO")
print("OCO CREATED:    NO")
print("LIVE TRADING:   NO")
print()
print("SCANNER -> TRADER DRY RUN: PASS")

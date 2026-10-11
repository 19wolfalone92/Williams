import os
import tempfile
# TEST SAFETY: never use production DB
from test_db_guard import assert_test_db_safe
_test_db_fd, _test_db_path = tempfile.mkstemp(
    prefix="williams_test_",
    suffix=".sqlite3"
)
os.close(_test_db_fd)
os.environ["WILLIAMS_TEST_DB"] = _test_db_path
os.environ["WILLIAMS_DB_PATH"] = _test_db_path
assert_test_db_safe()

from trader import Trader
from binance_client import BinanceSpotClient
from binance_rules import SymbolRules

class FakeClient(BinanceSpotClient):
    testnet=True
    def __init__(self):
        self.order_calls = 0
    def decimal_format(self,x): return f"{x:.8f}"
    def decimal_floor(self,x,step):
        x=float(x)
        step=float(step)
        if step <= 0:
            raise ValueError("step must be > 0")
        return float(f"{(int(x/step)*step):.8f}")
    def order(self,symbol,side,otype,**kw):
        self.order_calls += 1
        print("[FAKE] BUY",symbol,side,otype,kw)
        return {"orderId":"1001","status":"FILLED","executedQty":"0.01","cummulativeQuoteQty":"100.0"}
    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        return {"orderId": order_id or "1001", "status": "FILLED", "executedQty": "0.01", "cummulativeQuoteQty": "100.0"}
    def create_oco_sell(self,*a,**kw):
        print("[FAKE] OCO -> FAILURE")
        raise RuntimeError("SIMULATED OCO FAILURE")
    def open_orders(self,*a,**kw): return []
    def open_order_lists(self,*a,**kw): return []
    def all_order_lists(self,*a,**kw): return []
    def all_orders(self,*a,**kw): return [{"orderId":"1001","status":"FILLED","side":"BUY","executedQty":"0.01","cummulativeQuoteQty":"100.0"}]
    def account(self):
        return {"balances":[{"asset":"BTC","free":"0.01","locked":"0"},{"asset":"USDT","free":"9900","locked":"0"}]}

    def exchange_info(self,symbol):
        return {
            "symbols": [{
                "symbol": "BTCUSDT",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "status": "TRADING",
                "filters": [
                    {"filterType": "LOT_SIZE", "minQty": "0.00001", "stepSize": "0.00001"},
                    {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "5"}
                ]
            }]
        }

os.environ["MAX_OPEN_POSITIONS"] = "1"
t=Trader(testnet=True)
# Legacy single-symbol mutations are intentionally contained by TASK 009.4.
# This script is an offline safety test; no real or fake order should be sent.
t.dry_run=False
t.client=FakeClient()
t.symbol="BTCUSDT"
t.active_symbol="BTCUSDT"
t.db.state_set("position_state","FLAT")

try:
    t.market_buy(100)
except RuntimeError as exc:
    assert "Legacy Trader.market_buy is disabled" in str(exc)
else:
    raise AssertionError("legacy direct BUY unexpectedly passed its execution-path gate")

assert t.client.order_calls == 0
assert t.state() == "FLAT"
print("[PASS] legacy Trader BUY path is disabled before any order submission")

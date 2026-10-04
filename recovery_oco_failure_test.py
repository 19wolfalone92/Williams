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

class FakeClient:
    testnet=True
    def decimal_format(self,x): return f"{x:.8f}"
    def decimal_floor(self,x,step):
        x=float(x)
        step=float(step)
        if step <= 0:
            raise ValueError("step must be > 0")
        return float(f"{(int(x/step)*step):.8f}")
    def order(self,symbol,side,otype,**kw):
        print("[FAKE] BUY",symbol,side,otype,kw)
        return {"orderId":"1001","status":"FILLED","executedQty":"0.01","cummulativeQuoteQty":"100.0"}
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

t=Trader(testnet=True)
# TEST ONLY: FakeClient simulation, no real exchange.
t.dry_run=False
t.client=FakeClient()
t.symbol="BTCUSDT"
t.active_symbol="BTCUSDT"
t.filters={"LOT_SIZE":{"minQty":"0.00001","stepSize":"0.00001"},"PRICE_FILTER":{"tickSize":"0.01"},"NOTIONAL":{"minNotional":"5"}}
t.foreign_base_balance=0
t.db.state_set("position_state","FLAT")

# Simulate the baseline that the real application persists
# before opening a managed position.
t.db.state_set("foreign_base_balance:BTCUSDT", 0.0)

print("1) BUY")
order,qty,entry=t.market_buy(100)
assert qty>0 and entry>0
print("[PASS] BUY FILLED:",qty,entry)
assert t.state()=="ENTRY_PENDING"

print("2) SAVE TRADE")
tid=t.db.save_trade(symbol="BTCUSDT",entry_price=entry,quantity=qty,entry_order_id=str(order["orderId"]))
assert tid
print("[PASS] trade_id =",tid)

print("3) OCO FAILURE")
try:
    t.place_oco(qty,entry,trade_id=tid)
except Exception as e:
    print("[EXPECTED]",e)

assert t.state()=="EXIT_PENDING"
print("[PASS] state = EXIT_PENDING")

print("4) RESTART")
t2=Trader(testnet=True)
# TEST ONLY: FakeClient simulation, no real exchange.
t2.dry_run=False
t2.client=t.client
t2.symbol="BTCUSDT"
t2.active_symbol="BTCUSDT"
t2.filters=t.filters
# Baseline is persisted in the test SQLite DB,
# so a new Trader instance can recover it after restart.
t2.recover_state()

state=t2.state()
trade=t2.db.open_trade("BTCUSDT")
print("[INFO] RECOVERY STATE =",state)
print("[INFO] TRADE =",trade)

assert trade is not None
assert state == "RECONCILE_REQUIRED"
assert state!="OPEN"
assert state!="FLAT"

print()
print("================================")
print("BUY -> OCO FAILURE -> RECOVERY")
print("PASS")
print("STATE:",state)
print("TRADE PRESERVED: YES")
print("HARD RECOVERY BLOCK: YES")
print("================================")

from decimal import Decimal

from binance_rules import OrderMath, SymbolRules
from preflight_gate import PreflightCheckService


def _rules():
    return SymbolRules(
        symbol="TESTUSDT",
        base_asset="TEST",
        quote_asset="USDT",
        min_qty=Decimal("0.001"),
        max_qty=Decimal("1000"),
        step_size=Decimal("0.001"),
        min_price=Decimal("0.01"),
        max_price=Decimal("100000"),
        tick_size=Decimal("0.01"),
        min_notional=Decimal("5"),
    )


def test_quantity_is_floored_to_step():
    assert OrderMath.normalize_quantity(Decimal("1.2349"), _rules()) == Decimal("1.234")


def test_price_uses_tick():
    assert OrderMath.normalize_price(Decimal("1.235"), _rules()) == Decimal("1.24")


def test_stop_price_is_floored():
    assert OrderMath.safe_stop_price(Decimal("1.239"), _rules()) == Decimal("1.23")


def test_notional_buffer_blocks_borderline_order():
    rules = _rules()
    assert not OrderMath.validate_notional("1", "5.01", rules, Decimal("10"))
    assert OrderMath.validate_notional("1", "5.50", rules, Decimal("10"))


def test_oco_quantity_uses_less_of_fill_and_free_balance():
    assert OrderMath.oco_quantity("10.000", "9.9994", _rules()) == Decimal("9.999")


def test_api_permissions_require_withdrawals_disabled():
    good = {
        "enableReading": True,
        "enableSpotAndMarginTrading": True,
        "enableWithdrawals": False,
    }
    bad = dict(good, enableWithdrawals=True)
    assert PreflightCheckService.verify_api_permissions(good)
    assert not PreflightCheckService.verify_api_permissions(bad)


def test_unknown_orders_block():
    known, unknown = PreflightCheckService.classify_open_orders(
        [{"clientOrderId": "WILLV4_OCO_123"}],
        [{"listClientOrderId": "FOREIGN_123"}],
    )
    assert len(known) == 1
    assert len(unknown) == 1


class FakeClient:
    testnet = False
    api_key = "k"
    api_secret = "s"
    time_offset_ms = 50

    def sync_time(self):
        return {"serverTime": 1000}

    def ping(self):
        return {}

    def api_restrictions(self):
        return {
            "enableReading": True,
            "enableSpotAndMarginTrading": True,
            "enableWithdrawals": False,
        }

    def api_trading_status(self):
        return {"data": {"isLocked": False, "plannedRecoverTime": 0}}

    def account(self):
        return {"status": "TRADING", "accountType": "SPOT", "balances": []}

    def exchange_info(self, symbol):
        return {
            "symbols": [{
                "symbol": symbol,
                "status": "TRADING",
                "filters": [
                    {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                    {"filterType": "LOT_SIZE", "minQty": "0.001", "stepSize": "0.001"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "5"},
                ],
            }]
        }

    def open_orders(self):
        return []

    def open_order_lists(self):
        return []


def test_live_preflight_passes_clean_account():
    report = PreflightCheckService(
        FakeClient(),
        symbols=["BTCUSDT"],
        max_open_positions=1,
    ).verify_all()
    assert report["ready"] is True
    assert report["checks"]["api_restrictions"] == "PASS"
    assert report["checks"]["api_trading_status"] == "PASS"


def test_live_preflight_blocks_withdrawals():
    client = FakeClient()
    client.api_restrictions = lambda: {
        "enableReading": True,
        "enableSpotAndMarginTrading": True,
        "enableWithdrawals": True,
    }
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["api_restrictions"] == "FAIL"


def test_live_preflight_blocks_foreign_order():
    client = FakeClient()
    client.open_orders = lambda: [{"clientOrderId": "FOREIGN"}]
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["open_orders"] == "FAIL"

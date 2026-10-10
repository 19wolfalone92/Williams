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
    testnet = True
    base_url = "https://testnet.binance.vision"
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


def test_testnet_preflight_keeps_unknown_runtime_proofs_blocked():
    report = PreflightCheckService(
        FakeClient(),
        symbols=["BTCUSDT"],
        max_open_positions=1,
    ).verify_all()
    assert report["ready"] is False
    assert report["checks"]["environment"] == "PASS"
    assert report["checks"]["api_restrictions"] == "PASS"
    assert report["checks"]["api_trading_status"] == "PASS"
    assert report["checks"]["candles"] == "UNKNOWN"
    assert report["product"] == "SPOT"
    assert isinstance(report["checked_at_ms"], int)


def test_testnet_preflight_blocks_withdrawals():
    client = FakeClient()
    client.api_restrictions = lambda: {
        "enableReading": True,
        "enableSpotAndMarginTrading": True,
        "enableWithdrawals": True,
    }
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["api_restrictions"] == "FAIL"


def test_testnet_preflight_blocks_foreign_order():
    client = FakeClient()
    client.open_orders = lambda: [{"clientOrderId": "FOREIGN"}]
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["open_orders"] == "FAIL"



def test_preflight_does_not_invent_clock_offset_budget(monkeypatch):
    monkeypatch.delenv("LIVE_MAX_TIME_OFFSET_MS", raising=False)
    report = PreflightCheckService(FakeClient(), symbols=["BTCUSDT"]).verify_all()
    assert report["checks"]["server_time"] == "UNKNOWN"
    assert report["details"]["server_time"]["reason"] == "clock_offset_budget_unapproved"
    assert report["ready"] is False


def test_explicit_clock_offset_budget_is_required_for_server_time_pass(monkeypatch):
    monkeypatch.delenv("LIVE_MAX_TIME_OFFSET_MS", raising=False)
    report = PreflightCheckService(
        FakeClient(), symbols=["BTCUSDT"], max_offset_ms=100
    ).verify_all()
    assert report["checks"]["server_time"] == "PASS"
    assert report["ready"] is False  # Other unimplemented runtime proofs still block.


def test_known_orders_cannot_be_marked_reconciled_by_boolean_override():
    client = FakeClient()
    client.open_orders = lambda: [{"clientOrderId": "WILLV4_ENTRY_123"}]
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all(
        allow_reconciled_orders=True
    )
    assert report["checks"]["open_orders"] == "FAIL"
    assert report["details"]["open_orders"]["caller_override_ignored"] is True
    assert report["requires_reconciliation"] is True
    assert report["ready"] is False

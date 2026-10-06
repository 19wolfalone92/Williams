"""Blocking preflight checks for Binance Spot trading.

The gate never mutates exchange state. Any unknown/ambiguous state blocks
trading until reconciliation is performed.
"""

import os
from dataclasses import dataclass, field
from typing import Any


@dataclass
class PreflightReport:
    ready: bool = False
    environment: str = "UNKNOWN"
    checks: dict[str, str] = field(default_factory=dict)
    details: dict[str, Any] = field(default_factory=dict)
    requires_reconciliation: bool = False

    def fail(self, name: str, reason: str, **details):
        self.checks[name] = "FAIL"
        self.details[name] = {"reason": reason, **details}
        self.ready = False

    def passed(self, name: str, **details):
        self.checks[name] = "PASS"
        if details:
            self.details[name] = details

    def as_dict(self):
        return {
            "ready": self.ready,
            "environment": self.environment,
            "checks": dict(self.checks),
            "details": dict(self.details),
            "requires_reconciliation": self.requires_reconciliation,
        }


class PreflightCheckService:
    ENTRY_PREFIX = "WILLV4_ENTRY_"
    OCO_PREFIX = "WILLV4_OCO_"

    def __init__(self, client, *, symbols=None, max_open_positions=1, max_offset_ms=None):
        self.client = client
        self.symbols = [str(x).upper() for x in (symbols or []) if str(x).strip()]
        self.max_open_positions = int(max_open_positions)
        self.max_offset_ms = int(
            max_offset_ms if max_offset_ms is not None
            else os.getenv("LIVE_MAX_TIME_OFFSET_MS", "1000")
        )

    @staticmethod
    def verify_api_permissions(restrictions_data):
        return (
            bool(restrictions_data.get("enableReading", False))
            and bool(restrictions_data.get("enableSpotAndMarginTrading", False))
            and not bool(restrictions_data.get("enableWithdrawals", True))
        )

    @classmethod
    def classify_open_orders(cls, orders, order_lists=None):
        known, unknown = [], []
        for order in orders or []:
            client_id = str(order.get("clientOrderId") or order.get("origClientOrderId") or "")
            (known if client_id.startswith((cls.ENTRY_PREFIX, cls.OCO_PREFIX)) else unknown).append(order)
        for row in order_lists or []:
            client_id = str(row.get("listClientOrderId") or row.get("origClientOrderId") or "")
            (known if client_id.startswith(cls.OCO_PREFIX) else unknown).append(row)
        return known, unknown

    def _check_time(self, report):
        server = self.client.sync_time()
        offset = int(getattr(self.client, "time_offset_ms", 0))
        if abs(offset) > self.max_offset_ms:
            report.fail(
                "server_time",
                "clock offset exceeds safety limit",
                offset_ms=offset,
                max_offset_ms=self.max_offset_ms,
            )
            return False
        report.passed(
            "server_time",
            server_time_ms=int(server["serverTime"]),
            offset_ms=offset,
            max_offset_ms=self.max_offset_ms,
        )
        return True

    def _check_account(self, report):
        account = self.client.account()
        status = str(account.get("status", "TRADING")).upper()
        account_type = account.get("accountType")
        if status != "TRADING":
            report.fail("account", "account status is not TRADING", status=status)
            return False
        if account_type not in (None, "SPOT"):
            report.fail("account", "unexpected account type", account_type=account_type)
            return False
        report.passed("account", status=status, account_type=account_type or "SPOT")
        return True

    def _check_symbols(self, report):
        symbols = self.symbols or [os.getenv("SYMBOL", "BTCUSDT").upper()]
        for symbol in symbols:
            info = self.client.exchange_info(symbol)
            rows = info.get("symbols", [])
            if not rows:
                report.fail("exchange_info", "symbol metadata unavailable", symbol=symbol)
                return False
            row = rows[0]
            if row.get("status") != "TRADING":
                report.fail("exchange_info", "symbol is not TRADING", symbol=symbol, status=row.get("status"))
                return False
            filters = {f.get("filterType") for f in row.get("filters", [])}
            if "PRICE_FILTER" not in filters or not ({"LOT_SIZE", "MARKET_LOT_SIZE"} & filters):
                report.fail("exchange_filters", "required quantity/price filters missing", symbol=symbol)
                return False
            if not ({"MIN_NOTIONAL", "NOTIONAL"} & filters):
                report.fail("exchange_filters", "notional filter missing", symbol=symbol)
                return False
        report.passed("exchange_info", symbols=symbols)
        report.passed("exchange_filters", symbols=symbols)
        return True

    def _check_orders(self, report):
        orders = self.client.open_orders()
        lists = self.client.open_order_lists()
        known, unknown = self.classify_open_orders(orders, lists)
        report.details["open_orders"] = {
            "known_count": len(known),
            "unknown_count": len(unknown),
            "known": known,
            "unknown": unknown,
        }
        if unknown:
            report.fail("open_orders", "unknown open orders exist", count=len(unknown))
            return False
        if known:
            report.requires_reconciliation = True
            report.fail("open_orders", "known Williams orders require reconciliation", count=len(known))
            return False
        report.passed("open_orders")
        return True

    def verify_all(self):
        report = PreflightReport(
            environment="TESTNET" if self.client.testnet else "LIVE"
        )
        if not self.client.api_key or not self.client.api_secret:
            report.fail("credentials", "Binance API credentials are missing")
            return report.as_dict()

        if not self._check_time(report):
            return report.as_dict()

        try:
            self.client.ping()
            report.passed("api_reachable")
        except Exception as exc:
            report.fail("api_reachable", str(exc))
            return report.as_dict()

        if not self.client.testnet:
            try:
                restrictions = self.client.api_restrictions()
            except Exception as exc:
                report.fail("api_restrictions", str(exc))
                return report.as_dict()
            if not self.verify_api_permissions(restrictions):
                report.fail(
                    "api_restrictions",
                    "API key must allow reading + Spot trading and must have withdrawals disabled",
                    restrictions={
                        key: restrictions.get(key)
                        for key in (
                            "enableReading",
                            "enableSpotAndMarginTrading",
                            "enableWithdrawals",
                        )
                    },
                )
                return report.as_dict()
            report.passed("api_restrictions")

        if not self._check_account(report):
            return report.as_dict()
        if not self._check_symbols(report):
            return report.as_dict()
        if not self._check_orders(report):
            return report.as_dict()

        if self.max_open_positions != 1:
            report.fail(
                "max_open_positions",
                "automatic scanner requires exactly one position",
                configured=self.max_open_positions,
            )
            return report.as_dict()

        report.passed("max_open_positions", configured=self.max_open_positions)
        report.ready = True
        return report.as_dict()

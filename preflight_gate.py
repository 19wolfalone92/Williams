"""Blocking preflight checks for Binance Spot trading.

The gate never mutates exchange state. Any unknown/ambiguous state blocks
trading until reconciliation is performed.
"""

import os
import time
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
            "product": "SPOT",
            "checked_at_ms": int(time.time() * 1000),
            "checks": dict(self.checks),
            "details": dict(self.details),
            "requires_reconciliation": self.requires_reconciliation,
        }


class PreflightCheckService:
    ENTRY_PREFIX = "WILLV4_ENTRY_"
    OCO_PREFIX = "WILLV4_OCO_"
    EMERGENCY_PREFIX = "WILLV4_EMERGENCY_"
    MANUAL_PREFIX = "WILLV4_MANUAL_"

    def __init__(self, client, *, symbols=None, max_open_positions=0, max_offset_ms=None):
        self.client = client
        self.symbols = [str(x).upper() for x in (symbols or []) if str(x).strip()]
        self.max_open_positions = int(max_open_positions)
        # No approved clock-offset budget is defined in the repository contract.
        # An explicitly supplied constructor value or deployment setting is required;
        # absence must remain UNKNOWN rather than silently defaulting to 1000 ms.
        configured_offset = max_offset_ms
        if configured_offset is None:
            configured_offset = os.getenv("LIVE_MAX_TIME_OFFSET_MS")
        self.max_offset_ms = None if configured_offset in (None, "") else int(configured_offset)

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
            (known if client_id.startswith((cls.ENTRY_PREFIX, cls.OCO_PREFIX, cls.EMERGENCY_PREFIX, cls.MANUAL_PREFIX)) else unknown).append(order)
        for row in order_lists or []:
            client_id = str(row.get("listClientOrderId") or row.get("origClientOrderId") or "")
            (known if client_id.startswith(cls.OCO_PREFIX) else unknown).append(row)
        return known, unknown

    def _check_time(self, report):
        try:
            server = self.client.sync_time()
            if not isinstance(server, dict) or "serverTime" not in server:
                report.fail("server_time", "server time response is invalid")
                return False
            server_time_ms = int(server["serverTime"])
            if server_time_ms <= 0:
                report.fail("server_time", "server time value is invalid")
                return False
            offset = int(getattr(self.client, "time_offset_ms", 0))
        except Exception as exc:
            report.fail(
                "server_time",
                "time synchronization failed",
                error=self._safe_reason(exc),
            )
            return False
        if self.max_offset_ms is None:
            report.details["server_time"] = {
                "reason": "clock_offset_budget_unapproved",
                "offset_ms": offset,
            }
            return False
        if self.max_offset_ms < 0:
            report.fail("server_time", "clock offset budget is invalid")
            return False
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
            server_time_ms=server_time_ms,
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

    def _check_orders(self, report, *, allow_known_orders=False):
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
            # A boolean caller flag is not evidence that exchange/local state was
            # reconciled. Never mark this check PASS without durable, current,
            # generation-bound reconciliation evidence from the recovery service.
            report.fail(
                "open_orders",
                "known Williams orders require verified durable reconciliation",
                count=len(known),
                caller_override_ignored=bool(allow_known_orders),
            )
            return False
        report.passed("open_orders")
        return True

    REQUIRED_CHECKS = (
        "environment", "api_reachable", "server_time", "credentials",
        "authentication", "account", "balances", "api_restrictions",
        "api_trading_status", "exchange_info",
        "exchange_filters", "candles", "market_websocket",
        "user_data_stream", "scanner_heartbeat", "market_context",
        "diagnostics_freshness", "storage_integrity", "reconciliation",
        "execution_state_known", "open_orders", "android_freshness",
    )

    @staticmethod
    def _safe_reason(exc):
        # Do not serialize raw exception text: requests exceptions may contain
        # signed URLs, query parameters, or other request material.
        status = getattr(getattr(exc, "response", None), "status_code", None)
        code = getattr(exc, "code", None)
        kind = type(exc).__name__
        bits = [kind]
        if status is not None:
            bits.append("http_status=" + str(int(status)))
        if code is not None and isinstance(code, (str, int)):
            bits.append("error_code=" + str(code)[:40])
        return "; ".join(bits)

    def verify_all(self, *, allow_reconciled_orders=False):
        # Every required condition starts UNKNOWN. Only a check performed by
        # this method may replace UNKNOWN with PASS. Unimplemented runtime
        # proofs deliberately remain UNKNOWN and therefore cannot enable entry.
        report = PreflightReport(
            environment="TESTNET" if getattr(self.client, "testnet", None) is True else "LIVE"
            if getattr(self.client, "testnet", None) is False else "UNKNOWN"
        )
        for name in self.REQUIRED_CHECKS:
            report.checks[name] = "UNKNOWN"
            report.details[name] = {"reason": "required proof not performed"}

        if report.environment != "TESTNET" or getattr(self.client, "base_url", None) != "https://testnet.binance.vision":
            report.fail("environment", "Binance Spot Testnet host is not explicitly selected")
            return report.as_dict()
        report.passed("environment", host="https://testnet.binance.vision", product="SPOT")
        if not getattr(self.client, "api_key", None) or not getattr(self.client, "api_secret", None):
            report.fail("credentials", "Binance API credentials are missing")
            return report.as_dict()
        report.passed("credentials", present=True)

        try:
            server = self.client.sync_time()
            offset = int(getattr(self.client, "time_offset_ms", 0))
            if not isinstance(server, dict) or "serverTime" not in server:
                report.fail("server_time", "server time response is invalid")
                return report.as_dict()
            if self.max_offset_ms is None:
                # A synchronized clock alone is not proof that skew is within an
                # approved execution budget. Leave the check UNKNOWN and block.
                report.details["server_time"] = {
                    "reason": "clock_offset_budget_unapproved",
                    "offset_ms": offset,
                }
                return report.as_dict()
            if self.max_offset_ms < 0:
                report.fail("server_time", "clock offset budget is invalid")
                return report.as_dict()
            if abs(offset) > self.max_offset_ms:
                report.fail("server_time", "clock offset exceeds safety limit",
                            offset_ms=offset, max_offset_ms=self.max_offset_ms)
                return report.as_dict()
            report.passed("server_time", offset_ms=offset, max_offset_ms=self.max_offset_ms)
        except Exception as exc:
            report.fail("server_time", "time synchronization failed",
                        error=self._safe_reason(exc))
            return report.as_dict()

        try:
            self.client.ping()
            report.passed("api_reachable")
        except Exception as exc:
            report.fail("api_reachable", "REST connectivity check failed",
                        error=self._safe_reason(exc))
            return report.as_dict()

        # Spot Testnet authenticated account read is required. This is the
        # authentication proof; failures are sanitized and fail closed.
        try:
            account = self.client.account()
            if not isinstance(account, dict):
                report.fail("authentication", "account response is invalid")
                return report.as_dict()
            report.passed("authentication")
            status = str(account.get("status", "TRADING")).upper()
            account_type = account.get("accountType")
            if status != "TRADING" or account_type not in (None, "SPOT"):
                report.fail("account", "account status/type is not acceptable",
                            status=status, account_type=account_type)
                return report.as_dict()
            balances = account.get("balances")
            if not isinstance(balances, list):
                report.fail("balances", "account balances are missing or malformed")
                return report.as_dict()
            report.passed("account", status=status, account_type=account_type or "SPOT")
            report.passed("balances", count=len(balances))
        except Exception as exc:
            report.fail("authentication", "authenticated account read failed",
                        error=self._safe_reason(exc))
            report.fail("account", "account state could not be confirmed")
            report.fail("balances", "balances could not be confirmed")
            return report.as_dict()

        restrictions_reader = getattr(self.client, "api_restrictions", None)
        if callable(restrictions_reader):
            try:
                restrictions = restrictions_reader()
                if not isinstance(restrictions, dict) or not self.verify_api_permissions(restrictions):
                    report.fail("api_restrictions", "API permissions are unsafe or malformed")
                else:
                    report.passed("api_restrictions", withdrawals_disabled=True)
            except Exception as exc:
                report.fail("api_restrictions", "API permission check failed", error=self._safe_reason(exc))

        trading_status_reader = getattr(self.client, "api_trading_status", None)
        if callable(trading_status_reader):
            try:
                trading_status = trading_status_reader()
                data = trading_status.get("data", trading_status) if isinstance(trading_status, dict) else None
                if not isinstance(data, dict):
                    report.fail("api_trading_status", "trading status response is malformed")
                elif bool(data.get("isLocked", True)) or int(data.get("plannedRecoverTime", 0) or 0) > int(time.time() * 1000):
                    report.fail("api_trading_status", "account trading is locked or under recovery")
                else:
                    report.passed("api_trading_status", is_locked=False)
            except Exception as exc:
                report.fail("api_trading_status", "trading status check failed", error=self._safe_reason(exc))

        try:
            if not self._check_symbols(report):
                return report.as_dict()
        except Exception as exc:
            report.fail("exchange_info", "symbol metadata check failed",
                        error=self._safe_reason(exc))
            report.fail("exchange_filters", "symbol filters could not be confirmed")
            return report.as_dict()

        # The current architecture does not yet provide verifiable freshness
        # proofs for these runtime components. Keep them UNKNOWN instead of
        # manufacturing PASS from method existence or cached status fields.
        try:
            if not self._check_orders(report, allow_known_orders=allow_reconciled_orders):
                return report.as_dict()
        except Exception as exc:
            report.fail("open_orders", "open-order reconciliation read failed",
                        error=self._safe_reason(exc))
            return report.as_dict()

        # Account access alone does not prove candles, market/user WebSocket
        # freshness, scanner/context/diagnostic heartbeats, SQLite↔Binance
        # reconciliation, known execution state, or Android freshness.
        missing = [name for name in self.REQUIRED_CHECKS
                   if report.checks.get(name) != "PASS"]
        report.ready = not missing and not report.requires_reconciliation
        if missing:
            report.details["readiness"] = {
                "reason": "required checks are not all PASS",
                "blocking_checks": missing,
            }
        if report.requires_reconciliation:
            report.ready = False
            report.details["readiness"] = {
                "reason": "reconciliation is required before new entries"
            }
        return report.as_dict()

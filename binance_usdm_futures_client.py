"""Safety-oriented Binance USDⓈ-M Futures REST adapter.

This module deliberately does not contain Williams strategy logic. New exposure,
protection and exit methods are explicit so SELL-to-close can never be confused
with a short-entry signal.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import time
from decimal import Decimal, InvalidOperation, ROUND_DOWN, ROUND_UP
from typing import Any, Mapping
from urllib.parse import urlencode

import requests


class FuturesAPIError(RuntimeError):
    """Binance Futures API failure with conservative mutation uncertainty."""

    def __init__(
        self,
        message: str,
        *,
        unknown_execution: bool = False,
        status_code: int | None = None,
        payload: Any = None,
    ) -> None:
        super().__init__(message)
        self.unknown_execution = bool(unknown_execution)
        self.status_code = status_code
        self.payload = payload


class BinanceUsdmFuturesClient:
    """USDⓈ-M Futures adapter with Testnet-first safety defaults.

    Signed POST/DELETE requests are never blindly retried. Mainnet construction
    requires both allow_live=True and ALLOW_LIVE=true.
    """

    DEMO_BASE_URL = "https://demo-fapi.binance.com"
    LIVE_BASE_URL = "https://fapi.binance.com"
    is_usdm_futures = True

    def __init__(
        self,
        api_key: str,
        api_secret: str,
        *,
        testnet: bool = True,
        allow_live: bool = False,
        max_leverage: int = 1,
        recv_window: int = 5000,
        timeout: float = 15.0,
        session: requests.Session | None = None,
    ) -> None:
        if not str(api_key).strip() or not str(api_secret).strip():
            raise ValueError("Binance USDⓈ-M Futures API key and secret are required")
        if not testnet and not (
            allow_live and os.getenv("ALLOW_LIVE", "").strip().lower() == "true"
        ):
            raise ValueError(
                "Mainnet futures is disabled: require allow_live=True and ALLOW_LIVE=true"
            )
        if int(max_leverage) != 1:
            raise ValueError("Initial Williams futures build is capped at 1x leverage")
        if not 1000 <= int(recv_window) <= 60000:
            raise ValueError("recv_window must be in [1000, 60000]")

        self.api_key = str(api_key).strip()
        self.api_secret = str(api_secret).strip()
        self.testnet = bool(testnet)
        self.base_url = self.DEMO_BASE_URL if self.testnet else self.LIVE_BASE_URL
        self.max_leverage = int(max_leverage)
        self.recv_window = int(recv_window)
        self.timeout = float(timeout)
        self.session = session or requests.Session()
        self.session.headers.update({"X-MBX-APIKEY": self.api_key})
        self.time_offset_ms = 0

    @staticmethod
    def _is_mutation(method: str) -> bool:
        return str(method).upper() in {"POST", "PUT", "DELETE"}

    @staticmethod
    def _payload(response: requests.Response) -> Any:
        try:
            return response.json()
        except ValueError as exc:
            raise FuturesAPIError(
                "Binance Futures returned a non-JSON response",
                unknown_execution=response.request.method.upper() in {"POST", "PUT", "DELETE"},
                status_code=response.status_code,
                payload={"body": response.text[:500]},
            ) from exc

    def _request(
        self,
        method: str,
        path: str,
        params: Mapping[str, Any] | None = None,
        *,
        signed: bool = False,
    ) -> Any:
        method = str(method).upper()
        mutation = self._is_mutation(method)
        safe_get_attempts = 3 if method == "GET" else 1
        attempt = 0
        time_resync_used = False

        while attempt < safe_get_attempts:
            attempt += 1
            payload = dict(params or {})
            if signed:
                payload.setdefault("recvWindow", self.recv_window)
                payload["timestamp"] = int(time.time() * 1000) + self.time_offset_ms
                canonical = urlencode(payload, doseq=True)
                payload["signature"] = hmac.new(
                    self.api_secret.encode("utf-8"),
                    canonical.encode("utf-8"),
                    hashlib.sha256,
                ).hexdigest()

            try:
                response = self.session.request(
                    method,
                    self.base_url + path,
                    params=payload,
                    timeout=self.timeout,
                )
            except requests.RequestException as exc:
                if method == "GET" and attempt < safe_get_attempts:
                    time.sleep(min(0.25 * (2 ** (attempt - 1)), 1.0))
                    continue
                raise FuturesAPIError(
                    f"Binance Futures transport failure for {method} {path}; "
                    "mutation outcome must be reconciled before retry",
                    unknown_execution=mutation,
                ) from exc

            try:
                result = response.json()
            except ValueError:
                result = {
                    "code": response.status_code,
                    "msg": response.text[:500],
                }
                if response.status_code < 400 and mutation:
                    raise FuturesAPIError(
                        f"Non-JSON success response for mutation {path}; outcome unknown",
                        unknown_execution=True,
                        status_code=response.status_code,
                        payload=result,
                    )

            # Binance explicitly rejects an invalid timestamp before order
            # admission. One server-time resync and one retry are safe.
            if (
                signed
                and not time_resync_used
                and isinstance(result, dict)
                and result.get("code") == -1021
                and path != "/fapi/v1/time"
            ):
                time_resync_used = True
                self.sync_time()
                attempt -= 1
                continue

            status = int(response.status_code)
            api_error = (
                isinstance(result, dict)
                and isinstance(result.get("code"), (int, float))
                and int(result.get("code", 0)) < 0
            )
            if status >= 400 or api_error:
                uncertain = mutation and (
                    status >= 500 or status in {418, 429}
                )
                if method == "GET" and status in {418, 429, 500, 502, 503, 504} and attempt < safe_get_attempts:
                    time.sleep(min(0.25 * (2 ** (attempt - 1)), 1.0))
                    continue
                raise FuturesAPIError(
                    f"Binance Futures {status}: {result}",
                    unknown_execution=uncertain,
                    status_code=status,
                    payload=result,
                )
            return result

        raise FuturesAPIError(f"Binance Futures request exhausted retries: {method} {path}")

    def sync_time(self) -> dict[str, Any]:
        server = self._request("GET", "/fapi/v1/time")
        server_time = int(server["serverTime"])
        self.time_offset_ms = server_time - int(time.time() * 1000)
        return server

    def ping(self) -> Any:
        return self._request("GET", "/fapi/v1/ping")

    def exchange_info(self, symbol: str | None = None) -> dict[str, Any]:
        info = self._request("GET", "/fapi/v1/exchangeInfo")
        if symbol is None:
            return info
        wanted = str(symbol).upper()
        rows = [
            row for row in info.get("symbols", [])
            if str(row.get("symbol", "")).upper() == wanted
        ]
        if not rows:
            raise FuturesAPIError(f"Unknown USDⓈ-M Futures symbol: {wanted}")
        return {"symbols": rows, "timezone": info.get("timezone")}

    def symbol_filters(self, symbol: str) -> dict[str, dict[str, Any]]:
        payload = self.exchange_info(symbol)
        row = payload["symbols"][0]
        if str(row.get("status", "")).upper() != "TRADING":
            raise FuturesAPIError(f"{symbol}: futures contract is not TRADING")
        return {
            str(item.get("filterType", "")).upper(): item
            for item in row.get("filters", [])
        }

    def ticker_price(self, symbol: str) -> dict[str, Any]:
        return self._request("GET", "/fapi/v1/ticker/price", {"symbol": str(symbol).upper()})

    def book_ticker(self, symbol: str | None = None) -> Any:
        params = {"symbol": str(symbol).upper()} if symbol else {}
        return self._request("GET", "/fapi/v1/ticker/bookTicker", params)

    def ticker_24hr(self, symbol: str | None = None) -> Any:
        params = {"symbol": str(symbol).upper()} if symbol else {}
        return self._request("GET", "/fapi/v1/ticker/24hr", params)

    def mark_price(self, symbol: str) -> dict[str, Any]:
        return self._request("GET", "/fapi/v1/premiumIndex", {"symbol": str(symbol).upper()})

    def klines(self, symbol: str, interval: str, limit: int = 500, **kwargs: Any) -> Any:
        if not 1 <= int(limit) <= 1500:
            raise ValueError("Futures kline limit must be in [1, 1500]")
        params = {"symbol": str(symbol).upper(), "interval": str(interval), "limit": int(limit)}
        params.update(kwargs)
        return self._request("GET", "/fapi/v1/klines", params)

    def account(self) -> dict[str, Any]:
        return self._request("GET", "/fapi/v3/account", signed=True)

    def account_permissions(self) -> dict[str, Any]:
        """Read canTrade from Account Information V2, where Binance exposes it."""
        result = self._request("GET", "/fapi/v2/account", signed=True)
        if not isinstance(result, dict) or "canTrade" not in result:
            raise FuturesAPIError(
                "Binance Futures account permissions response omitted canTrade"
            )
        value = result.get("canTrade")
        if value is not True and str(value).strip().lower() != "true":
            if value is False or str(value).strip().lower() == "false":
                return {"canTrade": False}
            raise FuturesAPIError("Binance Futures canTrade permission is ambiguous")
        return {"canTrade": True}

    def balance(self) -> Any:
        return self._request("GET", "/fapi/v3/balance", signed=True)

    def position_risk(self, symbol: str | None = None) -> Any:
        params = {"symbol": str(symbol).upper()} if symbol else {}
        return self._request("GET", "/fapi/v3/positionRisk", params, signed=True)

    def symbol_configuration(self, symbol: str) -> dict[str, Any]:
        """Read margin mode/leverage from Binance's dedicated symbolConfig endpoint."""
        symbol = str(symbol).upper()
        result = self._request(
            "GET",
            "/fapi/v1/symbolConfig",
            {"symbol": symbol},
            signed=True,
        )
        if isinstance(result, list):
            if any(not isinstance(row, dict) for row in result):
                raise FuturesAPIError("Binance Futures symbolConfig contains a malformed row")
            rows = [row for row in result if str(row.get("symbol", "")).upper() == symbol]
            if len(rows) != 1:
                raise FuturesAPIError(
                    f"Binance Futures symbolConfig did not return exactly one row for {symbol}"
                )
            row = rows[0]
        elif isinstance(result, dict):
            row = result
        else:
            raise FuturesAPIError("Binance Futures symbolConfig returned a malformed payload")

        if str(row.get("symbol", "")).upper() != symbol:
            raise FuturesAPIError(f"Binance Futures symbolConfig returned a different symbol for {symbol}")
        if not str(row.get("marginType", "") or "").strip():
            raise FuturesAPIError(f"{symbol}: symbolConfig omitted marginType")
        try:
            leverage = int(row.get("leverage"))
        except (TypeError, ValueError) as exc:
            raise FuturesAPIError(f"{symbol}: symbolConfig omitted/invalid leverage") from exc
        if leverage < 1:
            raise FuturesAPIError(f"{symbol}: symbolConfig returned invalid leverage")
        return {**row, "leverage": leverage, "symbol": symbol}

    def user_trades(
        self,
        symbol: str,
        *,
        order_id: int | str | None = None,
        start_time: int | None = None,
        end_time: int | None = None,
        limit: int = 1000,
    ) -> list[dict[str, Any]]:
        if not 1 <= int(limit) <= 1000:
            raise ValueError("user_trades limit must be in [1, 1000]")
        params: dict[str, Any] = {
            "symbol": str(symbol).upper(),
            "limit": int(limit),
        }
        if order_id is not None:
            params["orderId"] = order_id
        if start_time is not None:
            params["startTime"] = int(start_time)
        if end_time is not None:
            params["endTime"] = int(end_time)
        result = self._request("GET", "/fapi/v1/userTrades", params, signed=True)
        if isinstance(result, list):
            if any(not isinstance(row, dict) for row in result):
                raise FuturesAPIError("Binance Futures userTrades contains a malformed row")
            return result
        if isinstance(result, dict) and isinstance(result.get("trades"), list):
            rows = result["trades"]
            if any(not isinstance(row, dict) for row in rows):
                raise FuturesAPIError("Binance Futures userTrades contains a malformed row")
            return rows
        raise FuturesAPIError("Binance Futures userTrades response is not a list")

    def ensure_one_way_mode(self) -> dict[str, Any]:
        mode = self._request("GET", "/fapi/v1/positionSide/dual", signed=True)
        if not isinstance(mode, dict) or "dualSidePosition" not in mode:
            raise FuturesAPIError(
                "Futures position mode response is malformed; one-way mode is not confirmed"
            )
        raw = mode.get("dualSidePosition")
        normalized = str(raw).strip().lower()
        if raw is True or normalized in {"true", "1"}:
            raise FuturesAPIError(
                "Hedge Mode is not supported by this execution contract; "
                "disable new exposure and reconcile account mode manually"
            )
        if raw is not False and normalized not in {"false", "0"}:
            raise FuturesAPIError(
                "Futures position mode is ambiguous; one-way mode is not confirmed"
            )
        return mode

    def set_leverage(self, symbol: str, leverage: int = 1) -> dict[str, Any]:
        leverage = int(leverage)
        if not 1 <= leverage <= self.max_leverage:
            raise ValueError(f"leverage must be in [1, {self.max_leverage}]")
        return self._request(
            "POST",
            "/fapi/v1/leverage",
            {"symbol": str(symbol).upper(), "leverage": leverage},
            signed=True,
        )

    def set_margin_type(self, symbol: str, margin_type: str = "ISOLATED") -> dict[str, Any]:
        margin_type = str(margin_type).upper()
        if margin_type != "ISOLATED":
            raise ValueError("Initial Williams futures build requires ISOLATED margin")
        try:
            return self._request(
                "POST",
                "/fapi/v1/marginType",
                {"symbol": str(symbol).upper(), "marginType": margin_type},
                signed=True,
            )
        except FuturesAPIError as exc:
            # Binance -4046 means the requested margin type is already active.
            if isinstance(exc.payload, dict) and exc.payload.get("code") == -4046:
                return {"symbol": str(symbol).upper(), "marginType": margin_type, "already_set": True}
            raise

    def prepare_symbol(self, symbol: str) -> dict[str, Any]:
        """Establish and then verify the initial one-way, isolated 1x policy."""
        symbol = str(symbol).upper()
        mode = self.ensure_one_way_mode()
        margin = self.set_margin_type(symbol, "ISOLATED")
        leverage = self.set_leverage(symbol, 1)
        configuration = self.symbol_configuration(symbol)
        if str(configuration.get("marginType", "")).upper() != "ISOLATED":
            raise FuturesAPIError(f"{symbol}: symbolConfig did not confirm isolated margin")
        if int(configuration.get("leverage", 0)) != 1:
            raise FuturesAPIError(f"{symbol}: symbolConfig did not confirm 1x leverage")
        return {
            "mode": mode,
            "margin": margin,
            "leverage": leverage,
            "configuration": configuration,
        }

    @staticmethod
    def _positive_decimal(value: Any, label: str) -> Decimal:
        try:
            dec = Decimal(str(value))
        except (InvalidOperation, ValueError, TypeError) as exc:
            raise ValueError(f"{label} must be a valid decimal") from exc
        if not dec.is_finite() or dec <= 0:
            raise ValueError(f"{label} must be finite and > 0")
        return dec

    def normalize_quantity(self, symbol: str, quantity: Any, *, market: bool = True) -> str:
        qty = self._positive_decimal(quantity, "quantity")
        filters = self.symbol_filters(symbol)
        lot = filters.get("MARKET_LOT_SIZE" if market else "LOT_SIZE") or filters.get("LOT_SIZE")
        if not lot:
            raise FuturesAPIError(f"{symbol}: exchangeInfo lacks LOT_SIZE filter")
        step = self._positive_decimal(lot.get("stepSize", "0"), "stepSize")
        min_qty = self._positive_decimal(lot.get("minQty", "0"), "minQty")
        max_qty = self._positive_decimal(lot.get("maxQty", "0"), "maxQty")
        normalized = (qty / step).to_integral_value(rounding=ROUND_DOWN) * step
        if normalized < min_qty:
            raise ValueError(f"{symbol}: normalized quantity is below minQty")
        if normalized > max_qty:
            raise ValueError(f"{symbol}: normalized quantity exceeds maxQty")
        return format(normalized.normalize(), "f")

    def get_order(
        self,
        symbol: str,
        *,
        order_id: int | str | None = None,
        orig_client_order_id: str | None = None,
    ) -> dict[str, Any]:
        if order_id is None and not orig_client_order_id:
            raise ValueError("get_order requires order_id or orig_client_order_id")
        params: dict[str, Any] = {"symbol": str(symbol).upper()}
        if order_id is not None:
            params["orderId"] = order_id
        if orig_client_order_id:
            params["origClientOrderId"] = orig_client_order_id
        return self._request("GET", "/fapi/v1/order", params, signed=True)

    def get_algo_order(
        self,
        symbol: str,
        *,
        algo_id: int | str | None = None,
        client_algo_id: str | None = None,
    ) -> dict[str, Any]:
        if algo_id is None and not client_algo_id:
            raise ValueError("get_algo_order requires algo_id or client_algo_id")
        params: dict[str, Any] = {"symbol": str(symbol).upper()}
        if algo_id is not None:
            params["algoId"] = algo_id
        if client_algo_id:
            params["clientAlgoId"] = client_algo_id
        return self._request("GET", "/fapi/v1/algoOrder", params, signed=True)

    def open_orders(self, symbol: str | None = None) -> list[dict[str, Any]]:
        params = {"symbol": str(symbol).upper()} if symbol else {}
        result = self._request("GET", "/fapi/v1/openOrders", params, signed=True)
        if isinstance(result, list):
            if any(not isinstance(row, dict) for row in result):
                raise FuturesAPIError("Binance Futures openOrders contains a malformed row")
            return result
        if isinstance(result, dict) and isinstance(result.get("orders"), list):
            rows = result["orders"]
            if any(not isinstance(row, dict) for row in rows):
                raise FuturesAPIError("Binance Futures openOrders contains a malformed row")
            return rows
        if isinstance(result, dict) and result.get("orderId") is not None:
            return [result]
        raise FuturesAPIError("Binance Futures openOrders returned a malformed payload")

    def open_algo_orders(self, symbol: str | None = None) -> list[dict[str, Any]]:
        params = {"symbol": str(symbol).upper()} if symbol else {}
        result = self._request("GET", "/fapi/v1/openAlgoOrders", params, signed=True)
        if isinstance(result, list):
            if any(not isinstance(row, dict) for row in result):
                raise FuturesAPIError("Binance Futures openAlgoOrders contains a malformed row")
            return result
        if isinstance(result, dict) and isinstance(result.get("orders"), list):
            rows = result["orders"]
            if any(not isinstance(row, dict) for row in rows):
                raise FuturesAPIError("Binance Futures openAlgoOrders contains a malformed row")
            return rows
        if isinstance(result, dict) and result.get("algoId") is not None:
            return [result]
        raise FuturesAPIError("Binance Futures openAlgoOrders returned a malformed payload")

    def normalize_price(
        self,
        symbol: str,
        price: Any,
        *,
        direction: str,
        purpose: str,
    ) -> str:
        """Round price away from accidental trigger tightening.

        LONG entry trigger rounds up and LONG stop rounds down. SHORT entry
        trigger rounds down and SHORT stop rounds up.
        """
        value = self._positive_decimal(price, "price")
        direction = str(direction).upper()
        purpose = str(purpose).upper()
        if direction not in {"LONG", "SHORT"} or purpose not in {"ENTRY", "STOP"}:
            raise ValueError("direction must be LONG/SHORT and purpose ENTRY/STOP")
        filters = self.symbol_filters(symbol)
        price_filter = filters.get("PRICE_FILTER")
        if not price_filter:
            raise FuturesAPIError(f"{symbol}: exchangeInfo lacks PRICE_FILTER")
        tick = self._positive_decimal(price_filter.get("tickSize", "0"), "tickSize")
        min_price = Decimal(str(price_filter.get("minPrice", "0")))
        max_price = Decimal(str(price_filter.get("maxPrice", "0")))
        if not min_price.is_finite() or min_price < 0:
            raise FuturesAPIError(f"{symbol}: invalid PRICE_FILTER minPrice")
        if not max_price.is_finite() or max_price <= 0:
            raise FuturesAPIError(f"{symbol}: invalid PRICE_FILTER maxPrice")
        round_up = (direction == "LONG" and purpose == "ENTRY") or (
            direction == "SHORT" and purpose == "STOP"
        )
        rounding = ROUND_UP if round_up else ROUND_DOWN
        normalized = (value / tick).to_integral_value(rounding=rounding) * tick
        if normalized < min_price or normalized > max_price:
            raise ValueError(f"{symbol}: normalized price is outside PRICE_FILTER")
        return format(normalized.normalize(), "f")

    def new_order_safe(
        self,
        symbol: str,
        side: str,
        type_: str,
        *,
        quantity: Any,
        client_order_id: str,
        reduce_only: bool = False,
        price: Any | None = None,
        time_in_force: str | None = None,
    ) -> dict[str, Any]:
        if not client_order_id or len(str(client_order_id)) > 36:
            raise ValueError("client_order_id is required and must be <= 36 characters")
        side = str(side).upper()
        if side not in {"BUY", "SELL"}:
            raise ValueError("side must be BUY or SELL")
        qty = self._positive_decimal(quantity, "quantity")
        params: dict[str, Any] = {
            "symbol": str(symbol).upper(),
            "side": side,
            "type": str(type_).upper(),
            "quantity": format(qty.normalize(), "f"),
            "newClientOrderId": str(client_order_id),
            "newOrderRespType": "RESULT",
        }
        if reduce_only:
            params["reduceOnly"] = "true"
        if price is not None:
            params["price"] = str(self._positive_decimal(price, "price"))
        if time_in_force:
            params["timeInForce"] = str(time_in_force).upper()

        try:
            result = self._request("POST", "/fapi/v1/order", params, signed=True)
        except FuturesAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.get_order(
                    symbol,
                    orig_client_order_id=str(client_order_id),
                )
                if isinstance(existing, dict) and str(existing.get("status", "")).upper():
                    return existing
            except Exception as reconcile_exc:
                raise FuturesAPIError(
                    f"Market/order submission outcome UNKNOWN for {symbol}; "
                    f"clientOrderId={client_order_id}. Reconciliation is required "
                    "before retry.",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload={"submit_error": exc.payload, "reconcile_error": str(reconcile_exc)},
                ) from exc
            raise FuturesAPIError(
                f"Order submission outcome UNKNOWN for {symbol}; "
                f"clientOrderId={client_order_id}. Do not blindly retry.",
                unknown_execution=True,
                status_code=exc.status_code,
                payload=exc.payload,
            ) from exc

        status = str(result.get("status", "")).upper() if isinstance(result, dict) else ""
        if not status:
            raise FuturesAPIError(
                f"Futures order response lacks authoritative status for {client_order_id}; reconcile",
                unknown_execution=True,
                payload=result,
            )
        return result

    def new_algo_order_safe(
        self,
        symbol: str,
        side: str,
        type_: str,
        *,
        client_algo_id: str,
        trigger_price: Any,
        quantity: Any | None = None,
        close_position: bool = False,
        reduce_only: bool = False,
        working_type: str = "MARK_PRICE",
        price_protect: bool = False,
    ) -> dict[str, Any]:
        if not client_algo_id or len(str(client_algo_id)) > 36:
            raise ValueError("client_algo_id is required and must be <= 36 characters")
        side = str(side).upper()
        order_type = str(type_).upper()
        if side not in {"BUY", "SELL"}:
            raise ValueError("side must be BUY or SELL")
        if order_type not in {"STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"}:
            raise ValueError("unsupported Futures conditional order type")
        trigger = self._positive_decimal(trigger_price, "trigger_price")
        if close_position and quantity is not None:
            raise ValueError("closePosition=true cannot be combined with quantity")
        if close_position and reduce_only:
            raise ValueError("closePosition=true cannot be combined with reduceOnly")
        if close_position and order_type not in {"STOP_MARKET", "TAKE_PROFIT_MARKET"}:
            raise ValueError("closePosition=true requires STOP_MARKET or TAKE_PROFIT_MARKET")
        if quantity is None and not close_position:
            raise ValueError("quantity is required unless closePosition=true")

        params: dict[str, Any] = {
            "algoType": "CONDITIONAL",
            "symbol": str(symbol).upper(),
            "side": side,
            "type": order_type,
            "triggerPrice": format(trigger.normalize(), "f"),
            "workingType": str(working_type).upper(),
            "priceProtect": "true" if price_protect else "false",
            "clientAlgoId": str(client_algo_id),
            "newOrderRespType": "RESULT",
        }
        if quantity is not None:
            params["quantity"] = format(self._positive_decimal(quantity, "quantity").normalize(), "f")
        if close_position:
            params["closePosition"] = "true"
        if reduce_only:
            params["reduceOnly"] = "true"

        try:
            result = self._request("POST", "/fapi/v1/algoOrder", params, signed=True)
        except FuturesAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.get_algo_order(
                    symbol,
                    client_algo_id=str(client_algo_id),
                )
                if isinstance(existing, dict) and str(existing.get("algoStatus", "")).upper():
                    return existing
            except Exception as reconcile_exc:
                raise FuturesAPIError(
                    f"Conditional order outcome UNKNOWN for {symbol}; "
                    f"clientAlgoId={client_algo_id}. Reconciliation is required "
                    "before retry.",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload={"submit_error": exc.payload, "reconcile_error": str(reconcile_exc)},
                ) from exc
            raise FuturesAPIError(
                f"Conditional order outcome UNKNOWN for {symbol}; "
                f"clientAlgoId={client_algo_id}. Do not blindly retry.",
                unknown_execution=True,
                status_code=exc.status_code,
                payload=exc.payload,
            ) from exc

        status = str(result.get("algoStatus", "")).upper() if isinstance(result, dict) else ""
        if not status:
            raise FuturesAPIError(
                f"Futures algo response lacks authoritative algoStatus for {client_algo_id}; reconcile",
                unknown_execution=True,
                payload=result,
            )
        return result

    def cancel_order_safe(
        self,
        symbol: str,
        *,
        order_id: int | str | None = None,
        orig_client_order_id: str | None = None,
    ) -> dict[str, Any]:
        if order_id is None and not orig_client_order_id:
            raise ValueError("cancel_order_safe requires order_id or orig_client_order_id")
        params: dict[str, Any] = {"symbol": str(symbol).upper()}
        if order_id is not None:
            params["orderId"] = order_id
        if orig_client_order_id:
            params["origClientOrderId"] = orig_client_order_id
        try:
            return self._request("DELETE", "/fapi/v1/order", params, signed=True)
        except FuturesAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                state = self.get_order(
                    symbol,
                    order_id=order_id,
                    orig_client_order_id=orig_client_order_id,
                )
            except Exception as reconcile_exc:
                raise FuturesAPIError(
                    f"Cancel outcome UNKNOWN for {symbol}; reconcile before any retry",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload={"cancel_error": exc.payload, "reconcile_error": str(reconcile_exc)},
                ) from exc
            status = str(state.get("status", "")).upper()
            if status in {"CANCELED", "EXPIRED", "FILLED", "REJECTED"}:
                return state
            raise FuturesAPIError(
                f"Cancel outcome UNKNOWN for {symbol}; observed order status={status or 'UNKNOWN'}",
                unknown_execution=True,
                status_code=exc.status_code,
                payload=state,
            ) from exc

    def cancel_algo_order_safe(
        self,
        symbol: str,
        *,
        algo_id: int | str | None = None,
        client_algo_id: str | None = None,
    ) -> dict[str, Any]:
        if algo_id is None and not client_algo_id:
            raise ValueError("cancel_algo_order_safe requires algo_id or client_algo_id")
        params: dict[str, Any] = {"symbol": str(symbol).upper()}
        if algo_id is not None:
            params["algoId"] = algo_id
        if client_algo_id:
            params["clientAlgoId"] = client_algo_id
        try:
            return self._request("DELETE", "/fapi/v1/algoOrder", params, signed=True)
        except FuturesAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                state = self.get_algo_order(
                    symbol,
                    algo_id=algo_id,
                    client_algo_id=client_algo_id,
                )
            except Exception as reconcile_exc:
                raise FuturesAPIError(
                    f"Algo cancel outcome UNKNOWN for {symbol}; reconcile before any retry",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload={"cancel_error": exc.payload, "reconcile_error": str(reconcile_exc)},
                ) from exc
            status = str(state.get("algoStatus", "")).upper()
            if status in {"CANCELED", "EXPIRED"}:
                return state
            raise FuturesAPIError(
                f"Algo cancel outcome UNKNOWN for {symbol}; observed algoStatus={status or 'UNKNOWN'}",
                unknown_execution=True,
                status_code=exc.status_code,
                payload=state,
            ) from exc

    @staticmethod
    def _direction(direction: str) -> str:
        normalized = str(direction).upper()
        if normalized not in {"LONG", "SHORT"}:
            raise ValueError("direction must be LONG or SHORT")
        return normalized

    def market_entry(
        self,
        symbol: str,
        direction: str,
        quantity: Any,
        client_order_id: str,
    ) -> dict[str, Any]:
        """Open exposure. This method never sets reduceOnly."""
        self.ensure_one_way_mode()
        direction = self._direction(direction)
        side = "BUY" if direction == "LONG" else "SELL"
        return self.new_order_safe(
            symbol,
            side,
            "MARKET",
            quantity=quantity,
            client_order_id=client_order_id,
            reduce_only=False,
        )

    def stop_entry(
        self,
        symbol: str,
        direction: str,
        quantity: Any,
        trigger_price: Any,
        client_algo_id: str,
    ) -> dict[str, Any]:
        """Arm a directional STOP_MARKET entry using the Futures algo API."""
        self.ensure_one_way_mode()
        direction = self._direction(direction)
        side = "BUY" if direction == "LONG" else "SELL"
        return self.new_algo_order_safe(
            symbol,
            side,
            "STOP_MARKET",
            client_algo_id=client_algo_id,
            trigger_price=trigger_price,
            quantity=quantity,
            working_type="MARK_PRICE",
            price_protect=False,
        )

    def protective_stop(
        self,
        symbol: str,
        direction: str,
        trigger_price: Any,
        client_algo_id: str,
    ) -> dict[str, Any]:
        """Place exchange-side protection for the entire one-way position."""
        self.ensure_one_way_mode()
        direction = self._direction(direction)
        side = "SELL" if direction == "LONG" else "BUY"
        return self.new_algo_order_safe(
            symbol,
            side,
            "STOP_MARKET",
            client_algo_id=client_algo_id,
            trigger_price=trigger_price,
            close_position=True,
            working_type="MARK_PRICE",
            price_protect=False,
        )

    def market_exit(
        self,
        symbol: str,
        direction: str,
        quantity: Any,
        client_order_id: str,
    ) -> dict[str, Any]:
        """Reduce existing exposure only; an exit can never create a reverse position."""
        self.ensure_one_way_mode()
        direction = self._direction(direction)
        side = "SELL" if direction == "LONG" else "BUY"
        return self.new_order_safe(
            symbol,
            side,
            "MARKET",
            quantity=quantity,
            client_order_id=client_order_id,
            reduce_only=True,
        )

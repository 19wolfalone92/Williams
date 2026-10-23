"""Binance USDⓈ-M Futures REST client.

The Williams runtime uses one-way mode (positionSide=BOTH), isolated margin,
and quantity/risk-based sizing.  The adapter intentionally exposes a small
Spot-compatible read surface so scanner/risk code can remain exchange-agnostic.
"""

from __future__ import annotations

import os
import time
import hmac
import hashlib
import math
import re
from decimal import Decimal, ROUND_DOWN
from urllib.parse import urlencode, urlparse

import requests

from binance_client import BinanceAPIError


class BinanceFuturesClient:
    is_futures = True
    CONDITIONAL_TYPES = {"STOP", "STOP_MARKET", "TAKE_PROFIT", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"}

    def __init__(
        self,
        api_key,
        api_secret,
        testnet=True,
        recv_window=5000,
        timeout=20,
    ):
        self.api_key = api_key
        self.api_secret = api_secret
        self.testnet = bool(testnet)
        if self.testnet:
            # Never allow an environment override to silently route a Testnet
            # client to the live Futures host. This flag controls both endpoint
            # selection and the runtime's live-order permission gate.
            configured_url = os.getenv(
                "BINANCE_FUTURES_TESTNET_URL", "https://demo-fapi.binance.com"
            ).strip().rstrip("/")
            parsed_url = urlparse(configured_url)
            if (
                parsed_url.scheme != "https"
                or parsed_url.hostname != "demo-fapi.binance.com"
                or parsed_url.username is not None
                or parsed_url.password is not None
                or parsed_url.port not in (None, 443)
                or parsed_url.path not in ("", "/")
                or parsed_url.query
                or parsed_url.fragment
            ):
                raise ValueError(
                    "Testnet client must use https://demo-fapi.binance.com; "
                    "custom/live Futures endpoints are forbidden in Testnet mode"
                )
            self.base_url = "https://demo-fapi.binance.com"
        else:
            configured_url = os.getenv(
                "BINANCE_FUTURES_URL", "https://fapi.binance.com"
            ).strip().rstrip("/")
            parsed_url = urlparse(configured_url)
            if (
                parsed_url.scheme != "https"
                or parsed_url.hostname != "fapi.binance.com"
                or parsed_url.username is not None
                or parsed_url.password is not None
                or parsed_url.port not in (None, 443)
                or parsed_url.path not in ("", "/")
                or parsed_url.query
                or parsed_url.fragment
            ):
                raise ValueError(
                    "Live Futures client must use https://fapi.binance.com"
                )
            self.base_url = "https://fapi.binance.com"
        self.recv_window = int(recv_window)
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({"X-MBX-APIKEY": self.api_key})
        self.time_offset_ms = 0
        self.last_used_weight_1m = 0
        self.last_order_count_10s = 0
        self.last_order_count_1m = 0
        self.request_weight_limit_1m = 6000
        self.order_limit_10s = 50
        self.order_limit_1m = 1200
        self.rate_limit_pause_until = 0.0

    def _request(self, method, path, params=None, signed=False):
        # This client is a Futures-only boundary. Reject accidental Spot routes,
        # absolute URLs, and path/query injection before credentials or network I/O.
        if (
            not isinstance(path, str)
            or re.fullmatch(r"/fapi/v[1-3]/[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*", path) is None
            or path.startswith("//")
            or "?" in path
            or "#" in path
            or any(ord(char) < 32 for char in path)
            or "\\\\" in path
        ):
            raise ValueError(
                "BinanceFuturesClient accepts only canonical /fapi/ USD-M Futures routes"
            )
        method = method.upper()
        # Signed mutations may be retried only when Binance explicitly rejects the
        # request for timestamp skew (-1021); transport/5xx ambiguity remains UNKNOWN.
        max_attempts = 3 if method == "GET" else (2 if signed else 1)
        delays = (0.5, 1.0, 2.0)
        last_exc = None
        time_resync_attempted = False

        for attempt in range(max_attempts):
            wait = max(0.0, self.rate_limit_pause_until - time.time())
            while wait > 0:
                time.sleep(min(wait, 30.0))
                wait = max(0.0, self.rate_limit_pause_until - time.time())

            payload = dict(params or {})
            if signed:
                payload.setdefault("recvWindow", self.recv_window)
                payload["timestamp"] = int(time.time() * 1000) + self.time_offset_ms
                query = urlencode(payload, doseq=True)
                payload["signature"] = hmac.new(
                    self.api_secret.encode(),
                    query.encode(),
                    hashlib.sha256,
                ).hexdigest()

            try:
                response = self.session.request(
                    method,
                    self.base_url + path,
                    params=payload,
                    timeout=self.timeout,
                    allow_redirects=False,
                )
            except (requests.Timeout, requests.ConnectionError) as exc:
                last_exc = exc
                if method == "GET" and attempt + 1 < max_attempts:
                    time.sleep(delays[attempt])
                    continue
                raise BinanceAPIError(
                    f"Binance Futures transport error: {exc}",
                    unknown_execution=method in {"POST", "PUT", "DELETE"},
                ) from exc

            used = response.headers.get("X-MBX-USED-WEIGHT-1M")
            orders_10s = response.headers.get("X-MBX-ORDER-COUNT-10S")
            orders_1m = response.headers.get("X-MBX-ORDER-COUNT-1M")
            if used:
                try:
                    self.last_used_weight_1m = int(used)
                except ValueError:
                    pass
            if orders_10s:
                try:
                    self.last_order_count_10s = int(orders_10s)
                except ValueError:
                    pass
            if orders_1m:
                try:
                    self.last_order_count_1m = int(orders_1m)
                except ValueError:
                    pass

            if response.status_code in (418, 429):
                retry_after = response.headers.get("Retry-After")
                try:
                    retry_seconds = max(1.0, float(retry_after))
                except (TypeError, ValueError):
                    retry_seconds = delays[min(attempt, len(delays) - 1)]
                self.rate_limit_pause_until = max(
                    self.rate_limit_pause_until,
                    time.time() + retry_seconds,
                )
                if method == "GET" and attempt + 1 < max_attempts:
                    continue
                try:
                    rate_payload = response.json()
                except ValueError:
                    rate_payload = {"code": response.status_code, "msg": response.text}
                raise BinanceAPIError(
                    f"Binance Futures {response.status_code}: {rate_payload}",
                    unknown_execution=method in {"POST", "PUT", "DELETE"},
                    status_code=response.status_code,
                    payload=rate_payload,
                )

            if response.status_code >= 500:
                if method == "GET" and attempt + 1 < max_attempts:
                    time.sleep(delays[attempt])
                    continue
                try:
                    error_payload = response.json()
                except ValueError:
                    error_payload = {"code": response.status_code, "msg": response.text}
                raise BinanceAPIError(
                    f"Binance Futures {response.status_code}: {error_payload}",
                    unknown_execution=method in {"POST", "PUT", "DELETE"},
                    status_code=response.status_code,
                    payload=error_payload,
                )

            try:
                result = response.json()
            except ValueError as exc:
                if response.status_code < 400:
                    raise BinanceAPIError(
                        "Binance Futures returned invalid JSON; reconciliation required",
                        unknown_execution=method in {"POST", "PUT", "DELETE"},
                        status_code=response.status_code,
                    ) from exc
                result = {"code": response.status_code, "msg": response.text}

            if (
                signed
                and not time_resync_attempted
                and isinstance(result, dict)
                and result.get("code") == -1021
            ):
                time_resync_attempted = True
                self.sync_time()
                continue

            if response.status_code >= 300 or (
                isinstance(result, dict) and result.get("code", 0) < 0
            ):
                raise BinanceAPIError(
                    f"Binance Futures {response.status_code}: {result}",
                    status_code=response.status_code,
                    payload=result,
                )
            return result

        raise BinanceAPIError(f"Binance Futures request failed: {method} {path}") from last_exc

    def sync_time(self):
        result = self._request("GET", "/fapi/v1/time")
        self.time_offset_ms = int(result["serverTime"]) - int(time.time() * 1000)
        return result

    def ping(self):
        return self._request("GET", "/fapi/v1/ping")

    def exchange_info(self, symbol=None):
        # USD-M exchangeInfo has no symbol parameter: Binance returns the whole
        # universe. Preserve the scanner-compatible interface by filtering locally.
        result = self._require_object(self._request("GET", "/fapi/v1/exchangeInfo"))
        rows = self._require_array(result.get("symbols"))
        if symbol is not None:
            rows = [row for row in rows if isinstance(row, dict)
                    and str(row.get("symbol", "")).upper() == str(symbol).upper()]
            if len(rows) != 1:
                raise BinanceAPIError(f"Futures symbol metadata missing/duplicated: {symbol}")
        return {**result, "symbols": rows}

    @staticmethod
    def _require_object(payload):
        if not isinstance(payload, dict):
            raise BinanceAPIError("Binance Futures expected object", payload=payload)
        return payload

    @staticmethod
    def _require_array(payload):
        if not isinstance(payload, list) or any(not isinstance(row, dict) for row in payload):
            raise BinanceAPIError("Binance Futures expected array of objects", payload=payload)
        return payload

    def ticker_price(self, symbol):
        row = self._require_object(self._request("GET", "/fapi/v2/ticker/price", {"symbol": symbol}))
        if row.get("symbol") != symbol:
            raise BinanceAPIError("Binance Futures contract quote symbol mismatch", payload=row)
        try:
            price = float(row["price"])
            if not math.isfinite(price) or price <= 0:
                raise ValueError("invalid contract price")
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            raise BinanceAPIError("Binance Futures contract price unverified", payload=row) from exc
        return row

    def ticker_prices(self):
        return self._request("GET", "/fapi/v1/ticker/price")

    def ticker_24hr(self, symbol=None):
        return self._request(
            "GET",
            "/fapi/v1/ticker/24hr",
            {"symbol": symbol} if symbol else {},
        )

    def book_ticker(self, symbol=None):
        return self._request(
            "GET",
            "/fapi/v1/ticker/bookTicker",
            {"symbol": symbol} if symbol else {},
        )

    def mark_price(self, symbol):
        return self._request(
            "GET",
            "/fapi/v1/premiumIndex",
            {"symbol": symbol} if symbol else {},
        )

    def depth(self, symbol, limit=100):
        return self._request("GET", "/fapi/v1/depth", {"symbol": symbol, "limit": limit})

    def trades(self, symbol, limit=100):
        return self._request("GET", "/fapi/v1/trades", {"symbol": symbol, "limit": limit})

    def agg_trades(self, symbol, limit=100):
        return self._request("GET", "/fapi/v1/aggTrades", {"symbol": symbol, "limit": limit})

    def klines(self, symbol, interval, limit=200):
        return self._request(
            "GET",
            "/fapi/v1/klines",
            {"symbol": symbol, "interval": interval, "limit": limit},
        )

    def account(self):
        # Binance replaced the account V2 endpoint with V3. Keep the backend aligned
        # with the Android adapter so Testnet account reconciliation uses one API contract.
        raw = self._require_object(self._request("GET", "/fapi/v3/account", signed=True))
        if not isinstance(raw.get("canTrade"), bool) or not isinstance(raw.get("assets"), list):
            raise BinanceAPIError("Malformed Futures account snapshot", payload=raw)
        assets = raw.get("assets", []) or []
        balances = []
        for asset in assets:
            wallet = float(asset.get("walletBalance", 0) or 0)
            available = float(asset.get("availableBalance", 0) or 0)
            balances.append(
                {
                    "asset": asset.get("asset", ""),
                    "free": str(available),
                    "locked": str(max(wallet - available, 0.0)),
                }
            )
        return {
            **raw,
            "status": "TRADING" if raw["canTrade"] else "NOT_TRADING",
            "accountType": "FUTURES",
            "balances": balances,
            "totalWalletBalance": raw.get("totalWalletBalance", "0"),
            "totalUnrealizedProfit": raw.get("totalUnrealizedProfit", "0"),
            "availableBalance": raw.get("availableBalance", "0"),
        }

    def position_risk(self, symbol=None):
        rows = self._require_array(self._request(
            "GET",
            "/fapi/v3/positionRisk",
            {"symbol": symbol} if symbol else {},
            signed=True,
        ))
        for row in rows:
            try:
                amount = Decimal(str(row["positionAmt"]))
                if not amount.is_finite() or not row.get("symbol"):
                    raise ValueError("invalid position")
            except (KeyError, ValueError, ArithmeticError) as exc:
                raise BinanceAPIError("Malformed Futures position snapshot", payload=row) from exc
        return rows

    def position(self, symbol):
        rows = self.position_risk(symbol)
        matching = [row for row in rows if str(row.get("symbol", "")).upper() == str(symbol).upper()]
        if len(matching) > 1 or any(row.get("positionSide", "BOTH") != "BOTH" for row in matching):
            raise BinanceAPIError("One-way Futures position cannot be proven", payload=matching)
        if matching:
            return matching[0]
        return {
            "symbol": symbol,
            "positionAmt": "0",
            "entryPrice": "0",
            "markPrice": "0",
            "unRealizedProfit": "0",
            "liquidationPrice": "0",
            "positionSide": "BOTH",
        }

    def get_multi_assets_mode(self):
        return self._request("GET", "/fapi/v1/multiAssetsMargin", signed=True)

    def set_multi_assets_mode_single(self):
        return self._request(
            "POST",
            "/fapi/v1/multiAssetsMargin",
            {"multiAssetsMargin": "false"},
            signed=True,
        )

    def set_position_mode_one_way(self):
        return self._request(
            "POST",
            "/fapi/v1/positionSide/dual",
            {"dualSidePosition": "false"},
            signed=True,
        )

    def get_position_mode(self):
        return self._request("GET", "/fapi/v1/positionSide/dual", signed=True)

    def set_margin_type(self, symbol, margin_type="ISOLATED"):
        try:
            return self._request(
                "POST",
                "/fapi/v1/marginType",
                {"symbol": symbol, "marginType": margin_type.upper()},
                signed=True,
            )
        except BinanceAPIError as exc:
            # -4046 means the requested margin type is already active.
            if isinstance(exc.payload, dict) and exc.payload.get("code") == -4046:
                return {"code": -4046, "msg": "No need to change margin type."}
            raise

    def set_leverage(self, symbol, leverage):
        from futures_leverage import parse_leverage
        return self._request(
            "POST",
            "/fapi/v1/leverage",
            {"symbol": symbol, "leverage": parse_leverage(leverage)},
            signed=True,
        )

    def leverage_brackets(self, symbol):
        return self._request("GET", "/fapi/v1/leverageBracket", {"symbol": symbol}, signed=True)

    def symbol_configuration(self, symbol):
        payload = self._request("GET", "/fapi/v1/symbolConfig", {"symbol": symbol}, signed=True)
        rows = payload if isinstance(payload, list) else [payload]
        matches = [row for row in rows if isinstance(row, dict) and row.get("symbol") == symbol]
        if len(matches) != 1:
            raise BinanceAPIError("Missing or ambiguous Futures symbol configuration", payload=payload)
        return matches[0]

    def api_restrictions(self):
        # Testnet credentials must never be sent to the live Wallet API host.
        # The Wallet SAPI permission endpoint is not part of the Futures Demo API.
        if self.testnet:
            raise BinanceAPIError(
                "API restrictions cannot be queried with Futures Testnet credentials; "
                "live Wallet API access is blocked in Testnet mode"
            )
        raise BinanceAPIError("Wallet API is outside the Futures-only client; use a separate Wallet client")

    def api_trading_status(self):
        # Do not leak a Testnet API key to the live Wallet API host.
        if self.testnet:
            raise BinanceAPIError(
                "API trading status cannot be queried with Futures Testnet credentials; "
                "live Wallet API access is blocked in Testnet mode"
            )
        raise BinanceAPIError("Wallet API is outside the Futures-only client; use a separate Wallet client")

    def open_orders(self, symbol=None):
        standard = self._require_array(self._request(
            "GET",
            "/fapi/v1/openOrders",
            {"symbol": symbol} if symbol else {},
            signed=True,
        ))
        conditional = self._require_array(self._request(
            "GET", "/fapi/v1/openAlgoOrders", {"symbol": symbol} if symbol else {}, signed=True
        ))
        return [self._normalize_order_response(row) for row in standard] + [
            self._normalize_algo_response(row) for row in conditional
        ]

    def all_orders(self, symbol, limit=1000):
        standard = self._require_array(self._request(
            "GET",
            "/fapi/v1/allOrders",
            {"symbol": symbol, "limit": int(limit)},
            signed=True,
        ))
        conditional = self._require_array(self._request(
            "GET", "/fapi/v1/allAlgoOrders", {"symbol": symbol, "limit": min(int(limit), 1000)}, signed=True
        ))
        return [self._normalize_order_response(row) for row in standard] + [
            self._resolve_algo_order(symbol, row) for row in conditional
        ]

    def my_trades(self, symbol, order_id=None, limit=1000):
        params = {"symbol": symbol, "limit": int(limit)}
        if order_id is not None:
            if str(order_id).startswith("algo:"):
                order = self.get_order(symbol, order_id=order_id)
                order_id = order.get("actualOrderId")
                if not order_id:
                    raise BinanceAPIError("Conditional order has no exchange fill orderId", payload=order)
            params["orderId"] = int(order_id)
        rows = self._request("GET", "/fapi/v1/userTrades", params, signed=True)
        normalized = []
        for row in rows or []:
            normalized.append(
                {
                    **row,
                    "qty": row.get("qty", row.get("executedQty", "0")),
                    "price": row.get("price", "0"),
                    "commission": row.get("commission", "0"),
                    "commissionAsset": row.get("commissionAsset", "USDT"),
                }
            )
        return normalized

    @staticmethod
    def _normalize_order_response(payload):
        result = dict(BinanceFuturesClient._require_object(payload))
        if not result.get("orderId") or not result.get("status"):
            raise BinanceAPIError("Futures order response lacks orderId/status", payload=payload)
        result.setdefault("clientOrderId", result.get("origClientOrderId", ""))
        result.setdefault("cummulativeQuoteQty", result.get("cumQuote", "0"))
        result.setdefault("time", result.get("updateTime", result.get("transactTime", 0)))
        result.setdefault("origQty", result.get("origQty", "0"))
        result.setdefault("executedQty", result.get("executedQty", "0"))
        result.setdefault("type", result.get("type", ""))
        return result

    @staticmethod
    def _normalize_algo_response(payload):
        row = BinanceFuturesClient._require_object(payload)
        if not row.get("algoId"):
            raise BinanceAPIError("Conditional Futures order is missing algoId", payload=row)
        status = str(row.get("algoStatus", "")).upper()
        # Trigger acceptance is not execution. Query the actual matching-engine
        # order before reporting FILLED/PARTIALLY_FILLED to campaign recovery.
        normalized_status = {"CANCELLED": "CANCELED", "FINISHED": "TRIGGERED"}.get(status, status)
        return {
            **row,
            "orderId": f"algo:{row['algoId']}",
            "clientOrderId": row.get("clientAlgoId", ""),
            "type": row.get("orderType", ""),
            "status": normalized_status,
            "stopPrice": row.get("triggerPrice", "0"),
            "origQty": row.get("quantity", "0"),
            "executedQty": "0",
            "avgPrice": "0",
            "time": row.get("createTime", 0),
        }

    def _resolve_algo_order(self, symbol, payload):
        result = self._normalize_algo_response(payload)
        if str(result.get("symbol", "")).upper() != str(symbol).upper():
            raise BinanceAPIError("Conditional order symbol mismatch", payload=payload)
        actual_id = payload.get("actualOrderId")
        if actual_id and str(actual_id) != "0":
            actual = self.get_order(symbol, order_id=actual_id)
            if str(actual.get("symbol", "")).upper() != str(symbol).upper() or actual.get("side") != result.get("side"):
                raise BinanceAPIError("Conditional/actual order identity mismatch", payload=payload)
            result.update({key: actual[key] for key in (
                "status", "executedQty", "avgPrice", "cumQuote", "cummulativeQuoteQty", "updateTime"
            ) if key in actual})
        return result

    def order(
        self,
        symbol,
        side,
        type_,
        quantity=None,
        quote_order_qty=None,
        price=None,
        stop_price=None,
        time_in_force=None,
        new_client_order_id=None,
        strategy_id=None,
        strategy_type=None,
        trailing_delta=None,
        reduce_only=False,
        working_type="CONTRACT_PRICE",
        price_protect=False,
        position_side="BOTH",
        close_position=False,
    ):
        type_name = str(type_).upper()
        if type_name == "STOP_LOSS":
            type_name = "STOP_MARKET"
        if quote_order_qty is not None:
            raise BinanceAPIError("USDⓈ-M Futures order quantity must be contracts/base quantity, not quoteOrderQty")
        if close_position and (quantity is not None or reduce_only or type_name not in {"STOP_MARKET", "TAKE_PROFIT_MARKET"}):
            raise ValueError("closePosition requires a market conditional without quantity/reduceOnly")

        p = {
            "symbol": symbol,
            "side": str(side).upper(),
            "type": type_name,
            "positionSide": str(position_side).upper(),
            "newOrderRespType": "RESULT",
        }
        if quantity is not None:
            p["quantity"] = quantity
        if price is not None:
            p["price"] = price
        if stop_price is not None:
            p["stopPrice"] = stop_price
        if time_in_force is not None:
            p["timeInForce"] = time_in_force
        if new_client_order_id:
            p["newClientOrderId"] = new_client_order_id
        if reduce_only:
            p["reduceOnly"] = "true"
        if working_type:
            p["workingType"] = working_type
        if price_protect:
            p["priceProtect"] = "true"
        if close_position:
            p["closePosition"] = "true"
        if type_name in self.CONDITIONAL_TYPES:
            p["algoType"] = "CONDITIONAL"
            if "stopPrice" in p:
                p["triggerPrice"] = p.pop("stopPrice")
            if "newClientOrderId" in p:
                p["clientAlgoId"] = p.pop("newClientOrderId")
            result = self._request("POST", "/fapi/v1/algoOrder", p, signed=True)
            try:
                return self._normalize_algo_response(result)
            except BinanceAPIError as exc:
                exc.unknown_execution = True
                raise
        result = self._request("POST", "/fapi/v1/order", p, signed=True)
        try:
            return self._normalize_order_response(result)
        except BinanceAPIError as exc:
            exc.unknown_execution = True
            raise

    def order_safe(self, symbol, side, type_, *, quantity=None, quote_order_qty=None,
                   price=None, stop_price=None, time_in_force=None,
                   new_client_order_id=None, strategy_id=None, strategy_type=None,
                   trailing_delta=None, reduce_only=False, working_type="CONTRACT_PRICE",
                   price_protect=False, position_side="BOTH", close_position=False):
        if not new_client_order_id:
            raise ValueError("order_safe requires new_client_order_id")
        try:
            result = self.order(
                symbol, side, type_,
                quantity=quantity,
                quote_order_qty=quote_order_qty,
                price=price,
                stop_price=stop_price,
                time_in_force=time_in_force,
                new_client_order_id=new_client_order_id,
                strategy_id=strategy_id,
                strategy_type=strategy_type,
                trailing_delta=trailing_delta,
                reduce_only=reduce_only,
                working_type=working_type,
                price_protect=price_protect,
                position_side=position_side,
                close_position=close_position,
            )
            if result.get("status") in {"REJECTED", "EXPIRED", "CANCELED", "EXPIRED_IN_MATCH"}:
                raise BinanceAPIError("Futures order returned terminal failure", payload=result)
            return result
        except BinanceAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.get_order(
                    symbol,
                    orig_client_order_id=new_client_order_id,
                    conditional=str(type_).upper() in self.CONDITIONAL_TYPES | {"STOP_LOSS"},
                )
            except Exception as reconcile_exc:
                raise BinanceAPIError(
                    f"Futures order execution UNKNOWN: symbol={symbol} clientOrderId={new_client_order_id}",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload=exc.payload,
                ) from reconcile_exc
            status = str(existing.get("status", "")).upper()
            if status in {"NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_CANCEL", "PENDING_NEW"}:
                return existing
            raise

    def get_order(self, symbol, order_id=None, orig_client_order_id=None, *, conditional=False):
        if conditional or str(order_id).startswith("algo:"):
            params = {}
            if order_id is not None:
                params["algoId"] = int(str(order_id).removeprefix("algo:"))
            if orig_client_order_id is not None:
                params["clientAlgoId"] = orig_client_order_id
            return self._resolve_algo_order(symbol, self._request("GET", "/fapi/v1/algoOrder", params, signed=True))
        p = {"symbol": symbol}
        if order_id is not None:
            p["orderId"] = int(order_id)
        if orig_client_order_id is not None:
            p["origClientOrderId"] = orig_client_order_id
        return self._normalize_order_response(
            self._request("GET", "/fapi/v1/order", p, signed=True)
        )

    def cancel_order(self, symbol, order_id=None, orig_client_order_id=None):
        if str(order_id).startswith("algo:"):
            order = self.get_order(symbol, order_id=order_id)
            actual_id = order.get("actualOrderId")
            if actual_id and str(actual_id) != "0":
                if order.get("status") in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                    self.cancel_order(symbol, order_id=actual_id)
            else:
                self._request("DELETE", "/fapi/v1/algoOrder", {"algoId": int(str(order_id).removeprefix("algo:"))}, signed=True)
            return self.get_order(symbol, order_id=order_id)
        p = {"symbol": symbol}
        if order_id is not None:
            p["orderId"] = int(order_id)
        if orig_client_order_id is not None:
            p["origClientOrderId"] = orig_client_order_id
        return self._normalize_order_response(
            self._request("DELETE", "/fapi/v1/order", p, signed=True)
        )

    def cancel_open_orders(self, symbol):
        return self._request(
            "DELETE",
            "/fapi/v1/allOpenOrders",
            {"symbol": symbol},
            signed=True,
        )

    def open_order_lists(self, symbol=None):
        return []

    def all_order_lists(self, symbol=None, limit=100):
        return []

    def cancel_oco(self, symbol, order_list_id=None, list_client_order_id=None):
        raise BinanceAPIError("OCO is not a Futures order primitive; use conditional STOP_MARKET protection")

    def create_oco_sell(self, *args, **kwargs):
        raise BinanceAPIError("OCO is not supported in USDⓈ-M Futures mode")

    def cancel_replace(
        self,
        symbol,
        cancel_order_id,
        side,
        type_,
        *,
        quantity=None,
        price=None,
        stop_price=None,
        time_in_force=None,
        new_client_order_id=None,
    ):
        # Keep the existing stop until its replacement is accepted. Cancellation
        # failure is ambiguous and must expose the new stop to reconciliation.
        if str(type_).upper() not in {"STOP_MARKET", "STOP_LOSS"}:
            raise ValueError("Futures cancel_replace supports STOP_MARKET protection only")
        new_payload = self.order_safe(
            symbol,
            side,
            "STOP_MARKET",
            quantity=quantity,
            stop_price=stop_price,
            time_in_force=time_in_force,
            new_client_order_id=new_client_order_id,
            reduce_only=True,
        )
        try:
            cancel_payload = self.cancel_order(symbol, order_id=cancel_order_id)
        except Exception as exc:
            raise BinanceAPIError(
                "Replacement stop accepted; old-stop cancellation requires reconciliation",
                unknown_execution=True,
                payload={"newOrderResponse": new_payload, "oldOrderId": cancel_order_id},
            ) from exc
        return {
            "cancelResult": "SUCCESS" if cancel_payload else "NOT_FOUND",
            "newOrderResult": "SUCCESS",
            "cancelResponse": cancel_payload,
            "newOrderResponse": new_payload,
        }

    @staticmethod
    def decimal_floor(value, step):
        return (
            Decimal(str(value)) / Decimal(str(step))
        ).to_integral_value(rounding=ROUND_DOWN) * Decimal(str(step))

    @staticmethod
    def decimal_format(value):
        return format(Decimal(str(value)).normalize(), "f")

"""Binance USDⓈ-M Futures REST client.

This module is deliberately separate from BinanceSpotClient: Futures has a
separate REST namespace, account/position model, and protective-order model.
It is not a drop-in replacement for Spot OCO-based campaign execution.
"""
from __future__ import annotations

import hashlib
import hmac
import time
from urllib.parse import urlencode

import requests


class BinanceFuturesAPIError(RuntimeError):
    def __init__(self, message, *, unknown_execution=False, status_code=None, payload=None):
        super().__init__(message)
        self.unknown_execution = bool(unknown_execution)
        self.status_code = status_code
        self.payload = payload


class BinanceUsdMFuturesClient:
    """Small, fail-closed USDⓈ-M Futures REST client.

    demo=True uses Binance Demo Trading (not the retired Futures testnet).
    Mutating requests are never blindly retried after transport/server errors;
    callers must reconcile by the stable client order ID.
    """

    def __init__(self, api_key, api_secret, demo=True, recv_window=5000, timeout=15, session=None):
        if not api_key or not api_secret:
            raise ValueError("Binance USD-M Futures API key and secret are required")
        self.api_key = str(api_key)
        self.api_secret = str(api_secret)
        self.demo = bool(demo)
        self.base_url = "https://demo-fapi.binance.com" if self.demo else "https://fapi.binance.com"
        self.recv_window = int(recv_window)
        if not 1 <= self.recv_window <= 60000:
            raise ValueError("recv_window must be in [1, 60000]")
        self.timeout = float(timeout)
        self.session = session or requests.Session()
        self.session.headers.update({"X-MBX-APIKEY": self.api_key})
        self.time_offset_ms = 0

    def _request(self, method, path, params=None, signed=False):
        method = str(method).upper()
        values = dict(params or {})
        if signed:
            values.setdefault("recvWindow", self.recv_window)
            values["timestamp"] = int(time.time() * 1000) + self.time_offset_ms
            query = urlencode(values, doseq=True)
            values["signature"] = hmac.new(
                self.api_secret.encode("utf-8"), query.encode("utf-8"), hashlib.sha256
            ).hexdigest()
        try:
            response = self.session.request(
                method, self.base_url + path, params=values, timeout=self.timeout
            )
        except (requests.Timeout, requests.ConnectionError) as exc:
            raise BinanceFuturesAPIError(
                f"Futures transport failure on {method} {path}; reconcile before retry",
                unknown_execution=method in {"POST", "PUT", "DELETE"},
            ) from exc

        try:
            payload = response.json()
        except ValueError:
            payload = {"msg": getattr(response, "text", "")}
        if not 200 <= int(response.status_code) < 300:
            code = payload.get("code") if isinstance(payload, dict) else None
            # -1021 is a definitive timestamp rejection; no mutation was accepted.
            raise BinanceFuturesAPIError(
                f"Binance Futures HTTP {response.status_code}: {payload}",
                unknown_execution=(
                    method in {"POST", "PUT", "DELETE"}
                    and int(response.status_code) >= 500
                ),
                status_code=response.status_code,
                payload=payload,
            )
        return payload

    @staticmethod
    def _object(payload, endpoint):
        if not isinstance(payload, dict):
            raise BinanceFuturesAPIError(f"{endpoint} returned non-object payload", payload=payload)
        return payload

    @staticmethod
    def _array(payload, endpoint):
        if not isinstance(payload, list):
            raise BinanceFuturesAPIError(f"{endpoint} returned non-array payload", payload=payload)
        return payload

    def ping(self):
        return self._request("GET", "/fapi/v1/ping")

    def server_time(self):
        return self._object(self._request("GET", "/fapi/v1/time"), "/fapi/v1/time")

    def sync_time(self):
        result = self.server_time()
        self.time_offset_ms = int(result["serverTime"]) - int(time.time() * 1000)
        return result

    def exchange_info(self, symbol=None):
        return self._request("GET", "/fapi/v1/exchangeInfo", {"symbol": symbol} if symbol else {})

    def klines(self, symbol, interval, limit=200, **params):
        query = {"symbol": symbol, "interval": interval, "limit": int(limit), **params}
        return self._array(self._request("GET", "/fapi/v1/klines", query), "/fapi/v1/klines")

    def ticker_price(self, symbol):
        return self._object(self._request("GET", "/fapi/v1/ticker/price", {"symbol": symbol}), "/fapi/v1/ticker/price")

    def premium_index(self, symbol=None):
        return self._request("GET", "/fapi/v1/premiumIndex", {"symbol": symbol} if symbol else {})

    def account(self):
        return self._object(self._request("GET", "/fapi/v2/account", signed=True), "/fapi/v2/account")

    def position_risk(self, symbol=None):
        payload = self._request("GET", "/fapi/v2/positionRisk", {"symbol": symbol} if symbol else {}, signed=True)
        return self._array(payload, "/fapi/v2/positionRisk")

    def position_mode(self):
        return self._object(self._request("GET", "/fapi/v1/positionSide/dual", signed=True), "/fapi/v1/positionSide/dual")

    def open_orders(self, symbol=None):
        return self._array(
            self._request("GET", "/fapi/v1/openOrders", {"symbol": symbol} if symbol else {}, signed=True),
            "/fapi/v1/openOrders",
        )

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        if order_id is None and not orig_client_order_id:
            raise ValueError("get_order requires order_id or orig_client_order_id")
        query = {"symbol": symbol}
        if order_id is not None:
            query["orderId"] = order_id
        if orig_client_order_id:
            query["origClientOrderId"] = orig_client_order_id
        return self._object(self._request("GET", "/fapi/v1/order", query, signed=True), "/fapi/v1/order")

    def order(self, symbol, side, type_, *, quantity=None, price=None, stop_price=None,
              time_in_force=None, new_client_order_id=None, position_side=None,
              reduce_only=None, close_position=None, working_type=None,
              price_protect=None, new_order_resp_type="RESULT"):
        side = str(side).upper()
        type_ = str(type_).upper()
        if side not in {"BUY", "SELL"}:
            raise ValueError("side must be BUY or SELL")
        if not new_client_order_id:
            raise ValueError("Futures order requires a stable new_client_order_id")
        params = {
            "symbol": str(symbol).upper(), "side": side, "type": type_,
            "newClientOrderId": str(new_client_order_id),
            "newOrderRespType": new_order_resp_type,
        }
        if quantity is not None:
            params["quantity"] = quantity
        if price is not None:
            params["price"] = price
        if stop_price is not None:
            params["stopPrice"] = stop_price
        if time_in_force is not None:
            params["timeInForce"] = time_in_force
        if position_side is not None:
            ps = str(position_side).upper()
            if ps not in {"BOTH", "LONG", "SHORT"}:
                raise ValueError("position_side must be BOTH, LONG or SHORT")
            params["positionSide"] = ps
        if reduce_only is not None:
            # Binance rejects reduceOnly in Hedge Mode.
            if str(params.get("positionSide", "BOTH")).upper() in {"LONG", "SHORT"}:
                raise ValueError("reduceOnly must be omitted in Hedge Mode; use correct positionSide and close side")
            params["reduceOnly"] = str(bool(reduce_only)).lower()
        if close_position is not None:
            if type_ not in {"STOP_MARKET", "TAKE_PROFIT_MARKET"}:
                raise ValueError("closePosition is supported only for STOP_MARKET/TAKE_PROFIT_MARKET")
            if quantity is not None:
                raise ValueError("closePosition=true must not be combined with quantity")
            if str(reduce_only).lower() == "true":
                raise ValueError("closePosition cannot be combined with reduceOnly")
            params["closePosition"] = str(bool(close_position)).lower()
        if working_type is not None:
            wt = str(working_type).upper()
            if wt not in {"MARK_PRICE", "CONTRACT_PRICE"}:
                raise ValueError("working_type must be MARK_PRICE or CONTRACT_PRICE")
            params["workingType"] = wt
        if price_protect is not None:
            params["priceProtect"] = str(bool(price_protect)).upper()
        return self._object(self._request("POST", "/fapi/v1/order", params, signed=True), "/fapi/v1/order")

    def order_safe(self, symbol, side, type_, *, new_client_order_id, **kwargs):
        if not new_client_order_id:
            raise ValueError("order_safe requires new_client_order_id")
        try:
            result = self.order(
                symbol, side, type_, new_client_order_id=new_client_order_id, **kwargs
            )
        except BinanceFuturesAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.get_order(symbol, orig_client_order_id=new_client_order_id)
            except Exception as reconcile_exc:
                raise BinanceFuturesAPIError(
                    f"Futures order UNKNOWN for {symbol}/{new_client_order_id}; reconciliation required",
                    unknown_execution=True, status_code=exc.status_code, payload=exc.payload,
                ) from reconcile_exc
            return existing
        status = str(result.get("status", "")).upper()
        if status in {"REJECTED", "EXPIRED", "CANCELED"}:
            raise BinanceFuturesAPIError(f"Futures order returned terminal status {status}", payload=result)
        return result

    def cancel_order(self, symbol, order_id=None, orig_client_order_id=None):
        if order_id is None and not orig_client_order_id:
            raise ValueError("cancel_order requires order_id or orig_client_order_id")
        params = {"symbol": symbol}
        if order_id is not None:
            params["orderId"] = order_id
        if orig_client_order_id:
            params["origClientOrderId"] = orig_client_order_id
        return self._object(self._request("DELETE", "/fapi/v1/order", params, signed=True), "/fapi/v1/order")

    def change_leverage(self, symbol, leverage):
        leverage = int(leverage)
        if not 1 <= leverage <= 125:
            raise ValueError("leverage must be in [1, 125]")
        return self._object(
            self._request("POST", "/fapi/v1/leverage", {"symbol": symbol, "leverage": leverage}, signed=True),
            "/fapi/v1/leverage",
        )

    def change_margin_type(self, symbol, margin_type):
        margin_type = str(margin_type).upper()
        if margin_type not in {"ISOLATED", "CROSSED"}:
            raise ValueError("margin_type must be ISOLATED or CROSSED")
        return self._object(
            self._request("POST", "/fapi/v1/marginType", {"symbol": symbol, "marginType": margin_type}, signed=True),
            "/fapi/v1/marginType",
        )

    def user_trades(self, symbol, order_id=None, limit=1000):
        params = {"symbol": symbol, "limit": int(limit)}
        if order_id is not None:
            params["orderId"] = int(order_id)
        return self._array(
            self._request("GET", "/fapi/v1/userTrades", params, signed=True),
            "/fapi/v1/userTrades",
        )

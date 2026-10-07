import hashlib, hmac, time
from decimal import Decimal, ROUND_DOWN
from urllib.parse import urlencode
import requests

class BinanceAPIError(RuntimeError):
    def __init__(self, message, *, unknown_execution=False, status_code=None, payload=None):
        super().__init__(message)
        self.unknown_execution = bool(unknown_execution)
        self.status_code = status_code
        self.payload = payload

class BinanceSpotClient:
    def __init__(self, api_key, api_secret, testnet=True, recv_window=5000, timeout=20):
        self.api_key=api_key; self.api_secret=api_secret; self.testnet=bool(testnet)
        self.base_url='https://testnet.binance.vision' if self.testnet else 'https://api.binance.com'
        self.recv_window=int(recv_window); self.timeout=timeout
        self.session=requests.Session(); self.session.headers.update({'X-MBX-APIKEY': self.api_key})
        self.time_offset_ms=0
        self.last_used_weight_1m=0
        self.last_order_count_10s=0
        self.last_order_count_1m=0
        self.request_weight_limit_1m=6000
        self.order_limit_10s=50
        self.order_limit_1m=1200
        self.rate_limit_pause_until=0.0
    def _request(self, method, path, params=None, signed=False):
        """
        Execute a Binance REST request.

        GETs are safe to retry on transient network/rate-limit failures.
        POST/DELETE are never blindly retried: a timeout/5xx can mean the
        matching-engine operation succeeded and therefore has UNKNOWN status.
        Callers must reconcile by order/clientOrderId before taking another action.
        """
        method = method.upper()
        # Non-GET requests are never retried for transport/5xx errors because
        # the matching engine may have accepted the order. A signed -1021
        # timestamp rejection is safe to retry once after time synchronization.
        max_attempts = (
            3 if method == "GET"
            else 2 if signed
            else 1
        )
        delays = (0.5, 1.0, 2.0)

        last_exc = None
        time_resync_attempted = False

        for attempt in range(max_attempts):
            now = time.time()
            wait = max(0.0, self.rate_limit_pause_until - now)
            is_order_mutation = method in {"POST", "DELETE"} and (
                path.startswith("/api/v3/order")
                or path.startswith("/api/v3/orderList")
                or path.startswith("/api/v3/openOrders")
            )

            # Proactive governor: slow down before Binance returns 429/418.
            # REQUEST_WEIGHT applies to all REST requests; ORDERS applies to
            # order mutations only, so market/account reads are not throttled
            # by the separate order counter.
            if self.request_weight_limit_1m > 0:
                ratio = Decimal(str(self.last_used_weight_1m)) / Decimal(str(self.request_weight_limit_1m))
                if ratio >= 0.98:
                    wait = max(wait, 2.0)
                elif ratio >= 0.90:
                    wait = max(wait, 0.25)

            if is_order_mutation and self.order_limit_10s > 0:
                order_ratio_10s = Decimal(str(self.last_order_count_10s)) / Decimal(str(self.order_limit_10s))
                if order_ratio_10s >= 0.96:
                    wait = max(wait, 1.0)
                elif order_ratio_10s >= 0.90:
                    wait = max(wait, 0.25)

            if is_order_mutation and self.order_limit_1m > 0:
                order_ratio = Decimal(str(self.last_order_count_1m)) / Decimal(str(self.order_limit_1m))
                if order_ratio >= 0.95:
                    wait = max(wait, 1.0)

            if wait > 0:
                time.sleep(min(wait, 30.0))

            p = dict(params or {})

            if signed:
                p.setdefault('recvWindow', self.recv_window)
                p['timestamp'] = int(time.time() * 1000) + self.time_offset_ms
                query = urlencode(p, doseq=True)
                p['signature'] = hmac.new(
                    self.api_secret.encode(),
                    query.encode(),
                    hashlib.sha256,
                ).hexdigest()

            try:
                r = self.session.request(
                    method,
                    self.base_url + path,
                    params=p,
                    timeout=self.timeout,
                )
            except (requests.Timeout, requests.ConnectionError) as exc:
                last_exc = exc
                # Only idempotent GET requests are retried after a transport
                # failure. POST/DELETE remain UNKNOWN and require reconciliation.
                if method == "GET" and attempt + 1 < max_attempts:
                    time.sleep(delays[attempt])
                    continue
                raise BinanceAPIError(
                    f'Binance transport error: {exc}',
                    unknown_execution=method in {"POST", "DELETE"},
                ) from exc

            used = r.headers.get('X-MBX-USED-WEIGHT-1M')
            orders = r.headers.get('X-MBX-ORDER-COUNT-1M')
            if used is not None:
                try:
                    self.last_used_weight_1m = int(used)
                except ValueError:
                    pass
            orders_10s = r.headers.get('X-MBX-ORDER-COUNT-10S')
            if orders_10s is not None:
                try:
                    self.last_order_count_10s = int(orders_10s)
                except ValueError:
                    pass
            if orders is not None:
                try:
                    self.last_order_count_1m = int(orders)
                except ValueError:
                    pass

            if r.status_code in (418, 429):
                retry_after = r.headers.get('Retry-After')
                try:
                    retry_seconds = max(1.0, float(retry_after))
                except (TypeError, ValueError):
                    retry_seconds = float(delays[min(attempt, len(delays)-1)])
                self.rate_limit_pause_until = max(
                    self.rate_limit_pause_until,
                    time.time() + retry_seconds,
                )
                if method == "GET" and attempt + 1 < max_attempts:
                    continue

            if r.status_code >= 500:
                if method == "GET" and attempt + 1 < max_attempts:
                    time.sleep(delays[attempt])
                    continue
                try:
                    payload = r.json()
                except ValueError:
                    payload = {'code': r.status_code, 'msg': r.text}
                raise BinanceAPIError(
                    f'Binance {r.status_code}: {payload}',
                    unknown_execution=method in {"POST", "DELETE"},
                    status_code=r.status_code,
                    payload=payload,
                )

            try:
                payload = r.json()
            except ValueError:
                payload = {'code': r.status_code, 'msg': r.text}

            # -1021 means Binance rejected the request because of timestamp.
            # The matching engine did not accept it, so one time-sync retry is safe.
            if (
                signed
                and not time_resync_attempted
                and isinstance(payload, dict)
                and payload.get('code') == -1021
            ):
                time_resync_attempted = True
                self.sync_time()
                continue

            if r.status_code >= 400 or (
                isinstance(payload, dict)
                and payload.get('code', 0) < 0
            ):
                raise BinanceAPIError(
                    f'Binance {r.status_code}: {payload}',
                    status_code=r.status_code,
                    payload=payload,
                )

            if method == "GET" and path == "/api/v3/exchangeInfo":
                self._update_rate_limits_from_exchange_info(payload)

            return payload

        if last_exc is not None:
            raise BinanceAPIError(
                f'Binance request failed: {last_exc}',
                unknown_execution=method in {"POST", "DELETE"},
            ) from last_exc

        raise BinanceAPIError(
            f'Binance request failed: {method} {path}'
        )

    def _update_rate_limits_from_exchange_info(self, payload):
        if not isinstance(payload, dict):
            return
        for row in payload.get("rateLimits", []) or []:
            if not isinstance(row, dict):
                continue
            kind = str(row.get("rateLimitType", "")).upper()
            interval = str(row.get("interval", "")).upper()
            interval_num = int(row.get("intervalNum", 0) or 0)
            limit = int(row.get("limit", 0) or 0)
            if limit <= 0:
                continue
            if kind == "REQUEST_WEIGHT" and interval == "MINUTE" and interval_num == 1:
                self.request_weight_limit_1m = limit
            elif kind == "ORDERS":
                if interval == "SECOND" and interval_num == 10:
                    self.order_limit_10s = limit
                elif interval == "MINUTE" and interval_num == 1:
                    self.order_limit_1m = limit

    def sync_time(self):
        server=self._request('GET','/api/v3/time'); self.time_offset_ms=int(server['serverTime'])-int(time.time()*1000); return server
    def ping(self): return self._request('GET','/api/v3/ping')
    def exchange_info(self,symbol=None): return self._request('GET','/api/v3/exchangeInfo',{'symbol':symbol} if symbol else {})
    def ticker_price(self,symbol): return self._request('GET','/api/v3/ticker/price',{'symbol':symbol})
    def ticker_prices(self): return self._require_json_array(self._request('GET','/api/v3/ticker/price',{}), '/ticker/price')
    def ticker_24hr(self,symbol=None): return self._request('GET','/api/v3/ticker/24hr',{'symbol':symbol} if symbol else {})
    def book_ticker(self,symbol=None): return self._request('GET','/api/v3/ticker/bookTicker',{'symbol':symbol} if symbol else {})
    def depth(self,symbol,limit=100): return self._request('GET','/api/v3/depth',{'symbol':symbol,'limit':limit})
    def trades(self,symbol,limit=100): return self._request('GET','/api/v3/trades',{'symbol':symbol,'limit':limit})
    def agg_trades(self,symbol,limit=100): return self._request('GET','/api/v3/aggTrades',{'symbol':symbol,'limit':limit})
    def avg_price(self,symbol): return self._request('GET','/api/v3/avgPrice',{'symbol':symbol})
    def klines(self,symbol,interval,limit=200): return self._request('GET','/api/v3/klines',{'symbol':symbol,'interval':interval,'limit':limit})
    def account(self): return self._request('GET','/api/v3/account',signed=True)
    def api_restrictions(self): return self._request('GET','/sapi/v1/account/apiRestrictions',{},signed=True)
    def api_trading_status(self): return self._request('GET','/sapi/v1/account/apiTradingStatus',{},signed=True)
    def my_trades(self, symbol, order_id=None, limit=1000):
        params = {'symbol': symbol, 'limit': int(limit)}
        if order_id is not None:
            params['orderId'] = int(order_id)
        return self._request('GET', '/api/v3/myTrades', params, signed=True)

    @staticmethod
    def _require_json_object(payload, endpoint):
        if isinstance(payload, dict):
            return payload
        raise BinanceAPIError(
            f"Binance {endpoint} returned {type(payload).__name__}; expected object",
            payload=payload,
        )

    @staticmethod
    def _require_json_array(payload, endpoint):
        if isinstance(payload, list):
            return payload
        raise BinanceAPIError(
            f"Binance {endpoint} returned {type(payload).__name__}; expected array",
            payload=payload,
        )

    def all_orders(self,symbol,limit=1000):
        return self._require_json_array(
            self._request('GET','/api/v3/allOrders',
                          {'symbol':symbol,'limit':limit},signed=True),
            '/allOrders',
        )

    def order_list(self,symbol,order_list_id=None,list_client_order_id=None):
        p={'symbol':symbol}
        if order_list_id is not None: p['orderListId']=order_list_id
        if list_client_order_id is not None: p['origClientOrderId']=list_client_order_id
        return self._require_json_object(
            self._request('GET','/api/v3/orderList',p,signed=True),
            '/orderList',
        )
    def open_order_lists(self,symbol=None):
        return self._require_json_array(
            self._request('GET','/api/v3/openOrderList',{},signed=True),
            '/openOrderList',
        )

    def all_order_lists(self,symbol=None,limit=100):
        return self._require_json_array(
            self._request('GET','/api/v3/allOrderList',
                          {'limit':limit},signed=True),
            '/allOrderList',
        )

    def open_orders(self,symbol=None):
        return self._require_json_array(
            self._request('GET','/api/v3/openOrders',
                          {'symbol':symbol} if symbol else {},signed=True),
            '/openOrders',
        )
    def websocket_signature_params(self, recv_window=None):
        params = {
            "apiKey": self.api_key,
            "timestamp": int(time.time() * 1000) + self.time_offset_ms,
            "recvWindow": int(
                recv_window if recv_window is not None else self.recv_window
            ),
        }

        # Binance signed requests use the canonical query-string form.
        query = urlencode(sorted(params.items()), doseq=True)

        params["signature"] = hmac.new(
            self.api_secret.encode(),
            query.encode(),
            hashlib.sha256,
        ).hexdigest()

        return params

    def create_user_listen_token(self):
        raise BinanceAPIError(
            'Spot Testnet does not support /sapi/v1/userListenToken; '
            'use userDataStream.subscribe.signature'
        )
    def order_safe(self, symbol, side, type_, *, quantity=None, quote_order_qty=None,
                   price=None, stop_price=None, time_in_force=None,
                   new_client_order_id=None):
        """Place one order and reconcile ambiguous transport failures first.

        The wrapper requires a clientOrderId. POST/5xx/timeout is never blindly
        retried because Binance may already have accepted the order.
        """
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
            )
        except BinanceAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.get_order(
                    symbol,
                    orig_client_order_id=new_client_order_id,
                )
            except BinanceAPIError as reconcile_exc:
                raise BinanceAPIError(
                    f"Order execution is UNKNOWN for {symbol}; "
                    f"clientOrderId={new_client_order_id}. "
                    "Reconciliation is required before retry.",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload=exc.payload,
                ) from reconcile_exc

            status = str(existing.get("status", "")).upper()
            if status in {
                "NEW", "PARTIALLY_FILLED", "FILLED",
                "PENDING_CANCEL", "PENDING_NEW",
            }:
                return existing
            raise BinanceAPIError(
                f"Existing Binance order is terminal after ambiguous submission: "
                f"{status or 'UNKNOWN'}",
                payload=existing,
            ) from exc

        status = str(result.get("status", "")).upper()
        if status in {"REJECTED", "EXPIRED", "CANCELED"}:
            raise BinanceAPIError(
                f"Binance order returned terminal failure: {status}",
                payload=result,
            )
        return result

    def order(self,symbol,side,type_,quantity=None,quote_order_qty=None,price=None,stop_price=None,time_in_force=None,new_client_order_id=None):
        p={'symbol':symbol,'side':side,'type':type_,'newOrderRespType':'FULL'}
        if quantity is not None:p['quantity']=quantity
        if quote_order_qty is not None:p['quoteOrderQty']=quote_order_qty
        if price is not None:p['price']=price
        if stop_price is not None:p['stopPrice']=stop_price
        if time_in_force is not None:p['timeInForce']=time_in_force
        if new_client_order_id:p['newClientOrderId']=new_client_order_id
        return self._request('POST','/api/v3/order',p,signed=True)
    def get_order(self,symbol,order_id=None,orig_client_order_id=None):
        p={'symbol':symbol}
        if order_id is not None:p['orderId']=order_id
        if orig_client_order_id is not None:p['origClientOrderId']=orig_client_order_id
        return self._request('GET','/api/v3/order',p,signed=True)
    def cancel_replace(self, symbol, cancel_order_id, side, type_, *, quantity=None, price=None, stop_price=None, time_in_force=None, new_client_order_id=None):
        """Safer single-order cancel/replace using STOP_ON_FAILURE.

        Binance documents cancel-replace as non-transactional: a successful
        cancel can still be followed by a failed replacement. Callers must
        inspect both cancelResult/newOrderResult and reconcile on ambiguity.
        """
        p={
            'symbol':symbol,
            'cancelReplaceMode':'STOP_ON_FAILURE',
            'cancelOrderId':cancel_order_id,
            'side':side,
            'type':type_,
            'newOrderRespType':'FULL',
        }
        if quantity is not None: p['quantity']=quantity
        if price is not None: p['price']=price
        if stop_price is not None: p['stopPrice']=stop_price
        if time_in_force is not None: p['timeInForce']=time_in_force
        if new_client_order_id: p['newClientOrderId']=new_client_order_id
        return self._request('POST','/api/v3/order/cancelReplace',p,signed=True)

    def cancel_order(self,symbol,order_id=None,orig_client_order_id=None):
        p={'symbol':symbol}
        if order_id is not None:p['orderId']=order_id
        if orig_client_order_id is not None:p['origClientOrderId']=orig_client_order_id
        return self._request('DELETE','/api/v3/order',p,signed=True)
    def cancel_open_orders(self,symbol): return self._request('DELETE','/api/v3/openOrders',{'symbol':symbol},signed=True)
    def _symbol_tick_size(self, symbol):
        info = self.exchange_info(symbol)
        for row in info.get('symbols', []) or []:
            if str(row.get('symbol', '')).upper() != str(symbol).upper():
                continue
            for f in row.get('filters', []) or []:
                if f.get('filterType') == 'PRICE_FILTER':
                    tick = Decimal(str(f.get('tickSize', '0')))
                    if tick > 0:
                        return tick
        raise BinanceAPIError(f'Binance PRICE_FILTER/tickSize unavailable for {symbol}')

    def create_oco_sell(self,symbol,quantity,take_profit_price,stop_price,stop_limit_price,list_client_order_id=None):
        # For SELL TAKE_PROFIT_LIMIT the limit leg is kept strictly below the
        # trigger and quantized to Binance's actual PRICE_FILTER tickSize.
        tick = self._symbol_tick_size(symbol)
        take_trigger = self.decimal_floor(Decimal(str(take_profit_price)), tick)
        take_limit = self.decimal_floor(take_trigger - tick, tick)
        stop_trigger = self.decimal_floor(Decimal(str(stop_price)), tick)
        stop_limit = self.decimal_floor(Decimal(str(stop_limit_price)), tick)
        if stop_limit >= stop_trigger:
            raise BinanceAPIError(
                f'Invalid Binance OCO stop leg for {symbol}: '
                f'stop_limit={stop_limit} must be below stop_trigger={stop_trigger}'
            )
        if take_limit <= stop_trigger:
            raise BinanceAPIError(
                f'Invalid Binance OCO price relationship for {symbol}: '
                f'take_limit={take_limit} stop_price={stop_price}'
            )
        p={
            'symbol':symbol,'side':'SELL','quantity':quantity,
            'aboveType':'TAKE_PROFIT_LIMIT',
            'abovePrice':str(take_limit),
            'aboveStopPrice':str(take_trigger),
            'aboveTimeInForce':'GTC',
            'belowType':'STOP_LOSS_LIMIT',
            'belowStopPrice':str(stop_trigger),
            'belowPrice':str(stop_limit),
            'belowTimeInForce':'GTC',
            'newOrderRespType':'FULL'
        }
        if list_client_order_id:p['listClientOrderId']=list_client_order_id
        return self._request('POST','/api/v3/orderList/oco',p,signed=True)
    def create_oco_sell_safe(
        self, symbol, quantity, take_profit_price, stop_price,
        stop_limit_price, list_client_order_id
    ):
        """Create one OCO and reconcile ambiguous transport failures by list ID."""
        if not list_client_order_id:
            raise ValueError("create_oco_sell_safe requires list_client_order_id")
        try:
            return self.create_oco_sell(
                symbol, quantity, take_profit_price, stop_price,
                stop_limit_price, list_client_order_id,
            )
        except BinanceAPIError as exc:
            if not exc.unknown_execution:
                raise
            try:
                existing = self.order_list(
                    symbol,
                    list_client_order_id=list_client_order_id,
                )
            except BinanceAPIError as reconcile_exc:
                raise BinanceAPIError(
                    f"OCO execution is UNKNOWN for {symbol}; "
                    f"listClientOrderId={list_client_order_id}. "
                    "Reconciliation is required before retry.",
                    unknown_execution=True,
                    status_code=exc.status_code,
                    payload=exc.payload,
                ) from reconcile_exc
            if existing:
                return existing
            raise BinanceAPIError(
                f"OCO execution is UNKNOWN for {symbol}; no matching order list found.",
                unknown_execution=True,
                status_code=exc.status_code,
                payload=exc.payload,
            ) from exc

    def cancel_oco(self,symbol,order_list_id=None,list_client_order_id=None):
        p={'symbol':symbol}
        if order_list_id is not None:p['orderListId']=order_list_id
        if list_client_order_id is not None:p['listClientOrderId']=list_client_order_id
        return self._request('DELETE','/api/v3/orderList',p,signed=True)
    @staticmethod
    def decimal_floor(value,step): return (Decimal(str(value))/Decimal(str(step))).to_integral_value(rounding=ROUND_DOWN)*Decimal(str(step))
    @staticmethod
    def decimal_format(value): return format(Decimal(str(value)).normalize(),'f')

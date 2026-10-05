import hashlib, hmac, time
from decimal import Decimal, ROUND_DOWN
from urllib.parse import urlencode
import requests

class BinanceAPIError(RuntimeError): pass

class BinanceSpotClient:
    def __init__(self, api_key, api_secret, testnet=True, recv_window=5000, timeout=20):
        self.api_key=api_key; self.api_secret=api_secret; self.testnet=bool(testnet)
        self.base_url='https://testnet.binance.vision' if self.testnet else 'https://api.binance.com'
        self.recv_window=int(recv_window); self.timeout=timeout
        self.session=requests.Session(); self.session.headers.update({'X-MBX-APIKEY': self.api_key})
        self.time_offset_ms=0
    def _request(self, method, path, params=None, signed=False):
        """
        Execute a Binance REST request.

        GET requests are safe to retry on transient network failures.
        POST/DELETE requests are intentionally NOT retried because repeating
        an order/cancel request can create dangerous duplicate side effects.

        Signed requests rebuild timestamp/signature on every attempt so a
        retry cannot reuse an expired timestamp.
        """
        method = method.upper()
        max_attempts = 3 if method == "GET" else 1
        delays = (0.5, 1.0, 2.0)

        last_exc = None

        for attempt in range(max_attempts):
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

                if attempt + 1 >= max_attempts:
                    raise

                time.sleep(delays[attempt])

                continue

            try:
                payload = r.json()
            except ValueError:
                payload = {'code': r.status_code, 'msg': r.text}

            if r.status_code >= 400 or (
                isinstance(payload, dict)
                and payload.get('code', 0) < 0
            ):
                raise BinanceAPIError(
                    f'Binance {r.status_code}: {payload}'
                )

            return payload

        if last_exc is not None:
            raise last_exc

        raise BinanceAPIError(
            f'Binance request failed: {method} {path}'
        )
    def sync_time(self):
        server=self._request('GET','/api/v3/time'); self.time_offset_ms=int(server['serverTime'])-int(time.time()*1000); return server
    def ping(self): return self._request('GET','/api/v3/ping')
    def exchange_info(self,symbol=None): return self._request('GET','/api/v3/exchangeInfo',{'symbol':symbol} if symbol else {})
    def ticker_price(self,symbol): return self._request('GET','/api/v3/ticker/price',{'symbol':symbol})
    def ticker_24hr(self,symbol=None): return self._request('GET','/api/v3/ticker/24hr',{'symbol':symbol} if symbol else {})
    def book_ticker(self,symbol): return self._request('GET','/api/v3/ticker/bookTicker',{'symbol':symbol})
    def klines(self,symbol,interval,limit=200): return self._request('GET','/api/v3/klines',{'symbol':symbol,'interval':interval,'limit':limit})
    def account(self): return self._request('GET','/api/v3/account',signed=True)
    def all_orders(self,symbol,limit=1000): return self._request('GET','/api/v3/allOrders',{'symbol':symbol,'limit':limit},signed=True)
    def order_list(self,symbol,order_list_id=None,list_client_order_id=None):
        p={'symbol':symbol}
        if order_list_id is not None: p['orderListId']=order_list_id
        if list_client_order_id is not None: p['origClientOrderId']=list_client_order_id
        return self._request('GET','/api/v3/orderList',p,signed=True)
    def open_order_lists(self,symbol=None): return self._request('GET','/api/v3/openOrderList',{},signed=True)
    def all_order_lists(self,symbol=None,limit=100): return self._request('GET','/api/v3/allOrderList',{'limit':limit},signed=True)
    def open_orders(self,symbol=None): return self._request('GET','/api/v3/openOrders',{'symbol':symbol} if symbol else {},signed=True)
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
    def cancel_order(self,symbol,order_id=None,orig_client_order_id=None):
        p={'symbol':symbol}
        if order_id is not None:p['orderId']=order_id
        if orig_client_order_id is not None:p['origClientOrderId']=orig_client_order_id
        return self._request('DELETE','/api/v3/order',p,signed=True)
    def cancel_open_orders(self,symbol): return self._request('DELETE','/api/v3/openOrders',{'symbol':symbol},signed=True)
    def create_oco_sell(self,symbol,quantity,take_profit_price,stop_price,stop_limit_price,list_client_order_id=None):
        p={'symbol':symbol,'side':'SELL','quantity':quantity,'aboveType':'TAKE_PROFIT_LIMIT','abovePrice':take_profit_price,'aboveStopPrice':take_profit_price,'aboveTimeInForce':'GTC','belowType':'STOP_LOSS_LIMIT','belowStopPrice':stop_price,'belowPrice':stop_limit_price,'belowTimeInForce':'GTC','newOrderRespType':'FULL'}
        if list_client_order_id:p['listClientOrderId']=list_client_order_id
        return self._request('POST','/api/v3/orderList/oco',p,signed=True)
    def cancel_oco(self,symbol,order_list_id=None,list_client_order_id=None):
        p={'symbol':symbol}
        if order_list_id is not None:p['orderListId']=order_list_id
        if list_client_order_id is not None:p['listClientOrderId']=list_client_order_id
        return self._request('DELETE','/api/v3/orderList',p,signed=True)
    @staticmethod
    def decimal_floor(value,step): return (Decimal(str(value))/Decimal(str(step))).to_integral_value(rounding=ROUND_DOWN)*Decimal(str(step))
    @staticmethod
    def decimal_format(value): return format(Decimal(str(value)).normalize(),'f')

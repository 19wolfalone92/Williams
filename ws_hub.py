import asyncio,json,logging,os,threading,time
from datetime import datetime,timezone
import websocket
import pandas as pd
from binance_client import BinanceSpotClient
from db import Database
from data import fetch_klines
from strategy import calculate_indicators,config_from_env
log=logging.getLogger('williams-ws')
class WebSocketHub:
    def __init__(self):
        self.symbol=os.getenv('SYMBOL','BTCUSDT').upper(); self.interval=os.getenv('INTERVAL','1h'); self.testnet=os.getenv('TESTNET','true').lower()=='true'; self.api_key=os.getenv('BINANCE_API_KEY',''); self.api_secret=os.getenv('BINANCE_API_SECRET',''); self.db=Database(
            os.getenv('WILLIAMS_DB_PATH')
            or os.getenv('DB_PATH')
            or 'data/trader.sqlite3'
        ); self.client=BinanceSpotClient(self.api_key,self.api_secret,self.testnet); self.clients=set(); self.lock=threading.RLock(); self.stop_event=threading.Event(); self.threads=[]; self.price=None; self.candles=[]; self.position=None; self.orders=[]; self.tp=None; self.sl=None; self.balance=None; self.base_asset=None; self.quote_asset=None; self.last_error=None; self.market_connected=False; self.user_connected=False; self._market_ws=None; self._user_ws=None; self.user_subscription_id=None; self.started=False
        self.market_connected_once=False
        self.market_reconnects=0
        self.market_last_message_at=None
        self.market_last_error=None
        self.user_sync_required=True
        self.user_last_event_at=None
        self._last_user_event_by_type={}
        self.user_stream_reconnects=0; self.user_connection_started_at=None
    def configure_credentials(self,key,secret,testnet=True):
        with self.lock:
            self.api_key=key.strip()
            self.api_secret=secret.strip()
            self.testnet=bool(testnet)
            self.client=BinanceSpotClient(
                self.api_key,
                self.api_secret,
                self.testnet
            )
            self.user_connected=False
            self.user_subscription_id=None

        # Only start the user thread when the hub is already running.
        # During startup(), start() will create both threads itself.
        if self.started and not self.stop_event.is_set():
            if not any(
                t.name=='williams-user-ws' and t.is_alive()
                for t in self.threads
            ):
                t=threading.Thread(
                    target=self._user_loop,
                    daemon=True,
                    name='williams-user-ws'
                )
                t.start()
                self.threads.append(t)

    def add_client(self,c):
        with self.lock:self.clients.add(c); snap=self.snapshot()
        self._queue(c,{'type':'snapshot','data':snap})
    def remove_client(self,c):
        with self.lock:self.clients.discard(c)
    def _queue(self,c,p):
        try:c.loop.call_soon_threadsafe(c.queue.put_nowait,p)
        except Exception:pass
    def broadcast(self,p):
        with self.lock:cs=list(self.clients)
        for c in cs:self._queue(c,p)
    def snapshot(self):
        with self.lock:
            return {
                'symbol':self.symbol,
                'interval':self.interval,
                'testnet':self.testnet,
                'price':self.price,
                'quote_balance':self.balance,
                'position':dict(self.position) if self.position else None,
                'take_profit_price':self.tp,
                'stop_loss_price':self.sl,
                'candles':list(self.candles),
                'orders':list(self.orders),
                'server_time':datetime.now(timezone.utc).isoformat(),
                'market_connected':self.market_connected,
                'user_connected':self.user_connected,
                'user_subscription_id':self.user_subscription_id,
                'user_sync_required':self.user_sync_required,
                'user_last_event_at':self.user_last_event_at,
                'user_stream_reconnects':self.user_stream_reconnects,
                'user_connection_started_at':self.user_connection_started_at,
                'market_reconnects':self.market_reconnects,
                'market_last_message_at':self.market_last_message_at,
                'market_last_error':self.market_last_error,
                'ws_connected':(
                    self.market_connected
                    and (not self.api_key or self.user_connected)
                ),
                'last_error':self.last_error,
            }

    def start(self):
        self.stop_event.clear()
        self.started=True

        # Bootstrap only once per running session.
        self._bootstrap()

        if not any(
            t.name=='williams-market-ws' and t.is_alive()
            for t in self.threads
        ):
            t=threading.Thread(
                target=self._market_loop,
                daemon=True,
                name='williams-market-ws'
            )
            t.start()
            self.threads.append(t)

        if not any(
            t.name=='williams-user-ws' and t.is_alive()
            for t in self.threads
        ):
            t=threading.Thread(
                target=self._user_loop,
                daemon=True,
                name='williams-user-ws'
            )
            t.start()
            self.threads.append(t)

    def stop(self):
        self.started=False
        self.stop_event.set()

        for ws in (self._market_ws,self._user_ws):
            if ws is not None:
                try:
                    ws.close()
                except Exception:
                    pass

        self.market_connected=False
        self.user_connected=False
        self.user_subscription_id=None

        current=threading.current_thread()

        for t in list(self.threads):
            if t is current:
                continue
            try:
                t.join(timeout=3)
            except Exception:
                pass

        self.threads=[
            t for t in self.threads
            if t.is_alive() and t is not current
        ]

        self._market_ws=None
        self._user_ws=None

    def _bootstrap(self):
        try:
            self.client.sync_time(); info=self.client.exchange_info(self.symbol); s=info['symbols'][0]; self.base_asset=s['baseAsset'];self.quote_asset=s['quoteAsset']; self._load_candles();self._refresh_account()
        except Exception as e:self.last_error=f'bootstrap: {e}'
    def _load_candles(self):
        df=fetch_klines(self.client,self.symbol,self.interval,limit=120)
        if df.empty:return
        ind=calculate_indicators(df.iloc[:-1].copy(),config_from_env()); self.candles=[self._row(i,r) for i,r in ind.iterrows()]
    def _row(self,i,r):return {'time':i.isoformat(),'open':float(r.open),'high':float(r.high),'low':float(r.low),'close':float(r.close),'jaw':None if pd.isna(r.jaw_shifted) else float(r.jaw_shifted),'teeth':None if pd.isna(r.teeth_shifted) else float(r.teeth_shifted),'lips':None if pd.isna(r.lips_shifted) else float(r.lips_shifted),'ao':None if pd.isna(r.ao) else float(r.ao),'long_signal':bool(r.long_signal),'fractal_up':bool(r.fractal_up),'fractal_down':bool(r.fractal_down)}
    def _refresh_account(self):
        if not self.api_key or not self.api_secret:return
        try:
            a=self.client.account(); self.balance=next((float(b['free']) for b in a.get('balances',[]) if b['asset']==self.quote_asset),0.0); qty=next((float(b['free'])+float(b['locked']) for b in a.get('balances',[]) if b['asset']==self.base_asset),0.0); tr=self.db.open_trade(); self.position={'side':'LONG','quantity':qty,'entry_price':float(tr['entry_price']) if tr else None} if tr and qty>0 else None; self._refresh_orders()
        except Exception as e:self.last_error=f'account: {e}'
    def _refresh_orders(self):
        try:
            active=[o for o in self.db.recent_orders(self.symbol,100) if str(o.get('status','')).upper() in {'NEW','PENDING_NEW','PARTIALLY_FILLED'}];self.orders=active[:20];self.tp=self.sl=None
            for o in active:
                typ=str(o.get('type','')).upper()
                if 'TAKE_PROFIT' in typ and o.get('price'):self.tp=float(o['price'])
                elif 'STOP_LOSS' in typ:self.sl=float(o.get('stop_price') or o.get('price') or 0) or None
        except Exception:pass
    def _market_url(self):
        base='wss://stream.testnet.binance.vision' if self.testnet else 'wss://stream.binance.com:9443';s=self.symbol.lower();return f'{base}/stream?streams={s}@aggTrade/{s}@kline_{self.interval}'
    def _market_loop(self):
        delay=1

        while not self.stop_event.is_set():
            app=None

            try:
                app=websocket.WebSocketApp(
                    self._market_url(),
                    on_open=lambda ws:self._set_market(True),
                    on_message=self._on_market_message,
                    on_error=lambda ws,e:self._set_market(
                        False,
                        str(e)
                    ),
                    on_close=lambda ws,c,m:self._set_market(
                        False,
                        f'connection closed (code={c}, reason={m or "none"})'
                    )
                )

                self._market_ws=app

                app.run_forever(
                    ping_interval=30,
                    ping_timeout=20
                )

                delay=1

            except Exception as e:
                self._set_market(False,str(e))

            finally:
                if self._market_ws is app:
                    self._market_ws=None

            if not self.stop_event.is_set():
                self.stop_event.wait(delay)
                delay=min(delay*2,30)

    def _set_market(self,connected,error=None):
        was_connected = self.market_connected
        self.market_connected = connected

        if connected:
            if self.market_connected_once and not was_connected:
                self.market_reconnects += 1

            self.market_connected_once = True
            self.market_last_error = None

            # A successful reconnect/open clears the previous
            # market WS error instead of leaving a stale red state.
            if self.last_error and self.last_error.startswith('market ws:'):
                self.last_error = None

        else:
            if error:
                self.market_last_error = str(error)
                self.last_error = f'market ws: {error}'

        self.broadcast({'type':'ticker','data':self._live()})
    def _user_loop(self):
        delay=1

        while not self.stop_event.is_set():
            if not self.api_key or not self.api_secret:
                self.user_connected=False
                self.stop_event.wait(1)
                continue

            app=None

            try:
                # Synchronise local timestamp with Binance before signing.
                try:
                    self.client.sync_time()
                except Exception as e:
                    self._set_user(
                        False,
                        f"sync_time: {type(e).__name__}: {e}"
                    )
                    self.stop_event.wait(delay)
                    continue

                params=self.client.websocket_signature_params()

                url=(
                    'wss://ws-api.testnet.binance.vision/ws-api/v3'
                    if self.testnet
                    else
                    'wss://ws-api.binance.com:443/ws-api/v3'
                )

                self.user_connected=False
                self.user_subscription_id=None
                self.user_sync_required=True

                reconnect_timer = None

                def opened(ws):
                    nonlocal reconnect_timer
                    self._user_ws=ws
                    self.user_connection_started_at=datetime.now(timezone.utc).isoformat()

                    # Spot signed user-data WebSocket sessions have a finite
                    # lifetime. Reconnect before the 24h boundary so the bot
                    # never depends on an exchange-side disconnect to recover.
                    def proactive_reconnect():
                        if not self.stop_event.is_set():
                            try:
                                ws.close()
                            except Exception:
                                pass
                    reconnect_timer = threading.Timer(23 * 60 * 60, proactive_reconnect)
                    reconnect_timer.daemon = True
                    reconnect_timer.start()

                    request={
                        'id':f'williams-user-{int(time.time()*1000)}',
                        'method':'userDataStream.subscribe.signature',
                        'params':params,
                    }

                    ws.send(
                        json.dumps(
                            request,
                            separators=(',',':')
                        )
                    )

                def on_message(ws,raw):
                    self._on_user_message(ws,raw)

                def on_error(ws,error):
                    self._set_user(
                        False,
                        f'{type(error).__name__}: {error}'
                    )

                def on_close(ws,code,msg):
                    nonlocal reconnect_timer
                    if reconnect_timer is not None:
                        reconnect_timer.cancel()
                        reconnect_timer = None
                    self.user_connected=False
                    self.user_subscription_id=None
                    self.user_sync_required=True

                    if not self.stop_event.is_set():
                        self.last_error=(
                            f'user ws: closed {code} {msg}'
                        )

                    self.broadcast({
                        'type':'account',
                        'data':self._live()
                    })

                # Binance requires the API key in the HTTP/WebSocket header.
                headers=[
                    f'X-MBX-APIKEY: {self.api_key}'
                ]

                app=websocket.WebSocketApp(
                    url,
                    header=headers,
                    on_open=opened,
                    on_message=on_message,
                    on_error=on_error,
                    on_close=on_close
                )

                self._user_ws=app

                app.run_forever(
                    ping_interval=30,
                    ping_timeout=20
                )

            except Exception as e:
                self._set_user(
                    False,
                    f'{type(e).__name__}: {e}'
                )

            finally:
                if reconnect_timer is not None:
                    reconnect_timer.cancel()
                    reconnect_timer = None
                self.user_connected=False

                if self._user_ws is app:
                    self._user_ws=None

            if self.stop_event.is_set():
                break

            self.stop_event.wait(delay)
            delay=min(delay*2,30)

    def _set_user(self,connected,error=None):
        self.user_connected=connected

        if not connected:
            self.user_subscription_id=None
            self.user_sync_required=True

        if error:
            self.last_error=f'user ws: {error}'

        self.broadcast({
            'type':'account',
            'data':self._live()
        })

    def _on_market_message(self,ws,raw):
        try:
            self.market_last_message_at=datetime.now(timezone.utc).isoformat()

            d=json.loads(raw).get('data',{})
            ev=d.get('e')

            if ev=='aggTrade':
                self.price=float(d['p'])
                self.broadcast({'type':'ticker','data':self._live()})

            elif ev=='kline':
                self._update_kline(d['k'])

        except Exception as e:
            log.debug('market parse: %s',e)
    def _update_kline(self,k):
        item={'time':datetime.fromtimestamp(int(k['t'])/1000,tz=timezone.utc).isoformat(),'open':float(k['o']),'high':float(k['h']),'low':float(k['l']),'close':float(k['c'])}
        raw=list(self.candles)
        if raw and raw[-1].get('time')==item['time']:raw[-1].update(item)
        else:raw.append(item)
        raw=raw[-120:];df=pd.DataFrame(raw);df['time']=pd.to_datetime(df['time'],utc=True);df=df.set_index('time')[['open','high','low','close']];ind=calculate_indicators(df,config_from_env());self.candles=[self._row(i,r) for i,r in ind.iterrows()][-120:];self.broadcast({'type':'candle','data':self.candles[-1]})
    def _on_user_message(self,ws,raw):
        try:
            msg=json.loads(raw)

            # Successful signed user-stream subscription.
            if (
                msg.get('status')==200
                and isinstance(msg.get('result'),dict)
                and msg.get('result',{}).get('subscriptionId') is not None
            ):
                self.user_subscription_id=(
                    msg['result']['subscriptionId']
                )
                self.last_error=None
                self.user_sync_required=True
                self.user_stream_reconnects += 1
                self._set_user(True)

                # Every successful reconnect starts with a REST snapshot.
                # This closes the window in which executionReport events could
                # have been missed while the socket was down.
                try:
                    self._refresh_account()
                    self._refresh_orders()
                    self.user_sync_required=False
                except Exception as e:
                    self._set_user(
                        False,
                        f'resync after user-stream reconnect: {type(e).__name__}: {e}'
                    )
                return

            # WS API error.
            if msg.get('status') not in (None,200):
                err=msg.get('error') or {}

                if isinstance(err,dict):
                    code=err.get('code')
                    text=err.get('msg')
                else:
                    code=None
                    text=str(err)

                self._set_user(
                    False,
                    f'Binance WS API error code={code}: {text}'
                )
                return

            event=msg.get('event',msg)

            if not isinstance(event,dict):
                return

            event_type=event.get('e')
            event_time=int(event.get('E') or 0)
            self.user_last_event_at=datetime.now(timezone.utc).isoformat()

            if event_time:
                previous=self._last_user_event_by_type.get(event_type)
                if previous is not None and event_time < previous:
                    self.user_sync_required=True
                    self.db.log_event(
                        'ERROR',
                        'user_stream_out_of_order',
                        f'Out-of-order {event_type}: previous={previous}, current={event_time}',
                        raw=event,
                    )
                self._last_user_event_by_type[event_type]=max(
                    previous or 0,
                    event_time,
                )

            self.db.log_event(
                'INFO',
                'user_stream_event',
                event_type,
                raw=event,
            )

            if event_type=='eventStreamTerminated':
                self._set_user(
                    False,
                    'Binance user stream terminated'
                )

                try:
                    ws.close()
                except Exception:
                    pass

                return

            if event_type=='executionReport':
                o={
                    'symbol':event.get('s'),
                    'side':event.get('S'),
                    'type':event.get('o'),
                    'orderId':event.get('i'),
                    'orderListId':event.get('g'),
                    'clientOrderId':event.get('c'),
                    'status':event.get('X'),
                    'price':event.get('p'),
                    'stopPrice':event.get('P'),
                    'origQty':event.get('q'),
                    'executedQty':event.get('z'),
                    'cummulativeQuoteQty':event.get('Z'),
                    'time':event.get('O'),
                    'updateTime':event.get('T'),
                }

                if o['symbol']==self.symbol:
                    self.db.save_order(o)
                    self._refresh_orders()

                status=str(o.get('status','')).upper()
                if status=='FILLED':
                    self._refresh_account()

                if status in {'FILLED','CANCELED','REJECTED','EXPIRED'}:
                    self.user_sync_required=True
                    try:
                        self._refresh_account()
                        self.user_sync_required=False
                    except Exception as e:
                        self._set_user(
                            False,
                            f'post-execution resync: {type(e).__name__}: {e}'
                        )

            elif event_type=='outboundAccountPosition':
                self._refresh_account()

            elif event_type=='balanceUpdate':
                self._refresh_account()

            self.broadcast({
                'type':'account',
                'data':self._live()
            })

        except Exception as e:
            log.debug(
                'user parse: %s',
                e
            )

    def _live(self):
        with self.lock:p=self.price;pos=dict(self.position) if self.position else None;tp=self.tp;sl=self.sl;bal=self.balance
        pnl=pct=None
        if pos and p is not None and pos.get('entry_price'):pnl=(p-pos['entry_price'])*pos['quantity'];pct=p/pos['entry_price']-1
        return {
            'symbol':self.symbol,
            'interval':self.interval,
            'testnet':self.testnet,
            'price':p,
            'quote_balance':bal,
            'position':pos,
            'take_profit_price':tp,
            'stop_loss_price':sl,
            'pnl':pnl,
            'pnl_pct':pct,
            'server_time':datetime.now(timezone.utc).isoformat(),
            'ws_connected':self.market_connected and (not self.api_key or self.user_connected),
            'market_connected':self.market_connected,
            'market_reconnects':self.market_reconnects,
            'market_last_message_at':self.market_last_message_at,
            'market_last_error':self.market_last_error,
            'last_error':self.last_error,
        }

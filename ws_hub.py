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
        self.symbol=os.getenv('SYMBOL','BTCUSDT').upper(); self.interval=os.getenv('INTERVAL','1h'); self.testnet=os.getenv('TESTNET','true').lower()=='true'; self.api_key=os.getenv('BINANCE_API_KEY',''); self.api_secret=os.getenv('BINANCE_API_SECRET',''); self.db=Database(os.getenv('DB_PATH','data/trader.sqlite3')); self.client=BinanceSpotClient(self.api_key,self.api_secret,self.testnet); self.clients=set(); self.lock=threading.RLock(); self.stop_event=threading.Event(); self.threads=[]; self.price=None; self.candles=[]; self.position=None; self.orders=[]; self.tp=None; self.sl=None; self.balance=None; self.base_asset=None; self.quote_asset=None; self.last_error=None; self.market_connected=False; self.user_connected=False
    def configure_credentials(self,key,secret,testnet=True):
        with self.lock:
            self.api_key=key; self.api_secret=secret; self.testnet=testnet; self.client=BinanceSpotClient(key,secret,testnet); self.user_connected=False
        if not self.stop_event.is_set() and not any(t.name=='williams-user-ws' and t.is_alive() for t in self.threads):
            t=threading.Thread(target=self._user_loop,daemon=True,name='williams-user-ws'); t.start(); self.threads.append(t)
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
        with self.lock:return {'symbol':self.symbol,'interval':self.interval,'testnet':self.testnet,'price':self.price,'quote_balance':self.balance,'position':dict(self.position) if self.position else None,'take_profit_price':self.tp,'stop_loss_price':self.sl,'candles':list(self.candles),'orders':list(self.orders),'server_time':datetime.now(timezone.utc).isoformat(),'ws_connected':self.market_connected and (not self.api_key or self.user_connected),'last_error':self.last_error}
    def start(self):
        if any(t.is_alive() for t in self.threads):return
        self.stop_event.clear(); self._bootstrap()
        for target in (self._market_loop,self._user_loop):
            t=threading.Thread(target=target,daemon=True);t.start();self.threads.append(t)
    def stop(self):self.stop_event.set();self.market_connected=False;self.user_connected=False
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
            try:
                app=websocket.WebSocketApp(self._market_url(),on_open=lambda ws:self._set_market(True),on_message=self._on_market_message,on_error=lambda ws,e:self._set_market(False,str(e)),on_close=lambda ws,c,m:self._set_market(False,f'closed {c}'))
                # Rotate the user stream before Binance's 24h token lifetime expires.
                app.run_forever(ping_interval=30,ping_timeout=10); delay=1
            except Exception as e:self._set_market(False,str(e))
            if not self.stop_event.is_set():time.sleep(delay);delay=min(delay*2,30)
    def _set_market(self,connected,error=None):
        self.market_connected=connected
        if error:self.last_error=f'market ws: {error}'
        self.broadcast({'type':'ticker','data':self._live()})
    def _user_loop(self):
        delay=1
        while not self.stop_event.is_set() and (not self.api_key or not self.api_secret):
            time.sleep(1)
        while not self.stop_event.is_set():
            try:
                token=self.client.create_user_listen_token()['token']; self.user_stream_started_at=time.time()
                url='wss://ws-api.testnet.binance.vision/ws-api/v3' if self.testnet else 'wss://ws-api.binance.com:443/ws-api/v3'
                def opened(ws):
                    self.user_connected=False
                    ws.send(json.dumps({'id':f'williams-{int(time.time()*1000)}','method':'userDataStream.subscribe.listenToken','params':{'listenToken':token}}))
                    # Force a clean reconnect before the listen token reaches its 24h lifetime.
                    timer=threading.Timer(23*3600, lambda: ws.close(1000, 'proactive user stream rotation'))
                    timer.daemon=True; timer.start()
                app=websocket.WebSocketApp(url,on_open=opened,on_message=self._on_user_message,on_error=lambda ws,e:self._set_user(False,str(e)),on_close=lambda ws,c,m:self._set_user(False,f'closed {c}'))
                app.run_forever(ping_interval=30,ping_timeout=10);delay=1
            except Exception as e:self._set_user(False,str(e))
            if not self.stop_event.is_set():
                # Binance listen tokens are valid for up to 24h; reconnect proactively at 23h.
                if self.user_stream_started_at and time.time()-self.user_stream_started_at < 23*3600:
                    time.sleep(delay)
                else:
                    self.user_stream_started_at=0.0; time.sleep(1)
                delay=min(delay*2,30)
    def _set_user(self,connected,error=None):
        self.user_connected=connected
        if error:self.last_error=f'user ws: {error}'
        self.broadcast({'type':'account','data':self._live()})
    def _on_market_message(self,ws,raw):
        try:
            d=json.loads(raw).get('data',{}); ev=d.get('e')
            if ev=='aggTrade':self.price=float(d['p']);self.broadcast({'type':'ticker','data':self._live()})
            elif ev=='kline':self._update_kline(d['k'])
        except Exception as e:log.debug('market parse: %s',e)
    def _update_kline(self,k):
        item={'time':datetime.fromtimestamp(int(k['t'])/1000,tz=timezone.utc).isoformat(),'open':float(k['o']),'high':float(k['h']),'low':float(k['l']),'close':float(k['c'])}
        raw=list(self.candles)
        if raw and raw[-1].get('time')==item['time']:raw[-1].update(item)
        else:raw.append(item)
        raw=raw[-120:];df=pd.DataFrame(raw);df['time']=pd.to_datetime(df['time'],utc=True);df=df.set_index('time')[['open','high','low','close']];ind=calculate_indicators(df,config_from_env());self.candles=[self._row(i,r) for i,r in ind.iterrows()][-120:];self.broadcast({'type':'candle','data':self.candles[-1]})
    def _on_user_message(self,ws,raw):
        try:
            msg=json.loads(raw)
            if msg.get('status')==200 and msg.get('result',{}).get('subscriptionId') is not None:self._set_user(True);return
            event=msg.get('event',msg)
            if event.get('e')=='eventStreamTerminated':self._set_user(False,'Binance user stream terminated');ws.close();return
            if event.get('e')=='executionReport':
                o={'symbol':event.get('s'),'side':event.get('S'),'type':event.get('o'),'orderId':event.get('i'),'orderListId':event.get('g'),'clientOrderId':event.get('c'),'status':event.get('X'),'price':event.get('p'),'stopPrice':event.get('P'),'origQty':event.get('q'),'executedQty':event.get('z'),'cummulativeQuoteQty':event.get('Z'),'time':event.get('O'),'updateTime':event.get('T')};
                if o['symbol']==self.symbol:self.db.save_order(o);self._refresh_orders()
                if str(o['status']).upper()=='FILLED':self._refresh_account()
            elif event.get('e')=='outboundAccountPosition':self._refresh_account()
            self.broadcast({'type':'account','data':self._live()})
        except Exception as e:log.debug('user parse: %s',e)
    def _live(self):
        with self.lock:p=self.price;pos=dict(self.position) if self.position else None;tp=self.tp;sl=self.sl;bal=self.balance
        pnl=pct=None
        if pos and p is not None and pos.get('entry_price'):pnl=(p-pos['entry_price'])*pos['quantity'];pct=p/pos['entry_price']-1
        return {'symbol':self.symbol,'interval':self.interval,'testnet':self.testnet,'price':p,'quote_balance':bal,'position':pos,'take_profit_price':tp,'stop_loss_price':sl,'pnl':pnl,'pnl_pct':pct,'server_time':datetime.now(timezone.utc).isoformat(),'ws_connected':self.market_connected and (not self.api_key or self.user_connected),'last_error':self.last_error}

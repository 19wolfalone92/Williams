import os, threading, time, asyncio, json
from datetime import datetime, timezone
from typing import Optional
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel
from db import Database
from trader import Trader
from data import fetch_klines
from strategy import calculate_indicators, config_from_env
from ws_hub import WebSocketHub
from credentials_store import CredentialStore
from market_scanner import MarketScanner

load_dotenv(); API_TOKEN=os.getenv('MOBILE_API_TOKEN','').strip(); VERSION='4.12.0'
app=FastAPI(title='Williams Binance Bot API',version=VERSION); hub=WebSocketHub()
class CredentialPayload(BaseModel): api_key:str; api_secret:str; testnet:bool=True
class ControlState:
    def __init__(self):
        self.lock=threading.RLock(); self.trader=None; self.thread=None; self.running=False; self.paused=False; self.last_error=None; self.api_key=''; self.api_secret=''; self.testnet=True
        self.credentials=CredentialStore()
        stored=self.credentials.load()
        if stored:
            self.api_key=stored['api_key']; self.api_secret=stored['api_secret']; self.testnet=stored['testnet']
    def configure(self,key,secret,testnet=True):
        with self.lock:
            key=key.strip(); secret=secret.strip(); testnet=bool(testnet)
            if self.running and (key!=self.api_key or secret!=self.api_secret or testnet!=self.testnet):
                raise RuntimeError('Stop the bot before changing Binance credentials.')
            if self.running and key==self.api_key and secret==self.api_secret and testnet==self.testnet:
                return
            self.api_key=key; self.api_secret=secret; self.testnet=testnet; self.trader=None
            self.credentials.save(self.api_key,self.api_secret,self.testnet)
        hub.configure_credentials(self.api_key,self.api_secret,self.testnet)
    def ensure_trader(self):
        with self.lock:
            if self.trader is None:self.trader=Trader(api_key=self.api_key or None,api_secret=self.api_secret or None,testnet=self.testnet)
            return self.trader
    def loop(self):
        t=self.ensure_trader()
        try:
            t.setup();
            with self.lock:self.running=True; self.last_error=None
            while self.running:
                if not self.paused:
                    try:t.process()
                    except Exception as e:self.last_error=f'{type(e).__name__}: {e}'; t.db.log_event('ERROR','api_loop_error',self.last_error)
                time.sleep(t.poll_seconds)
        except Exception as e:self.last_error=f'{type(e).__name__}: {e}'
        finally:
            with self.lock:self.running=False
    def start(self):
        with self.lock:
            if self.thread is not None and self.thread.is_alive(): return False
            self.paused=False; self.thread=threading.Thread(target=self.loop,daemon=True,name='williams-trader'); self.thread.start(); return True
    def stop(self):
        with self.lock:self.running=False; self.paused=False; thread=self.thread
        if thread is not None and thread.is_alive() and thread is not threading.current_thread():thread.join(timeout=5)
        return True
    def pause(self):
        with self.lock:self.paused=True
        return True
    def resume(self):
        with self.lock:self.paused=False
        return True
state=ControlState()

scanner_lock = threading.RLock()
scanner_cache = {
    "time": 0.0,
    "data": [],
    "scanning": False,
    "last_error": None,
}

SCANNER_CACHE_SECONDS = max(
    10,
    int(os.getenv("SCANNER_CACHE_SECONDS", "30"))
)

def _scanner_snapshot():
    with scanner_lock:
        now = time.time()
        age = (
            round(now - scanner_cache["time"], 2)
            if scanner_cache["time"]
            else None
        )

        fresh = bool(
            scanner_cache["time"]
            and age is not None
            and age < SCANNER_CACHE_SECONDS
        )

        return {
            "cached": bool(scanner_cache["data"]),
            "fresh": fresh,
            "cache_ttl_seconds": SCANNER_CACHE_SECONDS,
            "scanning": bool(scanner_cache["scanning"]),
            "age_seconds": age,
            "last_error": scanner_cache["last_error"],
            "candidates": list(scanner_cache["data"]),
        }

def _scanner_cache_fresh():
    with scanner_lock:
        if not scanner_cache["time"]:
            return False
        return (time.time() - scanner_cache["time"]) < SCANNER_CACHE_SECONDS

def _scanner_worker():
    try:
        with scanner_lock:
            scanner_cache["last_error"] = None

        t = state.ensure_trader()
        results = MarketScanner(t.client).scan()
        data = [candidate.to_dict() for candidate in results]

        with scanner_lock:
            scanner_cache["time"] = time.time()
            scanner_cache["data"] = data

    except Exception as exc:
        with scanner_lock:
            scanner_cache["last_error"] = (
                f"{type(exc).__name__}: {exc}"
            )

    finally:
        with scanner_lock:
            scanner_cache["scanning"] = False

def _start_scanner_background():
    with scanner_lock:
        if scanner_cache["scanning"]:
            return False

        # Mark the scan as active BEFORE starting the thread.
        # This prevents duplicate starts during the tiny thread
        # creation/scheduling window.
        scanner_cache["scanning"] = True

        thread = threading.Thread(
            target=_scanner_worker,
            daemon=True,
            name="williams-scanner",
        )
        thread.start()

        return True
@app.on_event('startup')
def startup():
    hub.configure_credentials(state.api_key,state.api_secret,state.testnet)
    hub.start()
    if os.getenv('AUTO_START','true').lower() == 'true' and state.api_key and state.api_secret:
        state.start()
@app.on_event('shutdown')
def shutdown():hub.stop(); state.stop()
def auth(authorization:Optional[str]=Header(None)):
    if len(API_TOKEN)<32:raise HTTPException(503,'MOBILE_API_TOKEN is not configured or is too short (minimum 32 characters).')
    if authorization!=f'Bearer {API_TOKEN}':raise HTTPException(401,'Unauthorized')
def db():return state.ensure_trader().db
@app.get('/api/v1/health')
def health():return {'ok':True,'service':'williams-binance-bot','version':VERSION,'websocket':True,'auth_configured':len(API_TOKEN)>=32}
@app.post('/api/v1/config/binance',dependencies=[Depends(auth)])
def configure(payload:CredentialPayload):
    if not payload.api_key or not payload.api_secret:raise HTTPException(400,'API key and secret are required')
    try:state.configure(payload.api_key,payload.api_secret,payload.testnet)
    except RuntimeError as e:raise HTTPException(409,str(e))
    return {'configured':True,'testnet':payload.testnet}
@app.delete('/api/v1/config/binance',dependencies=[Depends(auth)])
def clear_binance_config():
    state.stop()
    with state.lock:state.api_key='';state.api_secret='';state.trader=None;state.last_error=None;state.paused=False
    state.credentials.clear(); hub.configure_credentials('','',True);return {'configured':False,'cleared':True}
@app.websocket('/api/v1/ws')
async def realtime_ws(websocket:WebSocket):
    if len(API_TOKEN)<32 or websocket.headers.get('authorization')!=f'Bearer {API_TOKEN}':await websocket.close(1008);return
    await websocket.accept(); client=type('RealtimeClient',(),{})();client.websocket=websocket;client.loop=asyncio.get_running_loop();client.queue=asyncio.Queue();hub.add_client(client);sender=asyncio.create_task(_ws_sender(client))
    try:
        while True:
            msg=await websocket.receive_text()
            if msg.lower()=='ping':await websocket.send_text('{"type":"pong"}')
            elif msg.lower()=='snapshot':await websocket.send_text(json.dumps({'type':'snapshot','data':hub.snapshot()},separators=(',',':')))
    except WebSocketDisconnect:pass
    finally:sender.cancel();hub.remove_client(client)
async def _ws_sender(client):
    while True:await client.websocket.send_text(json.dumps(await client.queue.get(),separators=(',',':')))
@app.get('/api/v1/status',dependencies=[Depends(auth)])
def status():
    t=state.ensure_trader();ticker=balance=position=None
    if t.client.api_key and t.client.api_secret:
        try:
            ticker=float(t.client.ticker_price(t.symbol)['price']);balance=t.available_quote();trade=t.db.open_trade()
            if trade:
                qty=t._trade_remaining_qty(trade,t.client.all_orders(t.symbol,limit=1000))
                if qty>=t._min_qty():position={'side':'LONG','quantity':qty,'entry_price':float(trade['entry_price'])}
        except Exception as e:state.last_error=f'status: {e}'
    pnl=pnl_pct=None;tp=sl=None
    if position and ticker is not None:
        entry=position['entry_price'];qty=position['quantity'];pnl=(ticker-entry)*qty;pnl_pct=ticker/entry-1 if entry else None
        for o in db().recent_orders(t.symbol,100):
            if str(o.get('status','')).upper() not in {'NEW','PENDING_NEW','PARTIALLY_FILLED'}:continue
            typ=str(o.get('type','')).upper()
            if 'TAKE_PROFIT' in typ and o.get('price'):tp=float(o['price'])
            elif 'STOP_LOSS' in typ:sl=float(o.get('stop_price') or o.get('price') or 0) or None
    return {'version':VERSION,'symbol':t.symbol,'interval':t.interval,'testnet':t.client.testnet,'running':state.running,'paused':state.paused,'state':t.state(),'recovered':t.recovered,'last_error':state.last_error,'binance_configured':bool(t.client.api_key and t.client.api_secret),'price':ticker,'quote_balance':balance,'position':position,'pnl':pnl,'pnl_pct':pnl_pct,'take_profit_price':tp,'stop_loss_price':sl,'stop_loss_pct':t.stop_pct,'take_profit_pct':t.target_pct,'risk_per_trade_pct':t.risk_per_trade_pct,'max_daily_loss_pct':t.max_daily_loss_pct,'max_trades_per_day':t.max_trades_day,'consecutive_losses':t.db.consecutive_losses(t.symbol),'trades_today':t.db.trades_today(t.symbol),'server_time':datetime.now(timezone.utc).isoformat()}
@app.get('/api/v1/scanner',dependencies=[Depends(auth)])
def scanner(refresh:bool=False):
    started = False

    if refresh and not _scanner_cache_fresh():
        started = _start_scanner_background()

    result = _scanner_snapshot()
    result["version"] = VERSION

    if refresh:
        result["started"] = started

    return result

@app.get('/api/v1/market/klines',dependencies=[Depends(auth)])
def market_klines(limit:int=120):
    t=state.ensure_trader();df=fetch_klines(t.client,t.symbol,t.interval,limit=max(30,min(limit,250)));ind=calculate_indicators(df.iloc[:-1].copy(),config_from_env());rows=[]
    for idx,row in ind.iterrows():rows.append({'time':idx.isoformat(),'open':float(row.open),'high':float(row.high),'low':float(row.low),'close':float(row.close),'jaw':None if row.jaw_shifted!=row.jaw_shifted else float(row.jaw_shifted),'teeth':None if row.teeth_shifted!=row.teeth_shifted else float(row.teeth_shifted),'lips':None if row.lips_shifted!=row.lips_shifted else float(row.lips_shifted),'ao':None if row.ao!=row.ao else float(row.ao),'long_signal':bool(row.long_signal),'fractal_up':bool(row.fractal_up),'fractal_down':bool(row.fractal_down)})
    return {'symbol':t.symbol,'interval':t.interval,'candles':rows}
@app.post('/api/v1/control/start',dependencies=[Depends(auth)])
def start():return {'started':state.start()}
@app.post('/api/v1/control/stop',dependencies=[Depends(auth)])
def stop():return {'stopped':state.stop()}
@app.post('/api/v1/control/pause',dependencies=[Depends(auth)])
def pause():return {'paused':state.pause()}
@app.post('/api/v1/control/resume',dependencies=[Depends(auth)])
def resume():return {'resumed':state.resume()}
@app.post('/api/v1/control/recover',dependencies=[Depends(auth)])
def recover():t=state.ensure_trader();t.recover_state();return {'recovered':t.recovered,'state':t.state()}
@app.get('/api/v1/trades',dependencies=[Depends(auth)])
def trades(limit:int=50):return [dict(r) for r in db().conn.execute('SELECT * FROM trades ORDER BY id DESC LIMIT ?',(max(1,min(limit,200)),)).fetchall()]
@app.get('/api/v1/orders',dependencies=[Depends(auth)])
def orders(limit:int=50):return db().recent_orders(state.ensure_trader().symbol,max(1,min(limit,200)))
@app.get('/api/v1/logs',dependencies=[Depends(auth)])
def logs(limit:int=100):return [dict(r) for r in db().conn.execute('SELECT * FROM events ORDER BY id DESC LIMIT ?',(max(1,min(limit,300)),)).fetchall()]
@app.get('/api/v1/settings',dependencies=[Depends(auth)])
def settings():
    t=state.ensure_trader();return {'version':VERSION,'symbol':t.symbol,'interval':t.interval,'position_fraction':t.position_fraction,'stop_loss_pct':t.stop_pct,'take_profit_pct':t.target_pct,'poll_seconds':t.poll_seconds,'risk_per_trade_pct':t.risk_per_trade_pct,'max_daily_loss_pct':t.max_daily_loss_pct,'max_trades_per_day':t.max_trades_day,'max_consecutive_losses':t.max_consecutive_losses,'cooldown_minutes':t.cooldown_minutes,'min_risk_reward':t.min_risk_reward,'atr_period':t.atr_period,'max_atr_pct':t.max_atr_pct,'max_spread_pct':t.max_spread_pct,'require_htf_confirmation':t.require_htf_confirmation,'htf_interval':t.htf_interval,'testnet':t.client.testnet,'alligator':config_from_env(),'strategy_name':'Williams Profitunity Conservative','binance_configured':bool(t.client.api_key and t.client.api_secret)}

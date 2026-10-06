import os, tempfile
from decimal import Decimal, ROUND_DOWN
from datetime import datetime, timezone
from trader import Trader
from db import Database

class FakeClient:
    def __init__(self, price=100, quote=1000, base_free=0, base_locked=0):
        self.api_key='TEST'; self.api_secret='TEST'; self.testnet=True; self.price=price; self.quote=quote; self.base_free=base_free; self.base_locked=base_locked
        self.orders=[]; self.open_ocos=[]; self.next_order_id=1000; self.next_list_id=5000; self.created_oco_count=0
    def account(self):return {'balances':[{'asset':'USDT','free':str(self.quote),'locked':'0'},{'asset':'BTC','free':str(self.base_free),'locked':str(self.base_locked)}]}
    def all_orders(self,symbol,limit=1000):return list(self.orders)
    def open_order_lists(self,symbol=None):return list(self.open_ocos)
    def book_ticker(self,symbol):return {'bidPrice':str(self.price-0.02),'askPrice':str(self.price+0.02)}
    def create_oco_sell(self,symbol,quantity,take_profit_price,stop_price,stop_limit_price,list_client_order_id=None):
        lid=self.next_list_id;self.next_list_id+=1;qty=float(quantity);tp=float(take_profit_price);sl=float(stop_price);slp=float(stop_limit_price);a=self.next_order_id;self.next_order_id+=2
        legs=[{'symbol':symbol,'side':'SELL','type':'TAKE_PROFIT_LIMIT','orderId':a,'orderListId':lid,'clientOrderId':f'WILLV4_OCO_{a}-tp','status':'NEW','price':str(tp),'stopPrice':str(tp),'origQty':str(qty),'time':2000+self.created_oco_count},{'symbol':symbol,'side':'SELL','type':'STOP_LOSS_LIMIT','orderId':a+1,'orderListId':lid,'clientOrderId':f'WILLV4_OCO_{a}-sl','status':'NEW','price':str(slp),'stopPrice':str(sl),'origQty':str(qty),'time':2000+self.created_oco_count}]
        self.created_oco_count+=1;self.orders.extend(legs);self.open_ocos.append({'symbol':symbol,'orderListId':lid,'listClientOrderId':list_client_order_id,'listOrderStatus':'EXEC_STARTED','orders':legs});return {'orderListId':lid,'listClientOrderId':list_client_order_id,'orderReports':legs}
    @staticmethod
    def decimal_floor(value,step):return (Decimal(str(value))/Decimal(str(step))).to_integral_value(rounding=ROUND_DOWN)*Decimal(str(step))
    def decimal_format(self,value):return f'{float(value):.8f}'

def new_trader(path,c):
    t=Trader.__new__(Trader);t.symbol='BTCUSDT';t.interval='1h';t.position_fraction=.25;t.stop_pct=.02;t.target_pct=.04;t.poll_seconds=1;t.risk_per_trade_pct=.01;t.max_daily_loss_pct=.03;t.max_trades_day=5;t.max_consecutive_losses=3;t.cooldown_minutes=30;t.min_risk_reward=1.5;t.atr_period=14;t.max_atr_pct=.08;t.max_spread_pct=.0015;t.require_htf_confirmation=False;t.htf_interval='4h';t.db=Database(path);t.client=c;t.db.state_set('active_symbol','BTCUSDT');t.db.state_set('foreign_base_balance:BTCUSDT',0.0);t.filters={'LOT_SIZE':{'minQty':'0.001','stepSize':'0.001'},'PRICE_FILTER':{'tickSize':'0.01'},'MIN_NOTIONAL':{'minNotional':'10'}};t.base_asset='BTC';t.quote_asset='USDT';t.recovered=False;t.notify=lambda m:None;return t

def seed(t,c):
    buy={'symbol':'BTCUSDT','side':'BUY','type':'MARKET','orderId':1,'clientOrderId':'WILLV4_ENTRY_seed','status':'FILLED','price':'100','origQty':'1','executedQty':'1','cummulativeQuoteQty':'100','transactTime':1000,'time':1000};c.orders.append(buy);c.base_free=1;c.quote-=100;t.db.save_order(buy);t.db.save_trade(entry_time=datetime.fromtimestamp(1,tz=timezone.utc).isoformat(),symbol='BTCUSDT',side='LONG',entry_price=100,quantity=1,entry_order_id='1',fees=0)

def mark_exit(c,price):
    q=c.base_free+c.base_locked;c.orders.append({'symbol':'BTCUSDT','side':'SELL','type':'LIMIT','orderId':9000,'orderListId':5000,'clientOrderId':'WILLV4_OCO_exit','status':'FILLED','price':str(price),'origQty':str(q),'executedQty':str(q),'cummulativeQuoteQty':str(q*price),'transactTime':3000,'time':3000});c.base_free=0;c.base_locked=0;c.open_ocos.clear()

def check(name,fn):
    try:fn();print('[PASS]',name)
    except Exception as e:print('[FAIL]',name,type(e).__name__,e);raise

def scenario_exit(price):
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);seed(t,c);t.place_oco(1,100);mark_exit(c,price);r=new_trader(os.path.join(d,'x.sqlite3'),c);r.recover_state();assert r.db.open_trade() is None;assert abs(float(r.db.conn.execute('select exit_price from trades').fetchone()[0])-price)<1e-9

def scenario_missing():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);seed(t,c);r=new_trader(os.path.join(d,'x.sqlite3'),c);r.recover_state();assert c.created_oco_count==1;r.recover_state();assert c.created_oco_count==1

def scenario_restart():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);seed(t,c);t.place_oco(1,100);r=new_trader(os.path.join(d,'x.sqlite3'),c);r.recover_state();assert r.db.open_trade() is not None;assert c.created_oco_count==1

def scenario_foreign_balance():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient(base_free=2);t=new_trader(os.path.join(d,'x.sqlite3'),c);t.db.state_set('foreign_base_balance:BTCUSDT',2.0);t.recover_state();assert t.db.open_trade() is None;assert t.state()=='FLAT';assert c.created_oco_count==0

def scenario_excess_balance_blocks():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);seed(t,c);c.base_free=1.2;t.recover_state();assert t.state()=='RECONCILE_REQUIRED';assert c.created_oco_count==0

def scenario_state_machine_blocks_entry():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);t._set_state('RECONCILE_REQUIRED');called=[];t.recover_state=lambda: called.append('recovered')
        # process must exit before fetching market data or placing an entry.
        t.process();assert called==['recovered'];assert t.state()=='RECONCILE_REQUIRED'

def scenario_missing_entry_result_blocks():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);t.db.state_set('entry_client_order_id','WILLV4_ENTRY_unknown');t._set_state('ENTRY_PENDING');t.recover_state();assert t.state()=='RECONCILE_REQUIRED'

def scenario_entry_intent_recovery():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient(base_free=1);t=new_trader(os.path.join(d,'x.sqlite3'),c);c.orders.append({'symbol':'BTCUSDT','side':'BUY','type':'MARKET','orderId':77,'clientOrderId':'WILLV4_ENTRY_timeout','status':'FILLED','price':'100','origQty':'1','executedQty':'1','cummulativeQuoteQty':'100','transactTime':5000,'time':5000});t.db.state_set('entry_client_order_id','WILLV4_ENTRY_timeout');t._set_state('ENTRY_PENDING');t.recover_state();assert t.db.open_trade() is not None;assert t.state()=='OPEN'

def scenario_oco_link():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient();t=new_trader(os.path.join(d,'x.sqlite3'),c);seed(t,c);trade=t.db.open_trade();res,_,_=t.place_oco(1,100,trade_id=trade['id']);row=t.db.open_trade();assert str(row['exit_order_list_id'])==str(res['orderListId'])


def scenario_fresh_missing_baseline():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient(base_free=1)
        path=os.path.join(d,'x.sqlite3')
        t=new_trader(path,c)

        # Simulate a genuinely fresh DB with no baseline.
        t.db.state_delete('foreign_base_balance:BTCUSDT')

        assert t.db.state_get('foreign_base_balance:BTCUSDT') is None

        t.recover_state()

        baseline=t.db.state_get('foreign_base_balance:BTCUSDT')

        assert baseline is not None
        assert abs(float(baseline)-1.0)<1e-9
        assert t.state()=='FLAT'
        assert t.recovered is True
        assert c.created_oco_count==0

def scenario_fresh_db_recovers_explicit_bot_buy():
    with tempfile.TemporaryDirectory() as d:
        c=FakeClient(base_free=1)
        path=os.path.join(d,'x.sqlite3')
        t=new_trader(path,c)

        t.db.state_delete('foreign_base_balance:BTCUSDT')
        c.orders.append({
            'symbol':'BTCUSDT',
            'side':'BUY',
            'type':'MARKET',
            'orderId':77,
            'clientOrderId':'WILLV4_ENTRY_fresh_db',
            'status':'FILLED',
            'price':'100',
            'origQty':'1',
            'executedQty':'1',
            'cummulativeQuoteQty':'100',
            'transactTime':5000,
            'time':5000
        })

        t.recover_state()

        trade=t.db.open_trade('BTCUSDT')
        assert trade is not None
        assert trade['entry_order_id']=='77'
        assert abs(float(trade['quantity'])-1.0)<1e-9
        assert t.state()=='OPEN'
        assert abs(float(t.db.state_get('foreign_base_balance:BTCUSDT')))<1e-9
        assert c.created_oco_count==1

def test_recovery_suite():
    """Run every offline recovery scenario under pytest/CI."""
    run()


def run():
    tests=[
        ('fresh DB -> missing baseline -> safe FLAT recovery',scenario_fresh_missing_baseline),
        ('fresh DB -> explicit Williams BUY -> safe position recovery',scenario_fresh_db_recovers_explicit_bot_buy),
        ('BUY -> OCO -> crash -> TP',lambda:scenario_exit(104)),
        ('BUY -> OCO -> crash -> SL',lambda:scenario_exit(98)),
        ('BUY -> crash -> missing OCO',scenario_missing),
        ('BUY -> OCO -> crash -> restart',scenario_restart),
        ('foreign BTC is never a bot position',scenario_foreign_balance),
        ('excess BTC enters RECONCILE_REQUIRED',scenario_excess_balance_blocks),
        ('RECONCILE_REQUIRED blocks new entry',scenario_state_machine_blocks_entry),
        ('missing BUY result becomes a hard reconcile block',scenario_missing_entry_result_blocks),
        ('timed-out BUY is recovered by clientOrderId',scenario_entry_intent_recovery),
        ('Entry is linked to exact OCO list',scenario_oco_link)
    ]
    for n,f in tests:check(n,f)
    print(f'\nRecovery tests: {len(tests)}/{len(tests)} passed')
if __name__=='__main__':run()

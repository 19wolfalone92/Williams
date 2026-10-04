import logging, os, time, uuid
from datetime import datetime, timezone, timedelta
from dotenv import load_dotenv
from binance_client import BinanceAPIError, BinanceSpotClient
from data import fetch_klines
from db import Database
from strategy import calculate_indicators, config_from_env
from telegram_bot import Telegram
from portfolio_controller import PortfolioController

load_dotenv()
logging.basicConfig(level=logging.INFO, format='%(asctime)s | %(levelname)s | %(message)s')
log = logging.getLogger('williams-v4')

STATES = {'FLAT','ENTRY_PENDING','OPEN','EXIT_PENDING','RECONCILE_REQUIRED'}

def utc_now(): return datetime.now(timezone.utc).isoformat()

class Trader:
    def __init__(self, api_key=None, api_secret=None, testnet=None):
        self.symbol=os.getenv('SYMBOL','BTCUSDT').upper(); self.interval=os.getenv('INTERVAL','1h')
        self.position_fraction=float(os.getenv('POSITION_FRACTION','0.25')); self.stop_pct=float(os.getenv('STOP_LOSS_PCT','0.02')); self.target_pct=float(os.getenv('TAKE_PROFIT_PCT','0.04'))
        self.poll_seconds=int(os.getenv('POLL_SECONDS','20'))
        self.risk_per_trade_pct=float(os.getenv('RISK_PER_TRADE_PCT','0.01')); self.max_daily_loss_pct=float(os.getenv('MAX_DAILY_LOSS_PCT','0.03'))
        self.max_trades_day=int(os.getenv('MAX_TRADES_PER_DAY','5')); self.max_consecutive_losses=int(os.getenv('MAX_CONSECUTIVE_LOSSES','3')); self.cooldown_minutes=int(os.getenv('COOLDOWN_MINUTES','30'))
        self.min_risk_reward=float(os.getenv('MIN_RISK_REWARD','1.5')); self.atr_period=int(os.getenv('ATR_PERIOD','14')); self.max_atr_pct=float(os.getenv('MAX_ATR_PCT','0.08'))
        self.max_spread_pct=float(os.getenv('MAX_SPREAD_PCT','0.0015')); self.require_htf_confirmation=os.getenv('REQUIRE_HTF_CONFIRMATION','true').lower()=='true'; self.htf_interval=os.getenv('HTF_INTERVAL','4h')
        self.db=Database(
            os.getenv('WILLIAMS_DB_PATH')
            or os.getenv('DB_PATH')
            or 'data/trader.sqlite3'
        ); self.tg=Telegram(os.getenv('TELEGRAM_BOT_TOKEN',''),os.getenv('TELEGRAM_CHAT_ID',''))
        self.client=BinanceSpotClient(api_key if api_key is not None else os.getenv('BINANCE_API_KEY',''), api_secret if api_secret is not None else os.getenv('BINANCE_API_SECRET',''), testnet=(os.getenv('TESTNET','true').lower()=='true') if testnet is None else testnet)
        self.filters={}; self.base_asset=self.quote_asset=None; self.recovered=False
        self.auto_scan_enabled = os.getenv(
            'AUTO_SCAN_ENABLED', 'true'
        ).lower() == 'true'

        self.dry_run = os.getenv(
            'DRY_RUN', 'true'
        ).lower() == 'true'

        raw_symbols = os.getenv(
            'AUTO_SCAN_SYMBOLS',
            'BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,ADAUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT,DOTUSDT'
        )

        self.auto_scan_symbols = [
            x.strip().upper()
            for x in raw_symbols.split(',')
            if x.strip()
        ]

        if not self.auto_scan_symbols:
            raise RuntimeError(
                'AUTO_SCAN_SYMBOLS must contain at least one symbol'
            )

        self.max_open_positions = 1
        self.active_symbol = self.symbol

        self.db.state_set('active_symbol', self.symbol)

    def notify(self,text):
        log.info(text.replace('\n',' | '))
        try:self.tg.send(text)
        except Exception as e:log.error('Telegram error: %s',e)

    def setup(self):
        if not self.client.testnet and os.getenv('ALLOW_LIVE','false').lower()!='true': raise RuntimeError('LIVE trading is disabled. Set TESTNET=true or explicitly ALLOW_LIVE=true.')
        if not self.client.api_key or not self.client.api_secret: raise RuntimeError('BINANCE_API_KEY and BINANCE_API_SECRET are required.')
        self.client.sync_time(); info=self.client.exchange_info(self.symbol); s=info['symbols'][0]; self.filters={f['filterType']:f for f in s['filters']}; self.base_asset=s['baseAsset']; self.quote_asset=s['quoteAsset']
        self.db.log_event('INFO','startup','Trader initialized',{'symbol':self.symbol,'interval':self.interval,'testnet':self.client.testnet}); self.ensure_foreign_base_balance_baseline(); self.recover_state()
        self.notify(f'Williams STARTED\n{self.symbol} {self.interval}\nTESTNET={self.client.testnet}\nSTATE={self.state()}')

    def switch_symbol(self, symbol):
        """
        Безопасно переключает рабочую торговую пару.

        ВАЖНО:
        - не создаёт ордеров;
        - не отменяет ордера;
        - не меняет позицию;
        - разрешено только из FLAT;
        - заново загружает Binance filters/assets;
        - проверяет отсутствие незавершённой позиции новой пары.
        """
        new_symbol = str(symbol).upper().strip()

        if not new_symbol:
            raise ValueError('Symbol cannot be empty')

        if self.state() != 'FLAT':
            raise RuntimeError(
                f'Cannot switch symbol while state={self.state()}'
            )

        # Проверяем символ непосредственно через Binance.
        info = self.client.exchange_info(new_symbol)
        symbols = info.get('symbols', [])

        if not symbols:
            raise RuntimeError(
                f'Symbol {new_symbol} not found in Binance exchange info'
            )

        s = symbols[0]

        if s.get('status') != 'TRADING':
            raise RuntimeError(
                f'Symbol {new_symbol} is not TRADING: {s.get("status")}'
            )

        base_asset = s.get('baseAsset')
        quote_asset = s.get('quoteAsset')

        if not base_asset or not quote_asset:
            raise RuntimeError(
                f'Invalid assets for {new_symbol}'
            )

        # HARD SINGLE-POSITION GUARD:
        # Automatic scanner supports exactly one managed position.
        # Therefore symbol switching is forbidden while ANY open trade
        # exists, even if that trade belongs to another symbol.
        any_open_trade = self.db.open_trade()

        if any_open_trade is not None:
            raise RuntimeError(
                'Cannot switch symbol while a managed position exists: '
                f"{any_open_trade.get('symbol')}"
            )

        # An unresolved entry intent is also a hard cross-symbol block.
        # The exchange may have accepted the BUY even if its response
        # was lost or delayed.
        entry_intent = self.db.state_get('entry_client_order_id')

        if entry_intent:
            raise RuntimeError(
                'Cannot switch symbol while an entry intent is unresolved: '
                f'{entry_intent}'
            )

        # No unfinished position for the target symbol.
        # This remains as an explicit per-symbol guard even though the
        # global single-position guard above already covers it.
        if self.db.open_trade(new_symbol) is not None:
            raise RuntimeError(
                f'Open trade already exists for {new_symbol}'
            )

        # Загружаем filters только после успешной проверки.
        filters = {
            f['filterType']: f
            for f in s.get('filters', [])
        }

        # --------------------------------------------------------
        # ATOMIC RUNTIME SWITCH
        # --------------------------------------------------------
        # Everything above this point is preparation only.
        # No Trader runtime state has been changed yet.
        #
        # The commit below is protected by a rollback so a failure in
        # DB state/event persistence cannot leave Trader half-switched.

        old_symbol = self.symbol
        old_active_symbol = self.active_symbol
        old_filters = self.filters
        old_base_asset = self.base_asset
        old_quote_asset = self.quote_asset
        old_recovered = self.recovered

        try:
            # TRUE DB TRANSACTION:
            # active_symbol + symbol_switched event are committed together.
            # If anything fails, SQLite rolls both changes back.
            with self.db.transaction():
                self.symbol = new_symbol
                self.active_symbol = new_symbol
                self.filters = filters
                self.base_asset = base_asset
                self.quote_asset = quote_asset
                self.recovered = False

                self.db.state_set('active_symbol', new_symbol)

                self.db.log_event(
                    'INFO',
                    'symbol_switched',
                    'Trader working symbol switched',
                    {
                        'from': old_symbol,
                        'to': new_symbol,
                        'base_asset': base_asset,
                        'quote_asset': quote_asset,
                    }
                )

        except Exception:
            # Runtime rollback.
            # DB rollback is already handled by Database.transaction().
            self.symbol = old_symbol
            self.active_symbol = old_active_symbol
            self.filters = old_filters
            self.base_asset = old_base_asset
            self.quote_asset = old_quote_asset
            self.recovered = old_recovered
            raise

        return {
            'symbol': self.symbol,
            'base_asset': self.base_asset,
            'quote_asset': self.quote_asset,
            'status': s.get('status'),
            'filters': self.filters,
        }

    def state(self): return self.db.state_get('position_state','FLAT')
    def _set_state(self,state):
        if state not in STATES: raise ValueError(f'Unknown state {state}')
        self.db.state_set('position_state',state)

    def normalize_qty(self,qty):
        f=self.filters.get('LOT_SIZE') or self.filters.get('MARKET_LOT_SIZE'); step=f['stepSize'] if f else '0.000001'; min_qty=float(f['minQty']) if f else 0; q=self.client.decimal_floor(qty,step); return float(q) if float(q)>=min_qty else 0.0
    def normalize_price(self,price):
        f=self.filters.get('PRICE_FILTER'); tick=f['tickSize'] if f else '0.01'; return float(self.client.decimal_floor(price,tick))
    def _ensure_symbol_assets(self):
        if self.base_asset and self.quote_asset:
            return
        info=self.client.exchange_info(self.symbol)
        symbols=info.get('symbols',[])
        if not symbols:
            raise RuntimeError(f'Symbol {self.symbol} not found in Binance exchange info')
        s=symbols[0]
        self.base_asset=s.get('baseAsset')
        self.quote_asset=s.get('quoteAsset')
        if not self.quote_asset:
            raise RuntimeError(f'Quote asset not found for {self.symbol}')

    def available_quote(self):
        self._ensure_symbol_assets()
        a=self.client.account()
        return next((float(b['free']) for b in a.get('balances',[]) if b.get('asset')==self.quote_asset),0.0)
    def base_balance_total(self):
        self._ensure_symbol_assets()
        a=self.client.account()
        return next((float(b['free'])+float(b['locked']) for b in a.get('balances',[]) if b.get('asset')==self.base_asset),0.0)

    def bot_base_balance(self, total_balance=None):
        if total_balance is None:
            total_balance=self.base_balance_total()

        key = f'foreign_base_balance:{self.symbol}'
        baseline = self.db.state_get(key)

        # Backward compatibility:
        # older databases/tests used the global foreign_base_balance key.
        #
        # IMPORTANT:
        # A global legacy baseline has no symbol information. It must
        # never be copied blindly to a newly selected symbol.
        #
        # It is migrated only during the original active-symbol context.
        # After migration the legacy key is removed, so it cannot leak
        # into another symbol later.
        if baseline is None:
            legacy = self.db.state_get('foreign_base_balance')
            active_symbol = self.db.state_get('active_symbol')

            if (
                legacy is not None
                and active_symbol
                and str(active_symbol).upper() == self.symbol
            ):
                baseline = float(legacy)
                self.db.state_set(key, baseline)
                self.db.state_delete('foreign_base_balance')

        if baseline is None:
            raise RuntimeError(
                f'foreign_base_balance baseline is not initialized for {self.symbol}'
            )

        return max(0.0, float(total_balance)-float(baseline))

    def ensure_foreign_base_balance_baseline(self, total_balance=None):
        key = f'foreign_base_balance:{self.symbol}'
        baseline = self.db.state_get(key)
        if baseline is not None:
            return float(baseline)

        # Migrate the legacy global baseline only when it clearly
        # belongs to the currently active symbol.
        #
        # A global baseline has no symbol information, therefore it must
        # never be copied to a newly selected pair.
        legacy = self.db.state_get('foreign_base_balance')
        active_symbol = self.db.state_get('active_symbol')

        if (
            legacy is not None
            and active_symbol
            and str(active_symbol).upper() == self.symbol
        ):
            baseline = float(legacy)
            self.db.state_set(key, baseline)
            self.db.state_delete('foreign_base_balance')
            return baseline

        if total_balance is None:
            total_balance=self.base_balance_total()

        open_trade=self.db.open_trade(self.symbol)
        entry_intent=self.db.state_get('entry_client_order_id')

        if open_trade is None and not entry_intent and self.state() in {'FLAT','RECONCILE_REQUIRED'}:
            self.db.state_set(f'foreign_base_balance:{self.symbol}', float(total_balance))
            self.db.log_event(
                'INFO',
                'foreign_balance_baselined',
                'Captured pre-existing base-asset balance',
                {'asset':self.base_asset,'balance':float(total_balance)}
            )
            return float(total_balance)

        raise RuntimeError(
            'Cannot initialize foreign_base_balance while a managed position or unresolved entry exists'
        )
    def _min_qty(self):
        f=self.filters.get('LOT_SIZE') or self.filters.get('MARKET_LOT_SIZE'); return float(f['minQty']) if f else 0.0
    def _min_notional(self):
        f=self.filters.get('NOTIONAL') or self.filters.get('MIN_NOTIONAL') or {}; return float(f.get('minNotional',0) or 0)
    def _is_bot_order(self,o): return str(o.get('clientOrderId','')).startswith(('WILLV4_ENTRY_','WILLV4_OCO_'))
    def _is_bot_oco_list(self,lst):
        lid=str(lst.get('listClientOrderId',''))
        return lid.startswith('WILLV4_OCO_') or any(str(o.get('clientOrderId','')).startswith(('WILLV4_OCO_','tp-','sl-')) for o in lst.get('orders',[]))
    def _sell_belongs_to_bot(self,o,all_orders):
        cid=str(o.get('clientOrderId','')); lid=str(o.get('orderListId',''))
        if cid.startswith(('WILLV4_OCO_','tp-','sl-')): return True
        if not lid:return False
        if any(str(x.get('orderListId',''))==lid and str(x.get('clientOrderId','')).startswith(('WILLV4_OCO_','tp-','sl-')) for x in all_orders):return True
        return self.db.conn.execute("SELECT 1 FROM orders WHERE order_list_id=? AND client_order_id LIKE 'WILLV4_OCO_%' LIMIT 1",(lid,)).fetchone() is not None

    def _filled_sell_qty_after(self,buy,all_orders):
        bt=int(buy.get('time',buy.get('transactTime',0)) or 0)
        return sum(float(o.get('executedQty',0) or 0) for o in all_orders if o.get('side')=='SELL' and o.get('status')=='FILLED' and int(o.get('time',0) or 0)>=bt and self._sell_belongs_to_bot(o,all_orders))

    def _trade_remaining_qty(self,trade,all_orders):
        entry_id=str(trade.get('entry_order_id') or ''); buy=next((o for o in all_orders if str(o.get('orderId'))==entry_id and o.get('side')=='BUY'),None)
        if not buy:return float(trade.get('quantity') or 0)
        bought=float(buy.get('executedQty',0) or 0); sold=self._filled_sell_qty_after(buy,all_orders)
        return max(0.0,min(float(trade.get('quantity') or bought),bought)-sold)

    def _recover_entry_intent(self,all_orders):
        cid=self.db.state_get('entry_client_order_id')
        if not cid:return
        matches=[o for o in all_orders if str(o.get('clientOrderId'))==cid]
        if not matches:return
        order=matches[-1]; self.db.save_order(order)
        status=str(order.get('status','')).upper()
        if status=='FILLED':
            qty=float(order.get('executedQty',0) or 0)
            spent=float(order.get('cummulativeQuoteQty',0) or 0)
            entry=spent/qty if spent and qty else float(order.get('price',0) or 0)

            # HARD RECOVERY GUARD:
            # A confirmed BUY that is too small to represent a valid
            # Williams position must never silently become FLAT.
            if qty < self._min_qty():
                self.db.state_delete('entry_client_order_id')
                self._set_state('RECONCILE_REQUIRED')
                self.db.log_event(
                    'ERROR',
                    'invalid_recovered_entry',
                    f'Exchange BUY is FILLED but quantity {qty} is below minimum {self._min_qty()}; manual reconciliation required',
                    order
                )
                print(
                    f'[RECOVERY] FILLED BUY below minQty -> RECONCILE_REQUIRED: '
                    f'{qty} < {self._min_qty()}'
                )
                return

            if self.db.open_trade(self.symbol) is None:
                self.db.save_trade(
                    entry_time=datetime.fromtimestamp(
                        int(order.get('transactTime',order.get('time',0)))/1000,
                        tz=timezone.utc
                    ).isoformat(),
                    symbol=self.symbol,
                    side='LONG',
                    entry_price=entry,
                    quantity=qty,
                    entry_order_id=str(order.get('orderId')),
                    fees=0
                )

            self.db.state_delete('entry_client_order_id')
            self._set_state('EXIT_PENDING')
            self.db.log_event(
                'WARNING',
                'entry_recovered',
                'Recovered an entry that may have filled before the client received the response; OCO recovery required',
                order
            )
        elif status in {'CANCELED','REJECTED','EXPIRED'}:
            self.db.state_delete('entry_client_order_id'); self._set_state('FLAT')

    def recover_state(self):
        self.db.log_event('INFO','recovery_start','Starting exchange/SQLite reconciliation')
        open_trade=self.db.open_trade(self.symbol); all_orders=self.client.all_orders(self.symbol,limit=1000)
        for o in all_orders:self.db.save_order(o)
        self._recover_entry_intent(all_orders); open_trade=self.db.open_trade(self.symbol)
        open_lists=self.client.open_order_lists(self.symbol)
        for lst in open_lists:
            for o in lst.get('orders',[]):self.db.save_order(o)
        bot_open_lists=[x for x in open_lists if self._is_bot_oco_list(x)]
        open_oco_ids={str(x.get('orderListId')) for x in bot_open_lists if x.get('orderListId') is not None}
        position_qty=self.base_balance_total()

        # A fresh database has no foreign-balance baseline yet.
        # Never assume zero: that could misclassify an existing user-owned
        # base-asset balance as a Williams position.
        #
        # We can safely initialize the baseline only when there is no
        # managed position, no unresolved entry intent, and no unresolved
        # Williams BUY remaining on the exchange.
        baseline_key=f'foreign_base_balance:{self.symbol}'
        baseline=self.db.state_get(baseline_key)

        if baseline is None:
            entry_intent=self.db.state_get('entry_client_order_id')

            unresolved_bot_buy=False
            for buy in all_orders:
                if (
                    buy.get('side') == 'BUY'
                    and buy.get('status') == 'FILLED'
                    and self._is_bot_order(buy)
                ):
                    bought=float(buy.get('executedQty',0) or 0)
                    sold=self._filled_sell_qty_after(buy,all_orders)
                    remaining=max(0.0,bought-sold)

                    if remaining >= self._min_qty():
                        unresolved_bot_buy=True
                        break

            if open_trade is None and not entry_intent and not unresolved_bot_buy:
                self.ensure_foreign_base_balance_baseline(position_qty)
                baseline=self.db.state_get(baseline_key)
            else:
                self._set_state('RECONCILE_REQUIRED')
                self.recovered=True
                self.db.log_event(
                    'ERROR',
                    'recovery_baseline_missing',
                    'Recovery blocked because foreign base balance baseline is missing while managed exchange activity exists',
                    {
                        'symbol':self.symbol,
                        'total_balance':float(position_qty),
                        'open_trade':bool(open_trade),
                        'entry_intent':bool(entry_intent),
                        'unresolved_bot_buy':bool(unresolved_bot_buy)
                    }
                )
                return

        bot_position_qty=self.bot_base_balance(position_qty)
        if open_trade is None:
            candidates=[]
            for buy in all_orders:
                if buy.get('side')!='BUY' or buy.get('status')!='FILLED' or not self._is_bot_order(buy):continue
                bought=float(buy.get('executedQty',0) or 0); sold=self._filled_sell_qty_after(buy,all_orders); remaining=max(0,bought-sold)
                if remaining>=self._min_qty() and bot_position_qty>=self._min_qty():candidates.append((int(buy.get('time',buy.get('transactTime',0)) or 0),buy,remaining))
            if candidates:
                _,buy,remaining=max(candidates,key=lambda x:x[0]); quote=float(buy.get('cummulativeQuoteQty',0) or 0); bought=float(buy.get('executedQty',0) or 0); entry=quote/bought if quote and bought else float(buy.get('price',0) or 0); qty=min(remaining,bot_position_qty)
                self.db.save_trade(entry_time=datetime.fromtimestamp(int(buy.get('transactTime',buy.get('time',0)))/1000,tz=timezone.utc).isoformat(),symbol=self.symbol,side='LONG',entry_price=entry,quantity=qty,entry_order_id=str(buy.get('orderId')),fees=0); open_trade=self.db.open_trade(self.symbol); self.db.log_event('WARNING','trade_reconstructed','Reconstructed unresolved bot-owned LONG',buy)
        if open_trade:
            expected_qty=self._trade_remaining_qty(open_trade,all_orders)
            tolerance=max(self._min_qty(),expected_qty*float(os.getenv('BALANCE_TOLERANCE_PCT','0.002')))
            if expected_qty < self._min_qty(): self._recover_closed_trade(open_trade,all_orders); self._set_state('FLAT')
            elif abs(bot_position_qty-expected_qty)>tolerance:
                self._set_state('RECONCILE_REQUIRED'); self.notify(f'RECOVERY BLOCKED\nExpected Williams {expected_qty:.8f} {self.base_asset}; actual Williams balance {bot_position_qty:.8f}. Total account balance={position_qty:.8f}; foreign baseline={position_qty-bot_position_qty:.8f}. Manual reconciliation required.'); self.recovered=True; return
            else:
                if not open_oco_ids:
                    qty=min(expected_qty,bot_position_qty)
                    self._set_state('EXIT_PENDING')
                    try:
                        self.place_oco(
                            qty,
                            float(open_trade['entry_price']),
                            trade_id=open_trade['id']
                        )
                    except Exception as e:
                        self._set_state('RECONCILE_REQUIRED')
                        try:
                            self.db.log_event(
                                'ERROR',
                                'oco_recovery_failed',
                                'Failed to recreate OCO during recovery',
                                {
                                    'symbol': self.symbol,
                                    'trade_id': open_trade['id'],
                                    'error': str(e),
                                }
                            )
                        except Exception:
                            pass
                        print('[RECOVERY] OCO recreation failed -> RECONCILE_REQUIRED:', e)
                if self.state() != 'RECONCILE_REQUIRED':
                    self._set_state('OPEN')
        else:
            # Never allow a foreign balance to create a bot position. An unresolved
            # entry intent is also a hard block: the exchange may have accepted the
            # BUY even if it is temporarily absent from the history response.
            if self.state()=='ENTRY_PENDING':
                self._set_state('RECONCILE_REQUIRED')
                self.notify('RECOVERY BLOCKED\nAn entry request has no confirmed exchange result. Manual reconciliation is required before trading can resume.')
            elif self.state()=='RECONCILE_REQUIRED':
                pass
            else:
                self._set_state('FLAT')
        self.recovered=True; self.db.log_event('INFO','recovery_complete','Exchange/SQLite reconciliation complete',{'position_qty':position_qty,'bot_trade':bool(open_trade),'open_oco_ids':sorted(open_oco_ids),'state':self.state()})

    def _recover_closed_trade(self,trade,all_orders):
        entry_id=str(trade.get('entry_order_id') or ''); entry_time=int(next((b.get('time',b.get('transactTime',0)) for b in all_orders if str(b.get('orderId'))==entry_id),0) or 0)
        sells=[o for o in all_orders if o.get('side')=='SELL' and o.get('status')=='FILLED' and int(o.get('time',0) or 0)>=entry_time and self._sell_belongs_to_bot(o,all_orders)]
        if not sells:return
        # Prefer the trade's recorded OCO list; otherwise use the latest bot-owned exit execution.
        target_lid=str(trade.get('exit_order_list_id') or '')
        if target_lid:sells=[o for o in sells if str(o.get('orderListId'))==target_lid] or sells
        sell=max(sells,key=lambda x:int(x.get('time',x.get('transactTime',0)) or 0)); qty=float(sell.get('executedQty',0) or 0); proceeds=float(sell.get('cummulativeQuoteQty',0) or 0); exit_price=proceeds/qty if qty else float(sell.get('price',0) or 0)
        entry=float(trade['entry_price']); pnl=(exit_price-entry)*min(qty,float(trade['quantity'])); pct=(exit_price/entry-1) if entry else 0
        self.db.close_trade(trade['id'],datetime.fromtimestamp(int(sell.get('transactTime',sell.get('time',0)))/1000,tz=timezone.utc).isoformat(),exit_price,pnl,pct,'TAKE_PROFIT/STOP_LOSS (recovered)',sell.get('orderListId')); self.notify(f'RECOVERY\nPosition closed offline\nexit≈{exit_price:.8f}\nPnL≈{pnl:.8f} ({pct:.2%})')

    def _spread_pct(self):
        b=self.client.book_ticker(self.symbol); bid=float(b['bidPrice']); ask=float(b['askPrice']); mid=(bid+ask)/2
        return (ask-bid)/mid if mid else 1.0

    @staticmethod
    def _atr(df,period=14):
        prev=df['close'].shift(1); tr=__import__('pandas').concat([df['high']-df['low'],(df['high']-prev).abs(),(df['low']-prev).abs()],axis=1).max(axis=1); return float(tr.rolling(period).mean().iloc[-1])

    def _risk_gate(self, closed):
        if self.state()=='RECONCILE_REQUIRED': return False,'RECONCILE_REQUIRED'
        if self.state()!='FLAT': return False,f'state={self.state()}'
        if self.db.trades_today(self.symbol)>=self.max_trades_day:return False,'max daily trades reached'
        if self.db.consecutive_losses(self.symbol)>=self.max_consecutive_losses:return False,'max consecutive losses reached'
        today_pnl=self.db.pnl_today(self.symbol); balance=max(self.available_quote(),0.0)
        if balance>0 and today_pnl <= -balance*self.max_daily_loss_pct:return False,'daily loss limit reached'
        last_exit=self.db.last_exit_time(self.symbol)
        if last_exit:
            try:
                dt=datetime.fromisoformat(last_exit.replace('Z','+00:00'))
                if datetime.now(timezone.utc)-dt < timedelta(minutes=self.cooldown_minutes):return False,'cooldown active'
            except ValueError:pass
        if self.target_pct/max(self.stop_pct,1e-9) < self.min_risk_reward:return False,'risk/reward below minimum'
        spread=self._spread_pct()
        if spread>self.max_spread_pct:return False,f'spread {spread:.4%} > {self.max_spread_pct:.4%}'
        atr=self._atr(closed,self.atr_period); price=float(closed['close'].iloc[-1])
        if not price or atr/price>self.max_atr_pct:return False,f'volatility too high: ATR {atr/price:.2%}'
        if self.require_htf_confirmation:
            htf=fetch_klines(self.client,self.symbol,self.htf_interval,limit=160)
            if len(htf)<80:return False,'insufficient higher-timeframe data'
            hi=calculate_indicators(htf.iloc[:-1].copy(),config_from_env()).iloc[-1]
            if not bool(hi.get('bullish_alligator',False)) or float(hi.get('ao',0) or 0)<=0:return False,'higher-timeframe trend not confirmed'
        return True,'ok'

    def _position_quote(self,entry_price):
        balance=self.available_quote(); cap=balance*self.position_fraction
        risk_quote=balance*self.risk_per_trade_pct/max(self.stop_pct,1e-9)
        return max(0.0,min(cap,risk_quote))

    def market_buy(self,quote):
        # HARD SAFETY:
        # DRY_RUN must block BEFORE any DB mutation, entry intent,
        # state transition, or exchange order request.
        if self.dry_run:
            raise RuntimeError(
                'BUY blocked: DRY_RUN=true. '
                'No live order execution is permitted.'
            )

        # Persist the currently selected symbol only after the immutable
        # execution safety gate above has passed.
        self.db.state_set('active_symbol', self.symbol)

        # HARD ENTRY GUARD:
        # A BUY may only start from a completely reconciled FLAT state.
        current_state = self.state()
        if current_state != 'FLAT':
            raise RuntimeError(
                f'BUY blocked: invalid state={current_state}. '
                'Only FLAT may start a new entry.'
            )

        if quote<=0 or quote<self._min_notional():
            raise RuntimeError(
                f'Insufficient quote balance: quote={quote}, '
                f'minNotional={self._min_notional()}'
            )

        cid=f'WILLV4_ENTRY_{uuid.uuid4().hex[:20]}'
        self.db.state_set('entry_client_order_id',cid)
        self._set_state('ENTRY_PENDING')

        try:
            order=self.client.order(
                self.symbol,
                'BUY',
                'MARKET',
                quote_order_qty=self.client.decimal_format(quote),
                new_client_order_id=cid
            )
            self.db.save_order(order)
        except Exception:
            # Recovery can find a filled order by the durable client id.
            self.db.log_event(
                'ERROR',
                'entry_request_failed',
                'BUY request failed; recovery will reconcile by clientOrderId',
                {'clientOrderId':cid}
            )
            raise

        qty=float(order.get('executedQty',0))
        spent=float(order.get('cummulativeQuoteQty',0))
        avg=spent/qty if qty else 0

        if qty<self._min_qty():
            raise RuntimeError('BUY returned insufficient executed quantity')

        self.db.state_delete('entry_client_order_id')
        return order,qty,avg

    def place_oco(self,qty,entry_price,trade_id=None):
        qty=self.normalize_qty(qty)
        if qty<=0:raise RuntimeError('Position quantity became zero after LOT_SIZE rounding')
        tp=self.normalize_price(entry_price*(1+self.target_pct)); sl=self.normalize_price(entry_price*(1-self.stop_pct)); tick=float((self.filters.get('PRICE_FILTER') or {}).get('tickSize','0.01')); sl_limit=self.normalize_price(max(sl-tick*2,tick))
        if not(tp>entry_price and sl<entry_price and sl_limit<sl):raise RuntimeError(f'Invalid TP/SL after tick rounding: entry={entry_price}, tp={tp}, sl={sl}, sl_limit={sl_limit}')
        cid=f'WILLV4_OCO_{uuid.uuid4().hex[:20]}'; self._set_state('EXIT_PENDING')
        result=self.client.create_oco_sell(self.symbol,self.client.decimal_format(qty),self.client.decimal_format(tp),self.client.decimal_format(sl),self.client.decimal_format(sl_limit),cid)
        self.db.log_event('INFO','oco_created','Native TP/SL OCO created',result)
        for leg in result.get('orderReports',[]):self.db.save_order(leg)
        if trade_id is not None and result.get('orderListId') is not None:self.db.update_trade_oco(trade_id,result['orderListId'])
        return result,tp,sl

    def has_open_position(self):return self.db.open_trade(self.symbol) is not None and self._is_meaningful_position()
    def _is_meaningful_position(self):return self.bot_base_balance()>=self._min_qty()


    def _auto_scan_process(self):
        """
        Multi-symbol entry pipeline.

        HARD SAFETY RULES:
        - exactly one open position;
        - RECONCILE_REQUIRED blocks all entries;
        - scanner may only nominate STRICT_SIGNAL;
        - HTF confirmation is mandatory when configured;
        - selected symbol is revalidated after switching;
        - Trader._risk_gate() remains the final risk barrier;
        - DRY_RUN never reaches market_buy();
        - any preparation/recovery error aborts the cycle.
        """

        # Full recovery is performed at startup, after symbol switches,
        # and after ambiguous exchange operations. Do not run expensive
        # REST reconciliation on every normal scanner cycle.
        current_state = self.state()

        if current_state != 'FLAT':
            log.info(
                'AUTO-SCAN BLOCKED: state=%s symbol=%s',
                current_state,
                self.symbol,
            )
            return

        if self.db.open_trade() is not None:
            log.warning(
                'AUTO-SCAN BLOCKED: database reports an open trade'
            )
            return

        # Never allow more than one managed position.
        if self.max_open_positions != 1:
            raise RuntimeError(
                'Automatic scanner currently supports exactly one open position'
            )

        self.db.log_event(
            'INFO',
            'autoscan_start',
            'Automatic multi-symbol scan started',
            {
                'symbols': self.auto_scan_symbols,
                'interval': self.interval,
            },
        )

        balance = self.available_quote()

        if balance <= 0:
            log.warning(
                'AUTO-SCAN WAIT: no available quote balance'
            )
            return

        controller = PortfolioController(
            self.client,
            balance_quote=balance,
            symbols=self.auto_scan_symbols,
        )

        selection = controller.select(
            has_open_position=False
        )

        if selection is None:
            log.info(
                'AUTO-SCAN: no STRICT_SIGNAL candidate passed risk checks'
            )
            self.db.log_event(
                'INFO',
                'autoscan_wait',
                'No candidate passed strict signal and risk checks',
            )
            return

        candidate = selection.candidate

        self.db.log_event(
            'INFO',
            'autoscan_selection',
            f'Selected candidate {candidate.symbol}',
            {
                'symbol': candidate.symbol,
                'score': candidate.score,
                'setup_state': candidate.setup_state,
                'signal': candidate.signal,
            },
        )

        # Controller must never be allowed to hand execution
        # a weak/watch-only candidate.
        if not bool(candidate.signal):
            log.warning(
                'AUTO-SCAN REJECTED non-strict candidate: %s',
                candidate.symbol,
            )
            return

        if self.require_htf_confirmation and not bool(
            candidate.htf_confirmed
        ):
            log.warning(
                'AUTO-SCAN REJECTED without HTF confirmation: %s',
                candidate.symbol,
            )
            return

        selected_symbol = candidate.symbol.upper()

        log.info(
            'AUTO-SCAN SELECTED %s score=%.2f setup=%.2f rr=%.2f atr=%.4f spread=%.4f',
            selected_symbol,
            candidate.score,
            candidate.setup_score,
            candidate.risk_reward,
            candidate.atr_pct,
            candidate.spread_pct,
        )

        # --------------------------------------------------------
        # SWITCH SYMBOL
        # --------------------------------------------------------

        self.switch_symbol(selected_symbol)

        if self.symbol != selected_symbol:
            raise RuntimeError(
                f'Symbol switch verification failed: '
                f'{self.symbol} != {selected_symbol}'
            )

        if self.active_symbol != selected_symbol:
            raise RuntimeError(
                'active_symbol verification failed'
            )

        if self.state() != 'FLAT':
            log.warning(
                'AUTO-SCAN blocked after symbol switch: state=%s',
                self.state(),
            )
            return

        # --------------------------------------------------------
        # PREPARE BASELINE + RECOVERY
        # --------------------------------------------------------

        self.ensure_foreign_base_balance_baseline()
        self.recover_state()

        if self.state() != 'FLAT':
            log.warning(
                'AUTO-SCAN blocked after recovery: state=%s',
                self.state(),
            )
            return

        if self.db.open_trade(self.symbol) is not None:
            log.warning(
                'AUTO-SCAN blocked: selected symbol has an open trade'
            )
            return

        # --------------------------------------------------------
        # FRESH REVALIDATION
        #
        # The first scanner result may already be stale because
        # switch_symbol/recovery required REST requests.
        # Re-scan ONLY the selected symbol before execution.
        # --------------------------------------------------------

        fresh_balance = self.available_quote()

        fresh_controller = PortfolioController(
            self.client,
            balance_quote=fresh_balance,
            symbols=[selected_symbol],
        )

        fresh_selection = fresh_controller.select(
            has_open_position=False
        )

        if fresh_selection is None:
            log.info(
                'AUTO-SCAN ABORT: selected signal disappeared during revalidation'
            )
            return

        fresh_candidate = fresh_selection.candidate

        if not bool(fresh_candidate.signal):
            log.warning(
                'AUTO-SCAN ABORT: fresh candidate is not STRICT_SIGNAL'
            )
            return

        if self.require_htf_confirmation and not bool(
            fresh_candidate.htf_confirmed
        ):
            log.warning(
                'AUTO-SCAN ABORT: fresh HTF confirmation failed'
            )
            return

        if fresh_candidate.symbol.upper() != selected_symbol:
            log.warning(
                'AUTO-SCAN ABORT: selected symbol changed during revalidation'
            )
            return

        # --------------------------------------------------------
        # FINAL STRATEGY DATA
        # --------------------------------------------------------

        df = fetch_klines(
            self.client,
            self.symbol,
            self.interval,
            limit=250,
        )

        if len(df) < 100:
            log.warning(
                'AUTO-SCAN ABORT: insufficient candles for %s',
                self.symbol,
            )
            return

        closed = df.iloc[:-1].copy()

        if len(closed) < 100:
            log.warning(
                'AUTO-SCAN ABORT: insufficient closed candles for %s',
                self.symbol,
            )
            return

        ind = calculate_indicators(
            closed,
            config_from_env(),
        )

        last = ind.iloc[-1]
        last_time = str(ind.index[-1])

        if not bool(last.get('long_signal', False)):
            log.info(
                'AUTO-SCAN ABORT: final strict signal disappeared for %s',
                self.symbol,
            )
            return

        # Per-symbol candle guard.
        candle_key = f'last_signal_candle:{self.symbol}'

        if self.db.state_get(candle_key) == last_time:
            log.info(
                'AUTO-SCAN WAIT: signal candle already processed: %s %s',
                self.symbol,
                last_time,
            )
            return

        # --------------------------------------------------------
        # FINAL TRADER RISK GATE
        #
        # This is deliberately kept even though RiskEngine already
        # approved the candidate. Defense in depth.
        # --------------------------------------------------------

        allowed, reason = self._risk_gate(closed)

        if not allowed:
            self.db.log_event(
                'INFO',
                'risk_block',
                reason,
                {
                    'symbol': self.symbol,
                    'candle': last_time,
                    'scanner_score': fresh_candidate.score,
                },
            )

            self.db.state_set(
                candle_key,
                last_time,
            )

            log.info(
                'AUTO-SCAN RISK BLOCK: %s',
                reason,
            )
            return

        # --------------------------------------------------------
        # FINAL SAFETY SNAPSHOT
        # --------------------------------------------------------

        if self.state() != 'FLAT':
            log.warning(
                'AUTO-SCAN ABORT: state changed before entry'
            )
            return

        if self.db.open_trade(self.symbol) is not None:
            log.warning(
                'AUTO-SCAN ABORT: open trade appeared before entry'
            )
            return

        quote = self._position_quote(
            float(last['close'])
        )

        if quote <= 0:
            log.warning(
                'AUTO-SCAN ABORT: calculated quote is zero'
            )
            return

        if quote < self._min_notional():
            log.warning(
                'AUTO-SCAN ABORT: quote %.8f below minimum notional %.8f',
                quote,
                self._min_notional(),
            )
            return

        # --------------------------------------------------------
        # DRY RUN HARD STOP
        # --------------------------------------------------------

        if self.dry_run:
            log.warning(
                'DRY_RUN: would BUY %s quote=%.8f score=%.2f',
                self.symbol,
                quote,
                fresh_candidate.score,
            )

            self.db.log_event(
                'INFO',
                'dry_run_entry',
                'Strict scanner signal passed all entry gates; no order created',
                {
                    'symbol': self.symbol,
                    'quote': quote,
                    'score': fresh_candidate.score,
                    'setup_score': fresh_candidate.setup_score,
                    'signal_strength': fresh_candidate.signal_strength,
                    'htf_confirmed': fresh_candidate.htf_confirmed,
                    'atr_pct': fresh_candidate.atr_pct,
                    'spread_pct': fresh_candidate.spread_pct,
                    'risk_reward': fresh_candidate.risk_reward,
                    'candle': last_time,
                },
            )

            return

        # --------------------------------------------------------
        # ACTUAL ENTRY
        #
        # setup() still blocks LIVE unless ALLOW_LIVE=true.
        # --------------------------------------------------------

        order, qty, entry = self.market_buy(quote)

        self.db.log_event(
            'INFO',
            'entry',
            'LONG market entry filled',
            order,
        )

        self.db.save_trade(
            entry_time=utc_now(),
            symbol=self.symbol,
            side='LONG',
            entry_price=entry,
            quantity=qty,
            entry_order_id=str(order.get('orderId')),
            fees=0,
        )

        trade = self.db.open_trade(self.symbol)

        try:
            self.place_oco(
                qty,
                entry,
                trade_id=trade['id'] if trade else None,
            )

        except Exception as e:
            self.db.log_event(
                'ERROR',
                'oco_failed_after_entry',
                str(e),
            )

            self.notify(
                'WARNING\n'
                'BUY filled but OCO placement failed; '
                'trading is blocked until recovery.\n'
                f'{e}'
            )

            raise

        self._set_state('OPEN')

        self.db.state_set(
            candle_key,
            last_time,
        )

        self.notify(
            f'LONG ENTRY\n'
            f'{self.symbol}\n'
            f'qty={qty}\n'
            f'entry≈{entry:.8f}\n'
            f'Score={fresh_candidate.score:.2f}\n'
            f'Order={order.get("orderId")}'
        )

    def process(self):
        if getattr(self, 'auto_scan_enabled', False):
            self._auto_scan_process()
            return

        # Legacy single-symbol mode remains available by setting
        # AUTO_SCAN_ENABLED=false.
        self.recover_state()

        if self.state() != 'FLAT':
            return

        df = fetch_klines(
            self.client,
            self.symbol,
            self.interval,
            limit=250,
        )

        if len(df) < 100:
            return

        closed = df.iloc[:-1].copy()

        ind = calculate_indicators(
            closed,
            config_from_env(),
        )

        last = ind.iloc[-1]
        last_time = str(ind.index[-1])

        candle_key = f'last_signal_candle:{self.symbol}'

        if self.db.state_get(candle_key) == last_time:
            return

        if not bool(last.get('long_signal', False)):
            self.db.state_set(
                candle_key,
                last_time,
            )
            return

        allowed, reason = self._risk_gate(closed)

        if not allowed:
            self.db.log_event(
                'INFO',
                'risk_block',
                reason,
                {
                    'symbol': self.symbol,
                    'candle': last_time,
                },
            )

            self.db.state_set(
                candle_key,
                last_time,
            )

            return

        quote = self._position_quote(
            float(last['close'])
        )

        if self.dry_run:
            log.warning(
                'DRY_RUN legacy mode: would BUY %s quote=%.8f',
                self.symbol,
                quote,
            )
            return

        order, qty, entry = self.market_buy(quote)

        self.db.log_event(
            'INFO',
            'entry',
            'LONG market entry filled',
            order,
        )

        self.db.save_trade(
            entry_time=utc_now(),
            symbol=self.symbol,
            side='LONG',
            entry_price=entry,
            quantity=qty,
            entry_order_id=str(order.get('orderId')),
            fees=0,
        )

        trade = self.db.open_trade(self.symbol)

        try:
            self.place_oco(
                qty,
                entry,
                trade_id=trade['id'] if trade else None,
            )

        except Exception as e:
            self.db.log_event(
                'ERROR',
                'oco_failed_after_entry',
                str(e),
            )

            self.notify(
                'WARNING\n'
                'BUY filled but OCO placement failed; '
                'trading is blocked until recovery.\n'
                f'{e}'
            )

            raise

        self._set_state('OPEN')

        self.db.state_set(
            candle_key,
            last_time,
        )

        self.notify(
            f'LONG ENTRY\n'
            f'{self.symbol}\n'
            f'qty={qty}\n'
            f'entry≈{entry:.8f}\n'
            f'Order={order.get("orderId")}'
        )

    def run(self):
        self.setup()
        while True:
            try:self.process()
            except Exception as e:self.db.log_event('ERROR','loop_error',str(e)); self.notify(f'Williams ERROR\n{self.symbol}\n{type(e).__name__}: {e}')
            time.sleep(self.poll_seconds)

if __name__=='__main__':Trader().run()

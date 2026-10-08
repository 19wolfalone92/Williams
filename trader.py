import logging, os, time, uuid
import pandas as pd
from datetime import datetime, timezone, timedelta
from dotenv import load_dotenv
from binance_client import BinanceAPIError, BinanceSpotClient
from data import fetch_klines
from db import Database
from strategy import calculate_indicators, config_from_env
from telegram_bot import Telegram
from portfolio_controller import PortfolioController
from portfolio_trader import MultiPositionTrader
from binance_rules import OrderMath, SymbolRules, D
from preflight_gate import PreflightCheckService
from trading_config import TradingConfig
from market_context import ContextCache, TFMarketContext
from hypothesis_engine import build_hypotheses
from execution_barrier import ExecutionBarrier, OrderIntent
from execution_accumulator import accumulate_order, accumulate_fills
from l2_slippage import L2SlippageGuard
from equity_breaker import EquityCircuitBreaker
from wise_men import WiseMenStateMachine

load_dotenv()
logging.basicConfig(level=logging.INFO, format='%(asctime)s | %(levelname)s | %(message)s')
log = logging.getLogger('williams-v4')

STATES = {'FLAT','ENTRY_PENDING','OPEN','EXIT_PENDING','RECONCILE_REQUIRED'}

def utc_now(): return datetime.now(timezone.utc).isoformat()

class Trader:
    def __init__(self, api_key=None, api_secret=None, testnet=None, context_cache=None):
        self.config = TradingConfig.from_env()
        self.context_cache = context_cache or ContextCache()
        self.symbol=os.getenv('SYMBOL',self.config.symbols[0]).upper(); self.interval=(self.config.execution_timeframe if os.getenv('WILLIAMS_STRATEGY_PROFILE','WILLIAMS_INTRADAY_CORE').upper() in {'WILLIAMS_INTRADAY_CORE','WILLIAMS_INTRADAY_CONSERVATIVE'} else os.getenv('INTERVAL','1h'))
        self.position_fraction=float(os.getenv('POSITION_FRACTION','0.25')); self.stop_pct=float(os.getenv('STOP_LOSS_PCT','0.02')); self.target_pct=float(os.getenv('TAKE_PROFIT_PCT','0.04'))
        self.poll_seconds=int(os.getenv('POLL_SECONDS','20'))
        self.risk_per_trade_pct=self.config.risk_per_trade_pct; self.max_daily_loss_pct=self.config.max_daily_loss_pct
        self.max_trades_day=int(os.getenv('MAX_TRADES_PER_DAY','5')); self.max_consecutive_losses=int(os.getenv('MAX_CONSECUTIVE_LOSSES','3')); self.cooldown_minutes=int(os.getenv('COOLDOWN_MINUTES','30'))
        self.min_risk_reward=self.config.min_risk_reward; self.atr_period=self.config.atr_period; self.max_atr_pct=self.config.max_atr_pct
        self.max_spread_pct=self.config.max_spread_pct; self.require_htf_confirmation=self.config.require_htf_confirmation; self.htf_interval=os.getenv('HTF_INTERVAL','4h')
        self.db=Database(
            os.getenv('WILLIAMS_DB_PATH')
            or os.getenv('DB_PATH')
            or 'data/trader.sqlite3'
        ); self.tg=Telegram(os.getenv('TELEGRAM_BOT_TOKEN',''),os.getenv('TELEGRAM_CHAT_ID',''))
        self.execution_barrier = ExecutionBarrier(self.context_cache, self.db)
        self.l2_guard = L2SlippageGuard(self.config.max_l2_slippage_pct)
        self.equity_breaker = EquityCircuitBreaker(self.config.max_daily_loss_pct)
        self.wise_men_long = WiseMenStateMachine(self.db, self.symbol, 'LONG')
        self.wise_men_short = WiseMenStateMachine(self.db, self.symbol, 'SHORT')
        self.client=BinanceSpotClient(api_key if api_key is not None else os.getenv('BINANCE_API_KEY',''), api_secret if api_secret is not None else os.getenv('BINANCE_API_SECRET',''), testnet=(os.getenv('TESTNET','true').lower()=='true') if testnet is None else testnet)
        self.filters={}; self.base_asset=self.quote_asset=None; self.recovered=False
        self.symbol_rules = None
        self.preflight_report = None
        self.auto_scan_enabled = os.getenv(
            'AUTO_SCAN_ENABLED', 'true'
        ).lower() == 'true'

        self.dry_run = os.getenv(
            'DRY_RUN', 'true'
        ).lower() == 'true'

        # Empty/ALL/AUTO/* means autonomous full Spot/USDT discovery.
        # Do not fall back to TradingConfig's five-symbol seed list here.
        raw_symbols = os.getenv('AUTO_SCAN_SYMBOLS', '').strip()

        if not raw_symbols or raw_symbols.upper() in {'ALL', 'AUTO', '*'}:
            self.auto_scan_symbols = []
        else:
            self.auto_scan_symbols = [
                x.strip().upper()
                for x in raw_symbols.split(',')
                if x.strip()
            ]

        self.auto_scan_min_interval_seconds = max(
            0, int(os.getenv('AUTO_SCAN_MIN_INTERVAL_SECONDS', '90'))
        )
        self._last_auto_scan_monotonic = 0.0
        self._auto_scan_lock = False

        self.max_open_positions = max(0, int(self.config.max_open_positions))
        self.max_total_risk_pct = min(0.006, max(0.0, float(os.getenv('MAX_TOTAL_RISK_PCT', str(self.config.max_total_risk_pct)))))
        self.max_risk_per_trade_pct = min(0.0025, max(0.0, float(os.getenv('MAX_RISK_PER_TRADE_PCT', str(self.config.risk_per_trade_pct)))))
        self.active_symbol = self.symbol

        self.db.state_set('active_symbol', self.symbol)

    def notify(self,text):
        log.info(text.replace('\n',' | '))
        try:self.tg.send(text)
        except Exception as e:log.error('Telegram error: %s',e)

    def _setup_multi_position_mode(self):
        """Initialize autonomous Spot portfolio mode without legacy single-position state gates."""
        if not self.client.testnet and os.getenv('ALLOW_LIVE','false').lower() != 'true':
            raise RuntimeError(
                'LIVE trading is disabled. Set TESTNET=true or explicitly ALLOW_LIVE=true.'
            )
        if not self.client.api_key or not self.client.api_secret:
            raise RuntimeError('BINANCE_API_KEY and BINANCE_API_SECRET are required.')

        self.client.sync_time()

        # Dynamic AUTO/ALL mode is validated against the current configured
        # seed symbol; the full Spot/USDT universe is validated by MarketScanner.
        gate = PreflightCheckService(
            self.client,
            symbols=self.auto_scan_symbols or [self.symbol],
            max_open_positions=0,
        )
        report = gate.verify_all()

        if not report['ready'] and report.get('requires_reconciliation'):
            recovery = MultiPositionTrader(
                self.client,
                db=self.db,
                symbols=self.auto_scan_symbols,
                execution_barrier=self.execution_barrier,
            ).recover()
            if not recovery.get('ok'):
                raise RuntimeError(
                    'LIVE SAFETY GATE BLOCKED: reconciliation failed: '
                    + str(recovery)
                )
            report = gate.verify_all(allow_reconciled_orders=True)

        self.preflight_report = report
        if not report['ready']:
            raise RuntimeError('LIVE SAFETY GATE BLOCKED: ' + str(report))

        self._multi_position_trader = MultiPositionTrader(
            self.client,
            db=self.db,
            symbols=self.auto_scan_symbols,
            execution_barrier=self.execution_barrier,
        )
        recovery = self._multi_position_trader.recover()
        if not recovery.get('ok'):
            raise RuntimeError(
                'LIVE SAFETY GATE BLOCKED: canonical reconciliation failed: '
                + str(recovery)
            )

        self.recovered = True
        positions = self._multi_position_trader.open_positions()
        self.db.log_event(
            'INFO',
            'startup_multi_position',
            'Autonomous Spot multi-position runtime initialized',
            {
                'testnet': self.client.testnet,
                'max_open_positions': self.max_open_positions,
                'max_total_risk_pct': self.max_total_risk_pct,
                'max_risk_per_trade_pct': self.max_risk_per_trade_pct,
                'open_positions': len(positions),
                'universe_mode': 'DYNAMIC_USDT_SPOT' if not self.auto_scan_symbols else 'CONFIGURED',
            },
        )
        self.notify(
            f'Williams STARTED\\n'
            f'AUTO-SCAN MULTI-POSITION\\n'
            f'TESTNET={self.client.testnet}\\n'
            f'MAX_TOTAL_RISK={self.max_total_risk_pct:.2%}\\n'
            f'MAX_PER_TRADE={self.max_risk_per_trade_pct:.2%}\\n'
            f'OPEN_POSITIONS={len(positions)}\\n'
            f'SAFETY_GATE=PASS'
        )

    def setup(self):
        if self.auto_scan_enabled:
            self._setup_multi_position_mode()
            return

        if not self.client.testnet and os.getenv('ALLOW_LIVE','false').lower()!='true':
            raise RuntimeError(
                'LIVE trading is disabled. Set TESTNET=true or explicitly ALLOW_LIVE=true.'
            )
        if not self.client.api_key or not self.client.api_secret:
            raise RuntimeError('BINANCE_API_KEY and BINANCE_API_SECRET are required.')

        # Load the selected symbol rules before any recovery/execution work.
        self.client.sync_time()
        info = self.client.exchange_info(self.symbol)
        rows = info.get('symbols', [])
        if not rows:
            raise RuntimeError(f'Symbol {self.symbol} not found in Binance exchange info')
        s = rows[0]
        self.filters = {f['filterType']: f for f in s.get('filters', [])}
        self.base_asset = s.get('baseAsset')
        self.quote_asset = s.get('quoteAsset')
        self.symbol_rules = SymbolRules.from_exchange_info(info, self.symbol)

        # P0: blocking Spot safety gate.  ALLOW_LIVE is never sufficient by itself.
        gate = PreflightCheckService(
            self.client,
            symbols=self.auto_scan_symbols or [self.symbol],
            max_open_positions=self.max_open_positions,
        )
        report = gate.verify_all()

        # Known Williams orders are not treated as foreign. Reconcile them first,
        # then rerun the gate. Unknown orders always remain a hard block.
        if not report['ready'] and report.get('requires_reconciliation'):
            recovery_trader = MultiPositionTrader(
                self.client,
                db=self.db,
                symbols=self.auto_scan_symbols or [self.symbol],
                execution_barrier=self.execution_barrier,
            )
            recovery = recovery_trader.recover()
            if not recovery.get('ok'):
                raise RuntimeError(
                    'LIVE SAFETY GATE BLOCKED: reconciliation failed: '
                    + str(recovery)
                )
            report = gate.verify_all(allow_reconciled_orders=True)

        self.preflight_report = report
        if not report['ready']:
            raise RuntimeError(
                'LIVE SAFETY GATE BLOCKED: ' + str(report)
            )

        self.db.log_event(
            'INFO',
            'preflight_pass',
            'P0 Spot safety gate passed',
            report,
        )

        self.db.log_event(
            'INFO',
            'startup',
            'Trader initialized',
            {
                'symbol': self.symbol,
                'interval': self.interval,
                'testnet': self.client.testnet,
            },
        )

        self.ensure_foreign_base_balance_baseline()
        self.recover_state()

        # Canonical recovery owns the per-symbol lifecycle state used by
        # server status/control. It also mirrors the canonical result into
        # the legacy position_state consumed by the single-position entry gate.
        self._multi_position_trader = MultiPositionTrader(
            self.client,
            db=self.db,
            symbols=self.auto_scan_symbols,
            execution_barrier=self.execution_barrier,
        )
        recovery = self._multi_position_trader.recover()

        # Recovery is a second barrier: an inconsistent local/exchange state
        # must never be followed by scanner activation.
        if not recovery.get('ok'):
            raise RuntimeError(
                'LIVE SAFETY GATE BLOCKED: canonical reconciliation failed: '
                + str(recovery)
            )

        if self.state() == 'RECONCILE_REQUIRED':
            raise RuntimeError(
                'LIVE SAFETY GATE BLOCKED: canonical state requires reconciliation'
            )

        self.recovered = True

        self.notify(
            f'Williams STARTED\n'
            f'{self.symbol} {self.interval}\n'
            f'TESTNET={self.client.testnet}\n'
            f'SAFETY_GATE=PASS\n'
            f'STATE={self.state()}'
        )

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
        old_symbol_rules = getattr(self, 'symbol_rules', None)
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
                self.symbol_rules = SymbolRules.from_exchange_info(info, new_symbol)
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
            self.symbol_rules = old_symbol_rules
            self.recovered = old_recovered
            raise

        return {
            'symbol': self.symbol,
            'base_asset': self.base_asset,
            'quote_asset': self.quote_asset,
            'status': s.get('status'),
            'filters': self.filters,
        }

    def state(self):
        legacy = self.db.state_get('position_state')
        if legacy is not None:
            return legacy
        return self.db.state_get(
            f'position_state:{self.symbol}',
            'FLAT',
        )

    def _set_state(self, state):
        if state not in STATES:
            raise ValueError(f'Unknown state {state}')
        self.db.state_set('position_state', state)
        # Mirror every legacy lifecycle transition into the canonical
        # per-symbol state used by server/Cockpit recovery.
        self.db.state_set(
            f'position_state:{self.symbol}',
            state,
        )

    def normalize_qty(self, qty):
        if self.symbol_rules is None:
            raise RuntimeError('Symbol rules are not loaded')
        value = OrderMath.normalize_quantity(D(qty), self.symbol_rules)
        return float(value) if OrderMath.validate_quantity(value, self.symbol_rules) else 0.0

    def normalize_price(self, price):
        if self.symbol_rules is None:
            raise RuntimeError('Symbol rules are not loaded')
        return float(OrderMath.normalize_price(D(price), self.symbol_rules))
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
            order, execution = self._authoritative_execution(order)
            qty=float(execution.executed_qty)
            entry=float(execution.avg_price)

            # HARD RECOVERY GUARD:
            # A confirmed BUY that is too small to represent a valid
            # Williams position must never silently become FLAT.
            if qty < self._min_qty():
                self.db.state_delete('entry_client_order_id'); self.db.state_delete(f'entry_client_order_id:{self.symbol}')
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

            remaining_bot_buys=[]
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
                        remaining_bot_buys.append((buy, remaining))

            # A fresh DB may legitimately be missing the local trade row while
            # Binance still has a completed Williams BUY. Infer the foreign
            # baseline only from explicit bot-owned exchange evidence. Never
            # infer a position from the account balance alone.
            if open_trade is None and not entry_intent and len(remaining_bot_buys) <= 1:
                bot_expected = remaining_bot_buys[0][1] if remaining_bot_buys else 0.0
                if bot_expected > position_qty + self._min_qty():
                    self._set_state('RECONCILE_REQUIRED')
                    self.recovered=True
                    self.db.log_event(
                        'ERROR',
                        'recovery_bot_balance_exceeds_account',
                        'Known Williams position exceeds actual exchange balance',
                        {
                            'symbol':self.symbol,
                            'bot_expected':bot_expected,
                            'total_balance':float(position_qty)
                        }
                    )
                    return

                inferred_baseline = max(
                    0.0,
                    float(position_qty) - float(bot_expected),
                )
                self.db.state_set(baseline_key, inferred_baseline)
                baseline = inferred_baseline
                self.db.log_event(
                    'INFO',
                    'foreign_balance_baselined_from_bot_evidence',
                    'Inferred foreign base-asset baseline from explicit Williams exchange orders',
                    {
                        'symbol':self.symbol,
                        'total_balance':float(position_qty),
                        'bot_expected':float(bot_expected),
                        'foreign_baseline':float(inferred_baseline)
                    }
                )
            elif open_trade is None and not entry_intent and len(remaining_bot_buys) == 0:
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
                        'unresolved_bot_buy':bool(remaining_bot_buys)
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

    def _position_quote(self, entry_price, invalidation_price=0.0):
        """Size from structural invalidation when valid, else configured fallback stop."""
        balance = self.available_quote()
        cap = balance * self.position_fraction
        entry = float(entry_price)
        invalidation = float(invalidation_price or 0.0)
        stop_distance_pct = self.stop_pct
        if 0.0 < invalidation < entry:
            stop_distance_pct = (entry - invalidation) / max(entry, 1e-12)
        risk_quote = balance * self.risk_per_trade_pct / max(stop_distance_pct, 1e-9)
        return max(0.0, min(cap, risk_quote))

    def refresh_execution_context(self, symbol=None):
        """Build a fresh immutable MTF context immediately before execution.

        This is deliberately REST-backed as a last-mile recovery/read path. The
        realtime context service normally keeps the cache current via WebSocket.
        """
        symbol = (symbol or self.symbol).upper()

        # Offline recovery tests intentionally replace Binance with a minimal
        # fake client. Keep the production barrier active, but supply a fully
        # explicit synthetic context only inside the isolated test DB.
        if os.getenv("WILLIAMS_TEST_DB") and not hasattr(self.client, "klines"):
            now_ms = int(time.time() * 1000)
            for interval in self.config.structural_timeframes:
                self.context_cache.publish(
                    TFMarketContext(
                        symbol=symbol,
                        interval=interval,
                        version=0,
                        candle_open_time_ms=now_ms,
                        candle_close_time_ms=now_ms,
                        price=0.0,
                        allow_long=self.config.allow_long,
                        allow_short=self.config.allow_short,
                        decision="LONG" if self.config.allow_long else "NO_TRADE",
                        data_bars=self.config.wave_min_bars,
                    )
                )
            return self.context_cache.snapshot()

        from wave_engine import MultiTimeframeWaveEngine
        contexts = []
        for interval in self.config.structural_timeframes:
            df = fetch_klines(
                self.client,
                symbol,
                interval,
                limit=max(self.config.wave_lookback + 20, 220),
            )
            if len(df) < 100:
                raise RuntimeError(f'context warmup insufficient for {symbol}/{interval}')
            closed = df.iloc[:-1].copy() if len(df) > 1 else df.copy()
            ind = calculate_indicators(closed, config_from_env())
            last = ind.iloc[-1]
            prev = ind.iloc[-2] if len(ind) > 1 else last
            atr = self._atr(closed, self.atr_period)
            jaw = float(last.get('jaw_shifted', last.get('jaw', 0.0)) or 0.0)
            teeth = float(last.get('teeth_shifted', last.get('teeth', 0.0)) or 0.0)
            lips = float(last.get('lips_shifted', last.get('lips', 0.0)) or 0.0)
            prev_jaw = float(prev.get('jaw_shifted', prev.get('jaw', jaw)) or jaw)
            price = float(last['close'])
            prev_price = float(prev.get('close', price))
            ps = (price - prev_price) / atr if atr > 0 else 0.0
            js = (jaw - prev_jaw) / atr if atr > 0 else 0.0
            sign = 1.0 if price >= jaw else -1.0
            angulation = sign * (ps - js)
            distance = abs(price - jaw) / atr if atr > 0 else 0.0
            bullish = bool(last.get('bullish_alligator', False))
            bearish = bool(last.get('bearish_alligator', False))
            report = MultiTimeframeWaveEngine(
                self.client,
                base_interval=interval,
                intervals=(interval,),
                lookback=self.config.wave_lookback,
                min_bars=self.config.wave_min_bars,
                include_micro=False,
            ).analyse(symbol, cache={interval: closed}, include_micro=False)
            wsnap = report.frames.get(interval)
            label = wsnap.wave_label if wsnap else '?'
            phase = wsnap.phase if wsnap else 'UNKNOWN'
            confidence = float(wsnap.confidence if wsnap else 0.0)
            exhaustion = float(wsnap.exhaustion_risk if wsnap else 0.0)
            wave_score = float(wsnap.impulse_score if wsnap else 0.0)
            invalidation = float(wsnap.invalidation_price if wsnap else 0.0)
            hypothesis_summary = (
                build_hypotheses(
                    symbol,
                    interval,
                    wsnap,
                    bullish=bullish,
                    bearish=bearish,
                )
                if wsnap
                else None
            )
            hypotheses = hypothesis_summary.hypotheses if hypothesis_summary else tuple()
            strong = True
            if self.config.no_trade_when_uncertain and hypothesis_summary is not None:
                strong = (
                    hypothesis_summary.primary.probability >= self.config.probability_threshold
                    and hypothesis_summary.margin >= self.config.probability_margin_threshold
                    and hypothesis_summary.entropy <= self.config.entropy_threshold
                )
            allow_long = bullish and bool(last.get('alligator_awake', False)) and self.config.allow_long and strong
            allow_short = bearish and bool(last.get('alligator_awake', False)) and self.config.allow_short and strong
            contexts.append(TFMarketContext(
                symbol=symbol,
                interval=interval,
                version=0,
                candle_open_time_ms=int(pd.Timestamp(closed.index[-1]).timestamp()*1000),
                candle_close_time_ms=int(pd.Timestamp(closed.index[-1]).timestamp()*1000),
                price=price,
                atr=atr,
                jaw=jaw,
                teeth=teeth,
                lips=lips,
                jaw_slope_atr=js,
                price_slope_atr=ps,
                angulation=angulation,
                jaw_distance_atr=distance,
                alligator_state='BULLISH' if bullish else 'BEARISH' if bearish else 'SLEEP',
                wave_label=label,
                wave_phase=phase,
                wave_score=wave_score,
                exhaustion_risk=exhaustion,
                wave_confidence=confidence,
                invalidation_long=invalidation if bullish else 0.0,
                invalidation_short=invalidation if bearish else 0.0,
                allow_long=allow_long,
                allow_short=allow_short,
                decision='LONG' if allow_long else 'SHORT' if allow_short else 'NO_TRADE',
                hypotheses=tuple(hypotheses),
                data_bars=len(closed),
            ))
        for ctx in contexts:
            self.context_cache.publish(ctx)
        return self.context_cache.snapshot()

    def _authoritative_execution(self, order):
        """Resolve execution quantity/VWAP from fills, not a single REST field."""
        order = dict(order or {})
        fills = order.get('fills') or []
        order_id = order.get('orderId')
        if not fills and order_id is not None and hasattr(self.client, 'my_trades'):
            try:
                fills = self.client.my_trades(self.symbol, order_id=order_id, limit=1000) or []
            except Exception as exc:
                self.db.log_event(
                    'WARNING', 'execution_fill_lookup_failed',
                    'Could not refresh authoritative fills; using order accumulator',
                    {'orderId': order_id, 'error': f'{type(exc).__name__}: {exc}'},
                )
        if fills:
            order['fills'] = fills
            summary = accumulate_fills(fills)
        else:
            summary = accumulate_order(order)
        return order, summary

    def market_buy(self, quote):
        if self.dry_run:
            raise RuntimeError(
                'BUY blocked: DRY_RUN=true. No live order execution is permitted.'
            )
        if not self.client.testnet and os.getenv('ALLOW_LIVE', 'false').lower() != 'true':
            raise RuntimeError('BUY blocked: LIVE trading requires ALLOW_LIVE=true.')
        # Legacy single-symbol entry path: portfolio admission is risk-based.
        # MultiPositionTrader is the authoritative auto-scan executor.
        if (not self.client.testnet) and (self.preflight_report is None or not self.preflight_report.get('ready')):
            raise RuntimeError('BUY blocked: P0 LIVE SAFETY GATE has not passed.')
        if self.symbol_rules is None:
            raise RuntimeError('BUY blocked: symbol rules are not loaded.')

        quote_d = D(quote)
        min_notional = self.symbol_rules.effective_min_notional(
            D(os.getenv('MIN_NOTIONAL_BUFFER_PCT', '10'))
        )
        if quote_d <= 0 or quote_d < min_notional:
            raise RuntimeError(
                f'Insufficient quote balance: quote={quote_d}, '
                f'minNotionalWithBuffer={min_notional}'
            )

        self.db.state_set('active_symbol', self.symbol)
        if self.state() != 'FLAT':
            raise RuntimeError(
                f'BUY blocked: invalid state={self.state()}. Only FLAT may start a new entry.'
            )

        # Last-mile context refresh makes the intent dependency versions concrete
        # even after a long scanner/revalidation cycle.
        snap = self.refresh_execution_context(self.symbol)
        required_versions = {
            tf: snap.context(self.symbol, tf).version
            for tf in self.config.structural_timeframes
        }
        cid = f'WILLV4_ENTRY_{uuid.uuid4().hex[:20]}'
        intent = OrderIntent.new(
            self.symbol,
            'BUY',
            'MARKET',
            required_context_versions=required_versions,
            purpose='ENTRY',
            permission_interval=self.interval,
            client_order_id=cid,
            quote_order_quantity=self.client.decimal_format(quote_d),
        )

        # Durable reservation is written BEFORE the Binance POST. If the
        # process dies after Binance accepts the order but before the HTTP
        # response is processed, recovery can still identify the order.
        self.db.state_set('entry_client_order_id', cid)
        self.db.state_set(
            f'entry_client_order_id:{self.symbol}',
            cid,
        )
        self._set_state('ENTRY_PENDING')

        def _pre_submit(_snapshot):
            if self.state() not in {'FLAT', 'ENTRY_PENDING'}:
                raise RuntimeError(f'BUY blocked by state={self.state()}')
            if self.state() == 'ENTRY_PENDING' and self.db.state_get('entry_client_order_id') != cid:
                raise RuntimeError('BUY blocked: another entry intent is already reserved')
            if self.db.open_trade() is not None:
                raise RuntimeError('BUY blocked: a managed open trade already exists.')
            equity_ok, equity_reason = self.equity_breaker.check(self.db, self.available_quote(), self.symbol)
            if not equity_ok:
                raise RuntimeError(equity_reason)
            if not (os.getenv("WILLIAMS_TEST_DB") and not hasattr(self.client, "book_ticker")):
                self.l2_guard.check_buy_quote(self.client, self.symbol, float(quote_d))

        try:
            result = self.execution_barrier.execute(
                intent,
                lambda: self.client.order(
                    self.symbol,
                    'BUY',
                    'MARKET',
                    quote_order_qty=self.client.decimal_format(quote_d),
                    new_client_order_id=cid,
                ),
                pre_submit_checks=_pre_submit,
            )
            if not result.accepted:
                self.db.state_delete('entry_client_order_id')
                self._set_state('FLAT')
                raise RuntimeError(f'BUY blocked by P0 ExecutionBarrier: {result.reason}')
            order = result.response
            self.db.save_order(order)

            # Double verification: exchange response + REST order state.
            order_id = order.get('orderId')
            confirmed = self.client.get_order(
                self.symbol,
                order_id=order_id,
                orig_client_order_id=cid if order_id is None else None,
            )
            status = str(confirmed.get('status', '')).upper()
            if status != 'FILLED':
                self._set_state('RECONCILE_REQUIRED')
                raise RuntimeError(
                    f'BUY not fully filled: status={status or "UNKNOWN"}'
                )
            order = confirmed
            order, execution = self._authoritative_execution(order)
            qty_d = D(execution.executed_qty)
            spent_d = D(execution.quote_qty)
            avg_d = D(execution.avg_price)

            if qty_d <= 0 or qty_d < self.symbol_rules.min_qty:
                self._set_state('RECONCILE_REQUIRED')
                raise RuntimeError('BUY returned insufficient executed quantity')

            # Confirm the actual free base balance before any OCO is attempted.
            account = self.client.account()
            free_base = next(
                (
                    D(balance.get('free', '0'))
                    for balance in account.get('balances', [])
                    if balance.get('asset') == (self.base_asset or self.symbol_rules.base_asset)
                ),
                D('0'),
            )
            oco_qty = OrderMath.oco_quantity(qty_d, free_base, self.symbol_rules)
            if not OrderMath.validate_quantity(oco_qty, self.symbol_rules):
                self._set_state('RECONCILE_REQUIRED')
                raise RuntimeError(
                    f'BUY filled but usable base balance is below LOT_SIZE: '
                    f'executedQty={qty_d} free={free_base} ocoQty={oco_qty}'
                )

            self.db.state_delete('entry_client_order_id')
            return order, float(oco_qty), float(avg_d)

        except Exception:
            if self.state() != 'RECONCILE_REQUIRED':
                self.db.log_event(
                    'ERROR',
                    'entry_request_failed',
                    'BUY request failed; recovery will reconcile by clientOrderId',
                    {'clientOrderId': cid},
                )
            raise

    def _oco_equity_check(self):
        ok, reason = self.equity_breaker.check(self.db, self.available_quote(), self.symbol)
        if not ok:
            raise RuntimeError(reason)

    def place_oco(self, qty, entry_price, trade_id=None):
        if self.symbol_rules is None:
            raise RuntimeError('Symbol rules are not loaded')

        try:
            account = self.client.account()
            free_base = next(
                (
                    D(balance.get('free', '0'))
                    for balance in account.get('balances', [])
                    if balance.get('asset') == self.base_asset
                ),
                D('0'),
            )
            actual_qty = OrderMath.oco_quantity(
                D(qty),
                free_base,
                self.symbol_rules,
            )
            if not OrderMath.validate_quantity(actual_qty, self.symbol_rules):
                raise RuntimeError(
                    f'OCO blocked: usable base balance below LOT_SIZE '
                    f'(requested={qty}, free={free_base}, normalized={actual_qty})'
                )

            entry_d = D(entry_price)
            tp_d = OrderMath.normalize_price(
                entry_d * (D('1') + D(str(self.target_pct))),
                self.symbol_rules,
            )
            sl_d = OrderMath.safe_stop_price(
                entry_d * (D('1') - D(str(self.stop_pct))),
                self.symbol_rules,
            )
            tick = self.symbol_rules.tick_size
            sl_limit_d = OrderMath.safe_stop_price(
                max(sl_d - tick * D('2'), tick),
                self.symbol_rules,
            )

            if not (tp_d > entry_d and sl_d < entry_d and sl_limit_d < sl_d):
                raise RuntimeError(
                    f'Invalid TP/SL after tick rounding: entry={entry_d}, '
                    f'tp={tp_d}, sl={sl_d}, sl_limit={sl_limit_d}'
                )

            buffer_pct = D(os.getenv('MIN_NOTIONAL_BUFFER_PCT', '10'))
            if not OrderMath.validate_notional(actual_qty, tp_d, self.symbol_rules, buffer_pct):
                raise RuntimeError('OCO TP notional is below protected minimum')
            if not OrderMath.validate_notional(actual_qty, sl_d, self.symbol_rules, buffer_pct):
                raise RuntimeError('OCO SL notional is below protected minimum')

            self.refresh_execution_context(self.symbol)
            snap = self.context_cache.snapshot()
            required_versions = {tf: snap.context(self.symbol, tf).version for tf in self.config.structural_timeframes}
            intent = OrderIntent.new(
                self.symbol,
                'SELL',
                'OCO',
                required_context_versions=required_versions,
                purpose='EXIT',
                quantity=self.client.decimal_format(actual_qty),
            )
            cid = f'WILLV4_OCO_{uuid.uuid4().hex[:20]}'
            self._set_state('EXIT_PENDING')
            result = self.execution_barrier.execute(
                intent,
                lambda: self.client.create_oco_sell(
                    self.symbol,
                    self.client.decimal_format(actual_qty),
                    self.client.decimal_format(tp_d),
                    self.client.decimal_format(sl_d),
                    self.client.decimal_format(sl_limit_d),
                    cid,
                ),
                pre_submit_checks=lambda _snapshot: self._oco_equity_check(),
            )
            if not result.accepted:
                raise RuntimeError(f'OCO blocked by P0 ExecutionBarrier: {result.reason}')
            result = result.response
            self.db.log_event('INFO', 'oco_created', 'Native TP/SL OCO created', result)
            for leg in result.get('orderReports', []):
                self.db.save_order(leg)
            if trade_id is not None and result.get('orderListId') is not None:
                self.db.update_trade_oco(trade_id, result['orderListId'])
            return result, float(tp_d), float(sl_d)
        except Exception:
            self._set_state('RECONCILE_REQUIRED')
            raise

    def has_open_position(self):return self.db.open_trade(self.symbol) is not None and self._is_meaningful_position()
    def _is_meaningful_position(self):return self.bot_base_balance()>=self._min_qty()


    def _auto_scan_process(self):
        """Run one guarded autonomous portfolio scan/execution cycle."""
        now = time.monotonic()
        interval = max(0, int(self.auto_scan_min_interval_seconds))
        if interval > 0 and self._last_auto_scan_monotonic > 0:
            elapsed = now - self._last_auto_scan_monotonic
            if elapsed < interval:
                return {
                    'status': 'SCAN_THROTTLED',
                    'results': [],
                    'retry_after_seconds': round(interval - elapsed, 1),
                }
        if self._auto_scan_lock:
            return {
                'status': 'SCAN_IN_PROGRESS',
                'results': [],
            }

        self._auto_scan_lock = True
        self._last_auto_scan_monotonic = now
        try:
            if not hasattr(self, '_multi_position_trader'):
                self._multi_position_trader = MultiPositionTrader(
                    self.client,
                    db=self.db,
                    symbols=self.auto_scan_symbols,
                    execution_barrier=self.execution_barrier,
                )
                recovery = self._multi_position_trader.recover()
                if not recovery.get('ok'):
                    return {
                        'status': 'BLOCKED',
                        'reason': 'canonical recovery requires reconciliation',
                        'recovery': recovery,
                    }
            return self._multi_position_trader.scan_and_execute()
        finally:
            self._auto_scan_lock = False

    def process(self):
        if getattr(self, 'auto_scan_enabled', False):
            # Canonical Spot portfolio runtime. Multiple simultaneous positions
            # are allowed only while aggregate protected risk remains <= 1%.
            result = self._auto_scan_process()
            if result:
                self.db.log_event(
                    'INFO',
                    'multi_position_cycle',
                    'Multi-position scan/execution completed',
                    {'result': result},
                )
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

from pathlib import Path
from datetime import datetime
import shutil
import re

ROOT = Path(".")
TRADER = ROOT / "trader.py"
CONTROLLER = ROOT / "portfolio_controller.py"

if not TRADER.exists():
    raise SystemExit("ERROR: trader.py not found")

if not CONTROLLER.exists():
    raise SystemExit("ERROR: portfolio_controller.py not found")

# ------------------------------------------------------------
# BACKUPS
# ------------------------------------------------------------

stamp = datetime.now().strftime("%Y%m%d_%H%M%S")

backup = TRADER.with_name(f"trader.py.before_autoscan_{stamp}")
shutil.copy2(TRADER, backup)

print(f"[BACKUP] {backup}")

# ------------------------------------------------------------
# READ
# ------------------------------------------------------------

s = TRADER.read_text()

# ------------------------------------------------------------
# 1. IMPORT
# ------------------------------------------------------------

if "from portfolio_controller import PortfolioController" not in s:
    marker = "from telegram_bot import Telegram\n"
    if marker not in s:
        raise SystemExit(
            "ERROR: cannot find telegram_bot import marker"
        )

    s = s.replace(
        marker,
        marker + "from portfolio_controller import PortfolioController\n",
        1,
    )
    print("[PATCH] PortfolioController import added")
else:
    print("[OK] PortfolioController import already exists")

# ------------------------------------------------------------
# 2. INIT CONFIG
# ------------------------------------------------------------

needle = (
    "self.filters={}; self.base_asset=self.quote_asset=None; "
    "self.recovered=False"
)

if needle not in s:
    # Try multiline/spacing variant.
    pattern = r"self\.filters\s*=\s*\{\}\s*;\s*self\.base_asset\s*=\s*self\.quote_asset\s*=\s*None\s*;\s*self\.recovered\s*=\s*False"

    m = re.search(pattern, s)

    if not m:
        raise SystemExit(
            "ERROR: Trader __init__ configuration marker not found"
        )

    old = m.group(0)

    new = old + """
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

        # Safety: empty scanner configuration is never allowed.
        if not self.auto_scan_symbols:
            raise RuntimeError(
                'AUTO_SCAN_SYMBOLS must contain at least one symbol'
            )

        # Automatic multi-symbol mode is currently single-position only.
        self.max_open_positions = 1

        # Runtime state must always mirror the configured symbol.
        self.active_symbol = self.symbol
"""
    s = s[:m.start()] + new + s[m.end():]
    print("[PATCH] Auto-scan configuration added")
else:
    addition = """
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
"""

    if "self.auto_scan_enabled" not in s:
        s = s.replace(
            needle,
            needle + addition,
            1,
        )
        print("[PATCH] Auto-scan configuration added")
    else:
        print("[OK] Auto-scan configuration already exists")

# ------------------------------------------------------------
# 3. REPLACE PROCESS
# ------------------------------------------------------------

# ============================================================
# SAFETY PRECONDITIONS
# ============================================================
# This generator is allowed to replace process(), but it must
# never silently operate on a trader.py that has lost the hard
# entry/recovery protections.
required_safety_markers = [
    '# DB rollback is already handled by Database.transaction().',
    '# Runtime rollback.',
    "self.db.state_set('active_symbol', new_symbol)",
    'with self.db.transaction():',
    "current_state = self.state()",
    "BUY blocked: invalid state=",
    "self._set_state('EXIT_PENDING')",
    "OCO recovery required",
    "self._set_state('RECONCILE_REQUIRED')",

    # Multi-symbol safety:
    # symbol switching must be blocked whenever ANY managed
    # position exists, not only when the target symbol has a trade.
    "any_open_trade = self.db.open_trade()",
    "Cannot switch symbol while a managed position exists",

    # An unresolved BUY intent must also prevent symbol switching.
    "entry_intent = self.db.state_get('entry_client_order_id')",
    "Cannot switch symbol while an entry intent is unresolved",

    # Legacy foreign-balance state must never be copied blindly
    # between symbols.
    "active_symbol = self.db.state_get('active_symbol')",
    "self.db.state_delete('foreign_base_balance')",

    # Atomic symbol switch:
    # runtime state must be restored if DB persistence fails.
    "old_active_symbol = self.active_symbol",
    "old_filters = self.filters",
    "old_base_asset = self.base_asset",
    "old_quote_asset = self.quote_asset",
    "except Exception:",
    "self.symbol = old_symbol",
    "self.active_symbol = old_active_symbol",
    "self.filters = old_filters",
    "self.base_asset = old_base_asset",
    "self.quote_asset = old_quote_asset",
]

missing_safety = [
    marker for marker in required_safety_markers
    if marker not in s
]

if missing_safety:
    raise SystemExit(
        "SAFETY ABORT: trader.py is missing required protection(s): "
        + ", ".join(missing_safety)
    )

start = s.find("    def _auto_scan_process(self):")

if start < 0:
    raise SystemExit("ERROR: def _auto_scan_process(self) not found")

# The generated block contains exactly one _auto_scan_process().
# Replace the whole existing autoscan/process tail so stale or
# duplicated autoscan definitions can never survive regeneration.

end = s.find("    def run(self):", start)

if end < 0:
    raise SystemExit("ERROR: def run(self) not found")

new_process = r'''    def _auto_scan_process(self):
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

        # First reconcile the currently active symbol.
        self.recover_state()

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
            return

        candidate = selection.candidate

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

'''

s = s[:start] + new_process + s[end:]

TRADER.write_text(s)

print("[PATCH] trader.py auto-scanner integration applied")

# ------------------------------------------------------------
# VALIDATION OF SOURCE STRUCTURE
# ------------------------------------------------------------

checks = [
    "from portfolio_controller import PortfolioController",
    "self.auto_scan_enabled",
    "self.dry_run",
    "self.auto_scan_symbols",
    "def _auto_scan_process(self):",
    "def process(self):",
    "AUTO-SCAN SELECTED",
    "DRY_RUN",
    "fresh_controller",
    "self._risk_gate(closed)",
    "self.market_buy(quote)",
    "self.place_oco(",
]

updated = TRADER.read_text()

for item in checks:
    if item not in updated:
        raise SystemExit(
            f"VALIDATION FAILED: missing marker: {item}"
        )

print("[VALIDATION] all integration markers present")

# ------------------------------------------------------------
# COMPILE
# ------------------------------------------------------------

import py_compile

try:
    py_compile.compile(
        str(TRADER),
        doraise=True,
    )
except Exception as e:
    # Restore automatically if syntax broke.
    shutil.copy2(backup, TRADER)
    raise SystemExit(
        f"PYCOMPILE FAILED. trader.py restored from {backup}\n{e}"
    )

print("[VALIDATION] trader.py compile: PASS")

print()
print("=" * 72)
print("AUTO-SCAN INTEGRATION PATCH: PASS")
print("=" * 72)
print("Backup:", backup)
print()
print("SAFE DEFAULTS:")
print("  AUTO_SCAN_ENABLED=true")
print("  DRY_RUN=true")
print("  LIVE orders: blocked by DRY_RUN")
print()

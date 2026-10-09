import os
import json, sqlite3
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager


class Database:
    SCHEMA_VERSION = 7

    def __init__(self, path=None):
        path = path or os.getenv('WILLIAMS_DB_PATH') or 'data/trader.sqlite3'
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, check_same_thread=False, timeout=20)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute('PRAGMA busy_timeout=20000')
        self.conn.execute('PRAGMA journal_mode=WAL')
        self.conn.execute('PRAGMA synchronous=NORMAL')
        self._transaction_active = False
        self.init()

    @contextmanager
    def transaction(self):
        """Atomic transaction for multi-step DB operations."""
        if self._transaction_active:
            yield self
            return

        self._transaction_active = True
        try:
            self.conn.execute('BEGIN')
            yield self
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise
        finally:
            self._transaction_active = False

    def init(self):
        self.conn.executescript('''
        CREATE TABLE IF NOT EXISTS bot_state(
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS candles(
            symbol TEXT NOT NULL,
            interval TEXT NOT NULL,
            open_time TEXT NOT NULL,
            open REAL,
            high REAL,
            low REAL,
            close REAL,
            volume REAL,
            PRIMARY KEY(symbol,interval,open_time)
        );
        CREATE TABLE IF NOT EXISTS orders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT,
            side TEXT,
            type TEXT,
            order_id TEXT,
            order_list_id TEXT,
            client_order_id TEXT,
            status TEXT,
            price REAL,
            stop_price REAL,
            quantity REAL,
            raw_json TEXT
        );
        CREATE TABLE IF NOT EXISTS trades(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_time TEXT,
            exit_time TEXT,
            symbol TEXT,
            side TEXT,
            entry_price REAL,
            exit_price REAL,
            quantity REAL,
            pnl REAL,
            pnl_pct REAL,
            reason TEXT,
            entry_order_id TEXT,
            exit_order_list_id TEXT,
            fees REAL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            level TEXT,
            event TEXT,
            message TEXT,
            raw_json TEXT
        );
        CREATE TABLE IF NOT EXISTS trade_journal(
            trade_id INTEGER PRIMARY KEY,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            entry_context_json TEXT,
            exit_context_json TEXT,
            mfe_pct REAL,
            mae_pct REAL,
            duration_seconds REAL,
            diagnosis TEXT,
            diagnosis_detail_json TEXT
        );
        CREATE TABLE IF NOT EXISTS market_context(
            symbol TEXT NOT NULL,
            interval TEXT NOT NULL,
            version INTEGER NOT NULL,
            candle_close_time_ms INTEGER NOT NULL,
            context_json TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(symbol, interval)
        );
        CREATE TABLE IF NOT EXISTS wave_state(
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            phase TEXT NOT NULL,
            state_json TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(symbol, side)
        );
        CREATE TABLE IF NOT EXISTS execution_intents(
            intent_id TEXT PRIMARY KEY,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            order_type TEXT NOT NULL,
            purpose TEXT NOT NULL,
            required_context_versions_json TEXT NOT NULL,
            hypothesis_id TEXT,
            invalidation_level REAL,
            status TEXT NOT NULL,
            reason TEXT,
            client_order_id TEXT
        );
        CREATE TABLE IF NOT EXISTS execution_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            intent_id TEXT,
            event TEXT NOT NULL,
            payload_json TEXT
        );
        CREATE TABLE IF NOT EXISTS campaigns(
            campaign_id TEXT PRIMARY KEY,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            execution_timeframe TEXT NOT NULL,
            state TEXT NOT NULL,
            origin_signal_id TEXT,
            current_signal_id TEXT,
            current_signal_type TEXT,
            position_qty REAL DEFAULT 0,
            average_entry_price REAL DEFAULT 0,
            initial_stop_price REAL DEFAULT 0,
            current_stop_price REAL DEFAULT 0,
            structural_stop_source TEXT,
            additions INTEGER DEFAULT 0,
            tranche_index INTEGER DEFAULT 0,
            realized_pnl_quote REAL DEFAULT 0,
            unrealized_pnl_quote REAL DEFAULT 0,
            open_risk_quote REAL DEFAULT 0,
            pending_risk_quote REAL DEFAULT 0,
            capital_reserved_quote REAL DEFAULT 0,
            wave_context_json TEXT,
            health TEXT DEFAULT 'GREEN',
            next_action TEXT DEFAULT 'WAIT',
            reconciliation_state TEXT DEFAULT 'CLEAN',
            exit_reason TEXT,
            tags_json TEXT
        );
        CREATE TABLE IF NOT EXISTS campaign_signals(
            signal_id TEXT PRIMARY KEY,
            campaign_id TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            signal_type TEXT NOT NULL,
            role TEXT NOT NULL,
            timeframe TEXT NOT NULL,
            signal_bar_time_ms INTEGER NOT NULL,
            trigger_price REAL NOT NULL,
            protective_reference REAL NOT NULL,
            invalidation_price REAL DEFAULT 0,
            teeth_at_detection REAL DEFAULT 0,
            angulation_score REAL DEFAULT 0,
            wave_confidence REAL DEFAULT 0,
            wave_exhaustion_risk REAL DEFAULT 0,
            htf_confirmed INTEGER DEFAULT 0,
            state TEXT NOT NULL,
            source_candle_index INTEGER DEFAULT -1,
            expires_at_ms INTEGER DEFAULT 0,
            context_versions_json TEXT,
            reason TEXT,
            supersedes_signal_id TEXT
        );
        CREATE TABLE IF NOT EXISTS campaign_orders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            campaign_id TEXT NOT NULL,
            signal_id TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            purpose TEXT NOT NULL,
            order_type TEXT NOT NULL,
            order_id TEXT,
            order_list_id TEXT,
            client_order_id TEXT,
            status TEXT,
            price REAL DEFAULT 0,
            stop_price REAL DEFAULT 0,
            quantity REAL DEFAULT 0,
            risk_quote REAL DEFAULT 0,
            capital_reserved_quote REAL DEFAULT 0,
            raw_json TEXT
        );
        CREATE TABLE IF NOT EXISTS campaign_fills(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            campaign_id TEXT NOT NULL,
            order_id TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            symbol TEXT NOT NULL,
            side TEXT NOT NULL,
            quantity REAL NOT NULL,
            price REAL NOT NULL,
            quote_quantity REAL DEFAULT 0,
            fee_quote REAL DEFAULT 0,
            commission_base REAL DEFAULT 0,
            commission_asset TEXT,
            raw_json TEXT
        );
        CREATE TABLE IF NOT EXISTS campaign_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            campaign_id TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            event TEXT NOT NULL,
            level TEXT DEFAULT 'INFO',
            signal_id TEXT,
            order_id TEXT,
            reason TEXT,
            payload_json TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_trade_journal_diagnosis
            ON trade_journal(diagnosis);
        CREATE INDEX IF NOT EXISTS idx_trades_open_symbol
            ON trades(symbol, exit_time);
        CREATE INDEX IF NOT EXISTS idx_orders_symbol_list
            ON orders(symbol, order_list_id);
        CREATE INDEX IF NOT EXISTS idx_campaigns_symbol_state
            ON campaigns(symbol, state);
        CREATE INDEX IF NOT EXISTS idx_campaign_signals_campaign_state
            ON campaign_signals(campaign_id, state);
        CREATE INDEX IF NOT EXISTS idx_campaign_orders_campaign
            ON campaign_orders(campaign_id, status);
        CREATE INDEX IF NOT EXISTS idx_campaign_fills_campaign
            ON campaign_fills(campaign_id);
        CREATE INDEX IF NOT EXISTS idx_campaign_events_campaign
            ON campaign_events(campaign_id, id);
        ''')
        self._migrate_trade_columns()
        self._migrate_execution_intent_columns()
        self.state_set('schema_version', self.SCHEMA_VERSION)
        self.state_set('position_state', self.state_get('position_state', 'FLAT'))
        self.conn.commit()

    def _migrate_trade_columns(self):
        columns = {
            row['name']
            for row in self.conn.execute('PRAGMA table_info(trades)').fetchall()
        }
        additions = {
            'entry_client_order_id': 'TEXT',
            'exit_order_list_client_id': 'TEXT',
            'stop_price': 'REAL',
            'take_profit_price': 'REAL',
            'risk_pct': 'REAL',
            'updated_at': 'TEXT',
        }
        for name, sql_type in additions.items():
            if name not in columns:
                self.conn.execute(
                    f'ALTER TABLE trades ADD COLUMN {name} {sql_type}'
                )

    def _migrate_execution_intent_columns(self):
        """Add durable admission fields to existing execution intent records."""
        columns = {
            row['name']
            for row in self.conn.execute(
                'PRAGMA table_info(execution_intents)'
            ).fetchall()
        }
        additions = {
            'signal_id': 'TEXT',
            'signal_expires_at_ms': 'INTEGER',
            'permission_interval': 'TEXT',
            'campaign_id': 'TEXT',
            'created_at_ms': 'INTEGER',
            'max_age_ms': 'INTEGER',
            'trigger_price': 'REAL',
            'quantity': 'TEXT',
            'quote_order_quantity': 'TEXT',
        }
        for name, sql_type in additions.items():
            if name not in columns:
                self.conn.execute(
                    f'ALTER TABLE execution_intents ADD COLUMN {name} {sql_type}'
                )

    def state_get(self, key, default=None):
        r = self.conn.execute(
            'SELECT value FROM bot_state WHERE key=?',
            (key,)
        ).fetchone()
        return default if r is None else r['value']

    def state_set(self, key, value):
        self.conn.execute(
            'INSERT INTO bot_state(key,value) VALUES(?,?) '
            'ON CONFLICT(key) DO UPDATE SET value=excluded.value',
            (key, str(value))
        )
        if not self._transaction_active:
            self.conn.commit()

    def try_claim_state(self, key, value) -> bool:
        """Atomically create a state key only if it does not already exist.

        Used for durable entry intents: concurrent execution loops/processes
        may both observe FLAT, but only one can successfully claim the
        pending-entry key in SQLite.
        """
        cur = self.conn.execute(
            'INSERT OR IGNORE INTO bot_state(key,value) VALUES(?,?)',
            (str(key), str(value)),
        )
        if not self._transaction_active:
            self.conn.commit()
        return cur.rowcount == 1

    def state_delete(self, key):
        self.conn.execute(
            'DELETE FROM bot_state WHERE key=?',
            (key,)
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_execution_intent(self, intent, status, reason=''):
        self.conn.execute(
            '''INSERT OR REPLACE INTO execution_intents(
                intent_id,symbol,side,order_type,purpose,
                required_context_versions_json,hypothesis_id,invalidation_level,status,reason,
                client_order_id,signal_id,signal_expires_at_ms,permission_interval,campaign_id,
                created_at_ms,max_age_ms,trigger_price,quantity,quote_order_quantity
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
            (
                intent.intent_id,
                intent.symbol,
                intent.side,
                intent.order_type,
                intent.purpose,
                json.dumps(dict(intent.required_context_versions), sort_keys=True),
                intent.hypothesis_id,
                float(intent.invalidation_level or 0.0),
                status,
                reason,
                getattr(intent, 'client_order_id', ''),
                getattr(intent, 'signal_id', ''),
                getattr(intent, 'signal_expires_at_ms', 0),
                getattr(intent, 'permission_interval', ''),
                getattr(intent, 'campaign_id', ''),
                getattr(intent, 'created_at_ms', 0),
                getattr(intent, 'max_age_ms', 0),
                float(getattr(intent, 'trigger_price', 0.0) or 0.0),
                str(getattr(intent, 'quantity', '') or ''),
                str(getattr(intent, 'quote_order_quantity', '') or ''),
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_execution_event(self, intent_id, event, payload=None):
        self.conn.execute(
            'INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)',
            (intent_id, event, json.dumps(payload, default=str) if payload is not None else None),
        )
        if not self._transaction_active:
            self.conn.commit()

    def log_event(self, level, event, message, raw=None):
        self.conn.execute(
            'INSERT INTO events(level,event,message,raw_json) VALUES(?,?,?,?)',
            (
                level,
                event,
                message,
                json.dumps(raw, default=str) if raw is not None else None
            )
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_market_context(self, context):
        """Persist the latest immutable MTF context for restart/audit recovery."""
        payload = context.to_dict() if hasattr(context, "to_dict") else context
        self.conn.execute(
            """INSERT INTO market_context(symbol,interval,version,candle_close_time_ms,context_json,updated_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(symbol,interval) DO UPDATE SET
                 version=excluded.version,
                 candle_close_time_ms=excluded.candle_close_time_ms,
                 context_json=excluded.context_json,
                 updated_at=excluded.updated_at""",
            (str(context.symbol).upper(), str(context.interval).lower(), int(context.version),
             int(context.candle_close_time_ms), json.dumps(payload, default=str, sort_keys=True),
             datetime.now(timezone.utc).isoformat()),
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_wave_state(self, symbol, side, payload):
        """Persist the selected wave hypothesis/state independently of trade rows."""
        self.conn.execute(
            """INSERT INTO wave_state(symbol,side,phase,state_json,updated_at)
               VALUES(?,?,?,?,?)
               ON CONFLICT(symbol,side) DO UPDATE SET
                 phase=excluded.phase,
                 state_json=excluded.state_json,
                 updated_at=excluded.updated_at""",
            (str(symbol).upper(), str(side).upper(), str(payload.get("phase", "UNKNOWN")),
             json.dumps(payload, default=str, sort_keys=True), datetime.now(timezone.utc).isoformat()),
        )
        if not self._transaction_active:
            self.conn.commit()

    def load_wave_state(self, symbol, side):
        row = self.conn.execute(
            "SELECT state_json FROM wave_state WHERE symbol=? AND side=?",
            (str(symbol).upper(), str(side).upper()),
        ).fetchone()
        if not row:
            return None
        try:
            return json.loads(row["state_json"])
        except Exception:
            return None

    def save_order(self, data):
        oid = (
            str(data.get('orderId'))
            if data.get('orderId') is not None
            else None
        )
        vals = (
            data.get('side'),
            data.get('type'),
            str(data.get('orderListId'))
            if data.get('orderListId') is not None
            else None,
            data.get('clientOrderId'),
            data.get('status'),
            self._num(data.get('price')),
            self._num(data.get('stopPrice')),
            self._num(data.get('origQty')),
            json.dumps(data, default=str)
        )
        if oid:
            r = self.conn.execute(
                'SELECT id FROM orders WHERE symbol=? AND order_id=?',
                (data.get('symbol'), oid)
            ).fetchone()
            if r:
                self.conn.execute(
                    'UPDATE orders SET side=?,type=?,order_list_id=?,'
                    'client_order_id=?,status=?,price=?,stop_price=?,'
                    'quantity=?,raw_json=? WHERE id=?',
                    vals + (r['id'],)
                )
                if not self._transaction_active:
                    self.conn.commit()
                return

        self.conn.execute(
            'INSERT INTO orders('
            'symbol,side,type,order_id,order_list_id,client_order_id,'
            'status,price,stop_price,quantity,raw_json'
            ') VALUES(?,?,?,?,?,?,?,?,?,?,?)',
            (
                data.get('symbol'),
                data.get('side'),
                data.get('type'),
                oid,
                str(data.get('orderListId'))
                if data.get('orderListId') is not None
                else None,
                data.get('clientOrderId'),
                data.get('status'),
                self._num(data.get('price')),
                self._num(data.get('stopPrice')),
                self._num(data.get('origQty')),
                json.dumps(data, default=str)
            )
        )
        if not self._transaction_active:
            self.conn.commit()

    @staticmethod
    def _num(v):
        if v in (None, ''):
            return None
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    def open_trade(self, symbol=None):
        if symbol:
            r = self.conn.execute(
                'SELECT * FROM trades '
                'WHERE symbol=? AND exit_time IS NULL '
                'ORDER BY id DESC LIMIT 1',
                (str(symbol).upper(),)
            ).fetchone()
        else:
            r = self.conn.execute(
                'SELECT * FROM trades '
                'WHERE exit_time IS NULL '
                'ORDER BY id DESC LIMIT 1'
            ).fetchone()
        return dict(r) if r else None

    def open_trades(self):
        rows = self.conn.execute(
            'SELECT * FROM trades '
            'WHERE exit_time IS NULL '
            'ORDER BY id ASC'
        ).fetchall()
        return [dict(r) for r in rows]

    def trade_by_entry_client_order_id(self, client_order_id):
        r = self.conn.execute(
            'SELECT * FROM trades '
            'WHERE entry_client_order_id=? '
            'ORDER BY id DESC LIMIT 1',
            (str(client_order_id),)
        ).fetchone()
        return dict(r) if r else None

    def save_trade(self, **kwargs):
        now = datetime.now(timezone.utc).isoformat()
        kwargs.setdefault('updated_at', now)
        cols = ','.join(kwargs)
        cur = self.conn.execute(
            f'INSERT INTO trades({cols}) VALUES({",".join("?" for _ in kwargs)})',
            tuple(kwargs.values())
        )
        if not self._transaction_active:
            self.conn.commit()
        return cur.lastrowid

    def update_trade_oco(
        self,
        trade_id,
        order_list_id,
        list_client_order_id=None,
        stop_price=None,
        take_profit_price=None,
        risk_pct=None,
    ):
        self.conn.execute(
            'UPDATE trades SET '
            'exit_order_list_id=?, '
            'exit_order_list_client_id=COALESCE(?,exit_order_list_client_id), '
            'stop_price=COALESCE(?,stop_price), '
            'take_profit_price=COALESCE(?,take_profit_price), '
            'risk_pct=COALESCE(?,risk_pct), '
            'updated_at=? '
            'WHERE id=? AND exit_time IS NULL',
            (
                str(order_list_id),
                list_client_order_id,
                self._num(stop_price),
                self._num(take_profit_price),
                self._num(risk_pct),
                datetime.now(timezone.utc).isoformat(),
                int(trade_id),
            )
        )
        if not self._transaction_active:
            self.conn.commit()

    def update_trade_quantity(self, trade_id, quantity):
        self.conn.execute(
            'UPDATE trades SET quantity=?,updated_at=? '
            'WHERE id=? AND exit_time IS NULL',
            (
                self._num(quantity),
                datetime.now(timezone.utc).isoformat(),
                int(trade_id),
            )
        )
        if not self._transaction_active:
            self.conn.commit()

    def close_trade(
        self,
        trade_id,
        exit_time,
        exit_price,
        pnl,
        pnl_pct,
        reason,
        exit_order_list_id=None,
        fees=0,
    ):
        self.conn.execute(
            'UPDATE trades SET '
            'exit_time=?,exit_price=?,pnl=?,pnl_pct=?,reason=?,'
            'exit_order_list_id=COALESCE(?,exit_order_list_id),'
            'fees=?,updated_at=? '
            'WHERE id=? AND exit_time IS NULL',
            (
                exit_time,
                exit_price,
                pnl,
                pnl_pct,
                reason,
                exit_order_list_id,
                fees,
                datetime.now(timezone.utc).isoformat(),
                int(trade_id),
            )
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_trade_journal(
        self,
        trade_id,
        entry_context=None,
        exit_context=None,
        mfe_pct=None,
        mae_pct=None,
        duration_seconds=None,
        diagnosis=None,
        diagnosis_detail=None,
    ):
        self.conn.execute(
            """
            INSERT INTO trade_journal(
                trade_id, entry_context_json, exit_context_json,
                mfe_pct, mae_pct, duration_seconds, diagnosis,
                diagnosis_detail_json
            ) VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(trade_id) DO UPDATE SET
                entry_context_json=COALESCE(
                    excluded.entry_context_json,
                    trade_journal.entry_context_json
                ),
                exit_context_json=COALESCE(
                    excluded.exit_context_json,
                    trade_journal.exit_context_json
                ),
                mfe_pct=COALESCE(
                    excluded.mfe_pct,
                    trade_journal.mfe_pct
                ),
                mae_pct=COALESCE(
                    excluded.mae_pct,
                    trade_journal.mae_pct
                ),
                duration_seconds=COALESCE(
                    excluded.duration_seconds,
                    trade_journal.duration_seconds
                ),
                diagnosis=COALESCE(
                    excluded.diagnosis,
                    trade_journal.diagnosis
                ),
                diagnosis_detail_json=COALESCE(
                    excluded.diagnosis_detail_json,
                    trade_journal.diagnosis_detail_json
                )
            """,
            (
                int(trade_id),
                json.dumps(entry_context, default=str)
                if entry_context is not None else None,
                json.dumps(exit_context, default=str)
                if exit_context is not None else None,
                self._num(mfe_pct),
                self._num(mae_pct),
                self._num(duration_seconds),
                diagnosis,
                json.dumps(diagnosis_detail, default=str)
                if diagnosis_detail is not None else None,
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    @staticmethod
    def _decode_json(value, default=None):
        if not value:
            return default
        try:
            return json.loads(value)
        except (TypeError, ValueError, json.JSONDecodeError):
            return default

    def get_trade_journal(self, trade_id):
        r = self.conn.execute(
            'SELECT * FROM trade_journal WHERE trade_id=?',
            (int(trade_id),)
        ).fetchone()
        if not r:
            return None
        item = dict(r)
        item['entry_context'] = self._decode_json(
            item.pop('entry_context_json'),
            {}
        )
        item['exit_context'] = self._decode_json(
            item.pop('exit_context_json'),
            {}
        )
        item['diagnosis_detail'] = self._decode_json(
            item.pop('diagnosis_detail_json'),
            {}
        )
        return item

    def recent_trade_journal(self, limit=100):
        rows = self.conn.execute(
            'SELECT t.*, j.diagnosis, j.mfe_pct, j.mae_pct, '
            'j.duration_seconds, j.entry_context_json, '
            'j.exit_context_json, j.diagnosis_detail_json '
            'FROM trades t LEFT JOIN trade_journal j '
            'ON j.trade_id=t.id '
            'ORDER BY t.id DESC LIMIT ?',
            (max(1, min(int(limit), 500)),)
        ).fetchall()
        result = []
        for r in rows:
            item = dict(r)
            item['entry_context'] = self._decode_json(
                item.pop('entry_context_json'), {}
            )
            item['exit_context'] = self._decode_json(
                item.pop('exit_context_json'), {}
            )
            item['diagnosis_detail'] = self._decode_json(
                item.pop('diagnosis_detail_json'), {}
            )
            result.append(item)
        return result

    def learning_summary(self):
        totals = self.conn.execute(
            "SELECT COUNT(*) total, "
            "SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END) wins, "
            "SUM(CASE WHEN pnl<0 THEN 1 ELSE 0 END) losses, "
            "COALESCE(SUM(pnl),0) pnl, "
            "COALESCE(AVG(CASE WHEN pnl>0 THEN pnl END),0) avg_win, "
            "COALESCE(AVG(CASE WHEN pnl<0 THEN pnl END),0) avg_loss "
            "FROM trades WHERE exit_time IS NOT NULL"
        ).fetchone()

        diagnoses = self.conn.execute(
            "SELECT COALESCE(diagnosis,'UNCLASSIFIED') diagnosis, "
            "COUNT(*) count, COALESCE(SUM(t.pnl),0) pnl, "
            "COALESCE(AVG(t.pnl),0) avg_pnl "
            "FROM trades t LEFT JOIN trade_journal j "
            "ON j.trade_id=t.id "
            "WHERE t.exit_time IS NOT NULL "
            "GROUP BY COALESCE(diagnosis,'UNCLASSIFIED') "
            "ORDER BY count DESC"
        ).fetchall()

        rows = self.recent_trade_journal(500)
        closed = [x for x in rows if x.get('exit_time')]

        def group(field):
            buckets = {}
            for item in closed:
                ctx = item.get('entry_context') or {}
                raw = (
                    ctx.get(field)
                    if ctx.get(field) is not None
                    else item.get(field)
                )
                if field == 'score_bucket':
                    score = float(ctx.get('score') or 0.0)
                    raw = (
                        f"{int(score // 10) * 10}-"
                        f"{int(score // 10) * 10 + 9}"
                    )
                key = str(raw if raw is not None else 'UNKNOWN')
                b = buckets.setdefault(
                    key,
                    {'count': 0, 'wins': 0, 'losses': 0, 'pnl': 0.0}
                )
                pnl = float(item.get('pnl') or 0.0)
                b['count'] += 1
                b['pnl'] += pnl
                if pnl > 0:
                    b['wins'] += 1
                elif pnl < 0:
                    b['losses'] += 1
            for b in buckets.values():
                b['win_rate'] = b['wins'] / max(1, b['count'])
            return sorted(
                buckets.items(),
                key=lambda kv: (kv[1]['pnl'], kv[1]['count']),
                reverse=True,
            )

        gross_profit = sum(
            max(0.0, float(x.get('pnl') or 0.0))
            for x in closed
        )
        gross_loss = sum(
            min(0.0, float(x.get('pnl') or 0.0))
            for x in closed
        )
        running = peak = max_dd = 0.0
        best_trade = worst_trade = None
        durations = []
        mfes = []
        maes = []

        for item in reversed(closed):
            pnl = float(item.get('pnl') or 0.0)
            running += pnl
            peak = max(peak, running)
            max_dd = max(max_dd, peak - running)
            best_trade = (
                pnl if best_trade is None else max(best_trade, pnl)
            )
            worst_trade = (
                pnl if worst_trade is None else min(worst_trade, pnl)
            )
            if item.get('duration_seconds') is not None:
                durations.append(float(item['duration_seconds']))
            if item.get('mfe_pct') is not None:
                mfes.append(float(item['mfe_pct']))
            if item.get('mae_pct') is not None:
                maes.append(float(item['mae_pct']))

        return {
            'total': int(totals['total'] or 0),
            'wins': int(totals['wins'] or 0),
            'losses': int(totals['losses'] or 0),
            'win_rate': (
                float(totals['wins'] or 0)
                / float(totals['total'] or 1)
            ),
            'pnl': float(totals['pnl'] or 0),
            'avg_win': float(totals['avg_win'] or 0),
            'avg_loss': float(totals['avg_loss'] or 0),
            'expectancy': (
                float(totals['pnl'] or 0)
                / max(1, int(totals['total'] or 0))
            ),
            'profit_factor': (
                gross_profit / abs(gross_loss)
                if gross_loss < 0 else None
            ),
            'max_drawdown_quote': round(max_dd, 8),
            'best_trade_quote': best_trade,
            'worst_trade_quote': worst_trade,
            'avg_duration_seconds': (
                sum(durations) / len(durations)
                if durations else None
            ),
            'avg_mfe_pct': (
                sum(mfes) / len(mfes)
                if mfes else None
            ),
            'avg_mae_pct': (
                sum(maes) / len(maes)
                if maes else None
            ),
            'diagnoses': [dict(x) for x in diagnoses],
            'by_wave': group('wave_position'),
            'by_symbol': group('symbol'),
            'by_signal_family': group('signal_family'),
            'by_score_bucket': group('score_bucket'),
        }

    # ------------------------------------------------------------------
    # Trading campaign persistence
    # ------------------------------------------------------------------

    def save_campaign(self, campaign):
        data = campaign.to_dict() if hasattr(campaign, "to_dict") else dict(campaign)
        self.conn.execute(
            """INSERT INTO campaigns(
                campaign_id,updated_at,symbol,side,execution_timeframe,state,
                origin_signal_id,current_signal_id,current_signal_type,
                position_qty,average_entry_price,initial_stop_price,current_stop_price,
                structural_stop_source,additions,tranche_index,realized_pnl_quote,
                unrealized_pnl_quote,open_risk_quote,pending_risk_quote,
                capital_reserved_quote,wave_context_json,health,next_action,
                reconciliation_state,exit_reason,tags_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(campaign_id) DO UPDATE SET
                updated_at=CURRENT_TIMESTAMP,symbol=excluded.symbol,side=excluded.side,
                execution_timeframe=excluded.execution_timeframe,state=excluded.state,
                origin_signal_id=excluded.origin_signal_id,current_signal_id=excluded.current_signal_id,
                current_signal_type=excluded.current_signal_type,position_qty=excluded.position_qty,
                average_entry_price=excluded.average_entry_price,initial_stop_price=excluded.initial_stop_price,
                current_stop_price=excluded.current_stop_price,structural_stop_source=excluded.structural_stop_source,
                additions=excluded.additions,tranche_index=excluded.tranche_index,
                realized_pnl_quote=excluded.realized_pnl_quote,unrealized_pnl_quote=excluded.unrealized_pnl_quote,
                open_risk_quote=excluded.open_risk_quote,pending_risk_quote=excluded.pending_risk_quote,
                capital_reserved_quote=excluded.capital_reserved_quote,wave_context_json=excluded.wave_context_json,
                health=excluded.health,next_action=excluded.next_action,
                reconciliation_state=excluded.reconciliation_state,exit_reason=excluded.exit_reason,
                tags_json=excluded.tags_json""",
            (
                data["campaign_id"], datetime.now(timezone.utc).isoformat(), data["symbol"], data["side"],
                data["execution_timeframe"], data["state"], data.get("origin_signal_id",""),
                data.get("current_signal_id",""), data.get("current_signal_type",""),
                float(data.get("position_qty",0) or 0), float(data.get("average_entry_price",0) or 0),
                float(data.get("initial_stop_price",0) or 0), float(data.get("current_stop_price",0) or 0),
                data.get("structural_stop_source",""), int(data.get("additions",0) or 0),
                int(data.get("tranche_index",0) or 0), float(data.get("realized_pnl_quote",0) or 0),
                float(data.get("unrealized_pnl_quote",0) or 0), float(data.get("open_risk_quote",0) or 0),
                float(data.get("pending_risk_quote",0) or 0), float(data.get("capital_reserved_quote",0) or 0),
                json.dumps(data.get("wave_context",{}),default=str,sort_keys=True),
                data.get("health","GREEN"),data.get("next_action","WAIT"),data.get("reconciliation_state","CLEAN"),
                data.get("exit_reason",""),json.dumps(data.get("tags",{}),default=str,sort_keys=True),
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    def get_campaign(self, campaign_id):
        row=self.conn.execute("SELECT * FROM campaigns WHERE campaign_id=?",(str(campaign_id),)).fetchone()
        return dict(row) if row else None

    def open_campaigns(self):
        rows=self.conn.execute(
            "SELECT * FROM campaigns WHERE state NOT IN ('CLOSED','FLAT') ORDER BY updated_at DESC"
        ).fetchall()
        return [dict(r) for r in rows]

    def save_campaign_signal(self, signal, campaign_id, state="DETECTED", supersedes_signal_id=""):
        data = signal.to_dict() if hasattr(signal, "to_dict") else dict(signal)
        st = data.get("signal_type")
        role = data.get("role")
        self.conn.execute(
            """INSERT OR REPLACE INTO campaign_signals(
                signal_id,campaign_id,symbol,side,signal_type,role,timeframe,
                signal_bar_time_ms,trigger_price,protective_reference,invalidation_price,
                teeth_at_detection,angulation_score,wave_confidence,wave_exhaustion_risk,
                htf_confirmed,state,source_candle_index,expires_at_ms,context_versions_json,reason,supersedes_signal_id
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                data["signal_id"], str(campaign_id), data["symbol"], data["side"],
                st.value if hasattr(st,"value") else st, role.value if hasattr(role,"value") else role,
                data["timeframe"], int(data["signal_bar_time_ms"]), float(data["trigger_price"]),
                float(data["protective_reference"]), float(data.get("invalidation_price",0) or 0),
                float(data.get("teeth_at_detection",0) or 0), float(data.get("angulation_score",0) or 0),
                float(data.get("wave_confidence",0) or 0), float(data.get("wave_exhaustion_risk",0) or 0),
                1 if data.get("htf_confirmed") else 0, state, int(data.get("source_candle_index",-1) or -1),
                int(data.get("expires_at_ms",0) or 0), json.dumps(data.get("context_versions",{}),sort_keys=True),
                data.get("reason",""), supersedes_signal_id or None,
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    def set_campaign_signal_state(self, signal_id, state):
        self.conn.execute("UPDATE campaign_signals SET state=? WHERE signal_id=?",(str(state),str(signal_id)))
        if not self._transaction_active:
            self.conn.commit()

    def active_campaign_signals(self, campaign_id):
        rows=self.conn.execute(
            "SELECT * FROM campaign_signals WHERE campaign_id=? AND state IN ('DETECTED','ARMED') ORDER BY signal_bar_time_ms",
            (str(campaign_id),),
        ).fetchall()
        return [dict(r) for r in rows]

    def save_campaign_order(self, record):
        data=record.to_dict() if hasattr(record,"to_dict") else dict(record)
        self.conn.execute(
            """INSERT INTO campaign_orders(
                campaign_id,signal_id,symbol,side,purpose,order_type,order_id,order_list_id,
                client_order_id,status,price,stop_price,quantity,risk_quote,capital_reserved_quote,raw_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                data.get("campaign_id",""),data.get("signal_id",""),data.get("symbol",""),data.get("side",""),
                data.get("purpose",""),data.get("order_type",""),data.get("order_id"),data.get("order_list_id"),
                data.get("client_order_id"),data.get("status",""),float(data.get("price",0) or 0),
                float(data.get("stop_price",0) or 0),float(data.get("quantity",0) or 0),
                float(data.get("risk_quote",0) or 0),float(data.get("capital_reserved_quote",0) or 0),
                json.dumps(data.get("raw_json",data),default=str),
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    def save_campaign_fill(self, fill):
        data=fill if isinstance(fill,dict) else dict(fill)
        self.conn.execute(
            """INSERT INTO campaign_fills(
                campaign_id,order_id,symbol,side,quantity,price,quote_quantity,
                fee_quote,commission_base,commission_asset,raw_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (
                data.get("campaign_id",""),data.get("order_id"),data.get("symbol",""),
                data.get("side",""),float(data.get("quantity",0) or 0),float(data.get("price",0) or 0),
                float(data.get("quote_quantity",0) or 0),float(data.get("fee_quote",0) or 0),
                float(data.get("commission_base",0) or 0),data.get("commission_asset",""),
                json.dumps(data,default=str),
            ),
        )
        if not self._transaction_active:
            self.conn.commit()

    def log_campaign_event(self, campaign_id, event, *, level="INFO", signal_id=None, order_id=None, reason="", payload=None):
        self.conn.execute(
            """INSERT INTO campaign_events(campaign_id,event,level,signal_id,order_id,reason,payload_json)
               VALUES(?,?,?,?,?,?,?)""",
            (str(campaign_id),str(event),str(level),signal_id,order_id,str(reason),
             json.dumps(payload,default=str,sort_keys=True) if payload is not None else None),
        )
        if not self._transaction_active:
            self.conn.commit()

    def campaign_risk_reserved_quote(self):
        row=self.conn.execute(
            "SELECT COALESCE(SUM(open_risk_quote),0)+COALESCE(SUM(pending_risk_quote),0) AS risk "
            "FROM campaigns WHERE state NOT IN ('CLOSED','FLAT','RECONCILE_REQUIRED')"
        ).fetchone()
        return float(row["risk"] or 0.0)

    def campaign_capital_reserved_quote(self):
        row=self.conn.execute(
            "SELECT COALESCE(SUM(capital_reserved_quote),0) AS capital "
            "FROM campaigns WHERE state IN ('SIGNAL_DETECTED','ENTRY_ARMING','ENTRY_PENDING','ADD_ON_ARMING','ADD_ON_PENDING')"
        ).fetchone()
        return float(row["capital"] or 0.0)

    def recent_orders(self, symbol, limit=100):
        return [
            dict(r)
            for r in self.conn.execute(
                'SELECT * FROM orders WHERE symbol=? '
                'ORDER BY id DESC LIMIT ?',
                (str(symbol).upper(), int(limit))
            ).fetchall()
        ]

    def recent_all_orders(self, limit=200):
        return [
            dict(r)
            for r in self.conn.execute(
                'SELECT * FROM orders ORDER BY id DESC LIMIT ?',
                (max(1, min(int(limit), 1000)),)
            ).fetchall()
        ]

    def trades_today(self, symbol):
        return int(
            self.conn.execute(
                "SELECT COUNT(*) FROM trades "
                "WHERE symbol=? AND entry_time >= date('now')",
                (str(symbol).upper(),)
            ).fetchone()[0]
        )

    def pnl_today(self, symbol):
        r = self.conn.execute(
            "SELECT COALESCE(SUM(pnl),0) AS pnl FROM trades "
            "WHERE symbol=? AND exit_time IS NOT NULL "
            "AND exit_time >= date('now')",
            (str(symbol).upper(),)
        ).fetchone()
        return float(r['pnl'] or 0)

    def pnl_today_all(self):
        """Return realized PnL across all symbols for the current UTC day."""
        r = self.conn.execute(
            "SELECT COALESCE(SUM(pnl),0) AS pnl FROM trades "
            "WHERE exit_time IS NOT NULL AND exit_time >= date('now')"
        ).fetchone()
        return float(r["pnl"] or 0)

    def consecutive_losses(self, symbol, limit=20):
        rows = self.conn.execute(
            'SELECT pnl FROM trades WHERE symbol=? '
            'AND exit_time IS NOT NULL ORDER BY id DESC LIMIT ?',
            (str(symbol).upper(), int(limit))
        ).fetchall()
        n = 0
        for r in rows:
            if float(r['pnl'] or 0) < 0:
                n += 1
            else:
                break
        return n

    def last_exit_time(self, symbol):
        r = self.conn.execute(
            'SELECT exit_time FROM trades '
            'WHERE symbol=? AND exit_time IS NOT NULL '
            'ORDER BY id DESC LIMIT 1',
            (str(symbol).upper(),)
        ).fetchone()
        return r['exit_time'] if r else None

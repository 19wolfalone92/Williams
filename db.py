import os
import json, sqlite3
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager


class Database:
    SCHEMA_VERSION = 5

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
            reason TEXT
        );
        CREATE TABLE IF NOT EXISTS execution_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            intent_id TEXT,
            event TEXT NOT NULL,
            payload_json TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_trade_journal_diagnosis
            ON trade_journal(diagnosis);
        CREATE INDEX IF NOT EXISTS idx_trades_open_symbol
            ON trades(symbol, exit_time);
        CREATE INDEX IF NOT EXISTS idx_orders_symbol_list
            ON orders(symbol, order_list_id);
        ''')
        self._migrate_trade_columns()
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
                required_context_versions_json,hypothesis_id,invalidation_level,status,reason
            ) VALUES(?,?,?,?,?,?,?,?,?,?)''',
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

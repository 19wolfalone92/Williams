import os
import json, sqlite3
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager

class Database:
    SCHEMA_VERSION = 3
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
        """
        Atomic transaction for multi-step DB operations.

        While active, state_set/state_delete/log_event do not commit
        individually. The whole operation is committed together or
        rolled back together on exception.
        """
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
        CREATE TABLE IF NOT EXISTS bot_state(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS candles(symbol TEXT NOT NULL,interval TEXT NOT NULL,open_time TEXT NOT NULL,open REAL,high REAL,low REAL,close REAL,volume REAL,PRIMARY KEY(symbol,interval,open_time));
        CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at TEXT DEFAULT CURRENT_TIMESTAMP,symbol TEXT,side TEXT,type TEXT,order_id TEXT,order_list_id TEXT,client_order_id TEXT,status TEXT,price REAL,stop_price REAL,quantity REAL,raw_json TEXT);
        CREATE TABLE IF NOT EXISTS trades(id INTEGER PRIMARY KEY AUTOINCREMENT,entry_time TEXT,exit_time TEXT,symbol TEXT,side TEXT,entry_price REAL,exit_price REAL,quantity REAL,pnl REAL,pnl_pct REAL,reason TEXT,entry_order_id TEXT,exit_order_list_id TEXT,fees REAL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at TEXT DEFAULT CURRENT_TIMESTAMP,level TEXT,event TEXT,message TEXT,raw_json TEXT);
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
        CREATE INDEX IF NOT EXISTS idx_trade_journal_diagnosis ON trade_journal(diagnosis);

        ''')
        self.state_set('schema_version', self.SCHEMA_VERSION)
        self.state_set('position_state', self.state_get('position_state', 'FLAT'))
        self.conn.commit()

    def state_get(self, key, default=None):
        r = self.conn.execute('SELECT value FROM bot_state WHERE key=?', (key,)).fetchone()
        return default if r is None else r['value']

    def state_set(self, key, value):
        self.conn.execute('INSERT INTO bot_state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value', (key, str(value)))
        if not self._transaction_active:
            self.conn.commit()

    def state_delete(self, key):
        self.conn.execute('DELETE FROM bot_state WHERE key=?', (key,))
        if not self._transaction_active:
            self.conn.commit()

    def log_event(self, level, event, message, raw=None):
        self.conn.execute('INSERT INTO events(level,event,message,raw_json) VALUES(?,?,?,?)', (level, event, message, json.dumps(raw, default=str) if raw is not None else None))
        if not self._transaction_active:
            self.conn.commit()

    def save_order(self, data):
        oid = str(data.get('orderId')) if data.get('orderId') is not None else None
        vals = (
            data.get('side'), data.get('type'), str(data.get('orderListId')) if data.get('orderListId') is not None else None,
            data.get('clientOrderId'), data.get('status'), self._num(data.get('price')), self._num(data.get('stopPrice')),
            self._num(data.get('origQty')), json.dumps(data, default=str)
        )
        if oid:
            r = self.conn.execute('SELECT id FROM orders WHERE symbol=? AND order_id=?', (data.get('symbol'), oid)).fetchone()
            if r:
                self.conn.execute('UPDATE orders SET side=?,type=?,order_list_id=?,client_order_id=?,status=?,price=?,stop_price=?,quantity=?,raw_json=? WHERE id=?', vals + (r['id'],))
                self.conn.commit(); return
        self.conn.execute('INSERT INTO orders(symbol,side,type,order_id,order_list_id,client_order_id,status,price,stop_price,quantity,raw_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)', (
            data.get('symbol'), data.get('side'), data.get('type'), oid, str(data.get('orderListId')) if data.get('orderListId') is not None else None,
            data.get('clientOrderId'), data.get('status'), self._num(data.get('price')), self._num(data.get('stopPrice')), self._num(data.get('origQty')), json.dumps(data, default=str)))
        self.conn.commit()

    @staticmethod
    def _num(v):
        if v in (None, ''): return None
        try: return float(v)
        except (TypeError, ValueError): return None

    def open_trade(self, symbol=None):
        if symbol:
            r = self.conn.execute(
                'SELECT * FROM trades WHERE symbol=? AND exit_time IS NULL ORDER BY id DESC LIMIT 1',
                (str(symbol).upper(),)
            ).fetchone()
        else:
            r = self.conn.execute(
                'SELECT * FROM trades WHERE exit_time IS NULL ORDER BY id DESC LIMIT 1'
            ).fetchone()
        return dict(r) if r else None

    def save_trade(self, **kwargs):
        cols = ','.join(kwargs)
        cur = self.conn.execute(
            f'INSERT INTO trades({cols}) VALUES({",".join("?" for _ in kwargs)})',
            tuple(kwargs.values())
        )
        self.conn.commit()
        return cur.lastrowid

    def update_trade_oco(self, trade_id, order_list_id):
        self.conn.execute('UPDATE trades SET exit_order_list_id=? WHERE id=? AND exit_time IS NULL', (str(order_list_id), trade_id))
        self.conn.commit()

    def close_trade(self, trade_id, exit_time, exit_price, pnl, pnl_pct, reason, exit_order_list_id=None, fees=0):
        self.conn.execute('UPDATE trades SET exit_time=?,exit_price=?,pnl=?,pnl_pct=?,reason=?,exit_order_list_id=COALESCE(?,exit_order_list_id),fees=? WHERE id=? AND exit_time IS NULL', (exit_time, exit_price, pnl, pnl_pct, reason, exit_order_list_id, fees, trade_id))
        self.conn.commit()

    def save_trade_journal(self, trade_id, entry_context=None, exit_context=None,
                           mfe_pct=None, mae_pct=None, duration_seconds=None,
                           diagnosis=None, diagnosis_detail=None):
        self.conn.execute(
            """
            INSERT INTO trade_journal(
                trade_id, entry_context_json, exit_context_json,
                mfe_pct, mae_pct, duration_seconds, diagnosis,
                diagnosis_detail_json
            ) VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(trade_id) DO UPDATE SET
                entry_context_json=COALESCE(excluded.entry_context_json, trade_journal.entry_context_json),
                exit_context_json=COALESCE(excluded.exit_context_json, trade_journal.exit_context_json),
                mfe_pct=COALESCE(excluded.mfe_pct, trade_journal.mfe_pct),
                mae_pct=COALESCE(excluded.mae_pct, trade_journal.mae_pct),
                duration_seconds=COALESCE(excluded.duration_seconds, trade_journal.duration_seconds),
                diagnosis=COALESCE(excluded.diagnosis, trade_journal.diagnosis),
                diagnosis_detail_json=COALESCE(excluded.diagnosis_detail_json, trade_journal.diagnosis_detail_json)
            """,
            (
                int(trade_id),
                json.dumps(entry_context, default=str) if entry_context is not None else None,
                json.dumps(exit_context, default=str) if exit_context is not None else None,
                self._num(mfe_pct), self._num(mae_pct), self._num(duration_seconds),
                diagnosis,
                json.dumps(diagnosis_detail, default=str) if diagnosis_detail is not None else None,
            ),
        )
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
        item['entry_context'] = self._decode_json(item.pop('entry_context_json'), {})
        item['exit_context'] = self._decode_json(item.pop('exit_context_json'), {})
        item['diagnosis_detail'] = self._decode_json(item.pop('diagnosis_detail_json'), {})
        return item

    def recent_trade_journal(self, limit=100):
        rows = self.conn.execute(
            'SELECT t.*, j.diagnosis, j.mfe_pct, j.mae_pct, j.duration_seconds, '
            'j.entry_context_json, j.exit_context_json, j.diagnosis_detail_json '
            'FROM trades t LEFT JOIN trade_journal j ON j.trade_id=t.id '
            'ORDER BY t.id DESC LIMIT ?',
            (max(1, min(int(limit), 500)),)
        ).fetchall()
        result = []
        for r in rows:
            item = dict(r)
            item['entry_context'] = self._decode_json(item.pop('entry_context_json'), {})
            item['exit_context'] = self._decode_json(item.pop('exit_context_json'), {})
            item['diagnosis_detail'] = self._decode_json(item.pop('diagnosis_detail_json'), {})
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
            "SELECT COALESCE(diagnosis,'UNCLASSIFIED') diagnosis, COUNT(*) count, "
            "COALESCE(SUM(t.pnl),0) pnl, COALESCE(AVG(t.pnl),0) avg_pnl "
            "FROM trades t LEFT JOIN trade_journal j ON j.trade_id=t.id "
            "WHERE t.exit_time IS NOT NULL GROUP BY COALESCE(diagnosis,'UNCLASSIFIED') "
            "ORDER BY count DESC"
        ).fetchall()
        return {
            'total': int(totals['total'] or 0),
            'wins': int(totals['wins'] or 0),
            'losses': int(totals['losses'] or 0),
            'win_rate': (float(totals['wins'] or 0) / float(totals['total'] or 1)),
            'pnl': float(totals['pnl'] or 0),
            'avg_win': float(totals['avg_win'] or 0),
            'avg_loss': float(totals['avg_loss'] or 0),
            'diagnoses': [dict(x) for x in diagnoses],
        }

    def recent_orders(self, symbol, limit=100):
        return [dict(r) for r in self.conn.execute('SELECT * FROM orders WHERE symbol=? ORDER BY id DESC LIMIT ?', (symbol, limit)).fetchall()]

    def trades_today(self, symbol):
        return int(self.conn.execute("SELECT COUNT(*) FROM trades WHERE symbol=? AND entry_time >= date('now')", (symbol,)).fetchone()[0])

    def pnl_today(self, symbol):
        r = self.conn.execute("SELECT COALESCE(SUM(pnl),0) AS pnl FROM trades WHERE symbol=? AND exit_time IS NOT NULL AND exit_time >= date('now')", (symbol,)).fetchone()
        return float(r['pnl'] or 0)

    def consecutive_losses(self, symbol, limit=20):
        rows = self.conn.execute('SELECT pnl FROM trades WHERE symbol=? AND exit_time IS NOT NULL ORDER BY id DESC LIMIT ?', (symbol, limit)).fetchall()
        n = 0
        for r in rows:
            if float(r['pnl'] or 0) < 0: n += 1
            else: break
        return n

    def last_exit_time(self, symbol):
        r = self.conn.execute('SELECT exit_time FROM trades WHERE symbol=? AND exit_time IS NOT NULL ORDER BY id DESC LIMIT 1', (symbol,)).fetchone()
        return r['exit_time'] if r else None

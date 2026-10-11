import os
import json, sqlite3
import threading
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager


class Database:
    SCHEMA_VERSION = 10

    def __init__(self, path=None):
        path = path or os.getenv('WILLIAMS_DB_PATH') or 'data/trader.sqlite3'
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db_lock = threading.RLock()
        self.conn = sqlite3.connect(path, check_same_thread=False, timeout=20)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute('PRAGMA busy_timeout=20000')
        self.conn.execute('PRAGMA journal_mode=WAL')
        self.conn.execute('PRAGMA synchronous=FULL')
        self._transaction_active = False
        self.init()

    @contextmanager
    def transaction(self):
        """Serialize a critical SQLite transaction and acquire the write lock up front."""
        with self._db_lock:
            if self._transaction_active:
                # Nested calls participate in the outer transaction.
                yield self
                return
            self._transaction_active = True
            try:
                self.conn.execute('BEGIN IMMEDIATE')
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
            client_order_id TEXT,
            exchange TEXT NOT NULL DEFAULT 'BINANCE',
            account_scope TEXT NOT NULL DEFAULT 'spot-default',
            execution_scope TEXT NOT NULL DEFAULT 'spot',
            campaign_id TEXT,
            order_id TEXT,
            target_order_id TEXT,
            target_client_order_id TEXT
        );
        CREATE TABLE IF NOT EXISTS execution_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            intent_id TEXT,
            event TEXT NOT NULL,
            payload_json TEXT
        );
        CREATE UNIQUE INDEX IF NOT EXISTS uq_execution_intents_client_order_id
            ON execution_intents(client_order_id)
            WHERE client_order_id IS NOT NULL AND client_order_id <> '';
        CREATE TABLE IF NOT EXISTS execution_account_bindings(
            exchange TEXT NOT NULL,
            execution_scope TEXT NOT NULL,
            account_scope TEXT NOT NULL,
            credential_fingerprint TEXT NOT NULL,
            account_uid TEXT,
            account_type TEXT,
            testnet INTEGER NOT NULL,
            verified_at TEXT NOT NULL,
            PRIMARY KEY(exchange, execution_scope, account_scope)
        );
        CREATE TABLE IF NOT EXISTS execution_fills(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            exchange TEXT NOT NULL,
            account_scope TEXT NOT NULL,
            symbol TEXT NOT NULL,
            trade_id TEXT NOT NULL,
            order_id TEXT,
            intent_id TEXT,
            quantity TEXT NOT NULL,
            price TEXT NOT NULL,
            commission TEXT,
            commission_asset TEXT,
            raw_json TEXT NOT NULL,
            applied_at TEXT DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(exchange, account_scope, symbol, trade_id)
        );
        CREATE INDEX IF NOT EXISTS idx_execution_fills_order
            ON execution_fills(exchange, account_scope, symbol, order_id);
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
        self._migrate_execution_columns()
        self.state_set('schema_version', self.SCHEMA_VERSION)
        self.state_set('position_state', self.state_get('position_state', 'FLAT'))
        self.conn.commit()

    def _migrate_execution_columns(self):
        """Migrate legacy rows without inventing an exchange/account contour."""
        columns = {
            row["name"]
            for row in self.conn.execute("PRAGMA table_info(execution_intents)").fetchall()
        }
        legacy_without_scope = "execution_scope" not in columns
        additions = {
            "exchange": "TEXT NOT NULL DEFAULT 'BINANCE'",
            "account_scope": "TEXT NOT NULL DEFAULT 'spot-default'",
            "execution_scope": "TEXT NOT NULL DEFAULT 'spot'",
            "campaign_id": "TEXT",
            "order_id": "TEXT",
            "target_order_id": "TEXT",
            "target_client_order_id": "TEXT",
        }
        for name, sql_type in additions.items():
            if name not in columns:
                self.conn.execute(f"ALTER TABLE execution_intents ADD COLUMN {name} {sql_type}")
        if legacy_without_scope:
            # Old rows did not persist the account/execution contour. Do not assign
            # these potentially ambiguous records to Spot or Futures automatically.
            self.conn.execute(
                "UPDATE execution_intents SET execution_scope='legacy-unscoped',"
                "account_scope='legacy-unscoped'"
            )
        self.conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_execution_intents_scope_status "
            "ON execution_intents(execution_scope,account_scope,status)"
        )
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
        """Update status only; intent identity is inserted once by claim_execution_intent."""
        with self.transaction():
            cur = self.conn.execute(
                '''UPDATE execution_intents
                   SET status=?, reason=?
                   WHERE intent_id=? AND symbol=? AND side=? AND order_type=?
                     AND purpose=? AND client_order_id IS ?''',
                (
                    str(status), str(reason or ""), str(intent.intent_id),
                    str(intent.symbol), str(intent.side), str(intent.order_type),
                    str(intent.purpose),
                    (str(getattr(intent, "client_order_id", "")) or None),
                ),
            )
            if cur.rowcount != 1:
                raise RuntimeError(
                    f"execution intent identity missing or changed: {intent.intent_id}"
                )

    def save_execution_event(self, intent_id, event, payload=None):
        self.conn.execute(
            'INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)',
            (intent_id, event, json.dumps(payload, default=str) if payload is not None else None),
        )
        if not self._transaction_active:
            self.conn.commit()

    @staticmethod
    def _default_account_scope(scope="spot", account_scope=None):
        if account_scope is not None and str(account_scope).strip():
            return str(account_scope).strip()
        env_scope = os.getenv("WILLIAMS_ACCOUNT_SCOPE", "").strip()
        if env_scope:
            return env_scope
        return str(scope or "spot").strip().lower() + "-default"

    @staticmethod
    def _execution_lock_key(scope, account_scope=None):
        contour = str(scope or "spot").strip().lower()
        if account_scope is None:
            # Legacy key is read during migration/recovery only.
            return "execution_lock:" + contour
        return "execution_lock:" + contour + ":" + str(account_scope).strip()

    def _intent_account_scope(self, intent_id, scope="spot"):
        row = self.conn.execute(
            "SELECT execution_scope,account_scope FROM execution_intents WHERE intent_id=?",
            (str(intent_id),),
        ).fetchone()
        if row:
            return str(row["execution_scope"] or scope), str(
                row["account_scope"] or self._default_account_scope(scope)
            )
        return str(scope or "spot"), self._default_account_scope(scope)

    def verify_or_pin_execution_account(self, *, exchange, execution_scope, account_scope,
                                         credential_fingerprint, account_uid=None,
                                         account_type=None, testnet=False):
        """Pin an authenticated credential identity; any later change fails closed.

        The fingerprint is SHA-256 of the API key, never the secret. A key rotation
        requires an explicit offline rebind while trading is stopped. Binance Spot
        account responses do not always expose a stable UID, so UID is an additional
        cross-check when supplied, not an assumed field.
        """
        import hmac
        from datetime import datetime, timezone

        exchange = str(exchange).upper()
        execution_scope = str(execution_scope).strip().lower()
        account_scope = str(account_scope).strip()
        fingerprint = str(credential_fingerprint).lower()
        if not exchange or not execution_scope or not account_scope:
            raise ValueError("exchange/execution_scope/account_scope are required")
        if len(fingerprint) != 64 or any(ch not in "0123456789abcdef" for ch in fingerprint):
            raise ValueError("credential fingerprint must be a SHA-256 hex digest")
        uid = None if account_uid in (None, "") else str(account_uid)
        atype = None if account_type in (None, "") else str(account_type)
        with self.transaction():
            row = self.conn.execute(
                """SELECT credential_fingerprint,account_uid,account_type,testnet
                   FROM execution_account_bindings
                   WHERE exchange=? AND execution_scope=? AND account_scope=?""",
                (exchange, execution_scope, account_scope),
            ).fetchone()
            if row is None:
                self.conn.execute(
                    """INSERT INTO execution_account_bindings(
                        exchange,execution_scope,account_scope,credential_fingerprint,
                        account_uid,account_type,testnet,verified_at
                    ) VALUES(?,?,?,?,?,?,?,?)""",
                    (exchange, execution_scope, account_scope, fingerprint, uid, atype,
                     int(bool(testnet)), datetime.now(timezone.utc).isoformat()),
                )
                self.conn.execute(
                    "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(NULL,?,?)",
                    ("ACCOUNT_IDENTITY_PINNED", json.dumps({
                        "exchange": exchange, "execution_scope": execution_scope,
                        "account_scope": account_scope, "account_uid": uid,
                        "account_type": atype, "testnet": bool(testnet),
                        "fingerprint_prefix": fingerprint[:12],
                    }, sort_keys=True)),
                )
                return {"verified": True, "pinned": True, "account_uid": uid,
                        "account_type": atype, "testnet": bool(testnet)}
            if not hmac.compare_digest(str(row["credential_fingerprint"]), fingerprint):
                raise PermissionError("Binance credential identity changed; explicit stopped-state rebind required")
            if bool(row["testnet"]) != bool(testnet):
                raise PermissionError("Binance Testnet/live execution domain changed; explicit rebind required")
            stored_uid = None if row["account_uid"] in (None, "") else str(row["account_uid"])
            if stored_uid and uid and stored_uid != uid:
                raise PermissionError("Binance account UID changed for the pinned execution scope")
            stored_type = None if row["account_type"] in (None, "") else str(row["account_type"])
            if stored_type and atype and stored_type != atype:
                raise PermissionError("Binance account type changed for the pinned execution scope")
            self.conn.execute(
                """UPDATE execution_account_bindings SET
                     account_uid=COALESCE(account_uid,?),
                     account_type=COALESCE(account_type,?), verified_at=?
                   WHERE exchange=? AND execution_scope=? AND account_scope=?""",
                (uid, atype, datetime.now(timezone.utc).isoformat(),
                 exchange, execution_scope, account_scope),
            )
            return {"verified": True, "pinned": False, "account_uid": stored_uid or uid,
                    "account_type": stored_type or atype, "testnet": bool(testnet)}

    def claim_execution_intent(self, intent, scope=None, account_scope=None, purpose=None):
        """Atomically claim one immutable intent inside its exchange/account contour."""
        scope = str(scope or getattr(intent, "execution_scope", "spot") or "spot").strip().lower()
        account_scope = self._default_account_scope(scope, account_scope or getattr(intent, "account_scope", None))
        client_order_id = str(getattr(intent, "client_order_id", "") or "") or None
        exchange = str(getattr(intent, "exchange", "BINANCE") or "BINANCE").upper()
        campaign_id = str(getattr(intent, "campaign_id", "") or "") or None
        purpose = str(purpose or getattr(intent, "purpose", "") or "").upper()
        with self.transaction():
            # Identity collisions are classified before readiness locks, but never
            # authorize a network call. This preserves stable idempotency semantics.
            prior = self.conn.execute(
                """SELECT intent_id,status,client_order_id,execution_scope,account_scope
                   FROM execution_intents
                   WHERE intent_id=? OR (? IS NOT NULL AND client_order_id=?)
                   LIMIT 1""",
                (str(intent.intent_id), client_order_id, client_order_id),
            ).fetchone()
            if prior:
                return {
                    "claimed": False, "reason": "IDENTITY_CONFLICT",
                    "existing": dict(prior),
                }
            legacy_unresolved = self.conn.execute(
                """SELECT intent_id,status FROM execution_intents
                   WHERE execution_scope='legacy-unscoped'
                     AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                   LIMIT 1"""
            ).fetchone()
            if legacy_unresolved:
                return {"claimed": False, "reason": "LEGACY_SCOPE_REQUIRES_MANUAL_RECONCILIATION", "existing": dict(legacy_unresolved)}
            scoped_lock_key = self._execution_lock_key(scope, account_scope)
            for key in (scoped_lock_key, self._execution_lock_key(scope)):
                lock = self.conn.execute(
                    "SELECT value FROM bot_state WHERE key=?", (key,)
                ).fetchone()
                if lock:
                    try:
                        lock_payload = json.loads(lock["value"])
                    except Exception:
                        lock_payload = {}
                    allowed_scoped_protection = (
                        key == scoped_lock_key
                        and str(lock_payload.get("state", "")).upper() == "PROTECTION_PENDING"
                        and purpose in {"CAMPAIGN_PROTECTION", "CAMPAIGN_ENTRY_CANCEL"}
                    )
                    if not allowed_scoped_protection:
                        return {"claimed": False, "reason": "EXECUTION_LOCKED", "existing": lock_payload or lock["value"]}
            unresolved_rows = self.conn.execute(
                """SELECT intent_id,status,purpose FROM execution_intents
                   WHERE execution_scope=? AND account_scope=?
                     AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                   ORDER BY created_at,intent_id""",
                (scope, account_scope),
            ).fetchall()
            if unresolved_rows:
                repair_purposes = {"CAMPAIGN_PROTECTION", "CAMPAIGN_ENTRY_CANCEL"}
                repair_inflight_statuses = {"PENDING", "SUBMITTING", "SUBMITTED"}
                protection_repair_only = (
                    purpose in repair_purposes
                    and all(
                        str(row["status"]).upper() == "PROTECTION_PENDING"
                        or (
                            str(row["purpose"]).upper() in repair_purposes
                            and str(row["status"]).upper() in repair_inflight_statuses
                        )
                        for row in unresolved_rows
                    )
                )
                if not protection_repair_only:
                    return {"claimed": False, "reason": "UNRESOLVED_INTENT", "existing": dict(unresolved_rows[0])}
            try:
                self.conn.execute(
                    """INSERT INTO execution_intents(
                        intent_id,symbol,side,order_type,purpose,
                        required_context_versions_json,hypothesis_id,invalidation_level,
                        status,reason,client_order_id,exchange,account_scope,
                        execution_scope,campaign_id,order_id,target_order_id,target_client_order_id
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        str(intent.intent_id), str(intent.symbol).upper(), str(intent.side).upper(),
                        str(intent.order_type).upper(), str(intent.purpose),
                        json.dumps(dict(intent.required_context_versions), sort_keys=True),
                        str(getattr(intent, "hypothesis_id", "") or ""),
                        float(getattr(intent, "invalidation_level", 0.0) or 0.0),
                        "PENDING", "", client_order_id, exchange, account_scope,
                        scope, campaign_id, None,
                        str(getattr(intent, "target_order_id", "") or "") or None,
                        str(getattr(intent, "target_client_order_id", "") or "") or None,
                    ),
                )
            except sqlite3.IntegrityError:
                by_id = self.conn.execute(
                    "SELECT intent_id,status,client_order_id FROM execution_intents WHERE intent_id=?",
                    (str(intent.intent_id),),
                ).fetchone()
                if by_id is None and client_order_id:
                    by_id = self.conn.execute(
                        "SELECT intent_id,status,client_order_id FROM execution_intents WHERE client_order_id=?",
                        (client_order_id,),
                    ).fetchone()
                return {
                    "claimed": False,
                    "reason": "IDENTITY_CONFLICT",
                    "existing": dict(by_id) if by_id else None,
                }
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (str(intent.intent_id), "INTENT_CLAIMED", json.dumps({
                    "scope": scope, "account_scope": account_scope, "exchange": exchange,
                    "campaign_id": campaign_id, "client_order_id": client_order_id,
                }, sort_keys=True)),
            )
            return {"claimed": True, "reason": "", "existing": None}

    def execution_readiness(self, scope="spot", account_scope=None, purpose=None):
        """Fail closed; only a scoped protection repair may pass PROTECTION_PENDING."""
        try:
            scope = str(scope or "spot").strip().lower()
            account_scope = self._default_account_scope(scope, account_scope)
            purpose = str(purpose or "").upper()
            scope_lock_key = self._execution_lock_key(scope, account_scope)
            for key in (scope_lock_key, self._execution_lock_key(scope)):
                lock = self.conn.execute(
                    "SELECT value FROM bot_state WHERE key=?", (key,)
                ).fetchone()
                if lock:
                    try:
                        lock_payload = json.loads(lock["value"])
                    except Exception:
                        lock_payload = {}
                    is_scoped_protection_lock = (
                        key == scope_lock_key
                        and str(lock_payload.get("state", "")).upper() == "PROTECTION_PENDING"
                    )
                    if not (is_scoped_protection_lock and purpose in {"CAMPAIGN_PROTECTION", "CAMPAIGN_ENTRY_CANCEL"}):
                        return {"ready": False, "reason": "EXECUTION_LOCKED", "detail": lock["value"]}
            legacy_unresolved = self.conn.execute(
                """SELECT intent_id,status FROM execution_intents
                   WHERE execution_scope='legacy-unscoped'
                     AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                   LIMIT 1"""
            ).fetchone()
            if legacy_unresolved:
                return {"ready": False, "reason": "LEGACY_SCOPE_REQUIRES_MANUAL_RECONCILIATION", "detail": dict(legacy_unresolved)}
            unresolved_rows = self.conn.execute(
                """SELECT intent_id,status,purpose,symbol,client_order_id,order_id
                   FROM execution_intents
                   WHERE execution_scope=? AND account_scope=?
                     AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                   ORDER BY created_at,intent_id""",
                (scope, account_scope),
            ).fetchall()
            if unresolved_rows:
                repair_purposes = {"CAMPAIGN_PROTECTION", "CAMPAIGN_ENTRY_CANCEL"}
                repair_inflight_statuses = {"PENDING", "SUBMITTING", "SUBMITTED"}
                protection_repair_only = (
                    purpose in repair_purposes
                    and all(
                        str(row["status"]).upper() == "PROTECTION_PENDING"
                        or (
                            str(row["purpose"]).upper() in repair_purposes
                            and str(row["status"]).upper() in repair_inflight_statuses
                        )
                        for row in unresolved_rows
                    )
                )
                if not protection_repair_only:
                    return {"ready": False, "reason": "UNRESOLVED_INTENT", "detail": dict(unresolved_rows[0])}
                return {"ready": True, "reason": "PROTECTION_REPAIR_ONLY", "detail": dict(unresolved_rows[0])}
            return {"ready": True, "reason": "", "detail": None}
        except Exception as exc:
            return {"ready": False, "reason": "READINESS_CHECK_FAILED", "detail": repr(exc)}

    def list_monitor_execution_intents(self, scope="spot", account_scope=None, days=30):
        """Return recently resolved campaign entry intents that can still reveal late fills."""
        scope = str(scope or "spot").strip().lower()
        account_scope = self._default_account_scope(scope, account_scope)
        rows = self.conn.execute(
            """SELECT * FROM execution_intents
               WHERE execution_scope=? AND account_scope=?
                 AND created_at >= datetime('now', ?)
                 AND status IN ('OPEN','PARTIALLY_FILLED','CANCELED','EXPIRED')
                 AND (
                     purpose LIKE '%ENTRY%'
                     OR purpose LIKE '%ADD_ON%'
                     OR purpose='ADD_ON'
                 )
               ORDER BY created_at,intent_id""",
            (scope, account_scope, f"-{max(1, int(days))} days"),
        ).fetchall()
        return [dict(row) for row in rows]

    def mark_intent_reconcile_required(self, intent_id, reason):
        """Re-lock an account contour when a previously resolved order becomes ambiguous."""
        intent_id = str(intent_id)
        with self.transaction():
            row = self.conn.execute(
                "SELECT execution_scope,account_scope,symbol,client_order_id,order_id FROM execution_intents WHERE intent_id=?",
                (intent_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError(f"execution intent not found: {intent_id}")
            scope = str(row["execution_scope"] or "spot").lower()
            account_scope = str(row["account_scope"] or self._default_account_scope(scope))
            key = self._execution_lock_key(scope, account_scope)
            payload = json.dumps({
                "scope": scope, "account_scope": account_scope,
                "intent_id": intent_id, "reason": str(reason),
                "state": "RECONCILE_REQUIRED",
            }, sort_keys=True)
            self.conn.execute(
                "UPDATE execution_intents SET status='RECONCILE_REQUIRED',reason=? WHERE intent_id=?",
                (str(reason), intent_id),
            )
            self.conn.execute(
                """INSERT INTO bot_state(key,value) VALUES(?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value""",
                (key, payload),
            )
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (intent_id, "RECONCILE_REQUIRED", payload),
            )
            return {"scope": scope, "account_scope": account_scope, "symbol": row["symbol"]}

    def record_reconciliation_refresh(self, intent_id, evidence, *, changed=False):
        """Persist a verified refresh for an already resolved order without unlocking other intents."""
        with self.transaction():
            row = self.conn.execute(
                "SELECT status FROM execution_intents WHERE intent_id=?",
                (str(intent_id),),
            ).fetchone()
            if row is None:
                raise RuntimeError(f"execution intent not found: {intent_id}")
            payload = {
                "evidence_id": evidence.evidence_id,
                "exchange": evidence.exchange,
                "account_scope": evidence.account_scope,
                "execution_scope": evidence.execution_scope,
                "symbol": evidence.symbol,
                "client_order_id": evidence.client_order_id,
                "order_id": evidence.order_id,
                "status": evidence.status,
                "executed_qty": evidence.executed_qty,
                "history_qty": evidence.history_qty,
                "trade_ids": list(evidence.trade_ids),
                "observed_at": evidence.observed_at,
                "changed": bool(changed),
            }
            if changed or str(row["status"]).upper() != evidence.status:
                self.conn.execute(
                    "UPDATE execution_intents SET status=?,order_id=COALESCE(order_id,?),reason=? WHERE intent_id=?",
                    (evidence.status, evidence.order_id, "refreshed:" + evidence.evidence_id, str(intent_id)),
                )
                self.conn.execute(
                    "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                    (str(intent_id), "RECONCILIATION_REFRESHED", json.dumps(payload, sort_keys=True)),
                )
            return True

    def list_unresolved_execution_intents(self, scope=None, account_scope=None):
        query = """SELECT * FROM execution_intents
                   WHERE status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')"""
        args = []
        if scope is not None:
            query += " AND execution_scope=?"
            args.append(str(scope).strip().lower())
        if account_scope is not None:
            query += " AND account_scope=?"
            args.append(str(account_scope))
        query += " ORDER BY created_at,intent_id"
        rows = self.conn.execute(query, tuple(args)).fetchall()
        return [dict(row) for row in rows]

    def mark_execution_protection_pending(self, intent_id, reason="fill applied; protective order verification required"):
        """Atomically block entries while a known position's protective order is repaired."""
        intent_id = str(intent_id)
        with self.transaction():
            row = self.conn.execute(
                "SELECT execution_scope,account_scope,status FROM execution_intents WHERE intent_id=?",
                (intent_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError(f"execution intent not found: {intent_id}")
            scope = str(row["execution_scope"] or "spot").lower()
            account_scope = str(row["account_scope"] or self._default_account_scope(scope))
            key = self._execution_lock_key(scope, account_scope)
            payload = json.dumps({
                "scope": scope, "account_scope": account_scope,
                "intent_id": intent_id, "reason": str(reason),
                "state": "PROTECTION_PENDING",
            }, sort_keys=True)
            self.conn.execute(
                "UPDATE execution_intents SET status='PROTECTION_PENDING',reason=? WHERE intent_id=?",
                (str(reason), intent_id),
            )
            self.conn.execute(
                """INSERT INTO bot_state(key,value) VALUES(?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value""",
                (key, payload),
            )
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (intent_id, "PROTECTION_PENDING", payload),
            )

    def mark_execution_unknown(self, intent, reason, scope=None, account_scope=None):
        """Persist UNKNOWN and a per-account lock atomically; SUBMITTING survives lock-write failure."""
        intent_id = str(intent.intent_id)
        stored_scope, stored_account = self._intent_account_scope(
            intent_id, scope or getattr(intent, "execution_scope", "spot")
        )
        scope = str(scope or stored_scope).strip().lower()
        account_scope = self._default_account_scope(
            scope, account_scope or stored_account or getattr(intent, "account_scope", None)
        )
        key = self._execution_lock_key(scope, account_scope)
        payload = json.dumps({
            "scope": scope, "account_scope": account_scope, "intent_id": intent_id,
            "reason": str(reason), "state": "UNKNOWN",
        }, sort_keys=True)
        with self.transaction():
            cur = self.conn.execute(
                """UPDATE execution_intents SET status='UNKNOWN',reason=?
                   WHERE intent_id=? AND execution_scope=? AND account_scope=?""",
                (str(reason), intent_id, scope, account_scope),
            )
            if cur.rowcount != 1:
                raise RuntimeError(f"cannot lock unknown intent: {intent_id}")
            self.conn.execute(
                """INSERT INTO bot_state(key,value) VALUES(?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value""",
                (key, payload),
            )
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (intent_id, "UNKNOWN_LOCKED", payload),
            )

    def save_execution_result(self, intent, response):
        """Atomically persist the exchange response and the matching order identity."""
        payload = json.dumps(response, default=str, sort_keys=True)
        order_id = None
        if isinstance(response, dict) and response.get("orderId") is not None:
            order_id = str(response.get("orderId"))
        with self.transaction():
            cur = self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (str(intent.intent_id), "BINANCE_RESULT", payload),
            )
            updated = self.conn.execute(
                """UPDATE execution_intents SET status='SUBMITTED',reason='',
                   order_id=COALESCE(?,order_id)
                   WHERE intent_id=? AND status='SUBMITTING'""",
                (order_id, str(intent.intent_id)),
            )
            if updated.rowcount != 1:
                raise RuntimeError(f"intent not SUBMITTING while recording exchange result: {intent.intent_id}")
            return int(cur.lastrowid)

    def resolve_execution_intent(self, intent_id, evidence, scope=None):
        """Resolve only with a sealed, coordinator-issued verified reconciliation record."""
        from execution_recovery_contract import is_verified_reconciliation_evidence

        if not is_verified_reconciliation_evidence(evidence):
            raise PermissionError("UNKNOWN can only be resolved with verified coordinator evidence")
        status = str(evidence.status).upper()
        allowed = {"OPEN", "PARTIALLY_FILLED", "FILLED", "CANCELED", "EXPIRED", "REJECTED"}
        if status not in allowed:
            raise ValueError("unsupported reconciled order status")
        intent_id = str(intent_id)
        with self.transaction():
            row = self.conn.execute(
                "SELECT * FROM execution_intents WHERE intent_id=?",
                (intent_id,),
            ).fetchone()
            if row is None:
                raise RuntimeError(f"execution intent not found: {intent_id}")
            row = dict(row)
            expected_scope = str(scope or row["execution_scope"] or "spot").strip().lower()
            expected_account = str(row["account_scope"] or self._default_account_scope(expected_scope))
            if (
                str(evidence.exchange).upper() != str(row["exchange"]).upper()
                or str(evidence.account_scope) != expected_account
                or str(evidence.execution_scope).lower() != expected_scope
                or str(evidence.symbol).upper() != str(row["symbol"]).upper()
                or str(evidence.client_order_id) != str(row["client_order_id"] or "")
                or str(evidence.order_id) != str(
                    row["target_order_id"] if row["order_type"] == "CANCEL"
                    else (row["order_id"] or evidence.order_id)
                )
                or not bool(evidence.order_verified)
                or not bool(evidence.history_complete)
                or not bool(evidence.fill_totals_match)
                or not str(evidence.evidence_id).strip()
            ):
                raise PermissionError("reconciliation evidence does not match the durable intent/account/order")
            cur = self.conn.execute(
                """UPDATE execution_intents SET status=?,reason=?,order_id=COALESCE(order_id,?)
                   WHERE intent_id=? AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')""",
                (status, "reconciled:" + str(evidence.evidence_id), str(evidence.order_id), intent_id),
            )
            if cur.rowcount != 1:
                # Idempotent restart is allowed only when the same evidence is already recorded.
                prior = self.conn.execute(
                    """SELECT 1 FROM execution_events
                       WHERE intent_id=? AND event='RECONCILED'
                         AND json_extract(payload_json,'$.evidence_id')=? LIMIT 1""",
                    (intent_id, str(evidence.evidence_id)),
                ).fetchone()
                if prior:
                    return True
                raise RuntimeError(f"intent is not unresolved: {intent_id}")
            proof = {
                "evidence_id": str(evidence.evidence_id),
                "exchange": str(evidence.exchange),
                "account_scope": expected_account,
                "execution_scope": expected_scope,
                "symbol": str(evidence.symbol).upper(),
                "client_order_id": str(evidence.client_order_id),
                "order_id": str(evidence.order_id),
                "status": status,
                "executed_qty": str(evidence.executed_qty),
                "history_qty": str(evidence.history_qty),
                "trade_ids": list(evidence.trade_ids),
                "observed_at": str(evidence.observed_at),
                "history_complete": bool(evidence.history_complete),
                "fill_totals_match": bool(evidence.fill_totals_match),
                "order_verified": bool(evidence.order_verified),
            }
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (intent_id, "RECONCILIATION_VERIFIED", json.dumps(proof, sort_keys=True)),
            )
            self.conn.execute(
                "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                (intent_id, "RECONCILED", json.dumps({
                    "status": status, "exchange_state": status,
                    "evidence_id": str(evidence.evidence_id), "scope": expected_scope,
                    "account_scope": expected_account,
                }, sort_keys=True)),
            )
            remaining = self.conn.execute(
                """SELECT 1 FROM execution_intents
                   WHERE execution_scope=? AND account_scope=?
                     AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                   LIMIT 1""",
                (expected_scope, expected_account),
            ).fetchone()
            if not remaining:
                self.conn.execute(
                    "DELETE FROM bot_state WHERE key=?",
                    (self._execution_lock_key(expected_scope, expected_account),),
                )
                # Clear a legacy scope-only lock only when that whole contour is clean.
                any_scope_remaining = self.conn.execute(
                    """SELECT 1 FROM execution_intents
                       WHERE execution_scope=?
                         AND status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','AMBIGUOUS','RECONCILE_REQUIRED','PROTECTION_PENDING')
                       LIMIT 1""",
                    (expected_scope,),
                ).fetchone()
                if not any_scope_remaining:
                    self.conn.execute("DELETE FROM bot_state WHERE key=?", (self._execution_lock_key(expected_scope),))
            return not bool(remaining)

    def apply_execution_fill(self, fill, apply_effects):
        """Deduplicate and apply DB-only financial effects in one SQLite transaction.

        The callback must use this Database connection only; external side effects
        are forbidden because SQLite cannot roll them back.
        """
        data = dict(fill)
        required = ("exchange", "account_scope", "symbol", "trade_id", "quantity", "price")
        if any(data.get(k) in (None, "") for k in required):
            raise ValueError("fill requires exchange, account_scope, symbol, trade_id, quantity and price")
        identity = (
            str(data["exchange"]).upper(), str(data["account_scope"]),
            str(data["symbol"]).upper(), str(data["trade_id"]),
        )
        with self.transaction():
            try:
                self.conn.execute(
                    """INSERT INTO execution_fills(
                        exchange,account_scope,symbol,trade_id,order_id,intent_id,
                        quantity,price,commission,commission_asset,raw_json
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        identity[0], identity[1], identity[2], identity[3],
                        str(data.get("order_id") or ""), str(data.get("intent_id") or ""),
                        str(data["quantity"]), str(data["price"]),
                        str(data.get("commission") or ""),
                        str(data.get("commission_asset") or ""),
                        json.dumps(data, default=str, sort_keys=True),
                    ),
                )
            except sqlite3.IntegrityError:
                return False
            apply_effects(self, data)
            return True

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


# Serialize every public Database method on a shared connection. The reentrant
# lock permits nested database calls while preventing another thread from
# interleaving writes into an active transaction on the same sqlite3 connection.
def _serialized_database_method(method):
    def wrapped(self, *args, **kwargs):
        with self._db_lock:
            return method(self, *args, **kwargs)
    wrapped.__name__ = method.__name__
    wrapped.__doc__ = method.__doc__
    wrapped.__qualname__ = method.__qualname__
    return wrapped

for _database_method_name, _database_method in list(Database.__dict__.items()):
    if callable(_database_method) and not _database_method_name.startswith("_") and _database_method_name != "transaction":
        setattr(Database, _database_method_name, _serialized_database_method(_database_method))

"""Prove the v7->v8 private Binance order journal migration is lossless.

This runs actual SQLite SQL, with the migration text extracted from Kotlin.
No Binance credentials or network are needed.
"""
from pathlib import Path
import re
import sqlite3

def test_symbol_scoped_order_projection_migration_retains_historical_status():
    source=(Path(__file__).resolve().parents[1] /
            "app/src/main/java/com/williamsbot/TradingAuditStore.kt").read_text()
    match=re.search(r'PRIVATE_FUTURES_ORDER_SCOPE_V8_SQL\s*=\s*\n\s*"([^"]+)"',source)
    assert match is not None, "Order journal SQL migration absent"
    migration=match.group(1)
    assert 'if (oldVersion < 8) db.execSQL(PRIVATE_FUTURES_ORDER_SCOPE_V8_SQL)' in source
    assert 'SQLiteOpenHelper(context, "williams_trading_audit.db", null, 8)' in source
    assert 'order.put("client_order_id", scopedOrderKey)' in source
    assert '"client_order_id=?", arrayOf(scopedOrderKey)' in source
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE orders(client_order_id TEXT PRIMARY KEY, symbol TEXT NOT NULL,"
                   " order_id TEXT, status TEXT, executed_qty REAL)")
        db.execute("INSERT INTO orders VALUES(?,?,?,?,?)",("42","BTCUSDT","42","FILLED",1.0))
        db.execute("INSERT INTO orders VALUES(?,?,?,?,?)",("ABC","ETHUSDT","43","PARTIALLY_FILLED",0.3))
        db.execute(migration)
        assert db.execute("SELECT client_order_id,symbol,status,executed_qty "
            "FROM orders ORDER BY symbol").fetchall()==[
                ("BTCUSDT|42","BTCUSDT","FILLED",1.0),
                ("ETHUSDT|ABC","ETHUSDT","PARTIALLY_FILLED",0.3)]
        # Two symbols may use the SAME Binance client id/order id; never REPLACE.
        db.execute("INSERT INTO orders VALUES(?,?,?,?,?)",
            ("ETHUSDT|42","ETHUSDT","42","NEW",0.0))
        assert db.execute("SELECT client_order_id FROM orders ORDER BY client_order_id").fetchall()==[
            ("BTCUSDT|42",),("ETHUSDT|42",),("ETHUSDT|ABC",)]
        assert db.execute("SELECT status FROM orders WHERE client_order_id=?",
            ("BTCUSDT|42",)).fetchone()==("FILLED",)

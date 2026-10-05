from pathlib import Path
from types import SimpleNamespace
from db import Database
import trade_journal

def test_trade_journal_roundtrip(tmp_path: Path):
    db=Database(str(tmp_path/"t.sqlite3"))
    trade_id=db.save_trade(
        entry_time="2026-10-05T10:00:00+00:00",
        symbol="BTCUSDT", side="LONG",
        entry_price=100.0, quantity=1.0,
        entry_order_id="1", fees=0,
    )
    candidate=SimpleNamespace(
        symbol="BTCUSDT", score=82.0, base_score=78.0, setup_score=90.0,
        signal_strength=1.0, breakout_distance_pct=0.5, risk_pct=2.0,
        risk_reward=2.0, atr_pct=0.02, spread_pct=0.0005,
        htf_confirmed=True, setup_state="STRONG_SIGNAL",
        wise_man_count=2, signal_family="FRACTAL+SUPER_AO",
        wave_score=86.0, wave_position=3, wave_phase="IMPULSE",
        wave_confidence=82.0, wave_exhaustion_risk=20.0,
        nested_w3=True, nested_w3_parent_w5=True,
        wave_path="1d:W5 > 4h:W3 > 1h:W3", wave_reason="nested W3",
    )
    trade_journal.record_entry(db,trade_id,candidate)
    trade=db.open_trade("BTCUSDT")
    trade_journal.observe(db,trade_id,100.0,108.0,"LONG")
    db.close_trade(trade_id,"2026-10-05T11:00:00+00:00",108.0,8.0,0.08,"TAKE_PROFIT")
    trade_journal.record_exit(db,{**trade,"exit_time":"2026-10-05T11:00:00+00:00"},108.0,8.0,0.08,"TAKE_PROFIT")
    item=db.get_trade_journal(trade_id)
    assert item["entry_context"]["wave_position"]==3
    assert item["diagnosis"]=="SETUP_WORKED"
    assert item["mfe_pct"]>=8.0
    assert db.learning_summary()["wins"]==1

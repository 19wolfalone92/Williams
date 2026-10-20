"""Offline, causal replay of exported H1 diagnostics; never places orders.

Usage: python tools/replay_williams_diagnostics.py diagnostic_report.json ticks.json outdir
ticks.json must contain {"BTCUSDT": 0.1, ...} from a stated exchange-info source.
This is a bar simulation, not proof of the original intrabar/mark-price fills.
"""
import json
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))

import pandas as pd

from strategy import calculate_indicators,canonical_indicator_config_from_env
from run_williams_h1_paper_replay import APPROVED_SYMBOLS
from williams_edition_rules import SOURCE_PROFILES
from williams_market_geometry import angulation_evidence
from williams_market_reading import chart_lines,market_reading
from williams_paper_replay import PaperReplayConfig,run_williams_paper_replay
from williams_profiles import extract_profile_signals
from williams_wave_hierarchy import wave_bars_from_dataframe

H=3_600_000


def replay(report_path,ticks_path,destination):
    report=json.loads(Path(report_path).read_text());ticks=json.loads(Path(ticks_path).read_text())
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    rows=[];observations=[]
    history=report["signal_decision_history"];now=report["created_at_ms"]
    if set(history)!=set(APPROVED_SYMBOLS) or set(ticks)!=set(APPROVED_SYMBOLS):
        raise ValueError("Expected the ten approved Futures pairs and their explicit tick sizes")
    for symbol in APPROVED_SYMBOLS:
        df=pd.DataFrame(history[symbol]["candles"],columns=["open_time_ms","open","high","low","close","volume"])
        if (df.open_time_ms.diff().dropna()!=H).any() or (df.open_time_ms+H>now).any():
            raise ValueError(symbol+": history is not contiguous and closed")
        events={p:{} for p in SOURCE_PROFILES};counts={p:{"BUY":0,"SELL":0} for p in SOURCE_PROFILES}
        for end in range(44,len(df)):
            # Recalculate each closed prefix independently: no future pivot flags.
            prefix=df.iloc[:end+1];ind=calculate_indicators(prefix,canonical_indicator_config_from_env())
            for profile in SOURCE_PROFILES:
                signals=[]
                for side in ("LONG","SHORT"):
                    part=extract_profile_signals(symbol,ind,profile=profile,side=side,timeframe="1h",tick_size=ticks[symbol])
                    counts[profile]["BUY" if side=="LONG" else "SELL"]+=len(part)
                    signals.extend(part)
                events[profile][end]=signals
        frame=df.copy();frame.index=pd.to_datetime(frame.open_time_ms,unit="ms",utc=True)
        for profile in SOURCE_PROFILES:
            equity,trades,metrics=run_williams_paper_replay(frame,events[profile],PaperReplayConfig(tick_size=ticks[symbol]))
            trades.to_csv(destination/(symbol+"_"+profile+"_paper.csv"),index=False)
            rows.append(dict(symbol=symbol,profile=profile,bars=len(df),
                first_bar_ms=int(df.open_time_ms.iloc[0]),last_closed_bar_ms=int(df.open_time_ms.iloc[-1]),
                signal_observations=counts[profile],metrics=metrics,
                note="PAPER_ONLY; costs assumed; no additions/portfolio sharing/mark-price path modeled"))
        for trade in report["status"].get("trade_observations",[]):
            if trade["symbol"]!=symbol:continue
            at=trade["entry_snapshot"].get("signal_bar_time_ms",0)
            prefix=df[df.open_time_ms<=at]
            bars=wave_bars_from_dataframe(prefix);jaw,teeth,lips=chart_lines(bars)
            proof=angulation_evidence(trade["direction"],bars,jaw,teeth,lips)
            observations.append({k:trade.get(k) for k in ("symbol","direction","entry_price","exit_price","stop_price","exit_reason","opened_at","closed_at")}
                |dict(original_strategy_version=trade.get("strategy_version"),
                    current_wm1_geometry=proof,signal_bar_ms=at,
                    exchange_exit_order_type_verified=False,
                    exit_reason_source="APPLICATION_JOURNAL" if trade.get("exit_reason") not in ("EXCHANGE_RECONCILED_CLOSE",None,"") else "SPECIFIC_EXCHANGE_CAUSE_UNAVAILABLE",
                    exchange_fills_reconciled=trade.get("trade_audit_source")=="BINANCE_USDM_USER_TRADES",
                    warning="Recorded reason retained; exact original intrabar decision is not reproduced"))
        print(symbol,"complete",flush=True)
    result=dict(schema="WILLIAMS_DIAGNOSTIC_REPLAY_V1",source_created_at=report["created_at"],
        source_bars_per_pair=150,rows=rows,actual_trade_observations=observations,
        limitations=["150 captured trade-price H1 candles per pair; partial-day sample",
            "Current USD-M tick metadata supplied separately; not a historical tick-rules archive",
            "No live account/phone/order submission/reconnect test",
            "No mark-price intrabar history or exact ordering of two touches inside one H1 bar",
            "Paper costs and end-of-data liquidation are assumptions; results are not a profitability estimate",
            "Paper simulator covers initial entries and stops/opposition; addition/NTD/Course management and portfolio sharing need runtime tests"])
    (destination/"replay_summary.json").write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str))
    return result


if __name__=="__main__":
    replay(*sys.argv[1:])

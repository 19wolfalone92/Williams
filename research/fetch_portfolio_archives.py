"""Read-only 24-month USD-M archive acquisition. No credentials or orders.

Each original ZIP is checked against Binance's published SHA-256 and validated
for UTC coverage. Gaps remain explicit; archive acquisition is NOT a backtest.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import io
import json
import re
import time
import zipfile
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

from trading_config import DEFAULT_FUTURES_SYMBOLS

BASE = "https://data.binance.vision/data/futures/um/monthly"
START = (2024, 11)
MONTHS = 24
DATASETS = (("klines", "1h", 3600000), ("klines", "5m", 300000),
            ("markPriceKlines", "5m", 300000), ("fundingRate", "", 0))

def month_bounds(year, month):
    start = datetime(year, month, 1, tzinfo=timezone.utc)
    end = datetime(year + (month == 12), month % 12 + 1, 1, tzinfo=timezone.utc)
    return int(start.timestamp()*1000), int(end.timestamp()*1000)

def months():
    y, m = START
    for _ in range(MONTHS):
        yield y, m
        y, m = y + (m == 12), m % 12 + 1

def archive_path(symbol, kind, frame, year, month):
    stamp = f"{year:04}-{month:02}"
    if frame:
        return f"{kind}/{symbol}/{frame}/{symbol}-{frame}-{stamp}.zip"
    return f"{kind}/{symbol}/{symbol}-{kind}-{stamp}.zip"

def fetch(url, size_limit):
    for attempt in range(3):
        try:
            with urlopen(url, timeout=25) as response:
                raw = response.read(size_limit + 1)
            if len(raw) > size_limit:
                raise ValueError("archive exceeds bounded download size")
            return raw
        except HTTPError as exc:
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)
    raise RuntimeError("unreachable")

def verified_archive(path):
    checksum = fetch(BASE + "/" + path + ".CHECKSUM", 4096).decode("ascii").split()[0].lower()
    if not re.fullmatch(r"[a-f0-9]{64}", checksum):
        raise ValueError("invalid published SHA-256")
    raw = fetch(BASE + "/" + path, 32 * 1024 * 1024)
    if hashlib.sha256(raw).hexdigest() != checksum:
        raise ValueError("published SHA-256 mismatch")
    return raw, checksum

def number(value):
    n = Decimal(value)
    if not n.is_finite():
        raise ValueError("nonfinite archive value")
    return n

def ms(value, *, opening=False):
    n = int(value)
    if n > 10**15:
        if opening and n % 1000:
            raise ValueError("opening timestamp not millisecond aligned")
        n //= 1000
    return n

def validate(raw, kind, step, start, end):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        members = [m for m in archive.infolist() if not m.is_dir()]
        if len(members) != 1 or not members[0].filename.endswith(".csv"):
            raise ValueError("expected exactly one CSV")
        if members[0].file_size > 128 * 1024 * 1024 or archive.testzip() is not None:
            raise ValueError("unbounded or corrupt ZIP")
        with archive.open(members[0]) as member:
            rows = csv.reader(io.TextIOWrapper(member, encoding="utf-8-sig"))
            timestamps, intervals = [], []
            for i, row in enumerate(rows):
                if i == 0 and row and row[0].strip().lower() in ("open_time", "opentime", "calc_time", "fundingtime"):
                    continue
                if not row:
                    raise ValueError("empty CSV row")
                t = ms(row[0], opening=bool(step))
                if not start <= t < end or (timestamps and t <= timestamps[-1]):
                    raise ValueError("out-of-range, duplicate or unordered timestamp")
                if step:
                    if len(row) < 7 or (t-start) % step or ms(row[6]) != t+step-1:
                        raise ValueError("invalid candle grid or close boundary")
                    o, h, l, c, volume = map(number, row[1:6])
                    if min(o,h,l,c) <= 0 or h < max(o,l,c) or l > min(o,h,c) or volume < 0:
                        raise ValueError("invalid OHLCV")
                else:
                    if len(row) < 3:
                        raise ValueError("invalid funding row")
                    interval = int(row[1])
                    if not 1 <= interval <= 24:
                        raise ValueError("invalid funding interval")
                    number(row[2])
                    intervals.append(interval * 3600000)
                timestamps.append(t)
    if not timestamps:
        raise ValueError("empty archive")
    if step:
        expected = (end-start)//step
        missing = expected-len(timestamps)
        complete = missing == 0 and timestamps[0] == start and timestamps[-1] == end-step
        return {"rows":len(timestamps), "expected_rows":expected, "missing_rows":missing,
                "first_ms":timestamps[0], "last_ms":timestamps[-1], "complete":complete}
    # Funding can change interval. Preserve raw events; only identify coverage
    # gaps beyond the published neighbouring intervals, not fabricated settlements.
    gaps = sum(b-a > max(x,y)+1000 for a,b,x,y in zip(timestamps,timestamps[1:],intervals,intervals[1:]))
    complete = gaps == 0 and timestamps[0]-start <= intervals[0]+1000 and end-timestamps[-1] <= intervals[-1]+1000
    return {"rows":len(timestamps), "suspected_funding_gaps":gaps,
            "first_ms":timestamps[0], "last_ms":timestamps[-1], "complete":complete}

def self_test():
    def zipped(rows):
        b=io.BytesIO()
        with zipfile.ZipFile(b,"w") as z:
            z.writestr("test.csv", rows)
        return b.getvalue()
    good="open_time,open,high,low,close,volume,close_time\n0,10,11,9,10,1,299999\n300000,10,11,9,10,1,599999\n"
    assert validate(zipped(good),"klines",300000,0,600000)["complete"]
    assert not validate(zipped(good.splitlines()[0]+"\n"+good.splitlines()[2]),"klines",300000,0,600000)["complete"]
    for bad in (good.replace("299999","300000"),good.replace("300000,10","0,10"),good.replace("10,11,9","10,8,9")):
        try:
            validate(zipped(bad),"klines",300000,0,600000)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid candle accepted")
    fund="calc_time,funding_interval_hours,last_funding_rate\n0,8,0.0001\n28800000,8,-0.0001\n"
    assert validate(zipped(fund),"fundingRate",0,0,57600000)["complete"]
    assert len(list(months())) == 24 and list(months())[-1] == (2026,10)
    assert len(DEFAULT_FUTURES_SYMBOLS) == 15 and "SUIUSDT" in DEFAULT_FUTURES_SYMBOLS and "UNIUSDT" not in DEFAULT_FUTURES_SYMBOLS
    print("archive validation self-tests PASS")

def acquire(symbol, output):
    if symbol not in DEFAULT_FUTURES_SYMBOLS:
        raise ValueError("symbol outside approved universe")
    output.mkdir(parents=True,exist_ok=True)
    results=[]
    manifest=output/"manifest.json"
    for year,month in months():
        start,end=month_bounds(year,month)
        for kind,frame,step in DATASETS:
            path=archive_path(symbol,kind,frame,year,month)
            row={"path":path,"kind":kind,"frame":frame,"month":f"{year:04}-{month:02}"}
            try:
                raw,checksum=verified_archive(path)
                facts=validate(raw,kind,step,start,end)
                target=output/path
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(raw)
                row.update(facts,sha256=checksum,bytes=len(raw),status="VERIFIED" if facts["complete"] else "GAPS")
            except Exception as exc:
                row.update(complete=False,status="FAILED",error=f"{type(exc).__name__}: {exc}"[:300])
            results.append(row)
            report={"source":BASE,"symbol":symbol,"start":"2024-11-01T00:00:00Z",
                    "end_exclusive":"2026-11-01T00:00:00Z","expected_archives":MONTHS*len(DATASETS),
                    "archive_rows":results,"complete":len(results)==MONTHS*len(DATASETS) and all(x["complete"] for x in results),
                    "execution_authority":False,"backtest_completed":False,
                    "historical_contract_filters_verified":False}
            manifest.write_text(json.dumps(report,indent=2))
            print(symbol,row["month"],kind,frame,row["status"],flush=True)
    return report

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--self-test",action="store_true")
    p.add_argument("--symbol")
    p.add_argument("--output",type=Path)
    a=p.parse_args()
    if a.self_test:
        self_test()
        return
    if not a.symbol or a.output is None:
        p.error("--symbol and --output required")
    report=acquire(a.symbol,a.output)
    print("COMPLETE" if report["complete"] else "PARTIAL",flush=True)
    raise SystemExit(0 if report["complete"] else 2)

if __name__=="__main__":
    main()

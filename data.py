import os, time, pandas as pd, requests

# Legacy historical mode is retained for research/backtests only. Its
# endpoint follows the same TESTNET switch as the trading client so a live
# runtime cannot silently continue pulling Testnet candles.
BASE_URL = (
    'https://testnet.binance.vision'
    if os.getenv('TESTNET', 'true').lower() == 'true'
    else 'https://api.binance.com'
)


def market_data_base_url():
    """Return the market-data endpoint without changing execution safety defaults."""
    if os.getenv("WILLIAMS_RESEARCH_MAINNET", "false").lower() == "true":
        # Binance documents data-api.binance.vision as the public market-data
        # endpoint. This avoids coupling research downloads to account/execution
        # API routing or regional trading availability.
        return "https://data-api.binance.vision"
    return BASE_URL

def _normalize_interval(value):
    text = str(value or "").strip()
    return "1M" if text == "1M" else text.lower()


def _frame(rows):
    cols=['open_time','open','high','low','close','volume','close_time','quote_volume','trades','taker_buy_base','taker_buy_quote','ignore']
    df=pd.DataFrame(rows,columns=cols)
    if df.empty:return df
    for c in ['open','high','low','close','volume']:df[c]=pd.to_numeric(df[c],errors='coerce')
    df['open_time']=pd.to_datetime(df['open_time'],unit='ms',utc=True);df['close_time']=pd.to_datetime(df['close_time'],unit='ms',utc=True)
    return df.set_index('open_time')[['open','high','low','close','volume','close_time']].dropna()

def fetch_klines(client_or_symbol, symbol_or_interval, interval=None, limit=200, start=None, end=None):
    """Live: fetch_klines(client, symbol, interval, limit=200). Historical: fetch_klines(symbol, interval, start, end)."""
    if hasattr(client_or_symbol,'klines'):
        client=client_or_symbol;symbol=symbol_or_interval;iv=interval
        return _frame(client.klines(symbol,iv,limit=limit))
    symbol=client_or_symbol;iv=symbol_or_interval
    # Backward-compatible positional form: fetch_klines(symbol, interval, start, end).
    if interval is not None and isinstance(interval,(str,pd.Timestamp)) and (limit is not None) and isinstance(limit,(str,pd.Timestamp)) and start is None and end is None:
        start,end=interval,limit
    elif start is None:
        start=interval
    params={'symbol':symbol,'interval':iv,'limit':1000}
    start_ms=int(pd.Timestamp(start,tz='UTC').timestamp()*1000) if start else None
    end_ms=int(pd.Timestamp(end,tz='UTC').timestamp()*1000) if end else None
    if start_ms is not None:params['startTime']=start_ms
    if end_ms is not None:params['endTime']=end_ms
    rows=[];cursor=start_ms
    while True:
        p=dict(params)
        if cursor is not None:p['startTime']=cursor
        r=requests.get(market_data_base_url()+'/api/v3/klines',params=p,timeout=20);r.raise_for_status();batch=r.json()
        if not batch:break
        rows.extend(batch)
        if len(batch)<1000:break
        cursor=int(batch[-1][0])+1;time.sleep(0.05)
        if end_ms is not None and cursor>=end_ms:break
    return _frame(rows)

def save_csv(df,path):df.reset_index().to_csv(path,index=False)


def fetch_klines_history(client, symbol, interval, start_ms=0, end_ms=None):
    """Download all available Spot klines in Binance's 1000-row pages."""
    symbol = str(symbol).upper()
    interval = _normalize_interval(interval)
    rows = []
    cursor = int(start_ms or 0)
    end_value = int(end_ms) if end_ms is not None else None
    while True:
        params = {"symbol": symbol, "interval": interval, "limit": 1000, "startTime": cursor}
        if end_value is not None:
            params["endTime"] = end_value
        batch = client._request("GET", "/api/v3/klines", params)
        if not batch:
            break
        rows.extend(batch)
        if len(batch) < 1000:
            break
        next_cursor = int(batch[-1][0]) + 1
        if next_cursor <= cursor:
            break
        cursor = next_cursor
        if end_value is not None and cursor >= end_value:
            break
    return _frame(rows)


def fetch_klines_cached_history(client, symbol, interval, cache_dir=None):
    """Persistent full-history cache; subsequent calls only fetch new candles."""
    cache_dir = cache_dir or os.getenv("WAVE_HISTORY_CACHE_DIR", "data_cache/klines")
    os.makedirs(cache_dir, exist_ok=True)
    path = os.path.join(
        cache_dir,
        f"{str(symbol).upper()}_{_normalize_interval(interval)}.csv",
    )

    cached = pd.DataFrame()
    if os.path.exists(path):
        try:
            cached = pd.read_csv(path, parse_dates=["open_time"])
            if not cached.empty:
                cached["open_time"] = pd.to_datetime(cached["open_time"], utc=True)
                cached = cached.set_index("open_time")
        except Exception:
            cached = pd.DataFrame()

    if cached.empty:
        fresh = fetch_klines_history(client, symbol, interval, start_ms=0)
    else:
        start_ms = int(cached.index.max().timestamp() * 1000) + 1
        fresh = fetch_klines_history(client, symbol, interval, start_ms=start_ms)

    merged = pd.concat([cached, fresh]) if not fresh.empty else cached
    if merged.empty:
        return merged
    merged = merged[~merged.index.duplicated(keep="last")].sort_index()
    merged.reset_index().to_csv(path, index=False)
    return merged

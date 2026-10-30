"""Contracts: losing execution permission must not terminate H1 market research.
These are source integration tests, not a claim of phone/REST availability.
"""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=(ROOT/"app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
JOURNAL=(ROOT/"app/src/main/java/com/williamsbot/TradeJournal.kt").read_text(encoding="utf-8")

def test_h1_scan_has_independent_read_only_startup_gate():
    assert 'private val startupFrames = listOf("1h", "4h", "1d", "6h", "15m")' in RUNTIME
    assert 'readOnlyH1Ready' in RUNTIME
    assert 'historyReady = h1ReadyNow==coreSymbols.size' in RUNTIME
    assert 'if(readOnlyH1Ready) {' in RUNTIME
    assert '"H1_READY_CONTEXT_LOADING"' in RUNTIME
    assert 'val gateState = scannerDataReadinessState(' in RUNTIME
    assert 'historyReady = readOnlyH1Ready' in RUNTIME
    assert '!marketDataFresh -> "WAITING_FOR_MARKET_DATA"' not in RUNTIME.split(
        'internal fun scannerDataReadinessState(',1)[1].split('internal fun scannerExecutionBlocker(',1)[0]

def test_ticker_or_book_metadata_outage_only_blocks_entries_not_scanning():
    universe=RUNTIME.split('private fun loadUniverse(',1)[1].split(
        'private fun markScanProgress(',1)[0]
    assert 'runCatching { futuresMarketData.ticker24h() }' in universe
    assert 'runCatching { futuresMarketData.bookTickers() }' in universe
    assert '"READ_ONLY_MARKET_METADATA_PARTIAL:' in universe
    assert 'require(missing.isEmpty())' not in universe
    # Execution still requires fresh real top of book and suitable spread.
    entry=RUNTIME.split('private fun submitNativeFuturesCampaignEntryChecked(',1)[1]
    assert 'futuresMarketData.topOfBook(symbol)' in entry
    assert 'SPREAD_LIMIT' in entry

def test_chart_refetches_nonempty_but_stale_candles():
    chart=RUNTIME.split('fun klines(',1)[1].split('fun marketQuote(',1)[0]
    assert 'primaryCandles.isEmpty() || !candleSeriesCurrent(primaryCandles, selectedInterval)' in chart
    assert '.put("candles_fresh",candleSeriesCurrent(sourceCandles,selectedInterval))' in chart

def test_next_day_hard_pause_is_utc_and_does_not_block_read_only_scan():
    guard=RUNTIME.split('private fun futuresLossStreakGuard():',1)[1].split(
        'private fun dailyRiskBaselineReady(',1)[0]
    assert 'futuresMarketData.cachedExchangeNowMs()' in guard
    assert 'FUTURES_LOSS_STREAK_UTC_CLOCK_UNVERIFIED' in guard
    assert 'futuresUtcDayStartMs(nowMs)' in JOURNAL
    assert 'UTC_00_00_AUTOMATIC_IF_EXCHANGE_CLOCK_VERIFIED' in RUNTIME


def test_scan_uses_the_verified_h1_startup_cache_limit_and_does_not_force_180_bar_rest_reads():
    # Startup verifies and persists 150 H1 closed bars. Requiring 180 on every
    # scan misses a valid 150-bar cache and can stall all 10 charts when
    # Demo REST returns an occasional empty success response.
    scan_source = RUNTIME.split('private fun performCampaignScan()', 1)[1].split(
        'val ranked = mutableListOf<BaseAnalysis>()', 1
    )[0]
    assert 'val candles = fetchCandles(symbol, "1h", 150)' in scan_source
    # Optimized scanner may reuse the already verified closed 150-bar H1 series.
    # On partial history, stale candles, or failed exchange-clock freshness,
    # it must fall back to the original fresh read instead of accepting old data.
    assert 'primary.size >= 150' in RUNTIME
    assert 'candleSeriesCurrent(primary, "1h", futuresMarketData.exchangeNowMs())' in RUNTIME
    assert 'primary.takeLast(150) else fetchCandles(primarySymbol, "1h", 150)' in RUNTIME
    assert 'primaryCandles = if (primary != null' in RUNTIME
    # Position management correctly retains an independent 180-bar requirement.
    assert 'val candles = fetchCandles(symbol, "1h", 180)' not in scan_source

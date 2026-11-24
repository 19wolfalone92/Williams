"""Regression: old scanner book-ticker metadata must never veto live TC2.

This is a source-wiring contract for the Android engine. Python does not call
Android's private scanner or exchange adapter, so this test cannot establish
live Demo execution; Android/JUnit and signed readback remain separate gates.
"""
from pathlib import Path


SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()


def test_cached_scanner_spread_cannot_erase_valid_tc2_h1_candidate():
    scanner = SOURCE.split("var strictSignal =", 1)[1].split(
        "if (preliminaryWave.position", 1)[0]
    final = SOURCE.split("val finalSignal =", 1)[1].split(
        "var score =", 1)[0]
    assert "spread <= maxSpreadPct" not in scanner
    assert "baseCandidate.spreadPct in 0.0..0.0015" not in final
    assert "primaryDecisionSignal != null" in final


def test_pending_stop_does_not_consume_now_quote_but_immediate_market_does():
    admission = SOURCE.split("val dailyLossAllowsEntry =", 1)[1].split(
        "val existing=futuresCampaignCoordinator.intentsSnapshot()", 1)[0]
    assert "candidate.spreadPct !in" not in admission
    assert "futuresMarketData.topOfBook(symbol)" not in admission
    signal = SOURCE.split("val marketReentry=profitReentry(signal)", 1)[1].split(
        "val reference=signal.triggerPrice", 1)[0]
    assert "if(marketReentry)" in signal
    assert "futuresMarketData.topOfBook(symbol)" in signal
    assert "futuresEntryQuoteBlocker(" in signal
    assert "FUTURES_ENTRY_TOP_OF_BOOK_FAILED" in signal
    # Normal TC2 entry is a STOP order, not this immediate MARKET reentry.
    assert "if(marketReentry) NativeFuturesCampaignCoordinator.EntryKind.MARKET else NativeFuturesCampaignCoordinator.EntryKind.STOP" in SOURCE


def test_python_scanner_does_not_veto_future_stop_on_current_spread():
    source = (Path(__file__).resolve().parents[1] /
              "futures_williams_runtime.py").read_text()
    scanner = source.split("spread_pct = self._spread(symbol)", 1)[1].split(
        "if self.interval==", 1)[0]
    assert "return []" not in scanner
    assert "spread_pct = -1.0" in scanner


def test_missing_coarse_spread_cannot_veto_stop_and_unknown_spread_ranks_last():
    assert "spreads[symbol]=(ask-bid)/bid" in SOURCE
    assert "scannerEntrySymbolBlockers(" not in SOURCE
    assert "SYMBOL_SPREAD_UNAVAILABLE" not in SOURCE
    assert "it.symbol !in perSymbolScanBlockers" in SOURCE
    assert "it.spreadPct.takeIf" in SOURCE
    assert "Double.POSITIVE_INFINITY" in SOURCE
    assert "coarseMetadataMissing.isEmpty()" not in SOURCE

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


def test_fresh_signed_entry_path_still_checks_live_exchange_bid_ask():
    admission = SOURCE.split("val dailyLossAllowsEntry =", 1)[1].split(
        "val existing=futuresCampaignCoordinator.intentsSnapshot()", 1)[0]
    assert "candidate.spreadPct !in" not in admission
    assert "futuresMarketData.topOfBook(symbol)" in admission
    assert "currentSpreadPct = (topOfBook.second - topOfBook.first) / topOfBook.first" in admission
    assert "currentSpreadPct !in 0.0..maxSpreadPct" in admission
    assert "FUTURES_ENTRY_TOP_OF_BOOK_FAILED" in admission
    assert "FUTURES_ENTRY_SPREAD_BLOCKED" in admission


def test_spread_is_retained_as_read_only_scanner_ranking_information():
    assert "spreads[symbol]=(ask-bid)/bid" in SOURCE
    assert "thenBy { it.spreadPct }" in SOURCE

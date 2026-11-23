"""Release-candidate invariants for the 17 Nov TC2 STOP_MARKET recovery.

These are source-contract tests; actual signed Binance Demo FILLED→STOP and
physical Android background lifecycle still require separate acceptance.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/williamsbot"


def source(name):
    return (APP / name).read_text(encoding="utf-8")


def test_version_advances_after_46438_and_uses_stable_debug_identity():
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    workflow = (ROOT / ".github/workflows/android-apk.yml").read_text()
    # This historical regression guards a monotonic release identity; it
    # must not pin a November 17 APK number and reject newer signed builds.
    version = (ROOT / "VERSION").read_text().strip()
    parts = version.split(".")
    assert len(parts) == 3 and all(part.isdigit() for part in parts)
    assert tuple(map(int,parts)) >= (4,64,52)
    assert f"VERSION = '{version}'" in (ROOT / "server.py").read_text()
    assert f'versionName = "{version}"' in gradle
    version_code = int("".join(parts))
    assert f'maxOf({version_code}, ciRunNumber ?: {version_code})' in gradle
    assert 'WILLIAMS_DEBUG_KEYSTORE_B64' in workflow
    assert 'EXPECTED_SIGNER_SHA256="c177bba354724c24e9668864720e9bfdb2cb545964702903a1fa306060912de9"' in workflow
    assert '"$APKSIGNER" verify --verbose "$APK"' in workflow
    assert "williams-bot-debug-apk-" in workflow


def test_no_stale_spread_veto_on_deferred_h1_stop_but_market_quote_veto_kept():
    runtime = source("StandaloneRuntime.kt")
    assert "primaryDecisionSignal != null && baseCandidate.atrPct in 0.0..0.08" in runtime
    entry = runtime.split("private fun submitNativeFuturesCampaignEntryChecked(", 1)[1]
    early = entry.split("val existing=futuresCampaignCoordinator.intentsSnapshot()", 1)[0]
    assert "futuresMarketData.topOfBook(symbol)" not in early
    market = entry.split("val marketReentry=profitReentry(signal)", 1)[1].split(
        "val reference=signal.triggerPrice", 1
    )[0]
    assert "if(marketReentry)" in market
    assert "futuresMarketData.topOfBook(symbol)" in market
    assert "futuresEntryQuoteBlocker(" in market
    assert 'else NativeFuturesCampaignCoordinator.EntryKind.STOP' in entry
    adapter = source("BinanceUsdMFuturesTestnetAdapter.kt")
    assert '"STOP" -> "STOP_MARKET"' in adapter
    assert '"workingType" to "CONTRACT_PRICE"' in adapter
    policy = source("FuturesDeferredEntryQuotePolicy.kt")
    assert "if(kind == NativeFuturesCampaignCoordinator.EntryKind.STOP) return null" in policy
    assert "if(kind != NativeFuturesCampaignCoordinator.EntryKind.MARKET)" in policy


def test_trading_daily_halt_is_three_percent_with_stop_and_fees_preserved():
    runtime = source("StandaloneRuntime.kt")
    assert "if (lossPct >= futuresDailyLossLimitPct)" in runtime
    assert '"FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked"' in runtime
    assert runtime.count('put("max_daily_loss_pct", futuresDailyLossLimitPct)') >= 2
    assert "futuresCampaignCoordinator.cancelPendingEntries(\"DAILY_LOSS_LIMIT\")" in runtime
    assert "val effectiveLoss = stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct" in runtime
    assert "val stopFraction = kotlin.math.abs(trigger - stop) / trigger" in runtime


def test_fifteen_symbols_and_durable_exchange_account_stop_fences():
    policy = source("WilliamsSymbolPolicy.kt")
    coordinator = source("NativeFuturesCampaignCoordinator.kt")
    inbox = source("PrivateUserEventPolicy.kt")
    assert "FIFTEEN_NEW_RISK_FIFTEEN_MANAGEMENT_V2" in policy
    assert "williamsNewRiskSymbols = APPROVED_FUTURES_SYMBOLS.toList()" in policy
    assert "williamsNewRiskSymbolBlocker(plan.symbol)" in coordinator
    assert "halfMarginTransportFence" in coordinator
    assert "findProtectiveStop(" in coordinator
    assert "campaignLifecycleStatus(owner)" in coordinator
    assert "privateUserEventInboxAllowsExposure" in inbox
    assert 'snapshot.optLong("pending_count", -1L) == 0L' in inbox


def test_unknown_scan_spread_is_displayed_as_unknown_not_negative():
    ui = source("MainActivity.kt")
    assert 'fmt(candidate.spreadPct * 100.0, 3) + "%" else "нет данных"' in ui
    assert 'fmt(candidate.spreadPct * 100, 3) + "%" else "нет данных"' in ui

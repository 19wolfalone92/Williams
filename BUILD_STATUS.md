# Williams Bot 2.1 / Android 4.24.0 — evidence-based status
**Checked:** 2026-10-14  
**Working branch:** `futures-williams-campaign`  
**Latest audited code commit:** `490334751dd6a1654d52d6b55c78cdbb0f9ade3c`  
**Scope:** Binance USDⓈ-M Futures Demo/Testnet; autonomous on-device Android runtime.

## Latest verification evidence
- Python backend CI — **PASS** at the audited code commit: [run 38558792479](https://github.com/19wolfalone92/Williams/actions/runs/38558792479). Release-contract gate, production Python compile, static safety audit, and offline tests passed. Pytest result: **206 passed, 4 warnings**.
- Android CI — **PASS** at the audited code commit: [run 38558792532](https://github.com/19wolfalone92/Williams/actions/runs/38558792532). Android build and configured unit tests completed successfully.
- Williams Campaign CI — Python tests **PASS** at the audited code commit; combined workflow still running at the last status check: [run 38558792579](https://github.com/19wolfalone92/Williams/actions/runs/38558792579).
- Android APK build — **PASS**: [run 38558505033](https://github.com/19wolfalone92/Williams/actions/runs/38558505033). It produced a signed debug APK and an unsigned release APK. This build was from `357bd2e5df2928aef668f34eedfe6b8fb9d3ced0`; Android/Kotlin source did not change between that commit and the latest audited code commit.

## Source checks confirmed
- Python Testnet client is pinned to `https://demo-fapi.binance.com`; the Android Futures adapter is pinned to the Demo REST host.
- Android market feeds use the Futures Demo market/public WebSocket paths; private user data creates and renews a Futures listen key.
- Approved universe is exactly: BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, DOGEUSDT, ADAUSDT, LINKUSDT, AVAXUSDT, LTCUSDT.
- Both LONG and SHORT are represented in the Python and Android code. Android entry, protective-stop, close and trailing paths use the native Futures campaign coordinator/adapter.
- H1 is the decision timeframe; D1 is the macro veto and H4 the context filter. M15/M5 do not create entry signals.
- Android source has 0.20% initial campaign risk, 0.25% per-entry cap, 0.60% aggregate campaign cap, and 3% daily-loss policy. This is code evidence, not a live-account risk test.
- Durable intents, conservative reconciliation, exchange-side conditional entries/protective stops, stop replacement ownership checks, and fail-closed unresolved-mutation paths are present.

## Implemented in this pass
The read-only Futures Demo gate previously advertised P09 (“fresh closed-candle market data”) but only checked account mode and exchangeInfo. It now:
- synchronizes the exchange clock before signed account access and after market-data collection;
- validates all ten approved contracts via Futures exchangeInfo;
- fetches mark price and H1 klines for all ten approved symbols;
- rejects stale/future mark timestamps, stale H1 history, malformed candles, non-contiguous hourly bars, and inconsistent/non-finite OHLC;
- returns per-symbol freshness diagnostics; and
- has offline positive coverage plus explicit negative tests for stale marks, stale H1 candles, and invalid OHLC.
The release-contract gate enforces that these market-data checks and negative-path tests remain in the source. The tests passed in Python backend CI on commit `490334751dd6a1654d52d6b55c78cdbb0f9ade3c`.

## Remaining release blockers — not waived
- Authenticated read-only Futures Demo preflight has **not** been executed against the user's Demo account. The manual workflow requires configured Testnet-only repository secrets; do not put keys/secrets in chat or logs.
- No exchange-observed end-to-end LONG lifecycle is evidenced yet: pending entry fill → exact protective stop confirmed on Binance → close → flat confirmed → recovery state.
- No exchange-observed end-to-end SHORT lifecycle is evidenced yet, with equivalent protection, close and recovery checks.
- The actual Testnet execution trace and sustained all-ten-symbol scan have not been proven on an installed physical phone.
- No physical-device verification has established foreground-service survival through screen-off, app process death/restart, reboot, updates, and Android battery restrictions.
- The full primary-source audit against Bill Williams books and figures has not been signed off as approved; code and indicator unit tests alone do not prove full strategy fidelity.
- The release APK is unsigned and requires the user's release signing key. The signed debug APK is for controlled Testnet validation, not production release.

## Acceptance decision
**NOT READY FOR TESTNET ACCEPTANCE / NOT APPROVED FOR LIVE TRADING.** The code/CI checks above are positive evidence, but they do not replace authenticated Demo-account validation, LONG and SHORT exchange-observed order/protection/recovery cycles, and physical-phone verification.

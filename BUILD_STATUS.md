# Williams Bot 2.1 / Android 4.24.0 — evidence-based status
**Checked:** 2026-10-14  
**Working branch:** `futures-williams-campaign`  
**Scope:** Binance USDⓈ-M Futures Demo/Testnet; autonomous on-device Android runtime.

## Confirmed by the latest completed GitHub Actions runs before this release-gate correction
- Python backend CI — **PASS**: [run 38553142425](https://github.com/19wolfalone92/Williams/actions/runs/38553142425). Release-contract gate, Python compile, static safety audit, and offline tests succeeded.
- Williams Campaign CI — **PASS**: [run 38553142034](https://github.com/19wolfalone92/Williams/actions/runs/38553142034).
- Android CI — **PASS**: [run 38553142139](https://github.com/19wolfalone92/Williams/actions/runs/38553142139). Android compilation and configured unit tests completed successfully.
- Android APK build — **PASS**: [run 38553142100](https://github.com/19wolfalone92/Williams/actions/runs/38553142100). Artifacts were produced: installable signed debug APK and unsigned release APK. The artifacts were built from the commit preceding this status/release-gate correction, so they are not evidence for that later change.

## Source checks confirmed
- Futures client is pinned to `https://demo-fapi.binance.com` in Testnet mode; the Android Futures adapter also pins the Demo REST host.
- Android market feeds use the Futures Demo `market` and `public` WebSocket paths; private user data creates and renews a Futures listen key.
- The approved universe in configuration is exactly: BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, DOGEUSDT, ADAUSDT, LINKUSDT, AVAXUSDT, LTCUSDT.
- Both LONG and SHORT are represented in Python and Android code. Android entry, protective-stop, close and trailing code routes through the native Futures campaign coordinator and its One-Way-mode adapter.
- H1 is the decision timeframe; D1 is the macro veto and H4 the context filter. M15 does not create entries.
- Risk policy is coded with 0.20% initial Android campaign risk, 0.25% per-trade cap, 0.60% total campaign cap and a 3% daily-loss cap. This is code/config evidence, not a live-account test result.
- Durable intents, fail-closed reconciliation, exchange-side conditional entry/protective stops and protection ownership checks are present in the Android Futures coordinator.

## Correction made in this pass
The read-only Testnet gate previously advertised P09 (fresh closed-candle data) in its 30-step manifest but did not actually fetch or validate market snapshots. The gate is being changed to fetch mark price and H1 klines for all ten approved contracts, synchronize against exchange server time, reject stale marks/stale candles/malformed or inconsistent OHLC, and test these rejection cases. The release-contract gate now asserts that this real check and its tests remain present.

## Not yet proven; release blockers
- Authenticated Binance Futures Demo preflight has **not** been run in the latest Actions evidence. It needs the repository's Testnet-only API secrets. Do not paste keys/secrets into chat or logs.
- No end-to-end order lifecycle has yet been evidenced against Binance Demo: LONG pending-entry fill → confirmed exchange-side stop → close → flat confirmation, and the equivalent SHORT cycle. The code paths exist, but code/CI cannot substitute for an exchange-observed execution trace.
- No testnet run has proven sustained simultaneous scanning/history coverage for all ten contracts on an installed physical phone.
- No physical-device test has proven foreground-service survival across screen-off, process death, app update, and Android battery/background restrictions.
- No complete primary-source audit of the Williams books/figures has been closed as approved; indicator unit tests and code similarity do not establish full source fidelity.
- The unsigned release APK is not installable as a normal production artifact until signed with the release signing key. The debug APK is for controlled Testnet validation only.

## Acceptance rule
**Status remains NOT READY FOR TESTNET ACCEPTANCE** until the refreshed CI is green, the authenticated read-only gate passes on the actual Demo account, and both LONG and SHORT full order/protection/recovery cycles are captured from the exchange. No live-money readiness is claimed.

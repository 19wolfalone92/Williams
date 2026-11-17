# Williams Bot — Diagnostics (10) forensic audit (2026-11-12)

## Provenance and safety
- Android debug APK: **4.64.23-debug**, source revision `7226e1c6679ed299ad2f81dfb1c9a4d18ad54337`.
- Observed device: Samsung SM-S918B, Android API 37.
- Archive captured **2026-11-12 06:35:08 UTC**. This is an immutable **observation** of Demo, not a live signed Binance account query or an approval for trading.
- The working target branch is `feature/nov12-full-four-book-chapter-coverage-4.64.23`; the installed source revision is one commit behind its present head. This fix is kept isolated in a PR and must not be mistaken for an installed APK.

## Verified incident: process crash / OOM
- `android_background_health.json`: captured `OutOfMemoryError`; first application stack frame `NativeFuturesCampaignCoordinator.intents:5604`, where a full persisted `JSONArray(raw)` is parsed.
- OS process-exit history confirms a **CRASH** on 2026-11-11 19:42:10 UTC, with RSS approx. **439,660 KiB**. The retained 24h window also contains package-update exits; service-start attempts alone are not evidence of crashes.
- At diagnostic capture: Java heap **113,464,160 / 268,435,456 B** (~42.3%). **After generating the report:** **230,916,960 / 268,435,456 B** (~86.0%, only ~35.8 MiB free). Thus the initial PASS on memory does not cover the diagnostic's own allocation peak.
- The diagnostic report contains an estimated **12.94 MiB** of full `signal_decision_history`, **9.42 MiB** of `scanner_snapshot`, and **2.33 MiB** of observer history, before JSON object overhead and duplicated strings. This is a plausible contributor to memory pressure, not a proven single cause of the earlier crash.

## Second incident: signed Futures clock probe instability
- `execution.log`: repeated `Futures server clock probe failed; signed request blocked` with independent probes taking ~**16.4s**, **16.5s**, and **20.7s**.
- Coordinator changed from `RECONCILE_REQUIRED` at snapshot to `ENTRY_PENDING` at export end (`DIAG-STATE-RACE`). The correct safe response remains fail-closed, rather than reopening the entry gate from mixed-time evidence.
- The engine/foreground service **was running at capture**, but this does not prove 24h uninterrupted availability.

## Account/strategy observations (not instructions to trade)
- At capture: **zero signed observed open positions** and **one Binance-confirmed pending BNBUSDT SHORT STOP entry**, qty **0.08**, trigger **670.53**, planned protective stop **690.69**. The order was `NEW`, `executed_qty=0`, so `protection_status=NOT_SUBMITTED` is **not** evidence of an unprotected *filled* position.
- `futures_exchange_stop_proofs.json` is empty: no current signed STOP proof for an open position can be inferred.
- Five recent closed trades (BCH, LINK, LTC, AVAX, XRP) were recorded as `OPPOSITE_WILLIAMS_LEVEL_TRIGGERED`, but independent reversal of senior H4/D1 context was **not proven**. This is an exit-quality research gap, not enough to authorize a change in trading rules.
- Aggregate triage: **58 PASS**, **2 WARNING**, **19 NOT_VERIFIED**, **0 FAIL**. The zero-FAIL count is not a clean end-to-end Futures Demo acceptance.

## Code changes in this PR
1. **Avoid allocating the complete decision trace merely to discard it.** Internal `status(fast=true,includeDecisionTraces=false)` is used for diagnostics. Public status remains unchanged by default, and the full trace is still written separately as `signal_decision_history.json`.
2. **Avoid a second persisted-intents JSON parse** during the operator-only STOP supervision preflight. Both checks use one consistent parsed intent snapshot.
3. Add a regression contract for these allocation invariants and unchanged signed STOP evidence path.

## Not yet established
- Whether these reductions fully remove OOM on actual Android process lifetimes. Needs heap profiling/stress on SM-S918B and repeated large exports. Long-term solution may be page-wise SQLite intent readers and streamed forensic evidence rather than one giant JSONObject.
- Why long clock probes occur: verify per-probe raw HTTP status/timing, DNS/connect/read timeout, device clock adjustment, and network transitions, without suppressing signed-request checks.
- Whether any later Binance entry filled or obtained a STOP: **must requery positions/orders with fresh signed REST data**.
- Confirm `gradle :app:testDebugUnitTest :app:assembleDebug` on the fix branch. No APK should be called verified until GitHub CI and on-device test complete.

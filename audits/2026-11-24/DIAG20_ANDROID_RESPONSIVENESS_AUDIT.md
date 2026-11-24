# DIAG20 — Android responsiveness / Binance signed-safety scheduling

## Grounded capture

Input: user-supplied `Williams_Diagnostics (20).zip` captured 2026-11-24T06:50:39.315Z. Version `4.64.52-debug`, source `c33ee404b873a449f4d46db3e1c7dcce6a3fec2f` (GitHub's merge-test of diag18 PR#274 into diag17). Phone Samsung SM-S918B, Android API37.

79 checks: **38 PASS, 0 FAIL, 8 WARNING, 33 NOT_VERIFIED**. Android worker alive; H1 scanner in progress 6/15, scanner progress ~2.4s old, foreground service and CPU wakelock active. No evidence of a frozen OS thread at the instant of capture. Signed reconciliation last-success age **237.3s** (maximum 90s); private user stream connected but `sync_required=true` and last successfully signed REST sync for this socket session not yet established. New orders blocked by this plus `FUTURES_LOSS_STREAK_EXCHANGE_CLOSE_UNJOURNALLED`. These are **execution correctness failures**, not simply UI animation bugs.

Captured Java heap **53,037,216/268,435,456 bytes**, native **32,136,576 bytes**; immediately after assembling diagnostics Java heap **127,183,008 bytes**, peak unknown. REST used weight 226/6000, no 429, no cooldown. Scanner executor 3 active, queue 6/30; research/history/indicator queues 0 but history and indicator queues are reported unbounded. Execution journal **408 canonical and 408 normalized shadow rows**; SQLite quick_check OK; reconciliation progress in flight `SIGNED_POSITIONS_RETURNED`. UI signed position envelope **STALE**, last read earlier than strict 15s TTL; 0 rows in that stale view **DOES NOT prove the real Demo account is flat**.

## Code execution changes in this PR

| Source | Cause of perceived freeze | Repair | Trade/STOP authority |
|---|---|---|---|
| `MainActivity.kt` | Compose MAIN parsed entire status, 15 candidates, trade journal and event list every 5 seconds | Decode off MAIN on `Dispatchers.Default` and publish a completed envelope on MAIN | None |
| `StandaloneRuntime.kt` | Market/Analysis cockpit previously copied all 15 candidate decision-trace trees even though UI only parses 38 scalar fields | Always project candidate summary in in-process cockpit; status retains selected chart's decision trace, full source still in `/scanner` and ZIP | None |
| `StandaloneRuntime.kt` + `MainActivity.kt` | Scanner refresh GET generated/discarded full multi-MB 15-pair JSON before fetching the proper UI snapshot | New read-only `POST /api/v1/scanner/refresh` lightweight ACK, same native `requestScan()` policy | None |
| `StandaloneRuntime.kt` + `MainActivity.kt` | Every dashboard poll joins all visible historical trades against 408 intent rows and supervisions, even on unrelated tabs | Fetch display trade history only on Trades/historical chart; Trades gets immediate navigation refresh. Others preserve last UI list. Full trade history and PnL journal unchanged | None |
| `ChartLayout.kt`, `MainActivity.kt` | 6000-bar H1/H4 chart recalculated every 30s despite no new closed bar | Refresh after the next candle's close with bounded 30s minimum / 5min liveness probe. Live quote remains independent at 2s. | None; uses phone time **ONLY** as UI hint |
| `FuturesReservedRiskDisplayProjection.kt` | Dashboard status nested-summed each reserved campaign across entire 408+ ledger: O(N²) per 5s poll | Equivalent O(N) read-only reserved-risk display with parity JUnit | None; authoritative risk checks unchanged |
| `FuturesOptionalDisplayReadPolicy.kt` | Optional signed UI account/STOP GETs competed with active critical signed P0 position/private-stream reconciliations | Defer the optional display read only during an actual signed critical sweep; old position snapshot remains marked STALE. Never defer P0 STOP/position reconciliation itself. | No new authorization |

## Regression coverage and outstanding acceptance

JUnit: chart refresh cadence near close/empty history/unknown interval/H4/D1; 0.64% vs 0.60% historical journal scenario, canceled/filled/partial/duplicated owner/null risk and malformed child fill; optional account/private/STOP sweep priority. Python campaign and Android lint/build/signing run on exact PR head. Signed Binance Demo, user-device **interactive FPS/jank profile**, reconnect while position is open, and exact STOP coverage remain **NOT VERIFIED**.

No change to the trading books, TC2 entry model, 15 symbols, long/short, 3% daily loss, three consecutive losing closes, 5% breaker, 50% margin, position size, STOP placement, TP/SL or execution idempotency. Do not equate UI responsiveness with exchange order readiness. The earlier `ETHUSDT` STOP failure must be independently reverified by signed current Binance evidence.

**Status: HOLD for automated new entries until independent P0 financial-transport gates pass; Draft PR, no main merge or live capital use.**

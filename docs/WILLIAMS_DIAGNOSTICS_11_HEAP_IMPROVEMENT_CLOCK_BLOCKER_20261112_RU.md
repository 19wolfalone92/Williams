# Williams Diagnostics (11), 2026-11-12 — comparison with (10)

## Source truth
The user's new ZIP is from 2026-11-12 **07:03:23 UTC** (10:03:23 Europe/Minsk), Android 37, Samsung SM-S918B.
Its build is `4.64.23-debug`, source revision **`bd8fb854fa7babd260418cb346a8fdda65f6a8ca`**, the GitHub synthetic merge commit with parents `7a274a0` and `8df5f87` (PR #234 heap fix). The phone therefore has the first memory fix already. **No new APK with this clock change has yet been installed on the phone.**

## Differences from Diagnostics (10)
| Metric | #10 (06:35:08 UTC) | #11 (07:03:23 UTC) |
|---|---|---|
| Test triage | 58 PASS, 2 WARN, 19 NOT_VERIFIED, 0 FAIL | 56 PASS, 2 WARN, 20 NOT_VERIFIED, 0 FAIL |
| Java heap capture | 113,464,160 / 268,435,456 B (42.3%) | 134,956,416 / 268,435,456 B (50.3%) |
| Java heap **after report build** | 230,916,960 / 268,435,456 B (86.0%) | 183,268,736 / 268,435,456 B (68.3%) |
| Headroom after report | 35.8 MiB | 81.2 MiB |
| Latest historic fatal crash | OutOfMemoryError (2026-11-11 19:42 UTC) | Same historic event, **not a new post-fix crash** |
| Engine / Android FGS at capture | Active | Active |
| Trading gate | Blocked due transient reconciliation/mixed-time state | Blocked during exchange reconciliation; snapshot consistency improved but no entry authority |

Important: comparing single captures does not prove an OOM cure; the on-device Java heap used at start was actually higher in #11. Proof needs repeated large exports and uninterrupted runtime.

## What remains
- `status.state=RECONCILE_REQUIRED`, `coordinator.blocker=exchange_reconciliation_in_progress`; H1 signal generation can run but signed execution is **blocked**. The recurrence of this transient gate is observed, not evidence that the gate itself is incorrectly implemented.
- Historic `COCKPIT_RECONCILIATION` rows show repeated invalid/slow exchange clock calibrations, with a recent series at 2026-11-12 06:32–06:38 UTC. `/fapi/v1/time` samples require <=8s network RTT for signed **reads** and <=3s for **writes**. Current client allows an invalid time probe to last up to 25s, while blocking other signed calls behind `clockLock`. This is wasteful in mobile networks and delays STOP/account reconciliation.
- `BNBUSDT` pending SHORT stop entry (qty 0.08, trigger 670.53, planned protective 690.69, requested/confirmed leverage 10x) remained `NEW`, executed_qty `0`. No open Binance positions were observed in this signed snapshot; no current signed protective STOP proof exists in the ZIP. **Do not infer the order is still pending now** without a new signed query. A pending conditional entry can fill while Android is down.
- The historic Android crash `NativeFuturesCampaignCoordinator.intents:5604` is retained; not a new post-fix event. 24-hour autonomous operation and real post-fill STOP placement remain NOT_VERIFIED.
- The latest exit forensics still lack independent confirmation of higher-timeframe reversals for five recent opposite-H1 exits; changing the live strategy from these rows alone is unjustified.

## Fix added on top of PR #234
- Special-case **only unsigned** `GET /fapi/v1/time` to a maximum per-call deadline of **8.5 seconds**, just over the admissible 8-second network RTT. Two independent probes remain, maximum network hold from the pair becomes ~17s instead of 50s. Rate-limit waiting is deliberately outside the transport RTT.
- **No** changes to exchange clock validity, timestamp origin, recvWindow, financial mutation retry, entry admission, client IDs, STOP behavior, or read/write permissions. Signed writes still require a <=3s sample, fresh reconciliation and all original exposure gates.
- Added a non-regression source contract. CI/Android test confirmation is necessary before a new installable signed APK is claimed ready.

## Acceptance steps
1. Run Kotlin Android CI and signed debug APK workflow on the latest PR SHA.
2. On-device test mixed LTE/Wi-Fi and weak-signal clock probes, confirming that failed probes continue to block signed mutations but shorten reconciliation stall.
3. Monitor memory and Android ApplicationExitInfo over multiple exports and process restarts.
4. Signed Binance Demo recheck pending entry, active positions, algo STOPs; do not cancel/duplicate/enter blindly.

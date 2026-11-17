# Williams Diagnostics (12) — 2026-11-12 — physical-phone follow-up

Archive captured **07:28:44.452 UTC** on Samsung SM-S918B (API 37). Installed app version **4.64.23-debug**, source commit **`343d311b4efad842a3ad4197ee48b1d20e284458`**. This is the synthetic merge of the *previous* PR #234 head `d38720f` into the 4.64.23 feature base: both memory and 8.5-second *unsigned* clock probe fixes are present in the installed source. The diagnostics (12) follow-up below is NOT yet present on the phone.

## Actual comparison; no unconditional PASS claims

| Measurement | Diagnostics 10 / 06:35 UTC | 11 / 07:03 UTC | 12 / 07:28 UTC |
|---|---:|---:|---:|
| PASS / WARNING / NOT_VERIFIED | 58 / 2 / 19 | 56 / 2 / 20 | **57 / 3 / 18** |
| Java heap captured used | 42.3% | 50.3% | **63.0%** |
| Java heap AFTER report built used | 86.0% | 68.3% | **46.7%** |
| Headroom AFTER report | 35.8 MiB | 81.2 MiB | **136.5 MiB** |
| Battery optimization EXEMPTION | true | true | **false (new risk)** |
| Foreground service / worker | active | active | active |
| Gate at diagnostic instant | blocked during reconciliation | blocked during reconciliation | blocked during reconciliation |

- The historic `OutOfMemoryError` from 2026-11-11 19:42 UTC remains stored at `NativeFuturesCampaignCoordinator.intents:5604`, **not** a new crash after the heap fixes. Current retained Android OS exit history has 6 exits: 4 expected APK upgrades, 2 unexpected, 1 abnormal (same old OOM). DO NOT misclassify upgrades as crash fixes/failures.
- The post-build Java heap used counter **fell below the earlier snapshot** in #12, probably due Android GC. It does **not** prove that allocation peak or memory leak has been eliminated, and is not a comparable peak allocation benchmark. Retained app process age is short after APK install (~90 seconds), not proof of overnight continuity.
- Clock probe failures retained in event history occurred **before this newest APK** (latest shown at 06:33:58 UTC), not after the 07:27 UTC restarted service. Diagnostic #12 records fresh Binance clock (age ~17s). This suggests recent recovery, but the new 8.5s time-probe cap is NOT yet verified over weak-network stress.
- Snapshot reports current account-wide reconciliation `FINAL_SIGNED_POSITION_STABILITY_PROOF` at 07:28:43 UTC, started 07:28:36 UTC. The previous full reconciliation finished 07:28:31 UTC. `execution_enabled=false` and `RECONCILE_REQUIRED` **during the new in-flight reconciliation**, despite prior successful account/user-stream sync (user stream 07:27:46 UTC). This is intentionally fail-closed, not proof that the engine stopped or failed permanently.
- One Binance-confirmed pending BNBUSDT SHORT STOP entry: `entry_price=670.53`, `qty=0.08`, `executed_qty=0`, `planned_stop=690.69`, requested/confirmed leverage **10x**. `futures_positions_signed_snapshot` and signed STOP proofs are both empty. No filled position or unprotected filled position proven; this archive cannot establish what happened later. Do not create, cancel, duplicate or market-close orders based on its cached state.
- Senior-timeframe TC2 early-exit counterfactual remains unresolved. The same ten historic trades appear in read-only exit forensics; labels alone do not justify changing trading exits.

## New root-cause observation: Samsung battery policy regressed
- #12 `android_background_health.json`: `battery_optimization_ignored=false`, while `foreground_service_active=true`, `cpu_wake_lock_held=true`, `background_restricted=false`, `power_save_mode=false`.
- The `AND-01` warning increased from NOT_VERIFIED in #11 to WARNING. This is NOT a proved process crash, but a real Android background reliability risk; CPU wakelocks and foreground status do not bypass all device/sleep network restrictions.
- User must explicitly choose **Settings → Apps → Williams (Debug) → Battery → Unrestricted** and **Samsung Battery → Background usage limits → Never sleeping apps**. The app already has user-click navigation in Settings; it cannot automatically grant this OS exception.
- This PR makes the diagnostic AND-01 **name the exact false Android permission flag and remediation** rather than only stating generic FGS/CPU status. Regression test enforces continued WARNING when not exempt and continued NOT_VERIFIED (never PASS) when exempt.

## Release acceptance still pending
1. Fresh CI for new documentation and AND-01 diagnostic/test changes.
2. Install new signed APK with same signature, recheck device battery permission **after** install.
3. Weak Wi-Fi/LTE transitions and screen-off overnight test. Capture elapsed watchdog, actual OS process exits, clock probe failure timestamps, Binance signed account/position/STOP state.
4. No proposal to lower the admission gate, ignore a missing STOP, or auto-change Android device settings.

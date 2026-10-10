# WILLIAMS BOT 2.1 — Signal-to-Exit Chain Audit

**Audit date:** 2026-10-09  
**Scope:** Python Futures backend, Android native Futures engine, signal/risk path, exchange order lifecycle, recovery, exit accounting, and backtest/release controls.  
**Status:** Code-level corrections applied; final CI verification and Demo lifecycle tests remain mandatory. **NOT READY FOR LIVE TRADING.**

## End-to-end chain reviewed

1. OHLCV retrieval, cache validation, and closed-candle filtering.
2. Alligator/AO/AC/fractal/reversal feature construction.
3. Williams signal extraction and signal freshness/trigger geometry.
4. Multi-timeframe/Market Context and Wave filters.
5. Portfolio selection and risk allocation.
6. Last-mile preflight and durable mutation intent.
7. Conditional entry submission and authoritative response verification.
8. Entry-child fill, position, and user-trade reconciliation.
9. Exchange-side protective stop installation and verification.
10. Position management, trailing-stop replacement, pause/kill/recovery.
11. Reduce-only exit, protective-stop race handling, fill/fee accounting.
12. Flat-state verification, orphan-order checks, and release gate.

## High-priority defects corrected in this audit

### 1. Armed conditional entries could survive a reconciliation lockout

Blocking the scanner alone does not block conditional orders already resting at Binance. A pending entry on another symbol could still trigger while the runtime had an unresolved campaign.

**Correction:** Python and Android lockout paths now attempt cancellation of already-armed entries during reconciliation/daily-risk lockouts. Python cancellation also includes an initial entry whose campaign was moved to `RECONCILE_REQUIRED` but still carries the durable pending-entry flag. Cancellation failures remain reconciliation errors rather than being treated as success.

### 2. Python exit could remove protection before a reduce-only exit was accepted

The previous order was: cancel protective stop, then submit market exit. If the exit was rejected or its outcome became ambiguous, a live position could be left without its exchange-side stop.

**Correction:** The Python Futures exit path keeps the `closePosition` stop active during the reduce-only exit. After the position is flat, it reconciles/cancels the stop and accounts for any protective-child fill racing with the market exit before finalization.

### 3. Android native mutation responses were under-classified

HTTP 408/425 and Binance unknown-execution codes -1006/-1007 were not consistently treated as ambiguous mutation outcomes.

**Correction:** The native client classifies these responses as outcome-unknown, so they require reconciliation rather than blind retry.

### 4. Android could mark a submitted entry closed after verification failed

A post-submit lookup/verification exception could previously be confused with a definitive pre-submit failure.

**Correction:** Once a mutation has been accepted, failed verification retains a durable pending/reconciliation path. Active entry Algo orders are checked against symbol, client ID, side, type, trigger, quantity, and close/reduce flags. A live position is reconciled against the entry child order, userTrades, average fill, and reserved risk after protection is installed.

### 5. Android native entry/protection state trusted insufficient exchange response detail

A non-empty order ID or an active-looking status is not sufficient proof that the order has the intended side, type, trigger, quantity, or closePosition behavior.

**Correction:** Entry and protective orders now require authoritative follow-up verification. Invalid/missing position quantities are no longer silently converted to zero in the key ownership, management, kill, exit, and recovery paths.

### 6. Android daily-loss baseline could fail open

A missing/corrupt same-day baseline could be replaced by current equity, erasing the loss history. A failed baseline write could also have been ignored.

**Correction:** Invalid baselines lock out new entries, and a failed synchronous baseline commit blocks the current cycle.

### 7. Android did not reliably detect account-wide orphan Futures orders

A clean position snapshot does not prove that an unowned conditional order cannot create exposure later.

**Correction:** Native startup/cycle ownership checks inspect account-wide standard and Algo orders, fail closed on malformed/unknown order rows, and require account trade permission, single-asset margin mode, and confirmed isolated 1x symbol configuration before new exposure.

### 8. Android exit accounting could close without complete authoritative fills

Missing/malformed userTrades, incomplete exit status, mismatched quantities, or repeated reconciliation could result in incomplete or duplicated PnL accounting.

**Correction:** Exit finalization requires authoritative FILLED status, order identity, finite trade fields, trade quantity conservation, and protective-order cleanup. Entry fees are included when known; exit accounting is idempotent by order ID. If a protective child and market exit race and cannot yet be aggregated safely, the campaign stays `RECONCILE_REQUIRED` rather than being falsely closed.

### 9. Pausing/stopping could stop management while campaigns remained active

**Correction:** Python and Android now require the management monitor to remain alive while Futures campaigns or unresolved state remain active. A pause disables new entries; it is not treated as proof that open positions can be left unmanaged.

### 10. Risk and research-path validation gaps

- Risk-engine inputs/configuration now reject non-finite values and risk overrides above the hard per-trade cap.
- SHORT strict-signal gating now mirrors the LONG sell/buy-fractal balance-line requirement.
- Signal enrichment preserves candle index zero rather than converting it to `-1`.
- Backtest OHLC and signal inputs reject malformed/non-finite values; historical OHLCV rejects duplicate/out-of-order timestamps and invalid price/volume geometry; only closed candles are tested; run manifests record data/config/parameter hashes.

## Remaining blockers — do not bypass

1. **Android protective-stop/market-exit race:** if both orders execute during the same exit cycle, the native engine intentionally remains in `RECONCILE_REQUIRED` until both histories can be reconciled. It does not yet have the same fully automated aggregate protective-child/market-exit ledger as the Python backend. This is fail-closed, but it is not complete autonomous recovery.
2. **Android partial market exits:** quantity-conservation checks deliberately prevent false closure when a market exit leaves a residual or disagrees with persisted quantity. Full automatic partial-exit ledger/recovery still needs a dedicated test-backed implementation.
3. **Backtesting:** the current simulator is explicitly long-only. `allow_shorts=True` raises `NotImplementedError`; therefore it cannot yet validate the short strategy's performance. Do not infer short profitability from live-code symmetry.
4. **Funding and non-quote commissions:** non-quote commission assets are flagged as unknown rather than guessed. Funding payments/income are not yet fully incorporated into a complete net-PnL ledger across both engines.
5. **Parity:** the Android native engine intentionally does not implement the Python engine's WM2/WM3 add-on/pyramiding lifecycle. Strategy/execution parity is not established.
6. **Operational verification:** a successful unit-test suite does not substitute for Demo integration tests. The authorized Demo LONG/SHORT lifecycle, protective-stop race tests against Demo, and read-only release gate have not been executed as part of this audit.
7. **Performance verification:** no claim of profitability is made. Walk-forward/out-of-sample testing with fees, slippage, funding, and regime-separated results remains required.

## Release decision

**NOT READY FOR LIVE TRADING.**

Do not merge this Draft PR or enable real orders until the final exact-head Python, Campaign CI, Android lint/unit-test/APK workflows are green; the remaining Android partial/race reconciliation and accounting gaps are closed or explicitly accepted with operational safeguards; and the read-only release gate plus authorized Demo lifecycle tests pass. No real exchange orders were sent during this audit.


## Second-pass audit findings — 2026-10-09

### 11. WM2/WM3 could not start a campaign when WM1 was absent — corrected

The scanner previously treated only a WM1 reversal as a new-campaign signal. Super AO and confirmed fractal signals were always labelled ADD_ON and the runtime filtered them out of initial-entry selection. This contradicted the campaign contract: the first valid Wise-Man signal may start a campaign; later signals may add only after a campaign exists.

**Correction:** all valid directional campaign signals now make a candidate eligible for deep context analysis. When no campaign exists, the runtime promotes the earliest non-expired valid signal (WM1, WM2 or WM3) to ENTRY locally. Signal extraction keeps WM2/WM3 tagged ADD_ON so an existing campaign still uses the add-on path. Added LONG/SHORT tests for a WM2-first campaign and an expired-signal rejection test.

### 12. Android native lifecycle methods were missing from the active source — restored

A fresh Android build exposed unresolved calls to `exitPosition`, `cancelPendingEntry/Entries`, `cancelOwnedProtection` and `manageExistingCampaigns`. The methods were absent even though startup, pause, kill and management code called them.

**Correction:** restored the missing lifecycle methods and made exit handling write-ahead/idempotent: persist stable exit client ID before submission, keep exchange-side protection live while the reduce-only market exit is being resolved, query the authoritative order and post-exit position, reconcile protection cleanup, and do not close on an ambiguous response.

### 13. Native partial market-exit recovery — fail-closed residual handling added; aggregation remains open

A terminal market exit may report fills while a residual position remains. The native engine now validates the prior order identity and executed quantity, durably records prior terminal fills, refreshes the residual quantity before creating another reduce-only exit, and blocks the final CLOSED/PnL transition while multiple exit fills remain unaggregated. Startup recovery also refuses to bypass that ledger.

This intentionally favors reducing residual exposure without inventing PnL. Full automated aggregation of multiple native market-exit orders and protective-child fills is still not implemented; unresolved ledger entries remain a release blocker and require reconciliation.

### 14. Triggered stop without child order ID — test now enforces the safe outcome

When Binance reports a protective Algo stop as triggered but does not yet expose its child order ID, the Python backend attempts a reduce-only exit for residual exposure but keeps the campaign in `RECONCILE_REQUIRED` because stop fills and fees cannot be authoritatively reconciled. The regression test now verifies both outcomes: residual reduction is attempted, and the campaign is not falsely marked CLOSED.

## Latest verification checkpoint

The last checked exact head was `c879deebe0b3eca61a3ef7439f7337ee90dc4b95`; Python backend, Campaign CI and Android workflows had been queued after these corrections. Results for earlier SHAs do not qualify as final verification. The PR remains Draft, `main` is unchanged, and no real exchange orders were sent.


### 15. Stale exchange-side conditional entries could outlive the signal — corrected

A local `SignalSpec.expires_at_ms` only prevented a stale signal from being armed initially; Binance Algo STOP orders do not inherit that local expiry. The reconciliation path could therefore leave an expired initial entry or WM2/WM3 add-on working indefinitely, able to open exposure much later.

**Correction:** Python campaigns now persist initial-entry and add-on expiry timestamps. Reconciliation cancels an expired armed Algo by its stable client ID and verifies terminal status/child fills before releasing risk. The Android native campaign stores the corresponding entry expiry and cancels expired pending entries through its reconciliation path. The scanner also excludes expired signals before portfolio ranking.

### 16. A trigger or structural stop could already have been crossed before arming — corrected

Signal extraction checked only the latest close against the trigger and did not reject a setup if a later closed candle had already crossed the trigger intrabar or breached the signal's structural invalidation. This could arm a stale stop-entry after the setup was no longer valid.

**Correction:** the Python signal extractor now checks all bars after the source bar: LONG triggers must remain unbroken by later highs and their structural low must remain intact; SHORT triggers must remain unbroken by later lows and their structural high must remain intact. Invalid/missing price data fails closed. Regression tests cover trigger-cross and stop-breach cases in both directions. Native Android signal selection now enforces per-type freshness windows and allows the earliest valid WM1/WM2/WM3 signal to start a campaign, while still not supporting pyramiding.

### 17. Native position-quantity reconciliation could overwrite the evidence before comparing — corrected with conservative exit

The native reconciler copied the live Binance `positionAmt` into its local campaign object before checking whether the live quantity differed from the previously persisted quantity. That made the later comparison ineffective and could hide partial protective/market fills.

**Correction:** compare the pre-sync persisted quantity with the exchange quantity first. On unexplained drift, persist a durable `unreconciled_position_quantity_mismatch` record and attempt a reduce-only exit for the observed residual. Market/protective exit accounting refuses to mark the campaign CLOSED while that mismatch ledger exists. This avoids hiding missing fills, but automatic aggregation/recovery of the missing trades remains a manual reconciliation blocker.

### 18. Android CI was blocked before lint/tests by absent signing secrets — corrected for CI validation

The APK workflow exited before running lint or unit tests when a persistent debug keystore secret was absent. The workflow now uses the persistent signing key when configured, otherwise it allows lint, unit tests and debug/release build validation with the default ephemeral Android debug key. That fallback is for CI validation only; it is not proof of persistent-signature continuity and does not change the live-release prohibition.


### 19. Canonical Digital Williams core could let an expired older signal hide a valid newer setup — corrected

The orchestration layer selected the earliest ENTRY first and only then checked whether it was actionable. If that oldest candidate was expired or had an invalid protective reference, `compose()` returned BLOCK and never considered a later valid signal from the same scan. This created a mismatch with the scanner/campaign runtime, which filter expired candidates before ranking.

**Correction:** initial selection now filters by the canonical `PendingSignal.actionable()` contract before ranking. If at least one actionable candidate exists, the earliest actionable signal is selected. If candidates exist but none is actionable, the core emits a diagnostic BLOCK; if no entry candidates exist, it emits WAIT. Added tests for expired-first, all-expired, empty, and invalid-protection candidate sets.

The canonical execution contract also contradicted the Futures exit implementation by documenting cancellation of protection before the reduce-only market exit. It now specifies keeping the exchange-side stop live while the exit mutation is unresolved, then reconciling flat state, protection cleanup and all fills/fees before finalizing.


### 20. Risk engine could silently replace a supplied invalid structural stop with an ATR stop — corrected

When a LONG setup supplied a stop at/above entry, or a SHORT setup supplied a stop at/below entry, the risk engine silently substituted an ATR stop and could still approve the trade. That changed the structural invalidation thesis while preserving the appearance of a valid Williams risk calculation.

**Correction:** a nonzero supplied structural invalidation must be strictly below LONG entry or strictly above SHORT entry. Invalid direction/negative levels now fail closed. ATR fallback remains available only when no structural level was supplied (zero sentinel). Added LONG/SHORT regression tests.


### 21. Signal domain factory allowed contradictory order side and exposure direction — corrected

`SignalSpec.new()` accepted an explicitly contradictory pair such as `side=BUY, direction=SHORT`. Downstream Futures execution has a separate guard, so this did not establish that a wrong-side order would be submitted; however, the contradiction could travel through candidate ranking and decision traces before being rejected at the execution boundary.

**Correction:** the canonical signal factory now requires BUY/LONG and SELL/SHORT consistency and raises immediately on conflict. Added regression tests for both rejection and valid SHORT construction.


### 22. Android native signal ranking could let an expired earliest signal hide a later valid setup — corrected

The native engine built a list of structurally valid WM1/WM2/WM3 candidates, selected the earliest source bar, and only afterward checked expiry in `findSignal()`. Because `findSignal()` received only `primary.lastSignal`, an expired early candidate could make the whole symbol return no signal even when a later valid candidate existed.

**Correction:** native candidate ranking now filters signal validity and per-type expiry before selecting the earliest live signal. Freshness calculation is shared by frame selection, final signal admission and persisted entry expiry, reducing the chance that those paths drift apart.


### 23. The named Digital Williams core is not the single end-to-end orchestrator — parity/trace gap remains open

Repository inspection confirms that `DigitalWilliamsCore` is used by the API/server path, but the Python Futures runtime independently runs `MarketScanner → PortfolioController → FuturesCampaignExecutionService`, while Android `FuturesNativeEngine` has its own Kotlin signal/risk/lifecycle implementation. The three paths do not all consume the same decision object or emit the same structured stage-by-stage `DecisionTrace`. Therefore, passing tests for the canonical core do not prove identical signal selection or Williams semantics across the Futures runtime and native Android engine.

**Status: OPEN ARCHITECTURE GAP, not represented as fixed.** The native stale-signal issue above was corrected directly in its active path. Before claiming strategy parity, create shared LONG/SHORT golden vectors for WM1 reversal, WM2 Super AO, WM3 fractal, expiry, trigger-cross, invalidation, context veto, and exit reason; run equivalent assertions against Python Futures and Android native code. Add a persisted trace linking source candle, context versions, risk decision, client order IDs, fills, protection changes and final exit accounting. No live approval until this gap and the documented Android partial/racing-exit ledger are resolved.


### 24. Android native entry sizing did not re-check risk after tick normalization — corrected

Native quantity sizing used the raw signal trigger/stop. The final order path then normalized trigger and stop to the exchange tick size but did not recompute the risk using the actual submitted prices. On symbols with coarse ticks, normalization can widen the stop distance and increase worst-case loss above the admitted risk budget.

**Correction:** immediately before the durable entry intent/order submission, the native engine recomputes stop distance, fee/slippage reserve, risk and equity notional using normalized submitted prices and normalized quantity. Invalid or over-budget results fail closed instead of sending the order.


### 25. Python Futures initial-signal expiry boundary differed from the canonical/native rules — corrected

The Python Futures selector rejected a candidate only when `expires_at_ms < now`, making a signal actionable at the exact expiry timestamp. The canonical `PendingSignal` and native Android paths treat `now >= expiry` as expired.

**Correction:** Python now rejects `expires_at_ms <= now`. Added a boundary regression test proving an expired-at-this-millisecond candidate cannot hide a later live signal.


### 26. PendingSignal expiry fallback could revive an explicitly expired signal — corrected

`default_expiry_ms()` preserved an explicit expiry only when it was later than `created_at_ms`. If a stale source signal was reconstructed after its expiry, the function could replace the old expiry with a new window based on reconstruction time and make the old signal actionable again.

**Correction:** any positive explicit `expires_at_ms` is now authoritative even when it is already in the past. A new default expiry is calculated only when the signal has no explicit expiry (zero sentinel). Added a regression test for a signal created/reconstructed after its source-derived expiry.


### 27. Missing/negative PendingSignal expiry was treated as "never expires" — corrected

The pending-signal object previously considered only positive expiry timestamps eligible for expiry checks. A directly restored object with zero or negative expiry could therefore remain actionable indefinitely even though exchange-side conditional orders require an explicit finite lifetime.

**Correction:** zero/negative expiry is now invalid/expired for actionability. The canonical factory computes a default only when no positive explicit expiry was supplied, and explicit positive timestamps remain authoritative even when already expired. Added tests for zero and negative persisted expiry.


## Third-pass end-to-end chain audit — 2026-10-10

### Chain map and current disposition

| Stage | Primary path | Required invariant | Disposition |
|---|---|---|---|
| 1. Market data | Binance Futures klines → validated frame | Closed, ordered, finite OHLCV; timestamps and source timeframe are explicit | Python ingestion rejects malformed/duplicate/out-of-order candles; freshness checked before management |
| 2. Williams features | `strategy.calculate_indicators` / Android `analyseFrame` | Alligator/AO/AC/fractal/reversal values derive only from available bars | Python and Android implementations are separate; golden-vector parity remains unproven |
| 3. Signal extraction | `williams_signals` / Android `findSignal` | Explicit LONG/SHORT, source candle, structural invalidation, unbroken trigger, finite expiry | Expiry/trigger/invalidation checks added; unknown direction now fails closed in Futures portfolio selection |
| 4. Market context | Scanner → wave/context → HTF veto | Context may veto but must not invent a Williams signal | Python scanner and Android native engine remain independent; parity is an open architecture gap |
| 5. Portfolio/risk | `PortfolioController` → `RiskEngine` | No implicit direction; structural stop is not silently replaced; portfolio risk cap is enforced | Removed Futures' empty-direction-to-LONG fallback; explicit LONG/SHORT regression tests added |
| 6. Entry authorization | Runtime preflight → ExecutionBarrier → durable intent | Account, position ownership, permissions, timeframe and risk are valid before mutation | Account-wide ownership and write-ahead intent checks remain in place; H1 is now the default and only allowed new-entry decision timeframe |
| 7. Conditional entry | Stable clientAlgoId → exchange follow-up | Unknown POST/DELETE outcome is reconciled; stale triggers cannot survive expiry | Python cancellation/reconciliation is implemented; Demo lifecycle still not verified |
| 8. Entry fill/protection | Algo child → position/userTrades → closePosition stop | Do not call a campaign protected until the exact live stop is verified | Python/Android verify exchange-side protection; native partial/racing exit aggregation remains incomplete |
| 9. Campaign management | Closed H1 bars → structural exit/trailing | Management continues through pause/kill and never relies on an unconfirmed local position | Python management now uses H1 regardless of a misconfigured scan interval; Android retains each persisted campaign's timeframe for recovery |
| 10. Exit mutation | Stable reduce-only clientOrderId; stop remains live | No blind retry; reconcile partial fill, residual position, stop race and order history | Python has aggregate exit reconciliation; Android remains fail-closed but does not fully aggregate multiple market exits and protective-child fills |
| 11. Accounting/finalization | userTrades, realized PnL, fees, flat position, stop cleanup | Exactly-once accounting; no CLOSED state with missing fills/fees or residual exposure | Python accounting is strict; Android stores realized PnL in campaign records but the Futures native exit path does not currently call the general `TradingAuditStore.recordTrade` history writer. The app's unified closed-trade history can therefore omit native Futures campaigns; this is an open observability/accounting integration defect |
| 12. Restart/release | SQLite state → exchange reconcile → read-only gate | Exchange is authority; unknown state blocks new entries; test gates cover both directions | Fail-closed recovery exists, but Demo LONG/SHORT lifecycle and the read-only release gate have not been executed in this audit |

### 28. Futures direction could silently default to LONG — corrected

`PortfolioController` previously replaced a missing candidate direction with LONG before invoking risk analysis. The Futures execution layer later validates signal direction, but this default could still distort candidate selection and risk decisions upstream.

**Correction:** Futures candidates must explicitly declare LONG or SHORT; missing/invalid direction is rejected. Legacy Spot scanning retains its historical LONG fallback only when the client is not a USDⓈ-M Futures adapter. Regression tests cover unknown Futures direction rejection and explicit SHORT preservation.

### 29. The operative signal timeframe default contradicted the canonical strategy — corrected, with a configuration guard

The Python `TradingConfig`, `FuturesRuntime`, and Android native Futures engine defaulted signal generation to 5m. This allowed a micro-timeframe to create entry signals despite the project contract that H1 is the canonical decision chart, H4/D1 are context, M15 is monitoring/context, and M5 is diagnostics/replay only.

**Correction:** defaults changed to H1 in Python configuration/runtime and Android preferences. Python refuses new entry selection when a non-H1 interval is configured and cancels already-armed conditional entries; existing position management still runs using closed H1 bars. Android `findSignal` refuses new Futures entries unless the configured decision timeframe is H1. Explicit non-H1 configuration remains visible as a blocker rather than silently changing strategy semantics.

### 30. Native Futures closed-trade history integration remains open

The native Futures exit routines reconcile fills and store realized PnL, exit IDs, fees and campaign state in the Futures campaign record, but `FuturesNativeEngine` has no call to `TradingAuditStore.recordTrade`. That writer is used by the legacy Spot runtime and currently hardcodes the trade side to BUY. Do not infer that the unified trade-history table is complete merely because a Futures campaign reaches CLOSED.

**Required follow-up:** define a Futures closed-trade record contract with LONG/SHORT direction, authoritative weighted-average entry/exit prices, aggregate quantity, quote-denominated realized PnL, all known commissions, an explicit unknown-fee flag, R-multiple against the persisted admitted risk, and stable idempotency key. Persist it atomically/idempotently with campaign finalization only after the exchange is flat and all exits reconcile. Add Android tests proving LONG and SHORT records, duplicate recovery, and no record on unresolved/partial exits. This is not marked fixed in this pass.

### Verification boundary

The latest successful Campaign CI run on the prior exact head does not verify the code added in this third pass. Fresh CI for the new head is required. Android lint/unit-test/APK status must be checked separately. No Demo orders or real orders were sent.


### 31. WM2 Super AO was incorrectly coupled to a fractal/Balance-Line flag — corrected

The Python signal extractor required the third consecutive same-colour AO bar to also pass `long_fractal_outside` / `short_fractal_outside` on the preceding row. That silently turned WM2 into a hybrid WM2+fractal condition and could suppress an otherwise valid Super AO signal before the campaign-entry logic ever saw it.

**Correction:** WM2 identity is now based on the third consecutive same-colour AO bar; fractal confirmation remains its own WM3 path. Context, direction, trigger-not-already-broken, structural invalidation, expiry, risk and exchange preflight still gate execution. Updated the SHORT regression fixture so WM2 must be detected when the separate fractal-outside flag is false. Strategy semantics still require cross-engine golden-vector verification; Android parity is not claimed.


### 32. Exact-expiry boundary was still inconsistent in the last-mile path — corrected

Although initial signal selection treated `now >= expires_at_ms` as expired, the scanner and last-mile Python entry/add-on methods still used strict `<`/ `>=` comparisons. A signal at the exact expiry millisecond could therefore remain in scanner output and pass the last-mile expiry check if called directly or during a timing race.

**Correction:** scanner candidates require `expires_at_ms > now`; both initial-entry and add-on execution checks reject `expires_at_ms <= now`; runtime add-on selection uses the same boundary. Added LONG/SHORT regression tests that set the initial signal expiry exactly equal to the current mocked time and assert no conditional order is submitted.


### 33. Missing expiry was still treated as non-expiring in several live-chain gates — corrected

The canonical `PendingSignal` treated zero/negative expiry as invalid, but the Futures selector, scanner and last-mile entry/add-on methods still used truthy checks such as `if expires_at_ms and expires_at_ms <= now`. A malformed/restored signal with `expires_at_ms=0` could bypass those checks, be armed on Binance, and persist with no automatic expiry.

**Correction:** signal selection requires a positive expiry strictly later than now; scanner output excludes missing-expiry signals; initial-entry and add-on execution refuse missing, zero, negative or expired expiry before any exchange mutation. Test signal factories now supply explicit future expiry, and regression tests cover missing-expiry rejection for both LONG and SHORT and both initial/add-on entry paths.


### 34. Legacy armed conditional entries with missing expiry could remain live indefinitely — corrected

Last-mile entry creation now rejects missing expiry, but an already-persisted/legacy pending entry with `entry_expires_at_ms=0` was not cancelled during reconciliation because the expiry branch treated zero as “no expiry.” The exchange-side conditional order could therefore remain live after the local signal's validity was unknowable. The Python add-on reconciliation path had the same condition; Android's native initial-entry reconciliation also skipped zero expiry.

**Correction:** an active conditional entry/add-on with missing, zero or negative expiry is now treated as invalid and sent through the existing cancellation-and-authoritative-verification path. Android native initial-entry recovery applies the same fail-closed rule. Added a regression test for a pending Python entry with missing expiry. Cancellation failure still leaves the campaign locked for reconciliation; it is never treated as a successful cancel.


### 35. The indicator/diagnostic WM2 fields still imposed the removed fractal dependency — corrected

After correcting the Futures signal extractor, `strategy.calculate_indicators` still defined `long_super_ao_signal` and `short_super_ao_signal` as Super AO AND the prior bar's fractal-outside flag. That left indicator diagnostics, legacy signal counts and campaign signal extraction with contradictory WM2 semantics.

**Correction:** the WM2 indicator flags now identify only the third consecutive same-colour AO bar (`ao_green_streak == super_ao_bars` / mirrored red streak), independent of fractal flags. Added monotonic LONG/SHORT regression vectors where no separate fractal-outside flag is required.


### 36. Conservative risk defaults corrected; UTC stop-count guard remains open

The shared config and Futures runtime previously defaulted to 0.50% per-entry risk, 3% daily equity loss and five simultaneous campaigns. These defaults were not aligned with the project's conservative target profile.

**Correction on this branch:** defaults are now 0.25% per-entry risk, 1% daily equity loss, one open campaign, and a 0.60% configurable campaign risk ceiling. The configured consecutive-loss default is two. Regression tests cover defaults and explicit environment overrides.

**Not fixed by changing defaults:** the Futures runtime still needs an idempotent, durable count of confirmed protective-stop exits per UTC day. `max_consecutive_losses` and `cooldown_minutes` exist in shared configuration but are not yet wired into Futures entry admission. Daily percentage loss, consecutive losses, and a maximum number of full stop-outs are different controls and must not be treated as interchangeable. This remains a P1 release blocker.

### 37. Final-chain disposition after the third-pass review

- **Signal detection to conditional order:** source path mapped; exact Python/Android parity remains unproven.
- **Entry to protective stop:** strict exchange-side verification exists in Python; native Android has unresolved aggregation gaps for partial/racing exits.
- **Position management to exit:** Python uses H1 closed bars, structural reversal and monotonic trailing. This is the current implemented policy, not proof of complete book/course exit-rule parity.
- **Exit to accounting:** Python requires authoritative fill and fee reconciliation. Native unified closed-trade history integration remains open.
- **Risk lockouts:** 1% daily-equity guard is the default after this pass. Two-confirmed-stop-outs/day, configured consecutive-loss lockout and cooldown are still not implemented in the Futures scan gate.
- **Verification:** Python tests passed on the pre-config-change head; Android build/unit tests were still running when checked. New risk-default tests and all workflows must pass on the final exact SHA.

**Release status remains NOT READY FOR LIVE TRADING.** No real exchange orders were sent.


## Supplement — signal confirmation chronology and full signal lifetime

### Defect found

The fractal detector already returned both the fractal-center index and the later confirmation index. However, the emitted `SignalSpec.signal_bar_time_ms` was based on the center candle, and both scanner ordering and initial-signal selection used that timestamp. A fractal cannot be known until its right-side confirmation candles close. Sorting by the center candle could therefore make a late-confirmed fractal appear earlier than a different signal that actually became actionable first.

The expiry timer was also based on the center/signal candle's open time. Since a signal is only actionable after its candle closes (and a fractal only after its confirmation candle closes), this shortened the configured pending lifetime.

### Correction applied in this audit

- Added `SignalSpec.confirmation_time_ms`, retaining `signal_bar_time_ms` for the source candle and stable signal identity.
- WM1/WM2 record the source signal candle as their confirmation candle.
- WM3 records the actual confirmation candle separately from the fractal center.
- Scanner sorting and `CampaignEngine.choose_initial_signal` use confirmation chronology, not the source candle's historical timestamp.
- `signal_spec_from_dict` restores the confirmation timestamp during runtime reconstruction.
- Pending expiry is anchored after the confirmation candle and grants the configured number of full subsequent bars.
- Regression tests assert WM3 source time, confirmation time, and expiry, plus SHORT WM1 timestamp/lifetime.

### Revalidation required

These changes were committed after the previous green CI run. The latest exact SHA must pass Python tests, Campaign CI, and Android build/static checks before this correction can be considered verified. Demo order lifecycle remains untested. The overall release status remains **NOT READY FOR LIVE TRADING**.


## Supplement — native Android Futures signal path

### Defect found: native WM2 was not equivalent to the Python WM2 contract

The Android detector required an already-confirmed fractal before it could emit Super AO and inspected only three AO values. Three consecutive same-colour AO histogram bars require three consecutive AO changes, i.e. four AO values. This implementation could miss valid WM2 signals and tied a supposedly independent Wise-Man signal to WM3.

### Correction applied

- Native WM2 no longer requires a fractal input.
- The colour rule is now a small independently tested function requiring four finite AO values and three consecutive rises (LONG) or falls (SHORT).
- Native signal records distinguish source-bar time from confirmation time. WM3 confirmation is the open time of the second right-side bar; scanner selection and pending expiry use confirmation chronology.
- Native entry permission now checks both operative Alligator direction and the structural parent direction, rather than treating a trigger as sufficient permission.

### Remaining Python/Android strategy mismatch

The Android implementation still builds WM1 reversal and WM3 fractal candidates only inside the `configLong/configShort` branches, which require directional Alligator plus AO/AC sign agreement; its reversal detector also adds an AO/AC momentum-improving condition. Python signal extraction does not apply those same gates inside the WM1/WM2/WM3 detectors and leaves context admission downstream. This is a material behavior difference, not a mere implementation detail. It must be resolved by an explicit, source-audited strategy contract and cross-runtime parity tests before treating Android and Python as the same strategy. This audit does not silently remove the Android gates without validating their intended role.

### Revalidation required

The native changes and new Kotlin unit tests were committed after the previous green Campaign CI run. The latest exact SHA must pass Python/Campaign CI and Android unit/build checks. Demo LONG/SHORT lifecycles remain outstanding.

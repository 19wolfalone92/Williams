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

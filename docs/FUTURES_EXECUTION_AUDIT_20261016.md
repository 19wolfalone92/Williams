# WILLIAMS BOT 2.1 — P0 Execution Audit (2026-10-16)

## Scope, source of truth and safety boundary

Incremental code-level audit following the verified Futures development head
`f2e4548b6b0d7d14337983d331fe5e59ee804a85` (PR #71).
The repository's **default main HEAD** was `04da5f9c09572e99ff310be3a1908179165e528c`
at the start of this audit and is NOT the active implementation baseline.
This PR is stacked on PR #71, not on the old main. Never apply these Futures
patches to old Spot-oriented main in isolation.

**No exchange mutation was attempted by this audit.** Binance USD-M Futures
**Testnet** only. The compiled app must not be called trading-ready without
full-device trade evidence. Ten approved USDT futures instruments, LONG
and SHORT, and explicit trade-execution safety gates remain nonnegotiable.

## Verified architecture / dependency chain

```text
Binance public Futures REST + kline/book WebSockets
  -> BinanceUsdMFuturesMarketData.kt
  -> Android StandaloneRuntime.kt
       -> SQLite MarketHistoryStore and closed-bar cache
       -> D1/H4/H1/M15 wave evidence, Alligator/AO/AC, TC2 WM1/WM2/WM3
       -> signal provenance, context ranking and risk reservation
       -> serialized Futures Execution Door
       -> NativeFuturesCampaignCoordinator.kt (durable intent journal,
          PRE-SUBMISSION risk checks, exchange ownership/protection,
          cancellation, reconciliation)
       -> BinanceUsdMFuturesTestnetAdapter.kt
       -> Binance USD-M /fapi/v* endpoints
       -> authenticated account/positions/order/algo-order readback
       -> FuturesExecutionStore.kt (persistent SQLite)
       -> operator chart, USDT balance, status and diagnostics

Python strategy.py + williams_signals.py + wave_engine.py
  -> market_scanner.py + futures_williams_runtime.py
  -> separate Python execution / campaign implementation
  -> NOT mechanically identical to Kotlin native Android execution.
```

The native Android runtime is the authoritative device execution pathway.
Python is a reference/alternative implementation requiring explicit parity
tests before its signals can be declared equivalent on identical candles.

## Confirmed faults, first failing condition and correction

| ID | Priority | File / location | Root cause and reproducer | Actual correction |
|---|---|---|---|---|
| FI-P0-01 | P0 | `app/src/main/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt`, `contractPrice` | A syntactically valid but wrong-symbol, expired, future-dated or missing-time Binance ticker with numeric price was accepted as the last traded CONTRACT_PRICE. Can misclassify conditional STOP entry and opposing exit triggers. | New `parseFreshContractTickerPrice`: exact symbol match, finite positive price, required exchange event timestamp, max 60-second age, 5-second future tolerance; synchronized exchange clock. Mock tests cover each case. |
| FI-P0-02 | P0 | `NativeFuturesCampaignCoordinator.kt`, `cancelPendingEntries` | Adapter/SQLite/positions preflight executed **before** guarded `try`: network exception could leave READY/ENTRY_PENDING despite failed cancellation. | Guard all preflight reads, reject malformed/nonfinite positions and persist state RECONCILE_REQUIRED on failure. |
| FI-P0-03 | P0 | `StandaloneRuntime.kt`, `cancelPendingEntriesOutsideWilliamsSession` and native cancellation monitor | An unconfirmed cancel or invalidation monitoring failure could be logged without a persistent safety latch. Outstanding Binance conditional orders can still trigger. | Use `setReconcileRequired` for unsuccessful cancellation/validation; stops additional risky entry admission without pretending pending Binance order disappeared. |
| FI-P0-04 | P0 | `NativeFuturesCampaignCoordinator.kt`, `validatePlan` | Signal source/type/time did not have a final executor-side LONG/SHORT validation. A malformed caller could pass a SHORT signal into a LONG order. | Validate exact TC2 family, side equality and positive signal time (unless both omitted for explicit forced mode). Mirrored Kotlin unit tests. |
| FI-P0-05 | P0 analysis integrity | `wave_engine.py`, `_drop_unfinished` | If `close_time` absent, all candles—including current forming and future bars—were used. Malformed close time was silently ignored and only last future candle could be stripped. | Infer close from timezone-aware UTC candle-open index and exact interval, reject ambiguous input, drop ALL nonclosed rows, handle calendar month properly. Python regression tests for future bars/malformed dates/1M. |

## Verification and executable scenarios

- `app/src/test/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapterContractTest.kt`:
  mocked good vs wrong-symbol/stale/missing/future ticker; never calls Binance.
- `app/src/test/java/com/williamsbot/WilliamsTc2RulesTest.kt`:
  LONG/SHORT provenance and family checks.
- `test_wave_engine.py`: closed-time causal filtering including multiple
  future rows, malformed timestamps, no close time and calendar month.
- `test_android_remote_only.py`: static integration source contracts for
  cancellation preflight and safety latches. **These static assertions do not
  simulate real Android process death or actual exchange readback**.
- Exact-head GitHub Actions Python, Kotlin and APK CI must pass after every
  final commit. An unsigned Release APK is not an installable signed release.

## Open non-negotiable P0 blockers; not hidden or claimed fixed

1. Native Android must implement and prove safe **add-ons to already-owned,
   exchange-protected** campaigns and risk-budgeted reverse pyramid execution,
   with partial fills and stop coverage. The 1:5:4:3:2 calculator alone is NOT
   a completed add-on execution path.
2. Every bar/image case from Trading Chaos 1, New Trading Dimensions, Trading
   Chaos 2 and Trading Chaos Course must be reduced to causal invariants and
   adversarial candle fixtures; no screenshot pixel matching. Distinguish
   edition-specific rules from ATR or other engineering overlays.
3. Full alternative wave counts, degrees and nested timeframes are only
   *hypotheses*. An uncertain W1–W5/ABC state must not be treated as certainty
   or live-order permission.
4. Native/ Python exact candle-to-signal-to-order parity has not been
   established for WM1/WM2/WM3, advanced fracs, stops, pyramiding or exits.
5. The following exchange cases require real Testnet E2E proof:
   `SUBMISSION_UNKNOWN`, `CANCEL_SUBMISSION_UNKNOWN`, partial fills,
   delayed user stream and listen-key rollover, stop replacement, restart,
   account risk baseline restore, kill switch, availableMargin/USDT updates,
   no duplicate orders after process death. All ten contracts and both
   directions must be exercised (with separate user authorization).
6. Autonomous Android operation with screen off / Doze / process death and
   signed installable APK has not been proven by backend unit/CI builds.

**Acceptance status: P0 outstanding / TESTNET DEVICE ACCEPTANCE NOT PROVEN.**

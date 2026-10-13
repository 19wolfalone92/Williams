# WILLIAMS BOT 2.1 — Integrated Source-to-Code Correction Log

**Status:** IN PROGRESS — NOT APPROVED FOR LIVE EXECUTION  
**Branch:** `audit/williams-2.1-task-009-integration`  
**Scope:** corrective changes made during the current source-to-code pass. This is not a claim of 100% book fidelity or passing CI.

## 1. Corrections committed

### Execution admission / fail-closed persistence
- `execution_barrier.py`: durable `SUBMITTING` persistence raises `ExecutionPersistenceError` before the submit callback when the store is unavailable or rejects the write. Added `tests/test_execution_persistence_fail_closed.py` to prove the submit callback is not invoked on persistence failure.
- `execution_barrier.py`: campaign entry/add-on purposes require non-empty versioned context and operative timeframe direction permission at the final barrier. The campaign-specific pre-submit callback is supplementary, not a substitute for admission context.
- `market_scanner.py`, `portfolio_controller.py`, `portfolio_trader.py`, and `server.py`: wired the shared context cache into signal generation. Signal specs now carry a complete D1/H4/H1 plus configured signal-timeframe version vector; partial/missing context yields an empty vector and is blocked at the final barrier.
- Added `tests/test_campaign_entry_context_gate.py` and `tests/test_scanner_context_version_vector.py`.
- **Operational consequence:** new campaign exposure is blocked until the complete context vector and operative permission are present. This is intentional fail-closed behavior, not a reason to add a compatibility bypass.

### Fractal formation and causal timing
- `strategy.py`: introduced equal-extreme group handling and explicit pivot/confirmation indices. Equal highs/lows do not count as strictly lower/higher bars; confirmation is placed on the bar that completes the required qualifying right side.
- `williams_signals.py`: WM3 now consumes explicit confirmation and center indices instead of assuming confirmation is always pivot + 2.
- `tests/test_fractal_equal_extremes.py`: regression cases for delayed confirmation and an unconfirmed equal-extreme group.

**Important limitation:** the exact source language/illustration must still be checked against every edge case (overlapping equal-extreme groups, outside bars, missing candles, and confirmation age). The tests added here have not yet been executed by a confirmed CI run.

### Independent Wise-Man events
- `strategy.py`: removed the legacy same-candle “minimum N of 3” quorum from `long_signal`/`short_signal`. A valid reversal, Super AO, or fractal-breakout event can independently be the presenting Wise-Man event, subject to operative Alligator context and downstream risk/execution gates.
- Super AO is now a one-shot event on the configured Nth same-direction AO bar rather than a persistent true state after the threshold. `williams_signals.py` uses the same `SUPER_AO_BARS` threshold; regression tests were added but have not been executed by a confirmed CI run.
- Removed a redundant stacked `@staticmethod` decorator in `campaign_engine.py`.
- Android's WM2 scanner no longer requires a prior WM3 fractal to emit Super AO, correcting a direct Python/Kotlin strategy disagreement.
- Android's central signed-request path now rejects BUY-side order mutations whenever `executionReady()` is false. Because the durable mutation contract remains unverified, this keeps all new BUY exposure blocked even if a legacy call path bypasses campaign admission; SELL exits/protection and DELETE cancellation paths are not blocked by this specific backstop.
- Rechecked the version contract in this branch: `ci_release_gate.py`, `server.py`, and `app/build.gradle.kts` all currently declare `4.25.0`; the older 4.24.0 mismatch in a prior audit snapshot is not present in this branch snapshot.
- The legacy count remains diagnostic/ranking data; it is no longer the entry Boolean's same-bar prerequisite.

**Important limitation:** source-specific differences between TC1, NTD, and TC2 still require profile separation. This change does not assert that every historical signal from every edition should be enabled simultaneously.

### Williams Core risk/exit policy
- `risk_engine.py` and `trading_config.py`: defaults restored to the user's explicitly requested 0.50% risk per trade and 3.00% daily-loss limit. These are project policy overlays, not Bill Williams source rules.
- `campaign_engine.py`: aligned actual campaign admission with that 0.50% initial-risk request: campaign cap is 0.60%, default initial allocation is 5/6 of that cap (0.50%), leaving no more than 0.10% of equity for later add-ons before other portfolio constraints. Added `tests/test_campaign_risk_defaults.py`.
- `market_scanner.py`, `portfolio_trader.py`, and `trading_config.py`: removed the silent exchange-wide universe expansion. `ALL/AUTO/*` now resolves to the fixed allowlist `BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT`; explicit symbols outside that allowlist are filtered, and an invalid empty selection does not fall back to every Spot pair. Added `tests/test_spot_universe_allowlist.py`.
- `trading_config.py` and `.env.example`: default decision timeframe is now H1; the default structural chain is D1/H4/H1/M15, while M5 is reserved for replay/diagnostics. `WAVE_FULL_TF_ALL` is no longer enabled by default.
- Updated `ci_release_gate.py` to check the fixed universe and H1 policy instead of requiring the previous exchange-wide discovery configuration.
- A missing or directionally invalid structural invalidation stop now blocks admission; it no longer silently substitutes an ATR stop.
- Fixed take-profit is no longer generated by default. It can be enabled explicitly as a legacy/engineering overlay via `target_atr_multiplier`; Williams Core returns no fixed target and does not score R:R as if a target existed.
- `tests/test_williams_core_risk_policy.py`: regression cases for restored 0.50%/3.00% defaults, required structural stop, no default fixed target, directionally invalid stop, and explicit legacy target.
- `strategy.py` and `williams_signals.py`: Super AO is a one-shot event on the configured Nth same-direction AO bar, not a persistent true state after the threshold. Added `tests/test_super_ao_event_semantics.py` and `tests/test_configurable_super_ao.py`.

**Important limitation:** these defaults do not prove the production caller passes the same profile, nor do they replace portfolio/campaign aggregation. Call-site and actual sizing payloads still need runtime evidence.

## 2. Source-to-code status

| Topic | Current status | What is established / unresolved |
|---|---|---|
| Alligator | PARTIAL | Python computes median-price SMMA and displaced lines. Android also has its own implementation. Identical initialization, shift semantics, closed-candle boundaries, and outputs across all consumers remain unproven. |
| WM1 reversal bar | PARTIAL | The reversal-bar predicate is explicit. `_angulation()` is clearly labeled an engineering approximation; no exact author-certified numerical formula is claimed. Do not treat this approximation as a fully verified source rule. |
| WM2 Super AO | PARTIAL | Detection is independent of WM3; same-bar quorum is removed; event fires only on the configured Nth AO bar in both indicator and signal extraction. End-to-end campaign/order behavior and CI remain unverified. |
| WM3 fractal | PARTIAL | Equal-extreme grouping and dynamic confirmation indices have been implemented. All consumers, backtest replay, wave pivots, persistence, and Android still require cross-path parity proof. |
| Three Wise Men chronology | PARTIAL | Signals are no longer required to coincide on one candle. First-presenting signal vs later add-on must still be demonstrated through campaign state and live order intent. |
| Stop / exit | PARTIAL | Risk engine now requires structural invalidation and no longer invents a fixed target by default. Python campaign and Android stop/exit implementations still need mode-by-mode parity. |
| Platform parity | UNVERIFIED | Android WM2 no longer depends on WM3, but Android fractals still use strict five-bar inequalities while Python handles equal-extreme groups; no same-vector golden replay evidence or Android build/test proof has been produced. |
| Execution durability | BLOCKED | Python's final barrier fails closed if durable `SUBMITTING` intent cannot be persisted, and new campaign exposure requires versioned context/permission. Android still contains `androidDurableMutationContractVerified = false`; its central signed-request backstop blocks BUY-side mutations while execution is not ready. These code changes are not a substitute for executing fault-injection/recovery tests or proving all mutation paths. |
| CI / build | FAILING — CORRECTION IN PROGRESS | The latest inspected Python and Android workflows failed before unit/build execution because `ci_release_gate.py` still required the old dynamic-universe setting `SCAN_ALL_USDT=true`. The gate has now been updated to the fixed allowlist/H1 contract; this correction has not yet been verified by a new workflow on the final SHA. No claim of passing tests/build is made. |

## 3. Non-negotiable safety interpretation

- No entry or add-on from an unfinished candle.
- No use of a fractal pivot before its confirmation time.
- No automatic market entry after a conditional trigger has been missed.
- No blind replay of an ambiguous exchange mutation.
- No new exposure when structural stop, context, risk reservation, durable intent, or exchange state is unknown.
- Exits, protection, and recovery remain separate risk-reducing operations and must not be accidentally blocked by entry-only context rules.
- Android autonomous execution remains blocked until its own mutation/recovery contract is verified.

## 4. Evidence labels

- **SOURCE-CONFIRMED:** directly traced to an accessible book passage/figure.
- **CODE-CONFIRMED:** code is present at a specific commit.
- **BEHAVIOUR-CONFIRMED:** test/replay/fault-injection output proves behavior.
- **ENGINEERING OVERLAY:** a project policy, not an author rule.
- **UNRESOLVED:** evidence does not justify a deterministic implementation yet.

A green test suite alone is not source fidelity. Conversely, source interpretation alone is not proof that a live execution path obeys the rule. The strategy is not approved until both are traced end-to-end.

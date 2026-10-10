# WILLIAMS BOT 2.0 — CORRECTION IMPLEMENTATION REPORT
Status: NOT APPROVED FOR PRODUCTION
Audit target inspected: public repository 19wolfalone92/Williams, branch main at tree SHA 3fe008d85c06c90ed00d8035484b22aae657317b.
Correction branch: audit/williams-entry-contract-v1
Scope of actual change in this branch: adds docs/ENTRY_ENGINE_FORMAL_CONTRACT_v1.md. No existing runtime source was modified in this first controlled patch. No tests are claimed as executed.

## 1. Evidence and scope
Inspected repository tree and source text for:
- strategy.py
- williams_signals.py
- pending_signal.py
- market_context.py
- execution_barrier.py
- test_signal_conditions.py
- test_production_architecture.py
- repository README and CI/test layout

The formal architecture correction directive named by the user was not located as a standalone file in the project Library or repository during this inspection. The complete approved module handoffs likewise were not available as one versioned package. Therefore source-specific decisions that depend on those documents remain UNRESOLVED.

## 2. Confirmed defects / specification gaps

### C-W001 — Fractal pivot time vs availability time is not consistently represented
Severity: HIGH (causality defect if any consumer reads pivot-row flags as actionable)
Module: strategy.py / williams_signals.py
Evidence: strategy.py creates fractal_up/fractal_down flags on the pivot row using right-side bars, then separately creates confirmed_up_level/confirmed_down_level on a later row. This split can be safe only if every consumer uses the confirmed series and never the pivot-row flags for a decision. The schema itself does not encode pivot_time and available_at as separate typed fields.
Root cause: chart/formation annotation and decision availability coexist in the same dataframe without a type-level guard.
Impact: a future consumer can accidentally read a retrospectively marked pivot as a real-time event.
Correction: contract added: pivot time != availability time; decision engine must use only confirmation event available after both right-side bars close.
Regression test required: causal cutoff test and streaming-vs-batch equality test. NOT RUN.

### C-W002 — Angulation is explicitly an engineering approximation, not a canonical Williams predicate
Severity: HIGH
Module: williams_signals.py
Evidence: module docstring states angulation is an engineering approximation; _angulation uses windowed price-to-Jaw separation, positive delta and regression slope.
Root cause: qualitative source rule was made executable using a heuristic without a source-backed approved formula/threshold.
Impact: the bot may reject/accept setups differently from the approved Williams source.
Correction: contract labels this UNRESOLVED and forbids treating approximation as author-certified Williams logic. No strategy predicate changed in this patch.
Regression test required: source-approved reference examples after the exact formula is approved. NOT RUN.

### C-W003 — Signal expiry defaults introduce unapproved numerical semantics unless explicitly ratified
Severity: HIGH
Module: pending_signal.py / williams_signals.py
Evidence: default_expiry_ms uses environment defaults of 2 bars for REVERSAL, 2 for SUPER_AO and 8 for FRACTAL; williams_signals.py also assigns corresponding expires_at_ms defaults.
Root cause: expiry policy is executable and numerical, but approved handoff/source proving these exact values was not available in the inspected package.
Impact: stale signal cancellation and valid signal lifetime are implementation-dependent.
Correction: contract records expiry-by-type as UNRESOLVED pending approved source. Existing behavior is not silently changed.
Regression test required: expiry boundary tests after policy approval. NOT RUN.

### C-W004 — Opposing-signal replacement policy is not fully specified by the local helper
Severity: HIGH
Module: pending_signal.py
Evidence: should_replace returns True for symbol/side mismatch, but same-side replacement depends on newer signal_bar_time_ms and price delta; it does not itself define a complete atomic arbitration of a set of simultaneous signals.
Root cause: a pairwise replacement helper is not a complete conflict-resolution contract.
Impact: callers may resolve a set of competing signals in iteration-order-dependent ways.
Correction: contract specifies fail-closed behavior for incompatible simultaneous signals until a source-backed priority policy is approved. This is explicitly a system-safety fallback, not a Williams rule. Runtime integration not performed.
Regression test required: permutation-invariance tests across all signal orderings. NOT RUN.

### C-W005 — Persistence helper failures may be swallowed at execution boundary
Severity: CRITICAL risk requiring implementation-level verification
Module: execution_barrier.py
Evidence: _persist wraps db.save_execution_intent in try/except Exception: pass; execution-event persistence is similarly best-effort. Whether this can occur before exchange submission depends on the full execute path and DB contract.
Root cause: persistence error can be converted into silent continuation if caller does not separately fail closed.
Impact: a network side effect may occur without a durable intent/recovery record, risking duplicate submission after crash.
Correction: required contract: failure to persist the canonical intent before side effect must block submission; timeout/unknown state must reconcile by client order ID before retry. No runtime change made in this patch.
Regression test required: injected DB write failure must result in zero exchange submit calls. NOT RUN.

### C-W006 — Closed-bar and availability semantics are not enforced by the event schema itself
Severity: HIGH
Module: market data / strategy / context
Evidence: code paths use dataframes and timestamps; reviewed SignalSpec call sites carry signal_bar_time_ms and source_candle_index, but no universal typed availability_at/closed flag is enforced across all observations.
Root cause: causal requirements are spread across callers and data preparation rather than guaranteed by a shared contract.
Impact: H1/M15 synchronization and live/backtest parity cannot be proven by types/schema alone.
Correction: contract requires event_time, available_at, closed flag, source bar timestamps, and single Decision TF enforcement. Runtime enforcement remains unimplemented.
Regression test required: unfinished H1 event cannot authorize signal; causal cutoff property. NOT RUN.

### C-W007 — Entry-class semantic differences are partially embedded in heuristics/defaults
Severity: HIGH
Module: williams_signals.py / pending_signal.py
Evidence: reversal, Super AO and fractal signals use different trigger construction, expiry defaults and validation paths; some criteria are documented as approximations.
Root cause: no single approved entry-class contract mapping source rule -> setup -> confirmation -> trigger -> expiry -> invalidation was available.
Impact: inconsistent semantics and potential false/late entries.
Correction: formal contract introduced; exact source-specific predicates remain UNRESOLVED rather than guessed.
Regression test required: golden tests per approved entry class. NOT RUN.

## 3. Previous audit issues mapped to current evidence

- BUG-W001 fractal future dependency: PARTIALLY CONFIRMED risk; confirmed-level series is delayed, but unsafe pivot-row marker remains present and schema does not encode availability.
- BUG-W002 Alligator snapshot semantics: UNRESOLVED; code comments indicate current Teeth/trigger-time checks in fractal path, but no approved entry-class policy was found.
- BUG-W003 setup vs trigger: PARTIALLY ADDRESSED in williams_signals.py (conditional trigger prices are constructed); full Signal/Trigger/Authorization separation is not proven end-to-end.
- BUG-W004 stale pending signals: PARTIALLY ADDRESSED with expiry/replacement helper; numerical expiry/supersession semantics are not source-verified.
- BUG-W005 duplicate orders: PARTIALLY ADDRESSED by signal deduplication and repository documentation of clientOrderId, but end-to-end atomic trigger consumption is not proven.
- BUG-W006 trigger after invalidation: UNRESOLVED; no proof found of a versioned parent-signal check across every submit path.
- BUG-W007 AO/AC overload: PARTIALLY ADDRESSED in comments, but each entry class still needs an approved predicate contract.
- BUG-W008 Alligator fractal filter: implementation contains Teeth checks; exact snapshot timing remains UNRESOLVED.
- BUG-W009 repaint/future values: PARTIALLY ADDRESSED by confirmed-level series; type-level causality not proven.
- BUG-W010 unfinished H1 use: UNRESOLVED end-to-end.
- BUG-W011 conflict resolver: NOT VERIFIED; pairwise replacement is not a full deterministic arbitration contract.
- BUG-W012 context authorization: execution barrier has context permission checks for ENTRY, but all order paths/callers are not exhaustively verified.
- BUG-W013 reversal vs fractal timing: PARTIALLY represented differently; universal causal schema absent.
- BUG-W014 angulation: CONFIRMED specification gap; approximation exists.
- BUG-W015 distance/far-outside criterion: UNRESOLVED; thresholds and meaning need source-backed approval.
- BUG-W016 simultaneous intrabar crossings: UNRESOLVED in strategy-wide policy.
- BUG-W017 entry/stop same candle: UNRESOLVED in shared live/backtest policy.
- BUG-W018 expiry: code exists but exact values are unverified.
- BUG-W019 repeated reversal trigger: NOT VERIFIED end-to-end.
- BUG-W020 Single Execution Door: execution_barrier exists; direct-call exclusion across the full codebase not yet proven.
- BUG-W021 recovery duplicate intent: PARTIALLY ADDRESSED in docs/client IDs, durability failure path still needs tests.
- BUG-W022 local/exchange divergence: recovery support exists in repository, but complete fault-injection verification not performed.
- BUG-W023 realistic backtest: current backtester.py in the separate Williams-wolf repository is a simplified OHLC engine; it is not proof of same live decision logic.
- BUG-W024 trace contamination: trace separation not proven across all modules.
- BUG-W025 Williams rule vs engineering rule: CONFIRMED documentation risk where angulation approximation is described; contract now explicitly separates them.
- BUG-W026 ambiguous valid: formal contract bans a shared ambiguous valid boolean; runtime schemas not changed.
- BUG-W027 mutable confirmation: UNRESOLVED end-to-end.
- BUG-W028 timestamps: schema gap confirmed.
- BUG-W029 clock authority: UNRESOLVED.

## 4. Changes actually made
- Added docs/ENTRY_ENGINE_FORMAL_CONTRACT_v1.md on branch audit/williams-entry-contract-v1.
- It formalizes causal timing, fractal availability, signal lifecycle, trigger event semantics, fail-closed conflict handling, execution boundary, recovery, trace separation, and live/backtest differential acceptance.
- It explicitly lists source-dependent questions as UNRESOLVED.
- No Williams Core rules were changed.
- No runtime code was changed.
- No test suite was run by this correction pass. No test is reported as passing.

## 5. Required next implementation steps
P0:
1. Add typed event/availability fields and prohibit pivot-row fractal flags as decision inputs.
2. Audit all submit call sites; enforce one execution door and durable intent before network side effect.
3. Make DB persistence failure fail closed.
4. Make trigger consumption atomic/idempotent across retries and restart.
5. Add deterministic conflict set resolver (fail-closed until approved priority rules).
6. Add H1 closed-bar gate to every context/decision entry point.
7. Add restart fault-injection tests for accepted-but-uncommitted exchange submissions.

P1:
1. Obtain/attach the approved Architecture Correction & Formalization Directive and complete approved module handoffs.
2. Resolve U-01 through U-12 in ENTRY_ENGINE_FORMAL_CONTRACT_v1.md from approved sources.
3. Remove or clearly quarantine heuristic angulation from production authorization until approved.
4. Define source-backed per-entry AO/AC roles, Alligator snapshot semantics, expiry, supersession, trigger buffer and intrabar policy.

P2:
1. Shared live/backtest decision engine proof.
2. Causal cutoff and permutation-invariance tests.
3. Decision/Execution/Outcome trace separation tests.
4. CI execution of Python unit/integration/recovery tests and Android tests where SDK is available.
5. Independent adversarial re-audit of the integrated implementation.

## 6. Acceptance status
Specification patch: CREATED on correction branch.
Runtime implementation: NOT DONE.
Tests: NOT RUN in this pass.
Verified requirements: NONE claimed.
Production acceptance: NOT APPROVED FOR PRODUCTION.

# ENTRY ENGINE FORMAL CONTRACT v1
Status: DRAFT / NOT APPROVED FOR PRODUCTION
Branch: audit/williams-entry-contract-v1
Scope: causal signal lifecycle and authorization boundary; does not alter Williams Core.

## 1. Normative vocabulary
- Observation: immutable market-data fact available at a stated time.
- Setup: rule-specific structure assembled from observations; not an order.
- Confirmation: a rule-specific predicate evaluated only on information available at its evaluation time.
- Signal: durable proposal with immutable identity and explicit source/setup references.
- Trigger: timestamped market event satisfying a signal's price/time predicate.
- OrderAuthorization: one-time result of all strategy, risk, position, freshness and execution gates.
- OrderIntent: durable, idempotent request to the execution boundary.
- Position: exchange-reconciled exposure; never inferred from Signal/Trigger/Intent state alone.

These types MUST NOT be represented by a shared boolean or a single mutable "valid" field.

## 2. Causal time contract
Every candle/observation must carry:
- event_time (exchange/source timestamp);
- available_at (when this process could first observe it);
- interval and symbol;
- closed (whether the source candle is complete);
- source/version identity.

A decision at time T may consume only records with available_at <= T. Historical pivot time is not signal availability time.

### Fractal
A five-bar fractal candidate centered at bar i requires the two right-side bars. It MUST NOT be available before the second right-side bar is closed and available. The pivot timestamp and confirmation/availability timestamp MUST be separate.
A display marker may be attached to the pivot bar for charting only; it MUST NOT be consumed by the decision engine before availability_at.
Ties/equality follow the approved source rule; implementation must not silently change strictness.

### Higher timeframe
An unfinished H1 candle MUST NOT be treated as a completed H1 observation. If H1 is the canonical Decision Timeframe, only the latest closed H1 snapshot can authorize a new decision unless a separately approved contract explicitly defines intrabar H1 semantics. M15 may provide trigger/fill timing only; it cannot create an independent setup.

### Displacement
For shifted Alligator lines, calculation index, plotted/display index and decision-availability index are distinct. A forward display shift must never make a future value available to an earlier decision.

## 3. Signal lifecycle
Permitted conceptual states:
CANDIDATE -> CONFIRMED -> ACTIVE -> TRIGGERED -> CONSUMED
Terminal alternatives: INVALIDATED, SUPERSEDED, EXPIRED, CANCELLED, REJECTED.
Only transitions with recorded cause/time are permitted. Terminal states cannot return to ACTIVE. Replays of the same event are idempotent.

State semantics:
- CANDIDATE: insufficient right-side evidence; never actionable.
- CONFIRMED: formation criteria known; not necessarily trade-valid.
- ACTIVE: all required rule-specific validation passed and trigger is armed.
- TRIGGERED: the trigger event occurred while the signal was ACTIVE and not expired/invalidated.
- CONSUMED: the trigger has been atomically claimed by one canonical OrderIntent.
- INVALIDATED/SUPERSEDED/EXPIRED/CANCELLED/REJECTED: cannot authorize an order.

A state named ACTIVE does not mean trigger fired. A state named TRIGGERED does not mean order accepted or filled.

## 4. Trigger and authorization
Trigger MUST be an event, not a persistent boolean. Each trigger has trigger_id, signal_id, event_time, available_at, observed price/event payload, and consumed status.
One trigger may create at most one canonical OrderIntent. The unique idempotency key must survive restart and retries and be persisted before network submission.
Before authorization, re-check signal state/version, signal expiry, trigger identity, required closed-bar context versions, risk result, position limits, session/market status, and execution readiness. Missing/unknown required evidence fails closed.
Risk denial means no OrderIntent is submitted. Trigger/authorization/order/position state remain distinct.

## 5. Conflicts and supersession
No Williams priority among conflicting signal classes is inferred by this contract.
Until a source-backed arbitration rule is approved, simultaneous incompatible signals in the same decision cycle MUST fail closed: no new entry OrderIntent is authorized for that conflict set. This is a system safety policy, not a Williams trading rule.
The policy for same-side replacement, opposing fractal supersession, dynamic Alligator revalidation and max signal age remains UNRESOLVED unless an approved source-specific contract specifies it. Do not silently infer priority or expiry.

## 6. Alligator, AO/AC, reversal and angulation
Alligator values used for validation must include the snapshot/availability time and the precise line semantics. The contract does not decide whether every setup uses formation-time snapshot or trigger-time revalidation; that choice is UNRESOLVED per entry class.
AO and AC are evidence fields whose meaning is entry-class-specific. They are not generic BUY/SELL triggers. Each approved entry class must specify required AO/AC predicate, evaluation time and whether it is mandatory, optional or disqualifying. Missing rule = UNRESOLVED; no guessed default.
Reversal-bar formation and trigger are separate events. Trigger price/buffer and whether the source requires a tick offset must come from an approved strategy rule.
Angulation and "far/significantly outside the Alligator" are not executable until an approved source supplies a reproducible criterion. Any existing approximation must be labelled engineering heuristic and must not be represented as author-certified Williams logic or production-authorized logic.

## 7. Intrabar and execution policy
If OHLC data permits both stop and target, or opposing trigger levels, to be crossed in one candle, ordering is unknowable from OHLC alone. Use tick/lower-timeframe event data or a declared conservative policy approved for the strategy; otherwise mark the case AMBIGUOUS and exclude it from claims of verified performance.
All order mutation routes (entry, exit, stop, retry, recovery, reversal) must pass through one execution boundary. No strategy module calls the exchange order API directly.
Timeout after submission is UNKNOWN, not REJECTED. Reconcile by durable client order ID/exchange state before any retry. Do not blindly resubmit.

## 8. Recovery
Persist Signal, Trigger, OrderAuthorization and OrderIntent identities/states before side effects where possible. After restart:
1. block new entry authorization;
2. replay durable event log;
3. query exchange orders/fills/positions using bot-owned identifiers;
4. reconcile local vs exchange state;
5. resolve UNKNOWN submissions without blind retry;
6. enable trading only after reconciliation succeeds.
If local and exchange states disagree, enter RECONCILE_REQUIRED and fail closed.

## 9. Trace separation
DecisionTrace: only data available at decision time and decision predicates.
ExecutionTrace: intent, client order ID, request/response, timing, fills and retries.
OutcomeTrace: later PnL/MFE/MAE and post-trade outcome.
OutcomeTrace MUST NOT be an input to the decision engine or be joined into a historical decision as if it were known at decision time.

## 10. Live/backtest parity
The signal/decision engine must be shared. Only data adapter, clock/event source and execution adapter may differ.
A differential replay test feeds identical timestamped observations to live-mode and backtest-mode decision adapters and compares every decision/trigger/authorization event. Any difference is a failure.
Causality property: for any cutoff T, appending observations whose available_at > T cannot change decisions emitted at or before T.

## 11. Required event schema
Common fields: schema_version, event_id, event_type, symbol, decision_timeframe, event_time, available_at, source_bar_open_time, source_bar_close_time, strategy_version, signal_id (nullable only before signal creation), parent_event_id, context_snapshot_id, state_before, state_after, reason_code.
Never use one ambiguous timestamp field for pivot, availability, decision, trigger and execution.

## 12. Unresolved decisions requiring source-backed approval
U-01: canonical Decision TF in the current production configuration (handoff says single TF, but repository has multiple paths/configs).
U-02: exact entry classes enabled in production and source-backed predicates for each.
U-03: Alligator validation snapshot time per entry class.
U-04: AO/AC mandatory/optional/disqualifying roles per entry class.
U-05: angulation formula and threshold; existing approximation is not accepted as canonical Williams rule.
U-06: quantitative meaning of "far/significantly outside Alligator".
U-07: signal expiry by type; current time-based defaults must be confirmed against approved rules.
U-08: supersession rules for same-side/opposite-side signals.
U-09: trigger price buffer/tick rule for each entry class.
U-10: intrabar ambiguity policy and required data granularity.
U-11: exact risk/position/session authorization contract.
U-12: which repository/module is the canonical implementation target; do not infer that a helper module is wired into all live paths.

## 13. Acceptance gates
- No fractal decision before right-side confirmation is available.
- No unfinished H1 candle treated as closed.
- No trigger without an active signal.
- No order authorization without a fired trigger and passing risk gate.
- No duplicate OrderIntent for one trigger, including restart/retry.
- No conflicting entry authorization without explicit arbitration.
- No order API calls outside the execution boundary.
- Unknown exchange submission state blocks blind retry.
- Same input event stream yields identical live/backtest decision trace.
- Causality property above passes.
- Required unit, integration, recovery and differential tests pass in the canonical repository CI.
Until every gate is demonstrated by executed tests and approved unresolved decisions are closed, status remains NOT APPROVED FOR PRODUCTION.

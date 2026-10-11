# WILLIAMS BOT 2.1 — TASK 006.12
## Financial Writer Ownership Closure, Canonical Fill Applier & Atomic Projection Migration Plan

**Disposition: BLOCKED — NOT ACCEPTED**  
**Repository:** `19wolfalone92/Williams`  
**Branch:** `audit/williams-2.1-task-006-android-runtime`  
**PR #46:** Open / Draft / unmerged  
**Verified starting HEAD:** `c77de00a2761a0e9ff6f42af7f71b5622c68c3a2`  
**Verified base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`

## Executive decision

The branch HEAD was re-read from GitHub before work; it matched the supplied starting SHA. The source inspection confirms the key blocker from TASK 006.11: `exchange_fill_ledger` is durable fill evidence, but there is not yet one demonstrated production owner that atomically applies every required financial projection. Independent Spot lifecycle and campaign paths still exist. The current inspection does not prove an exhaustive source-to-sink graph or that every risk-increasing caller is behind one durable financial recovery contract.

**No production financial writer was modified and no applier stub or parallel ledger was introduced.** Wiring one caller to a new applier without closing the other paths would make the effect owner ambiguous. The correct outcome under the task's own stop conditions is BLOCKED.

No Mainnet/Spot Testnet trading mutation was performed. `main` was not changed; PR #46 was not merged and remains Draft.

## 1. Fresh baseline evidence

| Item | Result |
|---|---|
| Branch HEAD before changes | `c77de00a2761a0e9ff6f42af7f71b5622c68c3a2` |
| PR #46 head | `c77de00a2761a0e9ff6f42af7f71b5622c68c3a2` |
| PR state | open |
| PR draft | true |
| PR merged | false |
| PR base | `main` |
| Base/main SHA | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Previous task report | `docs/WILLIAMS_BOT_2_1_TASK_006_11_FINANCIAL_EFFECT_APPLIER.md` |

GitHub branch, PR and source files were queried through the repository connector. This is a remote-source audit; no claim is made about local uncommitted files.

## 2. Ownership model required before enabling a production path

The only acceptable ownership flow is:

```text
Binance authoritative per-trade evidence
  -> canonical validation + provenance
  -> existing exchange_fill_ledger (durable PENDING)
  -> fee-state resolution (KNOWN / UNKNOWN / INVALID / CONFLICTED)
  -> ONE Financial Effect Applier
  -> same-DB authoritative projections in one transaction
  -> durable APPLIED/completion record
  -> recovery verification and risk gate
```

All non-owner callers must be event producers or readers. They must not independently mutate the same fill's position, campaign, reserve, commission or PnL effect. If required projections live in multiple databases, atomicity cannot be claimed; a durable multi-stage protocol and crash tests are mandatory. Python and Android are separate ownership domains until independently proven otherwise.

## 3. Source-to-sink writer register (current evidence, not falsely labelled exhaustive)

The repository search was grounded in current source files and tests. This register captures confirmed roles and the unresolved reachability/ownership gaps. It does **not** claim all indirect callers, ORM/raw SQL writes, scheduled tasks, or Android call sites have been exhaustively enumerated; that gap blocks acceptance.

| Source / symbol | Evidence-backed role and sinks | Current ownership decision / blocker |
|---|---|---|
| `db.py::Database.transaction` | SQLite transaction uses `BEGIN IMMEDIATE`, commit/rollback; `check_same_thread=False`, WAL, busy timeout. | A transaction primitive, not the financial owner. Need verify every projection participating in a fill effect uses the same connection and transaction. |
| `db.py::Database.save_campaign_fill` | Writes `exchange_fill_ledger` and a compatibility `campaign_fills` row; ledger stores decimal strings and `PENDING`. Prior TASK 006.11 source audit recorded float conversion in compatibility writes. | Not a complete applier. Must be coordinated with projection writers; cannot be treated as APPLIED merely because the ledger row exists. |
| `execution_accumulator.py::accumulate_fills / accumulate_order` | Validates fill fields using `Decimal(str(value))`, rejects missing/malformed fields and aggregates trade IDs, quantity, quote and fee summaries. `ExecutionSummary` currently has zero-valued fee defaults, so completeness flags and caller checks are material. | May be used as validation/event extraction only. Not durable and not an effect owner. Audit all callers for accidental treatment of default zero as confirmed zero. |
| `portfolio_trader.py::MultiPositionTrader` and `_authoritative_execution` / entry-exit callers | Imports `accumulate_order`, `CampaignExecutionService`, `Database`, `ExecutionBarrier`; class owns durable Spot position lifecycle. REST order/history evidence and legacy writes occur in this lifecycle. | Potential independent financial writer. Exact full caller-to-sink inventory and proof of common applier/gate remain missing. |
| `trader.py::Trader` | Separate Spot trader path; imports `Database`, `MultiPositionTrader`, `accumulate_order`, `accumulate_fills`, and `ExecutionBarrier`. | Alternate production entry/execution route; must be shown to delegate all financial effects to the same owner or be safely gated. |
| `campaign_execution.py::CampaignExecutionService` | Reconciles pending campaign orders and calls campaign engine operations; execution adapter has order/stop/exit paths. Prior audit identified float quote aggregation and a `fee_quote=0.0` fallback on a path. | Conflicting writer risk. No new applier can be enabled until these writes are migrated or fail-closed blocked. |
| `campaign_engine.py::CampaignEngine` | Campaign coordinator persists lifecycle and risk reservations; fill methods include initial/add-on fill state changes (recorded in prior audit). Constructor risk limits and several inputs use floats. | Independent campaign/position/reserve mutation surface. It must become a projection writer called only by the applier, or have mutations separated from fill accounting. |
| `campaign_model.py` | Defines campaign and pending-order domain models/states; not by itself a durable effect owner. | Need enumerate persistence serializers and all methods that mutate financial values in model/state transitions. |
| `execution_barrier.py::ExecutionBarrier` | Serializes order mutation intents and readiness checks. | Order mutation barrier is not equivalent to a financial-effect barrier. Need prove every risk-increasing production path reaches a durable recovery gate before submission. |
| `execution_recovery.py::ExecutionRecoveryResolver` | Read-only resolution of ambiguous order/list intents; source states it does not retry/submit exchange mutations. | Correct scope for order-intent recovery, but not proof of fill/fee/projection recovery. |
| Tests `test_financial_integrity.py` | Covers accumulator validation, confirmed zero fee with explicit asset, and existing ledger/idempotency cases (full file includes more tests). | Test evidence is component-level; it does not prove one end-to-end production applier or every projection exactly once. |
| Tests `test_atomic_real_db.py` | Uses a temporary real SQLite file to demonstrate a successful transaction and rollback of writes on injected exception. | General DB transaction test, not fault injection across a complete financial effect. |
| Tests `test_execution_recovery.py` | Exercises order-intent recovery against a read-only fake exchange. | Does not prove durable financial projection recovery after process death. |
| Android `ExecutionAccumulator.kt`, `TradingAuditStore.kt`, `StandaloneRuntime.kt` | Separate Android-local runtime/database; prior audit observed in-memory accumulation, REAL/Double financial fields and fail-closed readiness flag. | Not atomically joined to Python. Need an Android-local owner and producer-backed recovery contract; process-death instrumentation remains NOT VERIFIED. |
| Futures execution paths | Not exhaustively source-to-sink audited for this task. | Must remain blocked; no claim of closure. |

### Additional searches still required before implementation
- All SQL write statements touching trades, campaign tables, balance/position/reserve/PnL/commission fields, protective orders, and ledger state.
- Every caller of `save_campaign_fill`, `record_initial_fill`, `record_add_on_fill`, execution reconciliation and accumulator methods.
- All production constructors, scheduler/worker loops, restart/recovery entry points, callbacks and CLI/background paths.
- Every actual BUY/add-on/retry path and the exact gate called immediately before mutation.
- Android writes and every place that can set readiness true.
- Futures mutation sinks and direct exchange-client mutation calls.

A complete graph cannot be inferred from filenames or imports alone. It requires caller and sink evidence at a pinned commit plus tests.

## 4. Canonical identity and fee/Decimal contract gaps

### Identity
The existing ledger key observed in TASK 006.11 is `(exchange, account_id, symbol, trade_id)`. It supports idempotent registration within that key scope, but the following still need explicit proof:
- account identity uniquely scopes API account and environment (e.g. Spot Testnet vs other endpoint);
- market/product domain is either part of identity or unambiguously encoded by account scope;
- raw authoritative source and schema/version are preserved;
- an identity collision with different canonical payload durably enters conflict/recovery-required state and never overwrites the original fill.

### Fee state
Required durable states are `KNOWN`, `UNKNOWN`, `INVALID`, `CONFLICTED`. Existing strict accumulator validation rejects absent commission/asset; this fails closed, but does not itself persist a valid fill with unresolved fee state. A durable PENDING fill with UNKNOWN fee must be representable without applying fee-dependent effects. A later fee resolution must post only the fee adjustment, not replay the principal quantity/cost effect.

Third-asset commission cannot be converted into quote currency without a verified conversion source, timestamp/rate policy and Decimal calculation. Zero commission is known only when the authoritative source explicitly supplies zero with a valid asset/context.

### Decimal
`execution_accumulator.py` uses Decimal for parsed values, but the entire downstream chain must remain Decimal/text/integer-scaled through authoritative projections. Legacy REAL/float projections are not exact. Converting stored REAL to Decimal later cannot recover digits already lost. No schema or historical-data migration was attempted.

## 5. Atomic projection migration plan (no mass migration performed)

1. **Freeze the contract, not the bot:** keep financial risk-increasing operations fail-closed whenever fill ownership or recovery is unresolved. Preserve safe diagnostic reads and evaluate emergency risk reduction separately.
2. **Inventory every writer and caller:** enumerate write sinks from current source and tests; attach exact caller paths and prove reachability. No production financial changes until this list is reviewed.
3. **Choose a single path:** prefer per-trade exchange-authoritative records with stable trade ID and explicit source provenance; do not treat order cumulative quantities as a substitute for fill identities where multiple fills exist.
4. **Extend existing ledger only if required:** add fee-state/provenance fields through a forward-only, idempotent migration after checking schema version 9 and existing migration patterns. Do not create a second ledger. Test interrupted migration and rerun before production enablement.
5. **Define projection boundary:** identify authoritative position/cost, commission, realized PnL, campaign and reservation tables actually used by this path. If same DB/connection, update them and ledger state in one `BEGIN IMMEDIATE` transaction. Do not write `APPLIED` until all mandatory projection changes have succeeded.
6. **Make old writers non-owners:** move their fill-related mutations behind the applier or convert them into validated evidence producers. Any not yet migrated path must fail closed for risk increases and must not independently update overlapping financial projections. Do not disable emergency SELL without a separately proven safe replacement.
7. **Add durable conflict and recovery states:** conflict payload, unknown fee, projection mismatch and uncertain commit must remain durable blockers; recovery rereads ledger and projections and determines whether to apply, finalize, or block without guessing.
8. **Fault-inject against file-backed SQLite:** kill/fail at each transaction boundary; use separate OS processes and separate connections; test lock contention and uncertain commit by reopening the database and asserting all tables, not just method results.
9. **Migrate one vertical slice:** only after old-writer gates are proven, enable one real production caller and show the complete chain from authoritative source to ledger to all required projections. Expand only after exact-SHA acceptance.
10. **Separate platform closure:** create an Android-local equivalent contract and tests; do not claim shared atomicity. Audit Futures independently and keep those sinks blocked until proven.

## 6. Required fault-injection matrix — current evidence status

| Scenario | Status | Missing proof |
|---|---|---|
| First fill through all mandatory projections | BLOCKED | No complete production applier |
| Exact duplicate | PARTIAL | Ledger identity tests do not prove all projections remain unchanged |
| Two concurrent independent processes | NOT VERIFIED | Existing threading/two-connection coverage is not OS-process proof |
| Failure before ledger write | NOT VERIFIED end-to-end | No applier-level injection |
| Failure after ledger PENDING before effect | BLOCKED | No recovery applier |
| Projection write failure | BLOCKED | No all-projection transaction boundary |
| Failure after projections before caller acknowledgement | NOT VERIFIED | No crash-point/reopen test |
| Uncertain COMMIT | NOT VERIFIED | No injected commit-outcome ambiguity test |
| Restart with PENDING | BLOCKED | No financial applier recovery routine |
| Late fee / conflicting fee sources | NOT VERIFIED | No durable fee-resolution protocol |
| Invalid Decimal / missing fee fields | PARTIAL | Accumulator has component-level fail-closed tests |
| Same identity, conflicting payload | PARTIAL | Ledger conflict does not prove all downstream state invariants |
| Duplicate REST/reconciliation delivery | NOT VERIFIED | No source-crossing end-to-end test |
| Recovery racing normal processing | NOT VERIFIED | No applier-level concurrency test |
| Legacy writer bypass attempt | BLOCKED | No common owner/gate enforced across writers |
| Increase-risk attempt with unresolved effect | BLOCKED | Gate coverage across every production entry point is not proven |
| Every projection equals exactly one effect after recovery | BLOCKED | No complete applier |

## 7. Recovery Gate status

The code has an order mutation barrier and an execution-intent resolver. These are not enough to prove that every BUY, add-on, retry-after-timeout, background path and Android entry is gated by a durable financial recovery producer.

Required contract before enabling any new risk:
- no unresolved fill/commit;
- no UNKNOWN/INVALID/CONFLICTED required fee;
- no duplicate identity conflict;
- positions, available balances, reserves and required protection reconciled;
- no required projection mismatch;
- gate's completion is derived from persisted checks, not a writable boolean alone.

Emergency risk reduction must be evaluated separately using authoritative exchange state. This report does not claim that all SELL paths should be blocked.

## 8. Evidence labels

| Label | TASK 006.12 result |
|---|---|
| CODE-CONFIRMED | Partial: existing ledger, transaction primitive, accumulator, order barrier and intent resolver inspected |
| TEST-PROVEN | Partial: existing accumulator/ledger/transaction/order-intent tests exist; no full applier tests |
| CI-PROVEN | Not accepted for a new implementation SHA; this task made no production-code changes |
| EMULATOR-PROVEN | NOT VERIFIED |
| DEVICE-PROVEN | NOT VERIFIED |
| TESTNET-OBSERVED | NOT PERFORMED; no exchange mutations |
| Exhaustive writer graph | NOT VERIFIED |
| Single effect owner | BLOCKED |
| End-to-end exactly-once | BLOCKED |

## 9. CI policy

No production-code implementation was made, so no new test suite is being represented as passed. Any CI run triggered by the report commit must be rechecked against the exact final SHA and reported only with final conclusions and job statuses. A docs-only green run does not prove financial correctness. No prior SHA run can substitute for a future implementation SHA.

## 10. Acceptance matrix

| Criterion | Status | Reason |
|---|---|---|
| Fresh GitHub baseline | PASS | Head/base/PR/main re-read |
| Exhaustive reachable source-to-sink graph | BLOCKED | Indirect callers and all direct sinks not yet fully proven |
| One canonical production applier | BLOCKED | Not implemented; unsafe to wire before ownership closure |
| No competing writer can apply same fill | BLOCKED | Independent Spot/campaign paths remain |
| Existing ledger reused, no second ledger | PASS | No new ledger created |
| Atomic mandatory projections or proven durable protocol | BLOCKED | Full projection boundary not established |
| Fee state KNOWN/UNKNOWN/INVALID/CONFLICTED | BLOCKED | No complete durable state machine |
| Decimal end-to-end | BLOCKED | Downstream legacy REAL/float projection paths remain |
| Fault injection + OS-process concurrency | NOT VERIFIED | No full applier harness |
| Risk gate at every entry point | BLOCKED | Complete caller coverage absent |
| Android process-death recovery | NOT VERIFIED | Separate local DB; no instrumentation proof |
| Futures path closure | NOT VERIFIED / BLOCKED | Not exhaustively audited; must remain blocked |
| main unchanged, PR Open/Draft | PASS | Verified before work; no merge/state change |

## 11. Exact next safe step

The next implementation step is a **writer-closure change**, not an isolated applier class:
1. produce the complete SQL/code write-sink list and caller graph at a pinned SHA;
2. map each sink to authoritative fields and each reachable caller;
3. add fail-closed guards at every non-migrated risk-increasing caller;
4. only then implement one vertical-slice applier over the existing ledger, with a single transaction covering all same-database authoritative projections;
5. prove with process-level concurrency and crash/commit fault injection before enabling the path.

If a non-migrated writer can still mutate the same financial projections, do not enable the new path.

## Final status

**TASK 006.12 — BLOCKED — NOT ACCEPTED**

Unclosed P0 invariant: one authoritative fill may still be processed through independent Spot/campaign financial writers, and complete caller/gate coverage is not proven. The missing evidence is an exhaustive source-to-sink graph plus a tested single-owner applier and crash-safe projection protocol. No production financial writers were changed because doing so before that evidence would risk duplicate or inconsistent financial effects.

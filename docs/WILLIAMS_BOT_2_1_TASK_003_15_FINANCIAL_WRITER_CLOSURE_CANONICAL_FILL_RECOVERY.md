# WILLIAMS BOT 2.1 — TASK 003.15
## Complete Financial Writer Closure, Canonical Fill Enforcement Design & Exactly-Once Recovery Proof

**Final status: BLOCKED — EVIDENCE INCOMPLETE**  
**Mode:** FORENSIC AUDIT + TEST DESIGN. No production-code changes, no database schema changes, no new writer, no strategy/risk changes, no live exchange mutation, no PR merge.

## 1. Revision identity and provenance

- Repository: `19wolfalone92/Williams`
- Requested source checkpoint: `e013aae4f0632387b899086b3097cd7cc1b32f22`
- Branch: `audit/williams-2.1-task-003-execution-safety`
- PR #48 observed at audit time: OPEN, DRAFT, NOT MERGED; base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`; head `8479dc173b4e5a736d7248ed72c803d089c52f64`.
- Previous audit report commit: `8479dc173b4e5a736d7248ed72c803d089c52f64`.
- Source acquisition: GitHub remote file API at the exact checkpoint SHA, not a local Git checkout. The execution environment did not provide a full checkout/Git object database. Therefore local HEAD, worktree cleanliness, local tests, and complete repository-wide call-graph extraction cannot be asserted.
- Report changes only: this Markdown report is committed on the task branch. No production file or schema was changed.

### Inspected source blobs at checkpoint

| File | Blob SHA | Evidence link |
|---|---|---|
| `db.py` | `c43cb57857cbbf85e613bf40622239911a8a6dfc` | [db.py at checkpoint](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/db.py) |
| `execution_accumulator.py` | `551bd0941206f7459b3fc8bbce3937e836b96f52` | [execution_accumulator.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/execution_accumulator.py) |
| `execution_barrier.py` | `1d82e0e6ad36dc19a10c0fc3ac67ade6dcad2a1c` | [execution_barrier.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/execution_barrier.py) |
| `campaign_execution.py` | `165298ecc9c09af4acb494e523945bc49786eeb3` | [campaign_execution.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/campaign_execution.py) |
| `campaign_engine.py` | `2183825fcab8f46e47a7ea8b2833c259a367229e` | [campaign_engine.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/campaign_engine.py) |
| `portfolio_trader.py` | `559c7f0a24a366b4cf822a0534ababd5af24ec8e` | [portfolio_trader.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/portfolio_trader.py) |
| `trader.py` | `3e946abe5eae3fdafaa4bca82a76cde0750de27e` | [trader.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/trader.py) |
| `binance_client.py` | `774b17505729b5b9f10d4f4c961e9defc046468e` | [binance_client.py](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/binance_client.py) |
| Android `TradingAuditStore.kt` | `8e6f4b0ea20824efd549c98c2681fc550f5b2c5f` | [TradingAuditStore.kt](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/app/src/main/java/com/williamsbot/TradingAuditStore.kt) |
| Android `StandaloneRuntime.kt` | `c179f5988b3759828bb457616e299260312447a6` | [StandaloneRuntime.kt](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/app/src/main/java/com/williamsbot/StandaloneRuntime.kt) |
| `test_task003_execution_safety.py` | `04b33f731e5cb0576e3465651ac8784c4246c2fe` | [existing execution safety tests](https://github.com/19wolfalone92/Williams/blob/e013aae4f0632387b899086b3097cd7cc1b32f22/test_task003_execution_safety.py) |

The source snapshot is identifiable, but the audit did not obtain a complete tree manifest or prove that this file list exhausts all financial writers. That is itself an acceptance blocker.

## 2. Executive finding

The inspected code shows a candidate idempotent journal method, `Database.apply_financial_fill_effect()`, but does **not** prove that every production financial path calls it. There are independent trade/campaign writers, float/REAL/Double financial representations, incomplete fee certainty semantics, and Python/Android state updates that are not proven to share one transaction with the candidate journal.

Accordingly:
- Complete source-to-sink graph: **NOT PROVEN**.
- All writer reachability classified: **NO; several remain UNRESOLVED or suspected LEGACY_REACHABLE**.
- Unknown fee distinguished safely from confirmed zero end-to-end: **NOT PROVEN**.
- End-to-end exact decimal preservation: **NOT PROVEN**.
- Exactly-once across ambiguous COMMIT/restart: **NOT PROVEN**.
- Cross-process duplicate-fill protection: **partially plausible at SQLite constraint level; end-to-end application guarantee NOT PROVEN**.
- Recovery gate blocks all risk-increasing actions on financial uncertainty in Python and Android: **NOT PROVEN**.
- CanonicalFillV1: **PROVISIONAL — CONTRACT GAPS**.

No destructive or fault-injection test was run in this task. Test cases below are **DESIGNED, NOT EXECUTED**.

## 3. Partial source-to-sink graph

The following is the maximum graph supported by the inspected files; arrows show observed candidate paths, not a claim that all branches were enumerated.

### 3.1 Python execution paths

```text
Binance REST order response / order fills
  -> binance_client.py (REST client and order/reconciliation helpers)
  -> portfolio_trader.py / campaign_execution.py / trader.py
  -> execution_accumulator.py (Decimal aggregate of fills or order totals)
  -> campaign execution reconciliation
  -> CampaignEngine.record_initial_fill / record_add_on_fill
  -> campaign/position state persistence and/or db.save_campaign_fill
  -> db.save_trade / trade close or PnL/fee projections

Binance trade-history / recovery / reconciliation
  -> portfolio_trader.py and trader.py recovery/reconcile methods
  -> order-derived or fill-derived aggregate
  -> campaign/trade/position projection writers
  -> database persistence

Candidate canonical path:
  exchange fill payload
  -> caller normalization (not proven universal)
  -> Database.apply_financial_fill_effect
  -> financial_effect_journal identity + canonical payload
  -> projection_writer callback
  -> APPLIED marker
  -> transaction commit
```

The canonical path is a candidate branch, not proven to be the sole route. The inspected code includes other write paths and there is no complete call-site/call-graph evidence showing all production entry points converge on the journal method.

### 3.2 Android path

```text
Binance REST / WebSocket event and recovery payloads
  -> StandaloneRuntime.kt
  -> execution-event persistence / order and campaign-position logic
  -> in-memory PositionState mutation
  -> savePersistedState()
  -> TradingAuditStore.kt (orders, executions, events, trades and state)
  -> TradingAuditStore.recordTrade()
```

Android keeps its own SQLite financial/audit representation. No evidence establishes a single cross-platform canonical ledger shared transactionally with Python. Android's `trades` fields include REAL-valued price, quantity, notional, PnL and fee fields; the inspected schema is not equivalent to the Python journal contract.

### 3.3 Sources still requiring exhaustive discovery

The inspected files show REST, order response/fill aggregation, campaign reconciliation, recovery, and Android execution-event paths. Exhaustive enumeration of every WebSocket handler, REST history fetch, missed-event recovery, Futures-specific path, direct SQL writer, and every call site is not complete. No path can be declared absent based on code-search misses or file naming.

## 4. Writer classification and bypass table

| Writer / component | Production reachability | Canonical bypass? | Duplicate / consistency risk | Required closure |
|---|---|---|---|---|
| `Database.apply_financial_fill_effect()` | Method exists; universal caller reachability unresolved | No, this is candidate canonical method | Identity is only as reliable as source IDs/payload and callers; callback contract not globally verified | Prove every fill source routes through it; prove callback uses same connection/transaction |
| `Database.resolve_financial_effect_fee()` | Method exists; caller inventory unresolved | Potentially, if fee resolution is performed elsewhere | Late/conflicting fee semantics and projection replay not proven globally | Enumerate callers; require conflict quarantine and no quantity/PnL reapplication |
| `Database.save_trade()` | Called from inspected trader/portfolio paths; production path appears reachable | **Yes / suspected LEGACY_REACHABLE** | Trade row may be written without authoritative per-fill identity or shared journal transaction | Trace every call site; prevent direct financial projection writes outside applier |
| `Database.close_trade()` | Method exists; exhaustive callers unresolved | Potential bypass | Exit/PnL projection not proven atomic with exit fill ledger | Prove caller paths and transaction ownership |
| `Database.save_campaign_fill()` | Method exists; campaign-related use needs full call-site proof | **Yes / suspected LEGACY_REACHABLE** | No proven unique exchange-fill identity; explicit float conversion | Route fill effects through canonical identity or prove this table is non-financial diagnostic only |
| `CampaignEngine.record_initial_fill()` | Called by campaign execution reconciliation in inspected source | **Yes / suspected LEGACY_REACHABLE** | Position qty/average entry/risk/campaign state can be mutated outside proven canonical transaction | Prove no bypass; idempotent fill identity and atomic projection required |
| `CampaignEngine.record_add_on_fill()` | Called by campaign execution/reconciliation in inspected source | **Yes / suspected LEGACY_REACHABLE** | Duplicate add-on may increase quantity/risk more than once | Same canonical fill key must guard campaign and position projections |
| `CampaignExecutionService` | Imported/instantiated by portfolio path in inspected source | Likely reachable; full runtime graph incomplete | Order aggregates may be projected as if fill effects; fee values include zero defaults | Separate per-fill identity from order aggregate; no zero-fee assumption |
| `PortfolioTrader` | Used by main trader path based on imports and inspected methods | Likely production reachable; exact startup wiring not fully proven | Recovery, emergency SELL, trade close and campaign updates may be separate writes | Complete call graph, risk gate integration, canonical exit-fill accounting |
| `Trader` | Main trading/recovery component in inspected source | Likely production reachable; exact invocation paths incomplete | Legacy trade writes/recovery may bypass journal | Prove or eliminate every direct financial writer |
| `execution_accumulator.py` | Imported by portfolio/campaign path | Production-reachable through imports/calls | Missing qty/price/commission default to zero; order-level aggregate may lack individual fill identity | Reject missing required fields; represent commission certainty separately |
| Direct SQL in `db.py` and other Python files | Not exhaustively enumerated | Unresolved | Hidden projection mutations can bypass any method-level guard | AST/SQL sink inventory plus runtime call-site tests |
| Android `TradingAuditStore.recordTrade()` | Method exists; caller closure incomplete | Separate path from Python canonical journal | REAL/Double and conflict-replace behavior can overwrite without payload conflict adjudication | Inventory all callers; define platform authority or common canonical contract |
| Android `StandaloneRuntime` position/campaign mutation | Runtime methods exist; full entry-point closure incomplete | Potential bypass | In-memory mutation then persistence is not proof of atomic ledger/projection application | Trace all fills and gate checks; durable operation intent and idempotent per-fill apply |
| Futures-specific writers | Not fully inventoried in this static pass | Unresolved | Contract may not represent Futures position side, realized funding/commission, liquidation/position mode effects | Separate Futures source/identity/ledger contract before claiming applicability |
| Tests / fixtures | Some tests are test-only by file purpose; all writer call sites not inventoried | N/A | Tests cannot establish production non-reachability | Classify from full tree/call graph, not names alone |

No writer is classified `NON_PRODUCTION` because non-production status was not proved. The remaining UNRESOLVED classifications independently prevent PASS.

## 5. Fee certainty analysis

### Observed behavior

In `execution_accumulator.py`, `accumulate_fills()` uses:
- `fill.get("commission", "0") or "0"`
- `fill.get("qty", "0") or "0"`
- `fill.get("price", "0") or "0"`

Thus absent, empty-string, or otherwise falsey commission input becomes Decimal zero. A zero fee and a missing fee are indistinguishable at this boundary. The dataclass also defaults fee totals to zero. If no detailed `fills` list is available, `accumulate_order()` returns order-level quantity/quote/average price and default-zero fee totals. In `campaign_execution.py`, the inspected paths also pass `fee_quote=0.0` / `fees=0.0` in some projections; the available evidence does not show these values are always sourced from confirmed fee data.

Android schema has `commission REAL DEFAULT 0`, and `trades.fee_known INTEGER DEFAULT 1`; this default is not proof that the source commission is known. Android's fee conversion helper and every call site were not exhaustively closed in this task.

### Required semantic model

The current candidate Python journal has fee status fields and unknown-fee pending behavior, but end-to-end status mapping and callers are unproven. The minimum contract states should be distinct:
- `UNKNOWN`: source omitted fee or cannot establish it;
- `CONFIRMED_ZERO`: authoritative source explicitly confirms exact zero;
- `KNOWN`: known nonzero amount and asset;
- `CONFLICT`: same fill identity carries inconsistent fee/payload;
- `PENDING_RESOLUTION`: fee can be resolved from a later authoritative source.

A zero numeric amount must never encode uncertainty. Unknown commission may block final net-PnL/fee projection and risk-increasing actions when risk, available balance, or protection sizing depends on net quantity/cost. It must not prevent a clearly risk-reducing protective action solely because fee valuation is pending; however, such an action still requires known order/position state and durable idempotent intent. Any exit whose outcome is unknown must reconcile before retry; never infer that an unconfirmed sell did not execute.

### Required cases not proven

- commission asset is quote currency;
- commission asset is base currency and reduces net received quantity;
- third-asset commission and conversion source/time/price;
- confirmed zero versus missing commission;
- late fee arrives after quantity projection;
- same fee arrives again;
- conflicting fee/payload for the same fill;
- restart while fee pending;
- no double quantity/PnL when late fee is applied.

## 6. Decimal integrity

| Boundary | Observed representation | Risk / invariant | Missing evidence or test |
|---|---|---|---|
| Exchange JSON -> accumulator | Decimal constructed from string, but missing fields default to string zero | Exactness only if original payload string is preserved and field validity is checked | Missing/empty/malformed fields must fail closed; test scientific notation and precision |
| Accumulated fill quantity and quote | `Decimal`; quote sums `qty * price`; VWAP is `quote / qty` | Decimal arithmetic preserves decimal inputs subject to context precision | No executed test for many partial fills, context precision, or reconciliation equality |
| Order aggregate fallback | Decimal qty/quote; avg may use order price | Aggregated order values do not supply individual fill identity/fees | Test partial fills and no detailed fills; never treat aggregate as unique fill |
| Campaign execution sizing | `float` quantities/prices/steps; `math.floor(float(value)/step + 1e-12)` | Binary float and epsilon can shift tick/step decisions; tolerance is not contractually justified | Decimal boundary tests immediately below/on/above tick and lot increments |
| Campaign model/projection | float conversion on load and risk/position calculations | Decimal source can be rounded before persistence or replay | Round-trip invariant tests and exact decimal migration plan |
| Python SQLite | financial tables include REAL fields; candidate journal stores decimal text | REAL is approximate binary floating point; not proof every value is wrong, but exact end-to-end invariant is unproved | Compare decimal-text authoritative ledger to each REAL projection under adversarial values |
| `save_campaign_fill()` | explicit conversion to float observed in prior audit | Irrecoverable precision loss possible | Prove table is non-authoritative or remove bypass in a future approved task |
| Python fee/PnL/risk | several inspected callers use float for quantity, price, fees, PnL/risk | Accumulated rounding can affect thresholds and accounting | Golden tests across fill -> persistence -> restart -> projection |
| Binance filters | `binance_client.py` uses Decimal / ROUND_DOWN helpers | Benefit lost if caller converts to float first | Trace every filter helper call and assert exact formatted request values |
| Android SQLite and Kotlin | REAL fields; `Double` in trade/projection path | Same approximate binary representation issue | Exact string or integer-unit contract tests, migration/recovery tests |
| Persistence -> recovery | not fully traced for all tables and both platforms | Reconstructed state may differ from original source values | Restart equivalence test for quantity, VWAP, fee, PnL, reserves |

### Reproducible numeric fixtures for future isolated tests

Use Decimal strings as inputs and compare exact decimal strings or integer units, not float tolerances alone:
1. Qty `0.00000013`, price `0.12345678`; quote must be exact product `0.000000016049382? ` calculated under a declared Decimal context, not rounded by implicit float conversion.
2. Two fills: `qty=0.1, price=0.1` and `qty=0.2, price=0.2`; total quote `0.05`, qty `0.3`, VWAP `0.166666...` under a declared context.
3. Tick boundary: price `1.00000001`, tick `0.00000001`; adjacent values one decimal unit below and above must normalize deterministically.
4. Large/small mixed magnitude: `qty=123456.78912345`, `price=0.00000017`; persist/reload/recompute must preserve canonical input and declared rounding.
5. Fee asset base: executed qty `0.01000000`, base commission `0.00001000`; net base must be `0.00999000`, not gross quantity.
6. Fee absent versus explicit `"0"`: these must produce different certainty states even though their numeric amount is zero.

Fixture 1's product should be calculated exactly by the test implementation using Decimal; this report intentionally does not encode a potentially misrounded literal. None of these fixtures was executed in this task.

## 7. SQLite transaction and unknown-COMMIT analysis

The inspected `Database.transaction()` uses an instance-local `threading.RLock`, `BEGIN IMMEDIATE`, a commit helper, and rollback on exceptions. Connection initialization sets WAL, `synchronous=FULL`, and busy timeout. These are useful primitives but not an exactly-once proof.

The candidate `apply_financial_fill_effect()` reportedly inserts the journal row, compares canonical payload on identity collision, invokes a projection callback for a known fee, marks the effect APPLIED, and commits within the helper transaction. That guarantees atomicity only if:
1. all projection writes use the same SQLite connection and transaction;
2. the callback does not commit independently or write to another database;
3. every production fill path actually calls the method;
4. replay checks authoritative existing state and treats a conflicting payload as conflict, not as a new effect;
5. unknown fee cannot silently transition to applied zero fee.

The per-instance RLock does not serialize independent processes. `BEGIN IMMEDIATE` obtains a SQLite write reservation and database uniqueness constraints can arbitrate duplicate insertions, but application code must handle lock/busy/unique conflict by rereading and comparing the canonical effect. The current audit did not execute a multi-process race.

### Unknown COMMIT

If SQLite commits successfully but the process loses the acknowledgement, a caller may see an exception even though journal and projection are durable. A safe protocol must not infer rollback from the exception and blindly reapply. After restart it must query by stable canonical fill identity, compare payload, verify the effect status and projections, then either accept the already-applied effect or quarantine/reconcile. If neither commit outcome can be determined, remain in recovery-required state and block risk increase.

No executable fault-injection proof currently establishes this protocol at the financial projection boundary. Existing execution barrier tests cover order-intent persistence/ambiguous exchange outcomes; they do not prove financial ledger exactly-once.

## 8. Inter-process race design

Run only on a temporary isolated SQLite file with two independent OS processes, each creating its own `Database(path)` connection and receiving the same canonical fill identity and identical payload.

Required assertions:
- exactly one logical financial effect identity exists;
- the second process observes identical prior payload as duplicate/no-op;
- position quantity, quote cost, fee total, realized PnL, and campaign add-on count equal a single application;
- if payload differs for the same identity, neither process overwrites the original effect; conflict is recorded/quarantined and risk-increasing actions remain blocked;
- one process killed around commit is recoverable without double application;
- no assertion relies on shared in-process RLock.

SQLite supplies transaction locking and uniqueness primitives; it does not supply the application's fill identity semantics, conflict adjudication, projection idempotency, or unknown-COMMIT recovery policy.

## 9. Recovery Gate

The source inspection does not prove a single gate joins all financial uncertainty states to every risk-increasing path in Python and Android. Existing order execution barrier logic is relevant to exchange mutation safety, but an order-intent gate is not by itself a financial-ledger reconciliation gate.

The required risk gate input set must include at least:
- unresolved/unknown exchange operation;
- unprocessed authoritative fills;
- fill identity or payload conflict;
- pending fee where net quantity/cost or risk admission depends on it;
- ledger/projection divergence;
- incomplete startup reconciliation;
- unprotected position or unknown protective-order state.

Expected policy:
- block new entries and add-ons while any critical financial state is unknown;
- block increasing protective exposure or replacing protection in a way that can increase risk while order state is ambiguous;
- permit only explicitly classified risk-reducing/recovery actions through a durable idempotent path, with actual position/order state reconciled first;
- no new risk until a durable recovery-complete marker is written after authoritative exchange reconciliation and projection invariants pass.

Python and Android need separate gate call-site inventories and restart tests. Those inventories are not complete here, so gate coverage is UNRESOLVED.

## 10. CanonicalFillV1 decision

**Decision: PROVISIONAL — CONTRACT GAPS.**

The candidate shape should include:
- exchange and environment;
- stable account scope (never secret credentials);
- product/instrument and market type;
- stable exchange fill/trade ID and order ID as secondary provenance;
- side, exact executed quantity and price, exact quote quantity when supplied;
- commission amount, commission asset, certainty state, source/provenance and event/transaction time;
- canonical raw payload or lossless normalized payload with version;
- deterministic identity key and canonical payload hash;
- processing state including pending, applied, conflict/quarantined, and recovery-required;
- versioned projection receipt/reconciliation evidence.

Identity must be namespaced at least by exchange + environment + account scope + market type + symbol/instrument + stable fill ID. Order ID alone is not a fill identity because one order can have multiple partial fills. If a source has no stable fill ID, it cannot be silently promoted to an exactly-once fill: retain it as an order aggregate/observation until authoritative trade history supplies fill identity.

Spot and Futures cannot be assumed identical. Futures requires explicit handling of position side/mode, realized PnL, commission, funding, reduce-only/close-position semantics and liquidation/ADL events as applicable. Do not approve one generic contract for Futures until those source fields and identity rules are evidenced.

## 11. Proposed test matrix — designed, not run

Every case must run against a fresh temporary database and deterministic fake exchange/source; no production database or live order endpoint.

| ID | Preconditions and input | Action | Expected ledger | Expected projections | Invariant / PASS criterion |
|---|---|---|---|---|---|
| T01 Duplicate fill | One valid fill identity/payload already APPLIED | Submit same fill twice | One effect row, same payload hash | Qty/cost/fee/PnL unchanged on replay | Exactly one logical effect |
| T02 Partial fills | One order, three distinct fill IDs with exact Decimal strings | Apply all then replay each | Three unique fill effects | Aggregate equals exact sum of fills | Order aggregate is not counted as a fourth fill |
| T03 Conflicting payload | Same identity, changed qty or price | Submit conflicting event | Original preserved; conflict quarantined | No second projection mutation | Conflict never overwrites/reapplies |
| T04 Unknown fee | Fill has no commission field | Apply fill | Fee state UNKNOWN/PENDING; never CONFIRMED_ZERO | Quantity only if contract permits provisional quantity; net PnL/risk projections remain gated | Missing != zero |
| T05 Confirmed zero | Explicit authoritative zero with source provenance | Apply fill | CONFIRMED_ZERO state | Fee total zero, known flag true | Zero only accepted with evidence |
| T06 Late fee | Fill quantity applied with pending fee | Resolve authoritative fee later | Same fill effect; fee state KNOWN | Fee/net PnL projection updated once; quantity unchanged | Fee resolution does not replay quantity |
| T07 Conflicting fee | Same fill, two incompatible fee payloads | Resolve second value | CONFLICT/quarantine | No silent overwrite; risk increase blocked | Conflict is explicit |
| T08 Reconciliation replay | REST history and WS provide same fill | Process both sources in both orders | One canonical identity | Single projection application | Source order does not change outcome |
| T09 Two-process race | Two independent processes, same DB path and same fill | Concurrent apply | One effect identity | Single qty/fee/PnL increment | No reliance on RLock |
| T10 Crash before commit | Inject exception before commit | Apply, terminate/reopen | No partial committed effect | No partial projection | Retry applies exactly once |
| T11 Unknown commit result | Commit completes, acknowledgement intentionally lost | Reopen and reconcile same fill | Existing effect discovered | No reapplication | Outcome resolved by identity/read-back |
| T12 Projection exception | Journal insert then projection callback fails | Force rollback | No APPLIED effect and no partial projection | No partial projection | Transaction is atomic |
| T13 Restart recovery | Kill process at each persistence boundary | Restart and reconcile exchange history | Canonical effect status determines action | Projections converge to authoritative fills | Restart idempotency |
| T14 Ledger/projection mismatch | Mutate a projection only in isolated fixture | Start recovery | Mismatch remains flagged | No new risk; repair/rebuild is deterministic | Divergence cannot pass gate |
| T15 Legacy bypass | Invoke each direct writer with a fill-like payload | Instrument SQL/financial state | No unjournaled financial effect allowed in target design | No qty/fee/PnL/campaign mutation without receipt | Sink-closure test fails on any bypass |
| T16 Add-on replay | Same add-on fill submitted twice | Call campaign add-on path twice | One fill effect | Position qty/additions/risk reserve change once | Campaign mutation idempotent by fill ID |
| T17 Incomplete recovery gate | Pending fill, unknown operation, conflict, or pending critical fee | Attempt entry/add-on | No new effect | Risk reserves/position exposure do not increase | Risk-increasing path denied |
| T18 Third-asset fee | Fee amount/asset plus authoritative conversion source and timestamp | Apply and restart | Original fee asset retained | Quote-equivalent valuation is reproducible and versioned | No silent omission of fee |
| T19 Base-asset fee | Base commission reduces net received quantity | Apply and replay | Fee asset and quantity preserved | Net base quantity applied once | Gross and net quantities are not conflated |
| T20 Spot/Futures identity | Same textual fill ID in distinct market/account/environment namespaces | Apply each | Distinct namespaced identities | Independent valid projections | No cross-market/account collision |

For every test, record source commit, Python/Android version, database journal mode, process IDs, injected fault point, raw input payload, ledger rows before/after, projection rows before/after, process exit status, and test runner output. PASS requires assertions, not merely absence of exception.

## 12. Checks actually performed

1. Retrieved PR #48 metadata: OPEN / DRAFT / NOT MERGED; branch head at report time `8479dc173b4e5a736d7248ed72c803d089c52f64`; base main SHA `04da5f9c09572e99ff310be3a1908179165e528c`.
2. Confirmed requested branch exists through GitHub branch search.
3. Retrieved the listed source files by exact checkpoint SHA and recorded their returned blob IDs.
4. Reused the findings from TASK 003.14 as prior evidence and extended the test design and contract gap analysis.
5. No local checkout or Git object database was available; no local tests were run in this task.
6. No new fault-injection, process-concurrency, Android runtime, database restart, or live exchange tests were executed.
7. Historical CI recorded in TASK 003.14 remains historical evidence only; it is not re-labelled as TASK 003.15 test execution and does not prove financial exactly-once semantics.

## 13. Unclosed risks and blockers

- Repository-wide file/tree and SQL sink inventory is incomplete.
- Full source-to-sink edges with caller-specific line-level evidence are not established for every source and writer.
- Production reachability of all writer call sites is not fully proven; at least the legacy trade/campaign paths remain bypass risks.
- Direct SQL writers outside inspected methods have not been exhaustively enumerated.
- Python and Android do not have a proven shared canonical fill ledger/projection transaction.
- Missing commission can be normalized to numeric zero in the accumulator.
- Float/REAL/Double values remain on financial projection and sizing paths.
- Callback same-connection/transaction behavior is a contract assumption until every caller is inspected and tested.
- Unknown-COMMIT recovery is not fault-injection proven.
- Cross-process duplicate-fill behavior is not tested.
- Recovery Gate coverage for all risk-increasing paths is not proven.
- Spot/Futures source identity and fee/PnL semantics are not fully specified.
- No prepared test above was executed; all remain design artifacts.

## 14. Recommendation / next stage

**Do not implement the financial writer yet.** First obtain a full, reproducible checkout at the pinned SHA and generate a complete source tree plus AST/SQL sink inventory. Then enumerate every exchange fill source and every writer/call site across Python, Android, Spot and Futures. Mark each edge with source lines and caller evidence. Only after that should an isolated test harness prove fee certainty, exact numeric round trips, transaction rollback, unknown-COMMIT reconciliation, multi-process uniqueness, projection consistency, and Recovery Gate behavior. Keep production changes and schema migrations in a later, separately approved task.

**Acceptance result: BLOCKED — EVIDENCE INCOMPLETE.** The mandatory criteria for PASS are not met.

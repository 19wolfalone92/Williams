# WILLIAMS BOT 2.1 — TASK 007.5
## Financial Ledger Atomicity, Complete Mutation-Path Enforcement & Crash-Recovery Proof

**Status: BLOCKED — NOT ACCEPTED**  
**Evidence type:** source review and GitHub metadata only unless explicitly marked test-confirmed.  
**Safety boundary:** No Binance Mainnet access, no trading mutations, no Testnet workflow. No merge and no PR creation.

## 1. Exact repository baseline

Repository: `19wolfalone92/Williams`  
Recovery branch: `audit/williams-2.1-task-007-recovery`  
Baseline HEAD verified at start: `90edcc64fa9fcf3dbf0b500d600583a40529d016`  
Base/main: `04da5f9c09572e99ff310be3a1908179165e528c`  
Compare at baseline: 125 commits ahead, 0 behind; merge-base reported as `04da5f9c09572e99ff310be3a1908179165e528c`.  
PR search for open and closed PRs whose head is this recovery branch returned none. No PR was created.

Refs:
- Recovery commit: https://github.com/19wolfalone92/Williams/commit/90edcc64fa9fcf3dbf0b500d600583a40529d016
- Base commit: https://github.com/19wolfalone92/Williams/commit/04da5f9c09572e99ff310be3a1908179165e528c
- Compare: https://github.com/19wolfalone92/Williams/compare/main...audit/williams-2.1-task-007-recovery

GitHub API cannot establish local working-copy dirty state or submodule state. No local checkout was available in this execution environment; an attempted clone failed because github.com DNS/network access was unavailable. These states remain NOT VERIFIED.

## 2. Exact-SHA CI baseline

The workflow-run listing for the recovery branch reported 126 runs across the returned listing/page window. The exact-SHA query for `90edcc64fa9fcf3dbf0b500d600583a40529d016` returned one run:

- Workflow: Williams Campaign CI
- Run ID: 38115192720
- Event: push
- Branch: audit/williams-2.1-task-007-recovery
- Head SHA: 90edcc64fa9fcf3dbf0b500d600583a40529d016
- Conclusion: success
- URL: https://github.com/19wolfalone92/Williams/actions/runs/38115192720
- Python job ID: 114398593926 — success
- Android job ID: 114398594124 — success

This is evidence only for the exact SHA above. It is not evidence for any later SHA or for untested fault-injection properties.

Workflow source review at the baseline:
- `.github/workflows/campaign-ci.yml`: runs on all branch pushes, PRs, and manual dispatch. Python job compiles all sources and runs the listed deterministic safety/campaign tests; Android job runs `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`.
- `.github/workflows/python-ci.yml`: push trigger is restricted to main/master and selected paths; workflow_dispatch exists. No run for the recovery HEAD was confirmed.
- `.github/workflows/android-apk.yml`: push trigger is restricted to main/master and Android/build paths; workflow_dispatch exists. No run for the recovery HEAD was confirmed.
- `.github/workflows/testnet-readonly.yml`: manual-only authenticated Spot Testnet read-only preflight. Not run; intentionally prohibited until local P0 gates are closed.

The generic Actions endpoint did not allow listing workflows through the connector, but workflow files were fetched directly from the recovery branch. Separate manual-dispatch of python-ci.yml/android-apk.yml is not available through the exposed GitHub connector actions in this environment. No unsupported API workaround was attempted.

## 3. Source findings — mutation-path enforcement

### 3.1 Observed Python paths (partial inventory; completeness NOT VERIFIED)

The previous report `docs/TASK_007_2_RECOVERY_CONCURRENCY_AUDIT.md` records these paths:
- Legacy Spot BUY: `Trader.market_buy → ExecutionBarrier.execute → BinanceSpotClient.order`
- Legacy Spot OCO: `Trader.place_oco → ExecutionBarrier.execute → BinanceSpotClient.create_oco_sell`
- Campaign entry/stop/exit: `CampaignExecutionService → barrier → order_safe`
- Campaign cancel: `CampaignExecutionService → barrier → cancel_order`
- Campaign cancel/replace: `CampaignExecutionService → barrier → cancel_replace`
- Multi-position entry: `MultiPositionTrader → _execute_mutation → barrier → order_safe`
- OCO create/cancel: `MultiPositionTrader → _execute_mutation → barrier → adapter OCO methods`
- Low-level adapter mutation methods include order, cancel, cancel-open-orders, cancel-replace, OCO create and OCO cancel.

The previous report itself says the full repository-wide reachable-caller proof is incomplete. Therefore no claim is made that every production mutation sink is barrier-enforced.

### 3.2 ExecutionBarrier review

At baseline, `execution_barrier.py`:
- requires a durable execution-intent store and raises `ExecutionPersistenceError` when unavailable;
- validates recovery state, unresolved same-symbol intents and campaign/position gates;
- requires a non-empty client order ID and checks intent/client ID collisions;
- includes a poisoned/reconciliation-required state after ambiguous mutation.

However, `OrderIntent` still declares `invalidation_level: float`, `risk_quote: float`, and `capital_reserved_quote: float`. This is a code-confirmed binary-float surface in execution/risk intent data. Full end-to-end call-graph enforcement and every failure transition are not proven by source presence alone.

### 3.3 Android review

The previous static contract test verifies that Android POST/DELETE helpers route through `durableMutation`, that a PENDING record is synchronously committed before `signedRequest`, and that UNKNOWN/persistence-error handling strings exist. This is static contract evidence only.

The Android data models include financial/rule values represented as `Double`, and symbol rules contain step/tick/minimum values as `Double`. These values require an audit to distinguish display/market metadata from any execution sizing or financial decision path. Physical device testing and process-kill fault injection remain NOT VERIFIED.

## 4. Canonical financial storage — confirmed risk

At baseline `db.py` reports `SCHEMA_VERSION = 10`. Its schema uses SQLite `REAL` for fields that include, among others:
- `orders.price`, `orders.stop_price`, `orders.quantity`;
- `trades.entry_price`, `trades.exit_price`, `trades.quantity`, `trades.pnl`, `trades.fees`;
- campaign position quantity, average entry, stop levels, realized/unrealized PnL, open/pending risk and capital reservation;
- campaign order price, stop, quantity, risk and capital reservation;
- campaign fill quantity, price, quote quantity, quote fee and base commission.

Some `REAL` columns in the same database are market observations or diagnostic metrics and are not automatically canonical financial state. Nevertheless, the listed order, fill, inventory, campaign, fee and risk fields can affect execution decisions and require a complete writer/reader audit.

The existing `campaign_fills` test fixtures pass numeric Python floats for quantity and price. A unique-fill test exists, but it does not establish a canonical Decimal journal or prove that every financial projection is atomically updated. The code uses Decimal in parts of the execution accumulator; this does not remove float/REAL from the full persistence path.

No schema migration was performed. A conversion without a complete inventory and lossless legacy-data policy would be unsafe.

## 5. Exactly-once financial effects — evidence gap

Existing code/tests provide partial safeguards:
- campaign fills are deduplicated by exchange trade identity in the local DB;
- conflicting repeated fill payloads are expected to raise;
- the recovery report records independent-process intent-claim testing on earlier SHAs;
- fee parsing/reconciliation blocks on some missing commission evidence.

Not yet proven on one exact SHA:
- a composite exchange/account/environment/symbol/trade identity with verified uniqueness scope;
- one durable financial-effect journal atomically linked to fill persistence;
- atomic or replay-safe projection of inventory, position, campaign/PnL, risk reservation and protection;
- exact-sum invariants under duplicate/concurrent fill handling;
- unknown fee and unvalued third-asset fee blocking all finalization paths;
- recovery after a crash between each projection.

Accordingly, exactly-once financial effects are NOT VERIFIED.

## 6. Crash consistency, concurrency, OCO, Spot/Futures and Android

No new fault-injection or independent-process tests were executed during this audit. Existing test source includes independent-process SQLite claim helpers and repeated-fill idempotency assertions, but those are not a substitute for running the complete required matrix at the final SHA.

Outstanding required evidence:
- mutation request accepted but response lost; result persistence/commit failure;
- fill persistence/effect application/projection crash points;
- two independent recovery processes and duplicate fill workers;
- cancel-vs-fill and cancel-replace-vs-fill;
- OCO sibling cancellation ambiguity and residual exposure;
- Spot free/locked balance, manual balance changes, simultaneous SELLs and oversell race;
- Futures separate contract or proven fail-closed disablement;
- Android lifecycle/process kill, durable financial journal and repeated runtime recovery.

No Binance Testnet workflow was run. No Mainnet or trading mutation was used. PHYSICAL DEVICE TEST — NOT VERIFIED.

## 7. Change and validation record

This report-only checkpoint does not change runtime behavior. No source fix is claimed. No migration is claimed. No test result is inferred from static analysis.

The required next technical steps are:
1. establish a complete mutation source-to-sink inventory and add enforceable negative tests for every production sink;
2. design a versioned canonical Decimal/string ledger migration with legacy-data validation and rollback/recovery behavior;
3. implement a durable financial-effect journal and idempotent projection/replay protocol;
4. add deterministic fault injection and true independent-process tests for each critical transition;
5. close OCO/Spot SELL reconciliation and either separately prove Futures safety or enforce a tested global Futures mutation block;
6. add Android lifecycle and financial-consistency evidence;
7. run all required workflows on the resulting exact final SHA and update this report with actual run/job IDs.

## 8. Acceptance matrix at this checkpoint

| Requirement | Status | Evidence / reason |
|---|---|---|
| Exact baseline HEAD and main | PASS | GitHub refs and compare metadata at start |
| Open/closed PR check | PASS | No PR returned for this head branch |
| Current baseline Campaign CI | PASS | Run 38115192720 on SHA 90edcc6 |
| Separate python-ci on baseline SHA | NOT VERIFIED | No run confirmed; workflow trigger excludes recovery branch pushes |
| Separate android-apk on baseline SHA | NOT VERIFIED | No run confirmed; workflow trigger excludes recovery branch pushes |
| Testnet read-only | NOT RUN | Local P0 gates not closed |
| Full mutation graph | BLOCKED | Existing report explicitly marks completeness not verified |
| Mandatory barrier enforcement | BLOCKED | All production-reachable callers not proven |
| Canonical financial field inventory | BLOCKED | REAL/float fields remain in critical paths; complete writer/reader map missing |
| Lossless ledger migration | NOT RUN | No migration performed |
| Durable financial-effect journal | BLOCKED | Atomic end-to-end projection/replay not proven |
| Exactly-once fill effect | BLOCKED | Partial dedupe exists; full financial invariant not proven |
| Crash consistency matrix | BLOCKED | Full matrix not executed |
| Independent-process recovery | BLOCKED | Partial historical tests only; no full matrix on current SHA |
| OCO lifecycle | BLOCKED | Required fault matrix not executed |
| Spot oversell protection | BLOCKED | Full balance/SELL race matrix not executed |
| Futures | NOT VERIFIED | Separate contract/disable gate not proven |
| Android lifecycle | BLOCKED | Static tests/CI do not prove process-kill recovery |
| Physical device | NOT VERIFIED | No physical device used |
| Main unchanged / no merge | PASS | No main write or merge performed during this audit |

**Final TASK 007.5 status: BLOCKED — NOT ACCEPTED.** No P0 closure is claimed.
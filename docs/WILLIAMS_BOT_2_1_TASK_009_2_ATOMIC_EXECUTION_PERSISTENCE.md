# WILLIAMS BOT 2.1 — TASK 009.2 Checkpoint

**Status: BLOCKED — atomic persistence primitives staged; end-to-end crash recovery and exactly-once campaign accounting not proven.**

- Baseline `main`: `04da5f9c09572e99ff310be3a1908179165e528c`
- TASK 009.1 starting HEAD: `0498217a5cc887d025671b87af01422f00c57e94`
- Integration branch: `audit/williams-2.1-task-009-integration`
- Current implementation checkpoint at document creation: `fa04b7ed82438dc4032e489e8d661150bba43fc8`
- No live exchange requests were made.

## Changes

### `db.py`

- Raised schema version to 7.
- Added a unique partial index on non-empty `execution_intents.client_order_id`. Existing database contents with duplicate IDs intentionally cause index creation/startup to fail rather than silently rewriting active intent history.
- Added `execution_fills` with unique key `(exchange, account_scope, symbol, trade_id)`.
- Changed critical transaction start to `BEGIN IMMEDIATE` and serialize public Database method calls on a per-instance reentrant lock. SQLite write locks and unique constraints provide cross-instance/process arbitration on the same database file.
- Replaced `INSERT OR REPLACE` for execution intents with immutable-identity status updates. New intents must be inserted through `claim_execution_intent`.
- Added `claim_execution_intent`, `execution_readiness`, `list_unresolved_execution_intents`, `mark_execution_unknown`, `save_execution_result`, `resolve_execution_intent` and `apply_execution_fill`.
- `save_execution_result` records the exchange response and advances the intent to `SUBMITTED` in one transaction.
- `apply_execution_fill` inserts the unique fill and runs DB-only effect callbacks in the same SQLite transaction. If the callback fails, the fill claim and callback writes roll back together.

### `execution_barrier.py`

- Requires an atomic claim/readiness API; no fallback to volatile persistence.
- Refuses submission if readiness is unknown, an execution lock exists, an unresolved intent exists, or the identity claim loses a conflict.
- Persists `SUBMITTING` before invoking the network callback.
- A timeout/exception after submit is attempted becomes `ExecutionOutcomeUnknown`; the barrier attempts to persist `UNKNOWN` and the durable lock. If lock writing fails, the already durable `SUBMITTING` intent remains unresolved and the readiness gate blocks new submissions.
- Exchange result recording is atomic with the local `SUBMITTED` state. Failure returns unknown, never success.

### Tests and CI

- Added `test_task0092_atomic_execution_persistence.py` with SQLite fault-injection tests for cross-instance claim races, stable client-order identity, crash-before-submit, restart with UNKNOWN, lock-write failure, result-write failure, duplicate fills, callback rollback, concurrent fill handling and late partial fills.
- Expanded `test_task0091_execution_barrier_fail_closed.py` with a real SQLite timeout/UNKNOWN lock test and concurrent barrier submissions sharing one client order ID.
- Updated `.github/workflows/campaign-ci.yml` so the two TASK 009.1/009.2 test files are explicitly included in the deterministic safety/campaign suite.

## Transaction boundaries and constraints

1. **Claim:** `BEGIN IMMEDIATE` → check lock/unresolved intents → insert immutable intent under primary key `intent_id` and unique non-empty `client_order_id` → insert `INTENT_CLAIMED` event → commit.
2. **Before network:** status changes to `SUBMITTING` in a transaction. If the process dies after this point, readiness remains blocked.
3. **Known response:** insert `BINANCE_RESULT` and update intent to `SUBMITTED` in one transaction.
4. **Unknown result:** update intent to `UNKNOWN`, persist scope lock and `UNKNOWN_LOCKED` event in one transaction. If this transaction fails, the earlier `SUBMITTING` row remains unresolved.
5. **Reconciliation release:** `resolve_execution_intent` records a state and evidence identifier, then removes the lock only if no unresolved intent remains. This method is not itself an exchange reconciler and does not independently verify the evidence identifier.
6. **Fill:** unique exchange/account/symbol/trade identity and DB-only financial effects are committed in the same transaction. External side effects inside the callback are prohibited.

## Fault-injection test status

The tests are committed and included in Campaign CI. A test is not marked PASS until the workflow run against the exact final branch HEAD completes and its logs show the test file was included.

| Scenario | Expected invariant | Current proof level |
|---|---|---|
| Same clientOrderId concurrent barrier requests | Exactly one submit callback | Test added; exact-HEAD CI pending |
| Two Database instances claim same intent | One atomic claim | Test added; exact-HEAD CI pending |
| Crash after intent before submit | Restart readiness blocked | Test added; exact-HEAD CI pending |
| Timeout after possible submit | UNKNOWN lock; next submit blocked | Test added; exact-HEAD CI pending |
| UNKNOWN lock write fails | SUBMITTING remains unresolved and blocks | Test added; exact-HEAD CI pending |
| Exchange-result persistence fails | Result/status transaction rolls back; unresolved intent blocks | Test added; exact-HEAD CI pending |
| Duplicate fill delivery | Second financial callback does not run | Test added; exact-HEAD CI pending |
| Financial effect callback fails | Fill claim and DB effects roll back | Test added; exact-HEAD CI pending |
| Concurrent handlers for one fill | One effect transaction commits | Test added; exact-HEAD CI pending |
| Late partial fill with distinct trade ID | Each unique fill applies once | Test added; exact-HEAD CI pending |
| Automatic exchange recovery after restart | Query exchange, orders, trades, cancellations and reconcile | **Not implemented / P0** |
| Cancel/fill race and campaign/ledger atomicity | Exchange fills, campaign, position and PnL remain consistent | **Not implemented / P0** |

## Remaining P0 blockers

1. The new fill primitive is **not wired into `CampaignExecutionService` / `CampaignEngine`**. Current campaign processing still aggregates order-level `executedQty` and calls campaign/trade writes through separate paths. Therefore this patch does **not** establish exactly-once financial effects for production campaign accounting.
2. There is no automatic exchange-backed startup reconciler that consumes `list_unresolved_execution_intents`, queries Binance order/trade history, resolves cancel/fill races and calls `resolve_execution_intent` only after verified evidence.
3. `resolve_execution_intent` records caller-provided evidence metadata; it does not prove the evidence came from an authenticated exchange query. Only a trusted reconciliation coordinator may be allowed to invoke it; that coordinator is not present yet.
4. `execution_readiness` currently treats unresolved intents as a database-wide blocker even though the durable lock key carries a scope. This is deliberately conservative for the current Spot-focused Python runtime; independent Spot/Futures readiness semantics are not yet implemented.
5. Direct Python mutation calls in `portfolio_trader.py` and direct Android signed POST/DELETE paths remain outside a proven common mutation boundary.
6. A process that cannot open/read the DB must not trade; the barrier fails closed, but cross-device/external-process coordination is not provided by a local SQLite file.
7. The backend release gate still fails before tests at the pre-existing `server.py: missing "VERSION = '4.24.0'"` contract. This was not changed in TASK 009.2.

## Decision

**Do not merge PR #51.** This checkpoint adds meaningful database-level safeguards but does not meet the task's acceptance criteria until exact-HEAD CI passes, exchange-backed recovery is integrated, and the actual campaign fill/financial path is migrated to the transactional fill ledger with end-to-end fault tests.

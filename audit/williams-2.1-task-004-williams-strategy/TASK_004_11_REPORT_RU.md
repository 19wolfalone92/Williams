# WILLIAMS BOT 2.1 — TASK 004.11
## Durable Operation-Intent Permit Enforcement, Mutation-Sink Closure & Crash Recovery

**Итоговый статус: BLOCKED — NOT ACCEPTED**

## 1. Проверенный baseline

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-004-williams-strategy`
- PR #49: **OPEN — Draft**, not merged.
- Base SHA: `04da5f9c09572e99ff310be3a1908179165e528c`
- Implementation/test SHA inspected in this task: `82ecc9e8f899b1128892e66c773d12763986425f`
- This report is a documentation-only follow-up to that implementation/test SHA. It must not be represented as production-code validation at the report commit SHA.
- No changes to `main`; no merge; no Binance Mainnet or Spot Testnet trading mutations were performed.

PR metadata and changed-file inventory were re-read. PR #49 remains open and draft. The branch already contains extensive Python and Android changes from prior tasks; this task does not claim that the whole branch diff was newly implemented here.

## 2. Changes actually made

### Python durable execution-intent persistence

File: `db.py`, method `Database.save_execution_intent(...)`.

- Removed `INSERT OR REPLACE` behavior for the existing `execution_intents` record.
- An existing `intent_id` is now checked against immutable fields: symbol, side, order type, purpose, canonicalized required-context versions, hypothesis ID, invalidation level and client order ID.
- An identical intent can update lifecycle `status` and `reason`; a conflicting immutable payload raises `ValueError` and does not overwrite the stored record.
- The method obtains a SQLite `BEGIN IMMEDIATE` transaction when it owns the transaction, then commits the insert/update.
- If the database indicates a caller-owned uncommitted transaction, persistence fails closed with `RuntimeError`. An uncommitted row must not be treated as a durable write-ahead intent before network I/O.
- If the transaction commit/write path raises, the method raises to the caller; the caller must not proceed to network mutation based on that call. This code does not by itself resolve an ambiguous commit outcome; recovery must read durable state.

### Added tests

File: `test_campaign_execution.py`.

- `test_execution_intent_lifecycle_update_preserves_immutable_payload`
- `test_execution_intent_rejects_conflicting_payload_for_same_id`
- `test_execution_intent_refuses_uncommitted_caller_transaction`

These tests cover immutable-field conflict handling and refusal to treat an uncommitted caller transaction as durable. They do **not** prove complete permit enforcement, inter-process exclusive network sending, process-death recovery, OCO recovery, cancel-replace recovery, or financial exactly-once behavior.

## 3. Mutation-sink findings and blockers

### Python

| Boundary/path | Evidence observed | Status |
|---|---|---|
| `BinanceSpotClient._request(method, path, ...)` → `session.request(...)` | Common REST boundary does not require and validate an operation-bound durable permit immediately before a mutating network call. | **BLOCKED — P0** |
| `ExecutionBarrier.execute(...)` | Persists campaign execution state and signal claim on the campaign path, but this is not a universal permit checked by the common HTTP boundary. | **BLOCKED — P0** |
| `Database.save_execution_intent(...)` | Conflict-overwrite behavior was corrected in this task; it is still a limited legacy intent schema, not the full canonical Operation Intent contract requested by TASK 004.11. | **PARTIAL — NOT ACCEPTED** |
| Direct/legacy, emergency, OCO, cancel, cancel-replace and any Futures mutation path | Exhaustive caller-to-sink reachability graph and mutation-spy closure were not established. | **NOT VERIFIED — P0** |

The client currently treats many POST/DELETE transport failures conservatively as unknown and avoids blind transport retries. This is useful but is not equivalent to durable operation intent, a network-bound permit, or authoritative recovery for every mutation category.

### Android

| Boundary/path | Evidence observed | Status |
|---|---|---|
| `StandaloneRuntime.kt: signedPost/signedDelete/signedCancelReplace → signedRequest → http.newCall(request).execute()` | The inspected shared request boundary does not validate a durable operation ID and permit before the network call. | **BLOCKED — P0** |
| `ExecutionGate.kt` | In-memory symbol reservation is not a durable operation-intent ledger and is not a permit validated at the network sink. | **BLOCKED — P0** |
| Campaign BUY/SELL, emergency exits, OCO, cancellation, cancel-replace, recovery and alternate clients | Exhaustive caller-to-sink graph and runtime mutation-spy proof are not complete. | **NOT VERIFIED — P0** |

No claim is made that the inventory above is exhaustive. The task requires every reachable mutation sink to be inventoried and protected; that criterion remains unmet.

## 4. Identity and contract status

Signal identity, operation identity, exchange-order identity and fill identity remain distinct concepts. This task did not complete the requested shared, versioned Operation Intent contract across Python and Android.

Not yet proven:
- canonical cross-platform operation identity and semantic payload fingerprint;
- immutable versioned intent schema covering account/environment/market/symbol and recovery metadata;
- common golden fixtures actually consumed by both Python and Android;
- durable state-transition history and transition legality;
- exclusive permit reservation across processes;
- a mutation-spy that rejects all sink calls lacking a valid permit and detects unregistered sinks.

**Status: BLOCKED — P0.**

## 5. UNKNOWN outcome and recovery

The following remain **NOT VERIFIED** end-to-end:
- crash after durable intent but before send;
- crash after send but before saving response;
- ambiguous SQLite COMMIT resolved by rereading durable state;
- normal order recovery through authoritative order and trade history;
- OCO list plus child-order reconciliation after a lost response;
- cancel outcome plus fills that race with cancellation;
- cancel-replace recovery when only one leg succeeded;
- emergency risk reduction while source order/position state is unknown;
- repeated recovery and duplicate response delivery without duplicate financial effects.

Unknown outcome must continue to block risk-increasing retries until authoritative reconciliation resolves the original operation. No live/testnet mutation was used to test these scenarios.

## 6. Financial integrity

No new financial ledger was introduced. Exactly-once fill identity, commission conflict/late-fee handling, atomic financial projection, process-death recovery and residual Spot exposure reconciliation remain separate requirements.

**Status: BLOCKED — NOT ACCEPTED.** Passing operation-intent persistence tests would not be sufficient proof of financial integrity.

## 7. CI evidence

The latest implementation/test SHA at the time of this report is `82ecc9e8f899b1128892e66c773d12763986425f`. Its three applicable workflows were found, but the most recent observed state was **IN_PROGRESS**; they are not reported as successful:

| Workflow | Run ID | SHA | Last observed status | Acceptance result |
|---|---:|---|---|---|
| Python backend tests | 38129894739 | `82ecc9e8f899b1128892e66c773d12763986425f` | IN_PROGRESS | **NOT VERIFIED** |
| Williams Campaign CI | 38129894732 | `82ecc9e8f899b1128892e66c773d12763986425f` | IN_PROGRESS | **NOT VERIFIED** |
| Build Android APK | 38129894745 | `82ecc9e8f899b1128892e66c773d12763986425f` | IN_PROGRESS | **NOT VERIFIED** |

Relevant earlier failure and correction:
- Campaign CI run `38129749326` on SHA `f54f56074f2ac4813d4e7efd3588a770c4cbaf46` failed because the two newly added tests called nonexistent `Database.close()` (2 failed, 188 passed). Those unsupported calls were removed in subsequent commits. This earlier failure is not hidden and is not treated as a result for the later SHA.
- The latest three workflow results must be rechecked before any acceptance decision. A run being present or in progress is not success.

## 8. Acceptance matrix

| Requirement | Status |
|---|---|
| Recheck GitHub baseline; PR remains Open Draft; main untouched | **PASS** |
| Remove silent immutable-payload replacement from existing Python intent persistence | **PASS — LIMITED SCOPE** |
| Fail closed if intent persistence is attempted inside an uncommitted caller transaction | **PASS — LIMITED SCOPE** |
| Full canonical Operation Intent contract on Python and Android | **BLOCKED** |
| Valid durable permit enforced at every actual network mutation sink | **BLOCKED — P0** |
| Complete reachable mutation-sink inventory and source-to-sink graph | **NOT VERIFIED — P0** |
| Cross-platform operation identity fixtures and differential tests | **NOT VERIFIED — P0** |
| Inter-process exclusive permit and ambiguous COMMIT recovery | **NOT VERIFIED — P0** |
| UNKNOWN recovery for order, OCO, cancel, cancel-replace and emergency paths | **NOT VERIFIED — P0** |
| Mutation-spy proves no sink bypass | **NOT VERIFIED — P0** |
| Fill/fee/financial-effect exactly-once proof | **BLOCKED** |
| Causal replay and Android process-death recovery | **NOT VERIFIED** |
| All three applicable CI SUCCESS on a single accepted final SHA | **NOT VERIFIED** |

## 9. Required next step

Do not enable or claim universal mutation-permit protection yet. The next implementation increment must introduce one operation-bound durable permit contract and enforce it directly at the common Python and Android network mutation boundaries, while ensuring every reachable caller either supplies a valid permit or is explicitly blocked. Add mutation-spy coverage over the actual boundary, then implement per-operation authoritative recovery and cross-process fault-injection tests.

**Final decision: TASK 004.11 — BLOCKED — NOT ACCEPTED.**

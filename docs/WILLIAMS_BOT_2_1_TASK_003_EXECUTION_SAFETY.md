# WILLIAMS BOT 2.1 — TASK 003: Execution Safety

**Status:** IMPLEMENTATION DRAFT — final acceptance requires green CI for the exact PR head and review of all mutation routes.  
**Safety scope:** Binance Spot/Testnet code only. This change does not enable Mainnet or initiate any trade.  
**Branch:** `audit/williams-2.1-task-003-execution-safety`  
**Base:** `main` (must remain unchanged until explicit review/merge).

## Mandatory execution contract

A mutating request may reach the exchange only after its immutable logical identity and request parameters have been written to the durable intent store and read back successfully. The pre-submit state is `PENDING`; immediately before network submission it is `SUBMITTING`. A missing database, missing writer, SQL error, unique-identity collision, failed commit, or failed read-back rejects the request before the network call.

An exchange response is also mandatory state, not telemetry. The response JSON, exchange order/list ID, status, executed quantity and cumulative quote quantity are committed and read back before the caller is told that a mutation has a known result. If this record cannot be confirmed, the intent is marked ambiguous where possible, a durable reconciliation latch is set, and execution is not retried.

A transport timeout, unexpected HTTP failure, malformed response, non-terminal cancellation, partial/filled cancel race, or non-authoritative query is **UNKNOWN**, not rejection or success. The same stable identity is retained while recovery queries the exchange. No new exposure is permitted while unresolved intent or reconciliation state remains.

Optional REST/event telemetry is best effort. It is not used as proof of submission or fill, and a telemetry-write failure alone does not prevent a safe, already-admitted protective/reducing operation.

## Mutation-path inventory

| Path | Required boundary | Durable identity / checks |
|---|---|---|
| Canonical campaign entry | `campaign_execution.py` → `ExecutionBarrier` | Stable client order ID and signal identity; versioned context and signal-age checks; directional admission; mandatory risk and campaign ownership callback; risk/capital reservation; global/symbol/campaign reconcile gates |
| Campaign add-on | `campaign_execution.py` → `ExecutionBarrier` | Stable signal/campaign identity; pending add-on ownership; expiry/staleness; current position; live equity/risk reservation; position/order capacity |
| Campaign hard stop / stop replacement | `campaign_execution.py` → `ExecutionBarrier` | Stable stop identity; confirmed campaign quantity; exact old-order ownership/status; no duplicate active protective stop; old/new cancelReplace states independently checked |
| Campaign market exit | `campaign_execution.py` → `ExecutionBarrier` | Stable exit identity; current campaign owner and confirmed position quantity; quantity capped to that campaign; no blind retry; actual exchange fills remain authoritative |
| Campaign order cancellation | `campaign_execution.py` → `ExecutionBarrier` | Stable cancel intent; target order ID/client ID and campaign owner verified; cancel/fill conflict triggers reconciliation |
| Portfolio/legacy BUY | `portfolio_trader.py` / `trader.py` → `ExecutionBarrier` | Stable signal-based ID; pending claim; current context versions, equity/drawdown/risk/capital and balance rechecked before send |
| Portfolio OCO creation/cancellation | `portfolio_trader.py` → `ExecutionBarrier` | Stable list identity; managed trade and inventory ownership; protective prices and target list identity; all child order states checked after cancellation |
| Portfolio/manual sell | `portfolio_trader.py` / `trader.py` → `ExecutionBarrier` | Stable logical exit identity; managed trade and confirmed free inventory checked; no random-ID retry after ambiguity |
| Android native order/exit/stop/OCO/cancelReplace | `StandaloneRuntime.kt` `signedRequest` | Low-level fence classifies every supported signed write; unknown order-like write endpoints are rejected before network; SQLite write-ahead intent and response result/read-back required; stable `clientOrderId`/`listClientOrderId`; kill/reconcile and unresolved-intent gates |
| Android REST audit log | `TradingAuditStore.kt` diagnostics | Optional only; not an execution authority and not a prerequisite for an otherwise safe mutation |

Low-level exchange client methods in `binance_client.py` are transport adapters, not an alternate strategy execution boundary. All observed Python strategy order operations are intended to enter through `ExecutionBarrier`; the Android runtime now has its own local durable fence because its network adapter is independent of the Python backend.

## State and recovery rules

- `PENDING` means a durable write-ahead intent exists but submission is not yet proved.
- `SUBMITTING` means the network request may be in flight; process death or lost response requires exchange reconciliation.
- `SUBMITTED` means the exchange response and its mandatory durable result record were verified; it is not synonymous with `FILLED`.
- `AMBIGUOUS` / `RECONCILE_REQUIRED` block new exposure. They cannot be cleared by telemetry or a generic retry.
- `PARTIALLY_FILLED` retains exact observed quantity. Any cancel/fill race or positive fills on a supposed zero-fill cancel path require position/fill accounting.
- A protective OCO is not resolved from a list-level acknowledgement alone. Child orders must be queried and inspected individually if the response lacks per-leg reports.
- Definitive absence is accepted only from an authoritative, exact-identity exchange query. Transport errors, 5xx responses, rate limits and malformed responses do not prove absence.
- Reconciliation that finds a fill does not silently mark the campaign clean. Where the execution result has not yet been applied to campaign/position accounting, the intent remains unresolved.

## Regression evidence expected on the exact PR head

The Task 003 Python tests include fault-injection for mandatory intent-write/commit failures, missing persistence support, parallel duplicate entries, response-loss timeout, partial-fill state preservation, cancel/fill races, stale contexts, `RECONCILE_REQUIRED`, telemetry outage on a safe exit, missing/corrupt storage, and mandatory exchange-result write failure. Source-contract checks cover the native Android write-ahead boundary and separate telemetry path.

**Acceptance is not complete until CI has passed for the exact final branch SHA and the full mutation-route review is confirmed.** No Mainnet execution is part of this task.


## Incident record and remediation (2026-10-10)

**Acceptance status remains: BLOCKED — NOT ACCEPTED.** This record distinguishes the initial failed run from later validation; an intermediate commit or a green workflow on a different SHA is not final evidence.

### Initial snapshot

- Initial task HEAD: `953d2c4e2831d1685f5531c76ac03fcedd42d00a`.
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`; no Task 003 changes were written to `main`.
- PR #48: open, draft, base `main`, not merged.
- The Actions run records were associated with the PR head SHA above, while their job logs checked out GitHub's generated PR merge commit `f9353a850acff43c2d8ffed187bf722113cc0660`. This distinction is retained in the evidence.

Initial failed workflow IDs:
- Python backend tests: [38080355142](https://github.com/19wolfalone92/Williams/actions/runs/38080355142), job `114295816305`. Exact failure: `AssertionError: server.py: missing "VERSION = '4.24.0'"` in `ci_release_gate.py`.
- Williams Campaign CI: [38080355099](https://github.com/19wolfalone92/Williams/actions/runs/38080355099), jobs `114295825122` (Python tests) and `114295824990` (Android build/static checks). Python summary: `5 failed, 172 passed`:
  - `test_trigger_then_reconcile_creates_hard_stop`: expected `OPEN`, got `RECONCILE_REQUIRED`.
  - `test_partial_conditional_entry_is_cancelled_and_residual_protected`: expected `OPEN`, got `RECONCILE_REQUIRED`.
  - `test_restart_adopts_pending_campaign_by_client_id`: `KeyError: 'campaign_id'`.
  - `test_native_android_signed_order_mutations_are_fenced_before_network`: expected two raw senders, observed three.
  - `test_native_optional_rest_telemetry_is_not_execution_authority`: `assert 4214 < 3373`; the assertion selected an optional telemetry call in the explicit `-1021` timestamp-rejection branch before the normal mandatory-result write.
  Android compile failure: `TradingAuditStore.kt:609:5 This annotation is not repeatable` (duplicate `@Synchronized`).
- Build Android APK: [38080355161](https://github.com/19wolfalone92/Williams/actions/runs/38080355161), job `114295816125`. Exact failure: same release-gate mismatch in `server.py`; APK build stopped at the gate before Kotlin compilation.

### Root-cause findings and changes made on the task branch

1. **Campaign state / protection ordering:** the recovery path attempted to create a hard stop before committing the exchange-confirmed fill to the campaign. The final protection validator correctly rejected the mutation because the durable campaign still had `position_qty == 0`. The flow now commits authoritative executed quantity/VWAP first, then creates/resizes protection. If protection fails or the process stops in that interval, the campaign is marked `RECONCILE_REQUIRED` and startup protection recovery remains mandatory. An unknown exchange status regression test was added.
2. **Restart adoption / observability:** recovery's exception result omitted `campaign_id`, masking the underlying reconciliation failure as `KeyError`. The error result now carries the known campaign identity. The restart test now asserts that the exchange is queried by the original `clientOrderId`, the same order is adopted, and no duplicate order is created.
3. **Native HTTP sender inventory:** the three senders were identified from source:
   - `StandaloneRuntime.kt:getBody()`: unsigned `GET baseUrl + path`, local/backend data/control API; not a Binance order mutation.
   - `StandaloneRuntime.kt:signedRequest()`: signed Binance REST method; the supported mutation path, with `validateAndPersistMutationIntent` before network and mandatory result write/read-back.
   - `StandaloneRuntime.kt:signedRawGet()`: signed Binance `GET` read helper; no POST/PUT/PATCH/DELETE builder.
   The test now encodes this specific inventory and checks that the two GET helpers remain read-only; it does not silently permit an unidentified fourth sender.
4. **Execution result versus telemetry:** the optional REST log call was removed from the `-1021` retry branch so the mandatory result and optional telemetry ordering assertion is meaningful. The timestamp rejection continues only with the same durable intent/clientOrderId. A failed mandatory result write still latches reconciliation and throws an UNKNOWN outcome.
5. **Release version:** `server.py`, `MainActivity.kt`, and `app/build.gradle.kts` all agree on `4.25.0` on both the task branch and `main`. The stale `ci_release_gate.py` constant `4.24.0` was the outlier. The gate now expects `4.25.0`, with a regression test comparing the gate, server, Android UI, and Gradle values.
6. **Duplicate command safety:** an early admission failure previously could persist `BLOCKED` over an existing `SUBMITTING`/`AMBIGUOUS` intent. That would erase a recovery obligation. The barrier now preserves an existing unresolved status on a repeated blocked command; tests assert that process-death and timeout states remain unresolved and the submit callback is not re-invoked.

### Follow-up Android lint failure

On intermediate SHA `be8d663f85b0f093ac2604ae818f9cbad1883971`, Python backend tests passed (run [38082475956](https://github.com/19wolfalone92/Williams/actions/runs/38082475956)), but Android APK workflow [38082475940](https://github.com/19wolfalone92/Williams/actions/runs/38082475940) failed at `:app:lintDebug`: `gradle/wrapper/gradle-wrapper.properties:3: Error: A newer version of Gradle than 8.14.5 is available: 8.14.6 [AndroidGradlePluginVersion]`. The wrapper and Android CI Gradle version were both advanced to `8.14.6` on this branch, and the release gate now checks that they stay aligned. The next intermediate run `38082867225` passed its Python test job but failed Android lint on `TradingAuditStore.kt:479`: `Use the KTX extension function SQLiteDatabase.transaction instead? [UseKtx]`. The manual transaction was replaced with the equivalent SQLite KTX transaction helper; exceptions still roll back, and result read-back remains after commit. This fix is not considered resolved until the final SHA completes lint, Android unit tests, and both APK builds successfully.

### Current validation and limits

The fixes above are committed only to `audit/williams-2.1-task-003-execution-safety`. Latest CI results for the final documentation-bearing SHA must still be captured below after the workflow runs complete. Until all required checks pass on one exact SHA and mutation-route review is complete, all unproven scenarios remain **NOT VERIFIED**.

Not established by offline tests alone:
- Full Binance Spot Testnet end-to-end recovery with a real accepted order whose HTTP response is lost.
- Live partial OCO fill plus sibling cancellation/fill race and exact residual inventory protection.
- Every Binance bulk-cancel variation; native unsupported order-like write endpoints are intended to fail closed, but this does not prove every possible external/legacy adapter is fenced.
- Android device process-kill/reinstall behavior and exchange-state restoration on physical hardware.
- Absence of every alternate order mutation path across all production call sites. Static inventories and tests are evidence, not a substitute for the final call-graph review.

No Mainnet endpoint, credentials, or real trading operation was used.


### Exact-SHA validation record — code SHA `5757463506e504b6ae3797c97561cfb108ff7fa9`

All three required PR workflows completed successfully on this same source SHA:

| Workflow | Run | Result / evidence |
|---|---|---|
| Python backend tests | [38083150396](https://github.com/19wolfalone92/Williams/actions/runs/38083150396) | PASS — `148 passed, 4 warnings`. |
| Williams Campaign CI | [38083150402](https://github.com/19wolfalone92/Williams/actions/runs/38083150402) | PASS — Python `180 passed, 4 warnings`; Android unit tests, lint and debug APK assembly passed. |
| Build Android APK | [38083150419](https://github.com/19wolfalone92/Williams/actions/runs/38083150419) | PASS — release contract gate, Android lint, unit tests, debug/release APK assembly, APK metadata checks and persistent debug signature verification passed. |

Build run `38083150419` reported:
- Debug APK: `versionName=4.25.0-debug`, `versionCode=1767`; SHA-256 `45e92c440a90eac653c310793230ab07521979e5277ba3096c99db0d90fe0897`.
- Unsigned release APK: `versionName=4.25.0`, `versionCode=1767`; SHA-256 `8b03527c0ec08c66532d0d00686d196d82ee2b3b05b84cdc7caf7ff051b59edc`.
- The debug APK signature matched the configured persistent debug keystore and `apksigner verify` succeeded.
- Debug artifact ID: `11680644565`. Release artifact was also uploaded by the workflow.

**SHA scope warning:** this record proves those workflow results for code SHA `5757463506e504b6ae3797c97561cfb108ff7fa9`, not for a later documentation-only commit. The PR's final HEAD must be checked again after this report commit; no prior-SHA result is to be reused as proof for a different SHA.

Even with green offline CI, the task remains **BLOCKED — NOT ACCEPTED** until the final HEAD has all required workflows green and the final mutation call-graph/recovery review is complete. The tests do not prove live Binance Testnet lost-response recovery or physical-device crash recovery.

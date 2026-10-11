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


### Documentation report SHA validation — `6bb3500b32e807c71aadaf66e1d34084e034c6cf`

After the evidence above was written, the report-bearing SHA `6bb3500b32e807c71aadaf66e1d34084e034c6cf` was independently checked. All three required PR workflows passed on that exact SHA:

- Python backend tests: [run 38083793938](https://github.com/19wolfalone92/Williams/actions/runs/38083793938) — **148 passed, 4 warnings**.
- Williams Campaign CI: [run 38083793903](https://github.com/19wolfalone92/Williams/actions/runs/38083793903) — **180 Python tests passed, 4 warnings**; Android unit tests, lint and debug assembly passed.
- Build Android APK: [run 38083793885](https://github.com/19wolfalone92/Williams/actions/runs/38083793885) — release gate, lint, Android unit tests, debug and unsigned release APK builds, metadata and debug signature checks all passed.

Artifacts from that build:
- Debug APK artifact ID `11681815110`; package `com.williamsbot.debug`, version `4.25.0-debug`, versionCode `1768`; SHA-256 `f1b0ac5d1affe54e39bad71c14730454250480143daebefc19e76aab376b6e00`.
- Unsigned release APK artifact ID `11680684989`; package `com.williamsbot`, version `4.25.0`, versionCode `1768`; SHA-256 `fdeca54ec792a6dab902a500089071ef7e4a084f6c8138b02a223f94ed0ffc8c`.
- Debug APK signature verification passed with the configured persistent debug keystore.
- Campaign CI debug APK artifact ID `11681690192`; lint report artifact ID `11680879566`; Python test report artifact ID `11681148413`.

The report-bearing SHA `b0917134f8df906289281e053bdf8bdf603e3e19` was then checked independently and all three required PR workflows passed on that exact SHA:
- Python backend tests: [run 38084161593](https://github.com/19wolfalone92/Williams/actions/runs/38084161593) — `148 passed, 4 warnings`.
- Williams Campaign CI: [run 38084161602](https://github.com/19wolfalone92/Williams/actions/runs/38084161602) — `180 passed, 4 warnings`; Android unit tests, lint and debug APK assembly passed.
- Build Android APK: [run 38084161697](https://github.com/19wolfalone92/Williams/actions/runs/38084161697) — lint, unit tests, debug/release APK builds, metadata and debug-signature checks passed.

For SHA `b0917134f8df906289281e053bdf8bdf603e3e19`, the debug APK was version `4.25.0-debug`, versionCode `1769`, SHA-256 `4f6fa6845daa1449df80ef5a9904f96351c69ef6f2314e5912e1d6e6406473f6`; the unsigned release APK was version `4.25.0`, versionCode `1769`, SHA-256 `9faada8ce27505174bb7774b6361aeb1727ed8cb4f881d15e9b8d50cf0a13402`. The workflow uploaded debug artifact ID `11681319295` and unsigned release artifact ID `11681463984`; debug signature verification passed.

Validation evidence is SHA-specific: the documentation-only commit that follows this record must also pass all three required workflows. Green CI does not, by itself, remove the overall `BLOCKED — NOT ACCEPTED` status while live Testnet lost-response recovery and physical-device crash recovery remain unverified.

### Latest exact-HEAD validation — code SHA `a5ac0afb64d40f8e96b62cb005cf233370cef997`

The branch and PR head were re-read from GitHub before recording this section. At the time of validation:
- Branch `audit/williams-2.1-task-003-execution-safety` HEAD: `a5ac0afb64d40f8e96b62cb005cf233370cef997`.
- PR #48 is open, draft, base `main`, and not merged.
- `main` HEAD remains `04da5f9c09572e99ff310be3a1908179165e528c`.
- The initial failing SHA `953d2c4e2831d1685f5531c76ac03fcedd42d00a` remains an incident baseline only; it is not used as proof for this SHA.

All three required workflows completed successfully on the same current HEAD SHA:
| Workflow | Run ID | Job ID(s) | Result |
|---|---:|---|---|
| Python backend tests | [38084552710](https://github.com/19wolfalone92/Williams/actions/runs/38084552710) | 114308236511 | PASS — `148 passed, 4 warnings`; release contract gate passed. |
| Williams Campaign CI | [38084552721](https://github.com/19wolfalone92/Williams/actions/runs/38084552721) | 114308294130, 114308294356 | PASS — `180 passed, 4 warnings`; Android unit-test task, `:app:lintDebug`, and debug APK assembly passed. |
| Build Android APK | [38084552703](https://github.com/19wolfalone92/Williams/actions/runs/38084552703) | 114308236635 | PASS — release gate, Android unit tests, lint, debug/release assembly, APK metadata checks and persistent debug-signature verification passed. |

APK evidence from Build Android APK run `38084552703`:
- Debug package `com.williamsbot.debug`, version `4.25.0-debug`, versionCode `1771`; APK SHA-256 `5519a4c565ca5ef6eb4f4ab5c87d9e55921cba1af7ee0e80c021b437389ce211`; artifact ID `11682280166`.
- Unsigned release package `com.williamsbot`, version `4.25.0`, versionCode `1771`; APK SHA-256 `aecf0dcccaf0cc041d9e27504674f00a58db8ae4d535ca6fd25cb55c4f805a18`; artifact ID `11681514552`.
- Debug APK signature matched the configured persistent debug keystore and `apksigner verify` succeeded.
- Campaign CI debug APK artifact ID `11681584299`; lint report artifact ID `11682120747`; backend pytest report artifact ID `11681930559`.

### Mutation-route review and explicit verification boundary

The current Android source has exactly three raw OkHttp `execute()` call sites in `StandaloneRuntime.kt`:
1. `NativeEngine.getBody(path)` — unsigned GET to `baseUrl + path` for read-only market/time data. It builds only GET requests; it is not a Binance order mutation boundary.
2. `NativeEngine.signedRequest(method, path, params)` — signed Binance request helper. Supported writes are classified by `executionMutationSpec`; `validateAndPersistMutationIntent` and the durable SQLite `PENDING → SUBMITTING` write/read-back occur before network submission. Unsupported order-like write endpoints fail closed. Mutation response state is committed and read back before the caller receives a successful return; failure latches `RECONCILE_REQUIRED`. REST telemetry is wrapped as optional best-effort logging after the mandatory result write.
3. `NativeEngine.signedRawGet(path, params)` — signed Binance GET-only helper; request construction has no POST/PUT/PATCH/DELETE branch. It records optional REST telemetry but does not submit orders.

Android write routes presently modeled in `executionMutationSpec`: POST `/api/v3/order`, POST `/api/v3/orderList/oco`, POST `/api/v3/order/cancelReplace`, DELETE `/api/v3/order`, and DELETE `/api/v3/orderList`. The DELETE open-orders bulk-cancel path and unmodeled order/algo-order write endpoints are intentionally blocked by the fail-closed classifier, not supported as a successful production feature. OCO cancellation/recovery requires child-leg state evidence; list-level acknowledgements alone are not sufficient.

The following checks are evidenced by the current offline CI test suites:
- Intent INSERT/save/commit/read-back failures prevent the Python exchange callback from being called.
- Duplicate parallel logical entries are serialized; ambiguous timeout and process-death-after-SUBMITTING tests forbid blind re-send.
- Mandatory exchange-result persistence failure remains ambiguous and blocks new exposure.
- Partial fill, cancel/fill race, OCO cancel/fill race, cancelReplace old-order partial fill, stale context and `RECONCILE_REQUIRED` gates have regression tests.
- Optional telemetry failure is not used as an execution result; release version regression checks `server.py`, `ci_release_gate.py`, Gradle and the Android UI.
- Campaign tests cover restart lookup by the stable `clientOrderId`, same-order adoption without duplicate placement, recovery into `OPEN` only after exchange-confirmed fill/protection, and blocking on genuinely unknown exchange state.

**NOT VERIFIED / acceptance blockers remain:**
- Live Binance Spot Testnet lost-response test in which Binance accepts a real Testnet order but the client deliberately drops the response; no trade was run as part of this task.
- Physical Android process-kill/restart recovery against actual Testnet order/fill/stop state.
- Live partial OCO fill with concurrent sibling fill/cancel and proof of exact residual inventory protection.
- Full repository-wide proof that no legacy/external caller can bypass the Python `ExecutionBarrier`; the branch has static checks and known call-path evidence, but the final call-graph inventory across every production call site remains to be completed.
- All Binance mass-cancel variants and unsupported algo-order mutation semantics.
- Fee reconciliation and complete post-restart account/position/protective-order equivalence against authoritative live exchange data.

Therefore, green CI on SHA `a5ac0afb64d40f8e96b62cb005cf233370cef997` is necessary but **does not satisfy acceptance**. Status remains **BLOCKED — NOT ACCEPTED** pending final call-graph proof and Testnet/device recovery evidence. No Mainnet endpoint, credentials, or real trading operation was used.

### Latest code validation and additional recovery guard — code SHA `4966e3aa164e61b0b2eac8f9da8b5c9c8cc7938d`

All three required workflows passed on the same code SHA after the additional legacy-entry recovery guard was added:
| Workflow | Run ID | Job ID(s) | Result |
|---|---:|---|---|
| Python backend tests | [38087293230](https://github.com/19wolfalone92/Williams/actions/runs/38087293230) | 114316344037 | PASS — `148 passed, 4 warnings`; release contract gate passed. |
| Williams Campaign CI | [38087293271](https://github.com/19wolfalone92/Williams/actions/runs/38087293271) | 114316401851, 114316401716 | PASS — `181 passed, 4 warnings`; Android unit-test task, lint and debug APK assembly passed. |
| Build Android APK | [38087293227](https://github.com/19wolfalone92/Williams/actions/runs/38087293227) | 114316343984 | PASS — release gate, Android unit tests, lint, debug/release APK builds, metadata checks and debug-signature verification passed. |

Artifact evidence for SHA `4966e3aa164e61b0b2eac8f9da8b5c9c8cc7938d`:
- Debug APK: `com.williamsbot.debug`, `4.25.0-debug`, versionCode `1792`, SHA-256 `3161b9b4cae6c43afc12c35b3705e33c1bd772f15be5b15078af503e26675217`, artifact ID `11681924176`.
- Unsigned release APK: `com.williamsbot`, `4.25.0`, versionCode `1792`, SHA-256 `e7bceee5d94aaf4c1b7306906f60be48206ab1664ee97fff36c01b626b40f29b`, artifact ID `11682734188`.
- Android unit-test diagnostics artifact ID `11682624265`; Campaign CI debug APK artifact ID `11682224935`; lint report artifact ID `11682764085`; backend pytest report artifact ID `11682028779`.

The call-graph review identified an additional local recovery-state hazard in `Trader.market_buy`: after `ExecutionBarrier` refused a request, the legacy caller unconditionally deleted `entry_client_order_id` and wrote `FLAT`. A concurrent unresolved mutation could therefore lose its recovery key even though the Barrier still blocked the exchange mutation. The code now checks the durable unresolved-intent/global latch before reserving a new CID, and re-checks the latch after a rejected Barrier result; unresolved or unreadable state preserves the key and sets `RECONCILE_REQUIRED`. Regression test `test_legacy_trader_preserves_entry_identity_while_execution_is_unresolved` passed in Campaign CI.

**Final acceptance remains BLOCKED — NOT ACCEPTED.** This code SHA's CI results are not evidence for the documentation commit that follows. That resulting PR head must independently pass all three required workflows. Live Testnet accepted-order/response-loss recovery, physical Android process-death recovery, live OCO sibling-fill races, and a fully exhaustive production call-graph proof remain **NOT VERIFIED**. No Mainnet or real trading operation was used.

### Incident baseline and current verification — TASK 003

This section distinguishes the original failing source SHA from later validation. The initial failures below are preserved as diagnostic evidence only; they are not used as proof for later source states.

**Original failing SHA:** `953d2c4e2831d1685f5531c76ac03fcedd42d00a`  
**Initial branch state:** `main` was `04da5f9c09572e99ff310be3a1908179165e528c`; PR #48 was open, draft, and not merged.

Original failed workflow runs on that exact SHA:
- Python backend tests — run [38080355142](https://github.com/19wolfalone92/Williams/actions/runs/38080355142), job `114295816305`: release gate failed with `AssertionError: server.py: missing "VERSION = '4.24.0'"`.
- Williams Campaign CI — run [38080355099](https://github.com/19wolfalone92/Williams/actions/runs/38080355099). Python job `114295825122` reported `5 failed, 172 passed, 4 warnings`; Android job `114295824990` failed Kotlin compilation at `TradingAuditStore.kt:609:5` with `This annotation is not repeatable.`.
- Build Android APK — run [38080355161](https://github.com/19wolfalone92/Williams/actions/runs/38080355161), job `114295816125`: the same stale release gate expectation `VERSION = '4.24.0'` failed; the job also showed the workflow was using Gradle `8.14.5`.

The five Python/Campaign test failures on the baseline SHA were:
1. `test_trigger_then_reconcile_creates_hard_stop`: expected `OPEN`, got `RECONCILE_REQUIRED`.
2. `test_partial_conditional_entry_is_cancelled_and_residual_protected`: expected `OPEN`, got `RECONCILE_REQUIRED`.
3. `test_restart_adopts_pending_campaign_by_client_id`: `KeyError: 'campaign_id'`.
4. `test_native_android_signed_order_mutations_are_fenced_before_network`: raw HTTP sender count differed from the expected inventory. This was treated as an inventory alarm, not grounds to weaken the test.
5. `test_native_optional_rest_telemetry_is_not_execution_authority`: `assert 4214 < 3373`; the source-order assertion did not match the actual mutation-result and telemetry code positions.

**Root-cause disposition and safety impact**
- Campaign recovery: recovery had been too coarse for confirmed exchange outcomes and partial fills, so it could leave a campaign in `RECONCILE_REQUIRED` even when the conditional order and fill/protection sequence could be reconciled. Recovery now queries the order by stable `clientOrderId`, resolves only authoritative states, persists confirmed fills before protection mutations, and leaves unknown/error responses blocked. Regression coverage includes both successful recovery and genuinely unknown exchange state. The latter must remain `RECONCILE_REQUIRED`.
- Restart adoption: the recovery response path did not consistently return campaign identity for a recovered pending entry. The regression now asserts the stable identity, authoritative lookup before adoption, one exchange order for the logical client ID, and no campaign success based on unconfirmed state. Unresolved states retain their reservation/recovery identity.
- Android HTTP sender inventory: the three raw `execute()` sites were enumerated rather than hidden by changing the expected count: `NativeEngine.getBody(path)` is unsigned GET-only market/time reads; `NativeEngine.signedRequest(method, path, params)` is the only modeled signed mutation path and requires durable intent before network; `NativeEngine.signedRawGet(path, params)` is signed GET-only and has no write method branch. The signed mutation helper rejects unsupported order-like writes before network. Modeled writes are POST `/api/v3/order`, POST `/api/v3/orderList/oco`, POST `/api/v3/order/cancelReplace`, DELETE `/api/v3/order`, and DELETE `/api/v3/orderList`. Mass cancel and unmodeled order/algo-order writes fail closed.
- Execution result versus telemetry: mandatory mutation result persistence/read-back is now the execution authority. A failure latches reconciliation and does not return success; REST telemetry is best-effort and cannot override the durable mutation result. This distinction has source-contract regression coverage.
- Version/build contract: project source and Gradle consistently use canonical app version `4.25.0`; `server.py`, the UI, Gradle and `ci_release_gate.py` are checked together by a regression test. The stale `4.24.0` expectation was corrected to match the established project version rather than disabling the gate. Gradle wrapper and Android workflow are aligned at `8.14.6`. The duplicate Kotlin annotation was removed.

### Exact-HEAD validation snapshot before this report update

At the time this section was prepared, GitHub reported:
- Branch `audit/williams-2.1-task-003-execution-safety` HEAD: `57eec5d08c8080ec94686ef75f984fee571109ed`.
- PR #48: open, draft, base `main`, not merged.
- `main` HEAD: `04da5f9c09572e99ff310be3a1908179165e528c` (unchanged).
- All three required workflows passed on that exact source SHA:
  - Python backend tests — run [38087582153](https://github.com/19wolfalone92/Williams/actions/runs/38087582153), job `114317202706`: **148 passed, 4 warnings**; release contract gate passed.
  - Williams Campaign CI — run [38087582166](https://github.com/19wolfalone92/Williams/actions/runs/38087582166), Python job `114317202660`: **181 passed, 4 warnings**; Android job `114317202799`: Android unit-test task, lint and debug APK assembly succeeded.
  - Build Android APK — run [38087582183](https://github.com/19wolfalone92/Williams/actions/runs/38087582183), job `114317202782`: release gate, lint, Android unit tests, debug/release APK assembly and metadata/signature checks succeeded. APK metadata: debug `4.25.0-debug`, versionCode `1798`; release `4.25.0`, versionCode `1798`.

This is an exact-SHA snapshot for `57eec5d08c8080ec94686ef75f984fee571109ed` only. Updating this report creates a new source SHA, so all three workflows must be checked again against the resulting HEAD; the prior green results must not be reused as evidence for the report-bearing commit.

**Still NOT VERIFIED / acceptance blockers**
- Live Binance Spot Testnet test where an order is accepted but the HTTP response is deliberately lost; no real trade was run.
- Physical Android process-kill/restart against actual Testnet order, fill, balance and protective-order state.
- Live partial OCO fill with concurrent sibling cancellation/fill and proof of exact residual inventory protection.
- Exhaustive repository-wide proof that every legacy/external production call site routes through the Python `ExecutionBarrier`; current tests and source inventory do not prove every external adapter.
- Every Binance mass-cancel variation and unsupported algo-order mutation behavior.
- Full fee-adjusted account/position/protective-order equivalence after restart against authoritative live exchange data.

Overall task status remains **BLOCKED — NOT ACCEPTED**. Green CI is necessary but does not substitute for the unresolved live Testnet/device recovery and exhaustive call-graph evidence. No Binance Mainnet endpoint, credentials, or real trading operation was used.


### Latest source review — TASK 003 Gradle contract and mutation-gate limitation

**Review basis before this report commit:** branch HEAD `a95d653df4cc6ae686bdcfa77b743d7950877d7e`; PR #48 remains open/draft and unmerged; `main` remains `04da5f9c09572e99ff310be3a1908179165e528c`. Results from other SHAs are not treated as validation of the report-bearing SHA.

#### Additional confirmed CI contract mismatch and correction

The exact-head review found a version mismatch not covered by the prior release gate:
- `gradle/wrapper/gradle-wrapper.properties`: Gradle `8.14.6`.
- `.github/workflows/android-apk.yml`: `GRADLE_VERSION: '8.14.6'`.
- `.github/workflows/campaign-ci.yml`: was pinned to `gradle-version: "8.14.5"`, and the Campaign CI log for run `38089001009` confirms Gradle `8.14.5` was provisioned.
- App version remains canonical `4.25.0` in `server.py`, `app/build.gradle.kts`, Android UI and release gate.

Correction on this task branch: Campaign CI is now pinned to `8.14.6`; `ci_release_gate.py` asserts both Android workflows match the wrapper; `test_release_version_contracts_are_consistent` checks the wrapper, APK workflow and Campaign workflow. This does not change `main` or disable a gate. All workflows must pass on the final SHA created by these changes.

#### Exact original error evidence (initial failing SHA only)

Run/job identities and source SHA are in the incident record above. The key unabridged error strings captured from the original GitHub Actions logs were:

```text
AssertionError: server.py: missing "VERSION = '4.24.0'"
```

This was the terminal release-gate assertion in both Python backend run `38080355142` (job `114295816305`) and Build Android APK run `38080355161` (job `114295816125`). The gate stopped the respective validation before downstream checks could serve as evidence.

Campaign Python job `114295825122` ended with:

```text
test_campaign_execution.py:96: AssertionError
E           AssertionError: assert 'RECONCILE_REQUIRED' == 'OPEN'
E             - OPEN
E             + RECONCILE_REQUIRED

test_campaign_execution.py:123: AssertionError
E           AssertionError: assert 'RECONCILE_REQUIRED' == 'OPEN'
E             - OPEN
E             + RECONCILE_REQUIRED

test_campaign_execution.py:146: KeyError
E           KeyError: 'campaign_id'

test_task003_execution_safety.py:539: AssertionError
E       AssertionError: native raw HTTP senders changed; audit every path before acceptance
E       assert 3 == 2

test_task003_execution_safety.py:573: AssertionError
E       assert 4214 < 3373

5 failed, 172 passed, 4 warnings in 4.44s
```

The Campaign Android job `114295824990` ended at Kotlin compilation with:

```text
e: file:///home/runner/work/Williams/Williams/app/src/main/java/com/williamsbot/TradingAuditStore.kt:609:5 This annotation is not repeatable.
> Task :app:compileDebugKotlin FAILED
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
> Compilation error. See log for more details
BUILD FAILED in 30s
```

These are preserved as baseline diagnostics only, not evidence of the present source state. Their root-cause disposition is documented above.

#### Remaining safety finding — native mutation gate is conservative but not yet accepted

At the current source, `NativeEngine.validateAndPersistMutationIntent` checks the global kill/reconciliation/unresolved-intent gates for BUY exposure, then applies `unresolvedExecutionMutationCount(spec.symbol) == 0` to **every** modeled mutation. This safely prevents ambiguous mutations from being repeated, but it also blocks cancellation or a reducing/protective operation on a symbol with any unresolved mutation. The required distinction between (a) an exact-target cancellation after authoritative order lookup and (b) a reducing exit against a separately confirmed position is therefore **NOT VERIFIED as implemented**. Do not weaken this gate by simply allowing all SELL/DELETE operations; an evidence-based, exact-identity recovery rule and regression tests are required.

Also, the native low-level boundary proves durable intent, mutation classification, kill/reconciliation latch and unresolved-intent gates. Signal freshness, campaign ownership, full risk-budget validation and position-quantity authority are currently enforced by higher-level call paths; this review has not yet proved every legacy/manual/emergency call site independently carries all of those checks. The source inventory is not a proof of absence of every bypass.

**Acceptance blockers after this source review**
- Exact-target cancellation and confirmed-position reducing exit need distinct recovery/admission rules when a symbol has an unresolved mutation.
- Exhaustive call-graph evidence must establish that every entry/replace/cancel/exit route retains signal freshness, campaign ownership, risk, and reconciliation checks.
- Live Testnet lost-response recovery, physical-device crash recovery, live OCO sibling-fill race, mass-cancel variations and fee-adjusted account/position equivalence remain **NOT VERIFIED**.

The task remains **BLOCKED — NOT ACCEPTED**, regardless of green CI. No Binance Mainnet endpoint, credentials, or real trade was used.

### Exact-SHA CI evidence — code SHA `f6f78cb9f57811738623409642cc5e33b7c0b79a`

This snapshot was checked after the Gradle contract test correction. All three required workflows completed successfully on this same source SHA; this does not close the explicit acceptance blockers listed above.

| Workflow | Run ID | Job ID(s) | Conclusion / result |
|---|---:|---|---|
| Python backend tests | [38089938567](https://github.com/19wolfalone92/Williams/actions/runs/38089938567) | 114324125821 | PASS — release gate passed; `148 passed, 4 warnings`. Pytest report artifact ID `11683104035`. |
| Williams Campaign CI | [38089938538](https://github.com/19wolfalone92/Williams/actions/runs/38089938538) | 114324174670, 114324174851 | PASS — Python Campaign suite `181 passed, 4 warnings`; Android unit tests, `:app:lintDebug`, and `:app:assembleDebug` passed. Lint report artifact ID `11684033299`; debug APK artifact ID `11683553969`. |
| Build Android APK | [38089938534](https://github.com/19wolfalone92/Williams/actions/runs/38089938534) | 114324125701 | PASS — release gate, quant source validation, Android lint, unit tests, debug/release APK builds, package metadata and persistent debug-signature checks passed. |

Build metadata from the successful APK workflow: app version `4.25.0`, versionCode `1881`; debug package `com.williamsbot.debug` / `4.25.0-debug`; release package `com.williamsbot` / `4.25.0`. Artifact IDs: debug APK `11683063035`, unsigned release APK `11682919923`. Uploaded archive digests were `ea2647d8269fc1c2de166fc32863d6acf886ebae01bce714c49c655801e54bc6` and `688f233982cd1b8e4259925ac958dfe6188931807268cee8b09b00c8cd1e0bc9`, respectively; these are workflow artifact-archive digests, not claimed as the raw APK file hashes.

The branch and PR were rechecked before this report update: branch HEAD was `f6f78cb9f57811738623409642cc5e33b7c0b79a`; `main` remained `04da5f9c09572e99ff310be3a1908179165e528c`; PR #48 remained open, draft, and unmerged. This documentation commit changes the branch HEAD, so the three workflows must be rerun against the resulting report-bearing SHA before claiming exact-final-SHA CI validation.

The task remains **BLOCKED — NOT ACCEPTED**. Green CI does not verify live Testnet response-loss recovery, physical Android process-death recovery, exact residual inventory after live OCO sibling-fill races, all mass-cancel variants, or the exhaustive absence of external/legacy bypasses. No Binance Mainnet or real trade was used.


### Exact-SHA verification after Gradle contract correction — SHA `575159b7cd7c837179ac6b96f064527c361ebf8a`

GitHub branch/PR state was checked before this section was added:
- Branch HEAD: `575159b7cd7c837179ac6b96f064527c361ebf8a`.
- PR #48: open, draft, not merged; base `main`.
- `main` remained `04da5f9c09572e99ff310be3a1908179165e528c`.
- All three required workflows completed successfully on this same SHA:

| Workflow | Run ID | Job ID(s) | Result |
|---|---:|---|---|
| Python backend tests | [38090340316](https://github.com/19wolfalone92/Williams/actions/runs/38090340316) | 114325305035 | PASS — `148 passed, 4 warnings`; release gate passed. |
| Williams Campaign CI | [38090340338](https://github.com/19wolfalone92/Williams/actions/runs/38090340338) | 114325316855, 114325316977 | PASS — `181 passed, 4 warnings`; Android unit tests, lint and debug APK build succeeded. Both Python and Android jobs provisioned Gradle `8.14.6`. |
| Build Android APK | [38090340317](https://github.com/19wolfalone92/Williams/actions/runs/38090340317) | 114325304653 | PASS — release gate, lint, Android unit tests, debug and unsigned release APK assembly, metadata checks and debug-signature verification passed. Gradle `8.14.6`. |

APK artifacts from Build Android APK run `38090340317`:
- Debug APK artifact ID `11682988607`: package `com.williamsbot.debug`, version `4.25.0-debug`, versionCode `1892`.
- Unsigned release APK artifact ID `11683882895`: package `com.williamsbot`, version `4.25.0`, versionCode `1892`.
- Campaign CI debug APK artifact ID `11683239965`; lint report artifact ID `11683539774`; backend pytest report artifact ID `11684042225`.
- APK signature verification and metadata checks passed. No checksum value is recorded here because the job log excerpt available for this review exposed the checksum commands but not their resulting digest lines.

The release-gate regression now checks the app version `4.25.0` across server/UI/Gradle and compares both CI Gradle pins against the wrapper version. The test initially exposed an over-escaped regex; the regex was corrected, and the above Campaign/backend workflows passed on the exact SHA after correction.

**This SHA's CI is necessary evidence, not acceptance.** The report update that follows changes the branch SHA; the three workflows have been configured to run when `docs/**` changes so the report-bearing commit can also be checked. The conservative Android same-symbol unresolved-mutation gate and the unverified recovery scenarios listed above remain blockers. Final status remains **BLOCKED — NOT ACCEPTED**.


### Exact-SHA verification — report-bearing source SHA `8534f5e306c672c650feccebe45d67ae28792fe4`

Live GitHub state was re-read for this snapshot:
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c` (unchanged).
- Working branch HEAD and PR #48 head: `8534f5e306c672c650feccebe45d67ae28792fe4`.
- PR #48: open, draft, unmerged; base is `main`.
- All three required workflow runs below report this exact head SHA.

| Workflow | Run ID | Job ID(s) | Observed result |
|---|---:|---|---|
| Python backend tests | [38090701126](https://github.com/19wolfalone92/Williams/actions/runs/38090701126) | 114326353144 | PASS — release contract gate and static audit passed; pytest `148 passed, 4 warnings`. |
| Williams Campaign CI | [38090701118](https://github.com/19wolfalone92/Williams/actions/runs/38090701118) | 114326353125, 114326353314 | PASS — Campaign/Python suite `181 passed, 4 warnings`; Android `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` succeeded. |
| Build Android APK | [38090701220](https://github.com/19wolfalone92/Williams/actions/runs/38090701220) | 114326353726 | PASS — release gate, lint, Android unit tests, debug and unsigned release APK assembly, APK metadata checks and persistent debug-signature verification succeeded. |

APK evidence from run `38090701220`:
- Debug APK: package `com.williamsbot.debug`, version `4.25.0-debug`, versionCode `1893`, SHA-256 `4fc376b0e3f0f36e161469771e3ef7bd19ce5791092ceb2dade8314efcfcad0d`.
- Unsigned release APK: package `com.williamsbot`, version `4.25.0`, versionCode `1893`, SHA-256 `507386f60dd5a86951c3499a946c116dd640aa9d60bb5f8a4c5ae1a9c05a0d5e`.
- Artifact IDs: debug APK `11684570193`; unsigned release APK `11684415413`; Android unit-test diagnostics `11683353494`; Campaign CI debug APK `11683693458`; lint report `11683858044`; Python pytest report `11683524761`.

The original failing snapshot `953d2c4e2831d1685f5531c76ac03fcedd42d00a` remains historical baseline evidence only. Its recorded failures were: release gate expected server version `4.24.0`; Kotlin compilation reported a non-repeatable annotation at `TradingAuditStore.kt:609`; Campaign tests observed `RECONCILE_REQUIRED` instead of `OPEN` in two deterministic MockExchange cases; restart adoption returned no `campaign_id`; native sender inventory detected three HTTP senders; and the telemetry-order static assertion failed. The later code snapshots fixed the build/test regressions without removing the release gate or lowering the sender inventory assertion. The canonical version contract now consistently uses `4.25.0` in `server.py`, UI, Gradle and `ci_release_gate.py`; wrapper/CI Gradle pins are `8.14.6`.

#### HTTP sender inventory at this snapshot

1. `StandaloneRuntime.kt:getBody(path)` — unsigned GET-only fetch to `baseUrl + path` (market/data reads); no order mutation verb is exposed. No write-ahead order intent is applicable to this read-only path.
2. `StandaloneRuntime.kt:signedRequest(method, path, params)` — signed Binance REST boundary used for modeled POST/DELETE mutations and signed GETs. Unsupported order-like writes fail before network. Modeled mutations call `validateAndPersistMutationIntent` before the HTTP request and durably record/read back the result afterward; failure latches reconciliation and forbids blind retry.
3. `StandaloneRuntime.kt:signedRawGet(path, params)` — signed GET-only Binance reads used for exchange lookups/reconciliation; the function builds only GET requests and records REST diagnostics best-effort. It does not submit/cancel/replace orders.

The test now expects three senders only after this inventory was performed; it still asserts the third helper is GET-only and checks that the signed mutation helper persists intent and execution result. This static inventory is not proof that every high-level call path carries every strategy/risk/ownership invariant.

#### Current limitations — do not accept the task

- Campaign deterministic recovery, restart-by-clientOrderId regression, native mutation fence static tests, result-write fail-closed tests, release contract gate, Python/Campaign suites, Android unit tests, lint and APK builds are green on the SHA above.
- **NOT VERIFIED:** physical Android process death at each intent/result durability boundary; live Binance Spot Testnet accepted-request/lost-response reconciliation; complete authoritative position and fee reconciliation after result persistence failure; live OCO sibling-fill races and exact residual protection; every mass-cancel variant; and exhaustive proof that no legacy/external call path bypasses the mutation boundary.
- The low-level native mutation gate remains conservative: unresolved mutations block same-symbol mutations generally. Safe exact-target cancellation and reducing exits need separate evidence-based admission rules; do not loosen the gate to allow all SELL/DELETE operations.
- High-level signal freshness, campaign ownership, risk budget, and confirmed position quantity must still be proven for every entry, replace, cancel, emergency exit and protective-order path. CI/static tests alone do not establish this end-to-end property.

These successful runs are evidence for this exact code SHA, not final acceptance. This documentation-only update changes the branch SHA; the three workflows must complete again on the resulting SHA before claiming exact-final-SHA CI evidence. Final status: **BLOCKED — NOT ACCEPTED**. No Binance Mainnet or real trade was used.

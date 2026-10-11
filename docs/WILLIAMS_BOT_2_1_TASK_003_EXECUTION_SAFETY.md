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


### TASK 003.1 — exact-HEAD checkpoint (2026-10-11)

This is a factual checkpoint, not a task-acceptance declaration.

- Branch: `audit/williams-2.1-task-003-execution-safety`.
- Branch HEAD / PR #48 head observed before this documentation update: `2519e122584c9fb78b023c81b5c39864e24e194f`.
- PR #48: open, draft, unmerged; base branch `main` at `04da5f9c09572e99ff310be3a1908179165e528c`.
- The diagnostic SHA `953d2c4e2831d1685f5531c76ac03fcedd42d00a` is an incident baseline only and is not treated as evidence for current code.

GitHub Actions reported the following completed results for the exact observed SHA `2519e122584c9fb78b023c81b5c39864e24e194f`:

| Workflow | Run ID | Job ID(s) | Result |
|---|---:|---|---|
| Python backend tests | 38115555012 | 114399629624 | SUCCESS — release contract gate, Python compilation, static safety audit and offline tests completed. |
| Williams Campaign CI | 38115555061 | 114399629541, 114399629730 | SUCCESS — deterministic Campaign/Python tests; Android debug unit tests, lint and debug APK assembly completed. |
| Build Android APK | 38115555058 | 114399629554 | SUCCESS — release contract gate, Python checks, Android lint/unit tests, debug and unsigned release APK builds, metadata and signature checks completed. |

The three runs above agree on the same SHA as returned by the commit-associated workflow-runs API. This establishes workflow completion only; it does not prove the full production safety contract or live/device recovery.

#### TASK 003.1 acceptance status

**BLOCKED — NOT ACCEPTED.** The following remain NOT VERIFIED unless separately evidenced by reproducible tests and reviewed call paths:

1. Physical Android process termination and restart at each intent/result persistence boundary.
2. Live Spot Testnet read-only reconciliation of an accepted order whose response was lost; no order was created or cancelled for this checkpoint.
3. Authoritative fee/fill/position reconstruction after a local result-write failure, including duplicate fill delivery and unknown commission handling.
4. Live OCO partial-fill/sibling-cancel races and exact residual protection; all supported bulk-cancel variants.
5. Exhaustive call-graph proof that every entry, exit, replace, cancel, OCO and protective mutation reaches the barrier and applies applicable risk/ownership/recovery gates.
6. Cross-process concurrency and forced-process-death recovery on the Android SQLite store, beyond the CI tests explicitly reported above.

The native mutation fence currently blocks unresolved same-symbol mutations conservatively. Do not loosen that rule or treat a SELL/DELETE operation as inherently safe without explicit target identity, confirmed exposure, durable intent and reconciliation semantics. Green CI is necessary but not sufficient for acceptance.

This report update creates a new branch commit. The three workflows must be checked again against the resulting report-bearing SHA; results for `2519e122584c9fb78b023c81b5c39864e24e194f` must not be reused as final-SHA evidence. `main` must remain unchanged and PR #48 must remain unmerged.


### TASK 003.2 — exact-HEAD audit checkpoint (2026-10-11)

**Status: BLOCKED — NOT ACCEPTED.** This section records the observed state and gaps; it does not claim the acceptance criteria are complete.

#### Repository / exact-SHA CI

- Repository: `19wolfalone92/Williams`.
- Branch: `audit/williams-2.1-task-003-execution-safety`.
- Branch HEAD / PR #48 head at inspection: `ff6d9be08616ca5605e9206ea4cbf4482e422cc2`.
- Base `main`: `04da5f9c09572e99ff310be3a1908179165e528c`; PR #48 remains open, draft, unmerged. No merge was performed.
- The commit-associated GitHub Actions query returned three completed successful workflows for this exact SHA:

| Workflow | Run ID | Job ID | Status | Evidence |
|---|---:|---:|---|---|
| Python backend tests | 38116231828 | 114401554968 | PASS | https://github.com/19wolfalone92/Williams/actions/runs/38116231828 |
| Williams Campaign CI | 38116231835 | 114401554881, 114401555072 | PASS | https://github.com/19wolfalone92/Williams/actions/runs/38116231835 |
| Build Android APK | 38116231794 | 114401554546 | PASS | https://github.com/19wolfalone92/Williams/actions/runs/38116231794 |

The associated jobs report conclusion `success`. This proves the configured jobs completed successfully at the queried commit; it does **not** prove all Task 003.2 scenarios, and does not imply a physical-device or live-exchange test. Per-test totals and artifacts were not independently re-extracted in this checkpoint; do not infer unreported test counts.

#### Source inventory — evidence and boundaries

| Entry point / path | Admission gate | Durable intent / barrier | Network sink / result | Recovery / tests | Status |
|---|---|---|---|---|---|
| Python `ExecutionBarrier.execute` | Context/version, signal age, required entry risk proof, final pre-submit callback, identity claims and reconciliation lock are present in the inspected implementation | `Database.save_execution_intent`, stable client-order identity claims, durable submit/result handling | The barrier accepts a supplied `submit` callback; this module alone does not enumerate every production callback to the exchange client's actual socket call | `test_task003_execution_safety.py` has persistence-failure, timeout/ambiguous, duplicate-thread entry, partial-fill, cancel/fill race, OCO cancel race and cancel-replace tests | PARTIAL — exhaustive production caller-to-client sink map NOT VERIFIED |
| Python campaign/trader/portfolio callers | Several modules are in the PR diff (`campaign_execution.py`, `portfolio_trader.py`, `trader.py`) | Expected to route through barrier, but all transitive callers and legacy/manual/worker paths have not been proven exhaustively in this checkpoint | Actual client sink closure has not been independently traced for every caller | Static and integration checks exist; no complete mutation-path test matrix was extracted here | NOT VERIFIED |
| Android `StandaloneRuntime.signedRequest(method,path,params)` | Explicitly models supported order, OCO and cancel-replace mutations; entry checks include kill/reconciliation/unresolved intents; unresolved same-symbol mutation gate applies broadly | `validateAndPersistMutationIntent` calls `beginExecutionMutation` then `markExecutionMutationSubmitting` before request; result is persisted by `recordExecutionMutationResult`; ambiguous outcomes latch reconciliation | The inspected static test asserts this ordering relative to `http.newCall(request).execute()` | Static source assertions exist; this is not a runtime mutation-spy or fault-injected Android test | PARTIAL — sink fencing has static evidence, runtime coverage NOT VERIFIED |
| Android `getBody(path)` | GET-only local/backend read helper | No exchange mutation intent applicable | OkHttp GET | Static test asserts GET usage | PASS for read-only method shape only |
| Android `signedRawGet(path,params)` | GET-only signed Binance read/reconciliation helper | No mutation intent applicable | OkHttp GET | Static test asserts no POST/DELETE/PUT in helper | PASS for read-only method shape only |
| Android other/legacy/external paths | No complete exhaustive transitive caller graph was established | Not established for every possible call site | Static test counts three raw OkHttp send sites in this source snapshot, but that count is not proof that no alternate client/library/process can mutate | Physical app, external command, and every legacy path not exercised | NOT VERIFIED |

#### Confirmed test coverage versus required matrix

The inspected `test_task003_execution_safety.py` includes actual temporary SQLite database tests for: intent-save failure preventing exchange callback; injected intent commit failure; missing mandatory writer; two parallel threads attempting the same entry; timeout after possible send blocking retry; cancel/fill race; OCO cancel/fill race; cancel-replace partial old fill; stale context; reconciliation state blocking entry; optional telemetry failure; mandatory result persistence failure; missing/corrupt store fail-closed; and static Android source-boundary assertions.

These tests are useful evidence, but **do not satisfy the full Task 003.2 matrix**:
- OS-process concurrency against one persistent SQLite file: NOT VERIFIED (the inspected duplicate-entry test uses threads in one process).
- Full fault injection at all 18 specified boundaries, including fill, position, Campaign, risk-reserve, protection writes, `SQLITE_BUSY/SQLITE_LOCKED`, malformed recovery rows and interruption during reconciliation: NOT VERIFIED.
- Independent-process duplicate fill application and exactly-once financial effects across fill, fees, position, Campaign, risk and protection: NOT VERIFIED.
- Actual Android mutation-spy proving zero network mutation for each negative admission/persistence scenario: NOT VERIFIED.
- Android process-death/relaunch tests at every durability boundary: NOT VERIFIED.
- Live Spot Testnet accepted-order/lost-response reconciliation and OCO/cancel-replace races: NOT RUN.
- Physical Android device: NOT VERIFIED.
- Futures: NOT VERIFIED; Spot-oriented evidence must not be generalized to Futures.

#### Financial integrity / recovery findings

- Python DB setup uses WAL and `synchronous=FULL`, a per-instance thread lock, and `BEGIN IMMEDIATE` for its transaction context. This is evidence for local transaction configuration, **not** proof of cross-process exactly-once financial effects.
- Python `ExecutionBarrier` correctly treats possible-send timeout and mandatory result persistence failure as ambiguous in the inspected test scenarios; new entries are expected to remain blocked. Exhaustive reconciliation across all callers and all ledger tables remains NOT VERIFIED.
- Android `TradingAuditStore` schema uses SQLite `REAL` for order/fill quantities, prices and commission amounts. That representation is not a decimal-exact financial ledger; end-to-end Decimal/integer-quantized accounting equivalence is NOT VERIFIED.
- Android `executions.commission` has `REAL DEFAULT 0`, while `commission_asset` is nullable. The schema alone does not prove unknown commission is distinguishable from a known zero commission in every consumer. A complete unknown-fee propagation audit and recovery test is NOT VERIFIED.
- Android schema contains a `fee_known` field for the `trades` table, but existing/upgrade defaults and every writer/consumer have not been demonstrated to preserve unknown fee state end-to-end. Fee correctness remains NOT VERIFIED.
- No evidence in this checkpoint proves that fill persistence, position, Campaign, risk reservation and protection are committed as one atomic financial effect or recover idempotently after process death. That requirement remains a P0 blocker.
- OCO and cancel-replace response classifiers conservatively reject missing per-leg/terminal evidence in the inspected paths. This is useful fail-closed behavior, not proof of authoritative reconciliation for every exchange race or supported mass-cancel variation.

#### Acceptance matrix — Task 003.2

| Criterion | Status | Reason |
|---|---|---|
| Exact HEAD / PR state checked; no merge | PASS | Observed SHA and open/draft/unmerged PR recorded above |
| Three required workflows successful on same exact SHA | PASS | Run IDs above |
| All production mutation sinks and transitive caller chains inventoried | NOT VERIFIED | Complete caller-to-network map not proven |
| No unprotected production mutation bypass | NOT VERIFIED | Static sender inventory is not exhaustive bypass proof |
| Mandatory persistence failure before send prevents network call | PARTIAL | Covered for selected Python fault points; Android runtime sink-spy and full matrix absent |
| Unknown POST outcome blocks blind retry and requires reconciliation | PARTIAL | Python timeout and selected Android static/result handling evidence; exhaustive recovery not proven |
| Stable logical identity prevents repeated entry | PARTIAL | Same-process/thread test and stable IDs; two OS processes not tested |
| Full 18-point persistence fault injection | NOT VERIFIED | Only selected fault points are currently covered |
| Two independent OS processes on shared persistent SQLite | NOT VERIFIED | Existing test observed here uses threads |
| Duplicate fill cannot duplicate fees/PnL/position/Campaign/risk effects | NOT VERIFIED | No full cross-process financial-ledger proof |
| Authoritative fees, balances, position, Campaign, risk and protection reconcile | NOT VERIFIED | No end-to-end recovery proof |
| Android mutation-spy negative scenarios | NOT VERIFIED | Existing Android evidence is static source assertions |
| Android process-death recovery | NOT VERIFIED | No physical/runtime proof supplied |
| OCO/cancel-replace all required crash/race scenarios | PARTIAL | Selected classifier/unit scenarios exist; complete matrix/live evidence absent |
| Global recovery gate covers all entry paths and all ledger stages | NOT VERIFIED | Exhaustive gate-release/call-path audit incomplete |
| Spot Testnet live recovery | NOT RUN | No exchange order placed or cancelled |
| Physical Android device | NOT VERIFIED | No device test claimed |
| Futures scenarios | NOT VERIFIED | Not established by Spot-focused checks |
| `main` unchanged and no merge | PASS | PR remains unmerged; base SHA recorded |

#### Remaining P0 / required next work

1. Add and run a full production mutation-sink/caller-chain inventory with tests observing the actual exchange-client sink, not only source text.
2. Add OS-process tests using two independent processes and one persistent SQLite file for duplicate signal/intent/fill, commit crash, ambiguous POST and recovery contention.
3. Implement the complete specified fault-injection matrix across durable intent, execution events, fills, fees, position, Campaign, risk reservation and protection.
4. Prove an atomic/idempotent financial-ledger application model; replace or strictly bound floating-point accounting where financial equality depends on decimal precision; propagate unknown fees without defaulting them to zero.
5. Add Android runtime mutation-spy and process-death/relaunch tests; retain the conservative fail-closed gate until safe exact-target recovery paths are evidenced.
6. Verify all supported OCO, cancel-replace, protection and bulk-cancel variants against authoritative per-order/per-leg state and residual exposure.
7. Prove that the global recovery gate only clears after Orders → Fills → Fees → Balances → Positions/Campaigns → Risk → Protection are reconciled.
8. Re-run every required workflow on the final report-bearing SHA after any changes. Live Spot Testnet recovery and physical-device results must remain NOT RUN / NOT VERIFIED unless actually performed.

No Binance Mainnet or real trading operation was used. Do not resume trading. Overall status remains **BLOCKED — NOT ACCEPTED**.


### TASK 003.3 — cross-process recovery checkpoint (2026-10-11)

**Overall status: BLOCKED — NOT ACCEPTED.** No production trading behavior was loosened. No Mainnet or real orders were used.

#### Exact HEAD / PR

- Verified PR #48 head before this checkpoint: `8f902a5b458402e5235a9fd1149277cb9026f6b8`.
- Base SHA: `04da5f9c09572e99ff310be3a1908179165e528c`.
- PR #48 is open, Draft, unmerged.
- Added independent-process regression tests in commit `2d056caa5655647da8922828b4dbe2880d8dc62d`:
  - `test_independent_processes_same_logical_intent_submit_at_most_once`: two spawned OS processes open independent SQLite connections to one pre-initialized persistent file, race the same logical intent, and assert exactly one submit callback and one accepted result.
  - `test_os_process_death_after_submitting_blocks_second_process_retry`: a spawned process durably writes SUBMITTING, is terminated, and a second process attempts the same intent; the test asserts no retry callback and unresolved state remains.
- These tests address only cross-process intent submission and restart blocking. They do not prove exactly-once fill accounting or financial ledger effects.

#### Exact-SHA CI for the test commit

Runs were created for SHA `2d056caa5655647da8922828b4dbe2880d8dc62d`:
- Python backend tests: run `38117093237`, job `114404039998`, status IN_PROGRESS at checkpoint.
- Williams Campaign CI: run `38117093169`, Python job `114404039824` and Android job `114404039747`, statuses QUEUED/IN_PROGRESS at checkpoint.
- Build Android APK: run `38117093268`, job `114404040092`, status IN_PROGRESS at checkpoint.

Links:
- https://github.com/19wolfalone92/Williams/actions/runs/38117093237
- https://github.com/19wolfalone92/Williams/actions/runs/38117093169
- https://github.com/19wolfalone92/Williams/actions/runs/38117093268

These run IDs are tied to `2d056caa...`, not to this later report commit. Final-head CI must be checked again after this documentation change. No conclusion is inferred while jobs are running.

#### Task 003.3 evidence matrix

| Area | Status | Evidence / limit |
|---|---|---|
| HEAD / PR / base | PASS | Queried live; PR open, Draft, unmerged |
| Python production mutation sink map | NOT VERIFIED | Exhaustive transitive caller-to-client graph still incomplete |
| Android production mutation sink map | NOT VERIFIED | Static sink inventory is not a runtime bypass proof |
| Runtime mutation spy | NOT VERIFIED | Not implemented in this checkpoint |
| Independent OS-process same-intent submission | PENDING | Two-process test added; exact-SHA workflow results pending at checkpoint |
| OS-process death after SUBMITTING | PENDING | Process termination/reopen test added; exact-SHA workflow results pending at checkpoint |
| Cross-process duplicate fill accounting | NOT VERIFIED | No canonical transactional fill-to-financial-effects ledger proof |
| Unknown-fee handling | NOT VERIFIED | Android REAL/default schema and end-to-end writers/readers/migrations not corrected/proven |
| Decimal-exact Android financial ledger | NOT VERIFIED | No complete migration to canonical decimal representation in this checkpoint |
| Full 18-point fault injection | NOT VERIFIED | Not implemented as a complete reproducible matrix |
| Android process-death across all 12 boundaries | NOT VERIFIED | Two-process Python test does not substitute for Android process-death |
| OCO/cancel-replace/bulk cancel full matrix | NOT VERIFIED | Selected existing unit cases only; authoritative race proof absent |
| Global Orders→Fills→Fees→Balances→Positions/Campaigns→Risk→Protection gate | NOT VERIFIED | Full gate-release proof absent |
| Python CI on test commit | PENDING | Run 38117093237 |
| Campaign CI on test commit | PENDING | Run 38117093169 |
| Android APK CI on test commit | PENDING | Run 38117093268 |
| Physical Android device | NOT VERIFIED | No physical-device test performed |
| Spot Testnet lost-response recovery | NOT RUN | No order placed/cancelled |
| Futures | NOT VERIFIED | No separate Futures proof |
| main unchanged / no merge | PASS | Base SHA unchanged; PR remains unmerged |

#### Remaining P0 blockers

1. Finish and verify all production mutation caller chains through actual network clients; add runtime mutation-spy negative-path tests.
2. Complete OS-process tests for concurrent fill delivery, recovery contention, SQLite BUSY/LOCKED/write/commit errors and malformed recovery rows.
3. Implement a canonical fill ledger with durable unique exchange fill identity and an atomic/idempotent application protocol for fees, net quantity, position, PnL, Campaign, risk reservation and protection.
4. Propagate explicit commission completeness states; unknown commission/asset/history must keep recovery unresolved. Convert or bound canonical Android financial values using decimal-safe representation and verified migrations.
5. Implement and run all 18 fault-injection scenarios with persisted pre-state, injected failure, recovery action, post-state and invariant assertions.
6. Add Android runtime mutation-spy and actual process-death/relaunch tests across the listed boundaries.
7. Close OCO, cancel-replace, mass-cancel and residual-exposure recovery scenarios against authoritative order/fill/balance state.
8. Prove every entry point observes one global recovery gate whose release requires all required ledger stages to reconcile.
9. Check required workflows and individual jobs on the final report-bearing SHA; do not reuse results from an earlier commit.

Do not resume trading. Status remains **BLOCKED — NOT ACCEPTED** until every P0 has executable evidence.


### TASK 003.4 — atomic fill-effect journal checkpoint (2026-10-11)

**Status: BLOCKED — NOT ACCEPTED.** This checkpoint adds a narrowly scoped durable journal primitive and regression tests; it does not establish end-to-end production financial accounting or authorize trading.

#### Exact refs

- Starting PR head: `2668ad2f2873be141055b4ee726e25e1b63a56cb`.
- Current branch head after implementation/tests: `888cf25129148a3533265e1702343506a3db53fd`.
- PR #48: Open / Draft / unmerged.
- Base and live `main` SHA observed via compare: `04da5f9c09572e99ff310be3a1908179165e528c`; branch is 146 commits ahead, 0 behind at this checkpoint.
- Changes were limited to `db.py` and `test_task003_execution_safety.py` so far. No main write or merge was performed.

#### Root cause observed in current Python persistence model

- `campaign_fills` uses an auto-increment local row ID and has no uniqueness constraint for exchange/account/environment/symbol/fill identity. Re-delivery therefore has no demonstrated exchange-fill exactly-once constraint at this table boundary.
- `campaign_fills.quantity`, `price`, `quote_quantity`, `fee_quote`, and `commission_base` are SQLite REAL; writer `save_campaign_fill()` converts values to `float`, and commission/fee defaults can become zero. This is not acceptable as a canonical exact financial ledger.
- Existing `trades.fees` is also REAL with a default of zero. The current production callers have not yet been proven to route all fill effects through the new journal.
- Android production storage and network mutation paths are not changed by this checkpoint.

#### Primitive added

`financial_effect_journal` stores a durable composite identity:
`exchange + environment + account_scope + symbol + exchange_fill_id`.
It stores quantity, price, quote quantity and commission as decimal strings, with explicit `KNOWN/UNKNOWN` commission state and `PENDING_FEE/PENDING/APPLIED` projection state.

- `Database.apply_financial_fill_effect(fill, projection_writer)` inserts the journal row and applies a supplied same-connection SQLite projection callback inside one `BEGIN IMMEDIATE` transaction. Repeated identical fill delivery observes the existing effect and does not invoke the callback again.
- If commission or commission asset is unknown, the journal is persisted as `PENDING_FEE`; projection callback is not called.
- `Database.resolve_financial_effect_fee(...)` supports later fee resolution and applies the still-pending projection inside the same transaction.
- A projection exception rolls back both projection writes and journal changes in that SQLite transaction.
- The callback contract is restricted to writes through the same `Database` connection. External calls, a second database, and non-transactional side effects are outside this primitive's atomicity guarantee.
- Existing `campaign_fills` and its writers are not yet migrated/routed through this API. Therefore this is a testable primitive, not proof that production financial effects are exactly-once.

#### Tests added

- `test_financial_fill_effect_is_idempotent_and_decimal_exact`
- `test_unknown_commission_blocks_projection_until_explicit_resolution`
- `test_projection_failure_rolls_back_journal_and_projection_atomically`
- `test_independent_processes_apply_one_financial_fill_effect`

These assert decimal-string preservation, one projection for repeated delivery, explicit unknown-fee blocking/resolution, transaction rollback, and one shared-file SQLite projection when two independent OS processes race the same fill. Results must be read from CI tied to the exact commit; the tests have not yet been reported as passing at the time of this report update.

#### Acceptance matrix at this checkpoint

| Area | Status | Evidence / remaining gap |
|---|---|---|
| Refs / PR / main unchanged | PASS | PR queried live; base/main SHA unchanged; no merge |
| Existing required CI on prior report SHA `2668ad2...` | PASS | Runs 38117133497, 38117133475, 38117133512 all completed success; job summaries successful; artifacts exist. These results do not apply to `888cf251...`. |
| New journal primitive | IMPLEMENTED — CI PENDING | Added in `db.py`; must pass exact-SHA tests |
| Duplicate fill identity | PARTIALLY VERIFIED — CI PENDING | Composite identity and concurrent test added; no full production caller integration yet |
| Atomic projection application | PARTIALLY VERIFIED — CI PENDING | Same-connection SQLite callback and rollback test; not every existing position/campaign/risk writer is wired into it |
| Unknown fee | PARTIALLY VERIFIED — CI PENDING | Journal preserves UNKNOWN and blocks callback; end-to-end fee asset valuation/PnL gating not integrated |
| Decimal integrity | PARTIAL | Journal uses Decimal validation and text serialization; existing campaign/trade REAL schema and float writers remain |
| Runtime mutation spy / all production sink chains | NOT VERIFIED | No transport-spy proof covering all Python/Android callers and bypasses |
| SQLite BUSY/LOCKED/commit failure and recovery races | NOT VERIFIED | Broader deterministic matrix still missing |
| Full 18-point fault injection | NOT VERIFIED | Not completed |
| Global recovery gate and authoritative Orders→Fills→Fees→Balances→Positions/Campaigns→Risk→Protection | NOT VERIFIED | No end-to-end release proof |
| OCO / cancel-replace / bulk cancel | NOT VERIFIED | No complete authoritative race matrix |
| Android mutation enforcement/process death | NOT VERIFIED | No injected transport or instrumentation/process-death proof added |
| Spot SELL oversell | NOT VERIFIED | No full concurrent balance/residual exposure proof |
| Futures | NOT VERIFIED | Must remain blocked unless a separate Futures audit proves safety |
| Physical Android device / Spot Testnet | NOT RUN | No physical-device or exchange mutation testing performed |

#### Required next minimum step

1. Confirm exact-SHA CI for `888cf25129148a3533265e1702343506a3db53fd`; diagnose any failure from logs before changes.
2. Add deterministic crash/recovery and SQLite BUSY/LOCKED/commit tests for the new journal, including process death after journal commit and after projection commit but before caller acknowledgement.
3. Integrate the journal with actual fill ingestion and all same-database financial projections, or explicitly leave those production routes blocked.
4. Add runtime mutation-spy coverage for the Python and Android sinks, then finish the 18-point fault matrix and global recovery-gate proof.
5. Re-run all required CI on the final report-bearing SHA.

Do not resume trading. Overall status remains **BLOCKED — NOT ACCEPTED**.


### TASK 003.5 — production fill integration audit checkpoint (2026-10-11)

**Status: BLOCKED — NOT ACCEPTED.** This checkpoint fixes canonical Decimal identity handling in the journal primitive and adds conflict/duplicate regression tests. It deliberately does not claim production fill integration: source-to-sink inspection found existing writers still bypass the journal.

#### Exact refs and baseline

- Task starting HEAD verified from PR #48: `0794f918ceb82bbbb19b4f6e24f73f0f50125627`.
- Base / `main`: `04da5f9c09572e99ff310be3a1908179165e528c`.
- Existing PR #48 remains Open / Draft / unmerged.
- Existing required CI on starting SHA `0794f918...`: Python backend tests run `38118044987`, Build Android APK run `38118045001`, Williams Campaign CI run `38118044998` all completed Success. Jobs `114406851606`, `114406851800`, `114406894423`, `114406894642` completed successfully. These runs do not apply to later commits.
- Current implementation/test commits in this checkpoint: `a4dd3d9fbebbb0ce3cfbedf0498022215a5dd198` (Decimal canonicalization and accurate first-vs-duplicate status), `4486eeb6b286c7849db1e3808aa3f8fc62a8faed` (conflict/duplicate regression tests), and `f35d67e1d1dd860b0bb510477615961336e5a8b8` (align earlier assertions with canonical Decimal serialization), `e90f72738493058d47528e79bd0ccf0d3b7657ab` (avoid context-dependent Decimal.normalize rounding), and `46d42c5fe4f9a8826e0ca4e563c1dcf695d25d2e` (test preservation of >28-digit Decimal values). The final report-bearing SHA is the documentation commit created after these code/test changes; required CI must be checked against that exact final SHA.
- No main write or merge performed. No exchange mutation was performed.

#### Source-to-sink findings (CODE-CONFIRMED; integration NOT VERIFIED)

| Fill source / entry point | Normalization | Ledger / effect | Existing projections | Recovery / test status |
|---|---|---|---|---|
| `campaign_execution.py` conditional order adoption / recovery, including lines around 653–665 and 697–698 | Aggregates order `executedQty`, `cummulativeQuoteQty`; may query `my_trades` for totals | Does not call `apply_financial_fill_effect`; passes order-level totals to campaign engine | `CampaignEngine.record_initial_fill` / `record_add_on_fill`, then campaign persistence and `save_trade`; fee is explicitly passed as `0.0` at the inspected callsites | Existing campaign recovery tests exist, but journal-linked per-fill recovery NOT VERIFIED |
| `portfolio_trader.py::_authoritative_execution` | Calls `my_trades` if order payload lacks fills; computes aggregate `ExecutionSummary` | Does not call journal | `save_trade(... fees=float(...))`; position and PnL readers consume trade values | Existing tests do not prove journal integration or unknown-fee fail-closed behavior |
| `trader.py` legacy execution/recovery | Uses order history, executed quantities, fill accumulator in some paths | No journal call identified in inspected file | Direct `save_trade` writers, fee values may default to zero | Legacy route remains NOT VERIFIED / not proven blocked |
| `Database.save_campaign_fill` | Converts quantity, price, quote quantity and fee fields through `float` | Writes only `campaign_fills`; no journal call | SQLite REAL columns | No caller found in the inspected execution modules; full-repository caller inventory remains incomplete |
| Android `StandaloneRuntime.kt` | Order response / fill accumulation paths found, with `feeKnown` in some local accumulator data | No integration with Python SQLite journal; no equivalent end-to-end durable per-fill journal established in this task | Local runtime state / order and campaign accounting | Android financial-effect exactly-once NOT VERIFIED |

This table is a partial source map, not a claim that every production caller and adapter has been exhausted. In particular, aggregated order quantities are not equivalent to individual authoritative fill identity, and order-level totals cannot safely replace per-trade commission records.

#### Primitive correction

- `Database._canonical_decimal()` now normalizes Decimal-equivalent values to a canonical string (including trailing-zero variants) without binary float arithmetic. Zero is serialized as `"0"`.
- `apply_financial_fill_effect()` now reports first insertion versus duplicate correctly for the `PENDING_FEE` path instead of inferring duplication from a timestamp that exists for both new and existing rows.
- Tests added: `test_fill_identity_conflict_is_fail_closed_and_does_not_reapply`, `test_decimal_equivalent_fill_payload_is_canonical_duplicate`, and `test_unknown_fee_first_delivery_and_duplicate_are_distinguished`. Existing journal assertions were updated to expect the newly canonicalized strings (`0.01`, `61234.12`, `0.00001`) rather than preserving irrelevant trailing zeros. Decimal canonicalization now strips fractional trailing zeros from fixed-point text instead of calling `Decimal.normalize()`, which can round values under the active precision context. The high-precision regression test verifies preservation of a 39-digit fractional quantity.
- Expected invariant: the same identity with conflicting immutable quantity/price/quote/fee data raises a conflict and does not invoke the projection callback again. Decimal-equivalent string representations map to the same canonical payload.

#### Financial bypasses still open (CODE-CONFIRMED)

- `campaign_fills.quantity/price/quote_quantity/fee_quote/commission_base` remain SQLite REAL.
- `Database.save_campaign_fill()` still writes through `float` and defaults missing fee fields to zero.
- `trades.fees` remains REAL with a zero default; `Database.save_trade()` accepts direct writers.
- `campaign_execution.py` passes `fee_quote=0.0` to campaign initial/add-on fill methods at inspected callsites.
- `portfolio_trader.py` aggregates fill commissions but ultimately writes trade fees via `float`; unknown/failed fee lookup does not establish a durable pending fee effect.
- `trader.py` has direct `save_trade` paths.
- `campaign_engine.py` updates position quantity, average entry, open risk, campaign state and events through multiple writes. These operations are not currently wrapped in the new financial-effect transaction.
- A same-connection callback primitive is not production integration. No production caller was changed to call it in this checkpoint because safely mapping per-trade identities, fee-asset valuation, campaign ownership and all same-database projections requires the real caller contract; wiring aggregated order data into a per-fill journal would be unsafe.

#### Acceptance matrix

| Requirement | Status | Evidence / gap |
|---|---|---|
| Starting refs and previous CI | PASS | Verified live; all three previous-SHA workflows successful |
| Canonical Decimal duplicate identity | IMPLEMENTED — CI PENDING | Code changed; regression tests added; new-SHA CI not yet proven |
| Conflicting fill identity | TEST ADDED — CI PENDING | Asserts conflict and no second projection |
| Unknown-fee duplicate status | TEST ADDED — CI PENDING | Asserts first receipt differs from redelivery |
| Production fill ingestion connected to journal | NOT VERIFIED | Existing production paths aggregate order fills and bypass journal |
| All writers/readers inventoried | NOT VERIFIED | Initial source map only; complete repo-wide reference search not yet evidenced |
| REAL/float financial paths eliminated | BLOCKED | Existing REAL schema, float writers/readers, and zero fee defaults remain |
| Crash injection / process-death recovery for financial projections | NOT VERIFIED | Earlier two-process journal tests do not cover production projection recovery or all required crash points |
| SQLite BUSY/LOCKED/commit/read-back failures | NOT VERIFIED | Full deterministic tests not implemented |
| Global recovery gate across every entry point | NOT VERIFIED | No proof every risk-increasing entry point checks pending financial effects |
| Mutation sinks and negative runtime spy | NOT VERIFIED | Full Python/Android runtime transport spy not completed |
| Android financial ledger and process death | NOT VERIFIED | No Android financial-journal integration or process-death evidence |
| Spot SELL / OCO / cancel-replace exposure | NOT VERIFIED | Not fully proven against authoritative per-fill ledger |
| Futures fail-closed | NOT VERIFIED | No separate proof that every Futures mutation path is blocked |
| Physical device / Spot Testnet | NOT RUN | No device or exchange mutation testing |

#### Required next minimum step

1. Wait for and inspect all required CI on the new report-bearing SHA. Diagnose failures before changing code.
2. Complete a repository-wide writer/reader inventory, including all `save_trade`, `save_campaign_fill`, campaign-state, risk and protection writers.
3. Design a production adapter from individual authoritative exchange fills (with actual trade IDs and commission assets) to the journal; do not use order IDs as a substitute for trade IDs.
4. Integrate journal application and campaign/position/risk projections under a single SQLite transaction where they share the DB, or use an explicitly durable recovery protocol where they do not.
5. Until that integration and global gate are proven, keep risk-increasing trading blocked.

Overall status remains **BLOCKED — NOT ACCEPTED**. Do not resume trading.


## TASK 003.7 — Production Fill-to-Ledger Integration

**Status: BLOCKED — NOT ACCEPTED.** This checkpoint records the verified baseline and source-level blockers. It does not claim production integration or crash-recovery acceptance.

### GitHub baseline re-check

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-003-execution-safety`
- PR #48: open, draft, unmerged.
- Base/main SHA: `04da5f9c09572e99ff310be3a1908179165e528c`
- Baseline HEAD verified directly: `17a0c27d76bbbcfdad800bc0f84824d707f18fcb`
- Main remained at the base SHA; no merge performed.
- Prior four required workflow runs for the baseline SHA completed successfully:
  - Python backend tests: run `38120083497`
  - Build Android APK: run `38120083530`
  - Williams Campaign CI (PR): run `38120083522`
  - Williams Campaign CI (push): run `38120081389`
- These CI results apply only to the baseline SHA and do not prove the TASK 003.7 changes or its acceptance criteria.

### Confirmed source-to-sink blockers

1. `db.py::apply_financial_fill_effect` and `db.py::resolve_financial_effect_fee` implement the journal primitive, but the inspected production paths in `campaign_execution.py`, `campaign_engine.py`, `portfolio_trader.py`, `trader.py`, and `StandaloneRuntime.kt` do not demonstrate end-to-end use of this protocol.
2. `campaign_execution.py` calls `CampaignEngine.record_initial_fill` / `record_add_on_fill` with `fee_quote=0.0` in two inspected locations. This is not evidence that the authoritative fee was zero.
3. `campaign_engine.py` fill methods accept float-valued quantity, price, risk and default fee arguments; `db.py::save_campaign_fill` writes float values to SQLite REAL columns.
4. `db.py::save_trade` remains an independent trade writer. Call paths in `portfolio_trader.py` and `trader.py` were found; their full reconciliation with the journal is not proven.
5. `execution_accumulator.py` has a fallback that derives an aggregate summary from `executedQty` / `cummulativeQuoteQty`. Such aggregate values are not a per-fill identity ledger.
6. `StandaloneRuntime.kt` contains direct `signedPost()` and `signedDelete()` callsites and its own runtime state. A shared durable financial journal, atomic projection boundary and process-death proof are not established.
7. A repository-wide caller/writer inventory, full fee-source audit, Python/Android mutation-sink audit, Global Recovery Gate coverage, Spot SELL/OCO proof, and Futures fail-closed proof remain incomplete.

### Required next implementation boundary

Before wiring a production callback, complete the caller and projection inventory and identify the actual authoritative fill IDs and commission fields returned by each live ingestion/recovery source. The existing journal callback must write all required same-database projections through the same `Database` connection; it must not call exchange APIs or write another database. Unknown fees must remain pending and must not be represented as zero. Any conflict or uncertain projection outcome must block new risk-increasing entries until reconciliation proves consistency.

### Acceptance evidence still missing

- Production fill paths connected to the canonical journal.
- All bypass writers eliminated or proven safe.
- Durable, fail-closed handling of conflicting immutable fill payloads.
- Atomic or durable-recovery projections covering campaign, position, risk and accounting.
- Fault injection on real production entry points, including independent processes, commit/rollback failures and SQLite contention.
- Verified Global Recovery Gate coverage across Python and Android.
- Spot SELL, OCO/cancel-replace and Futures mutation-sink safety.
- Android runtime/process-death evidence; APK build success alone is insufficient.
- All mandatory workflows green on one final post-change SHA.

No Binance Mainnet or Spot Testnet trading mutations were performed for this audit.


## TASK 003.8 — Unified production fill ingestion checkpoint

**Checkpoint date:** 2026-10-11  
**Review baseline SHA:** `4374ac6bfc1c98e2f17780d3b21d2d11f126931b`  
**Status:** **BLOCKED — NOT ACCEPTED**. This section records the source-to-sink findings that are confirmed at this checkpoint. It does not claim that the complete repository inventory or production integration is finished.

### GitHub baseline

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-003-execution-safety`
- PR #48: open, draft, not merged; head SHA `4374ac6bfc1c98e2f17780d3b21d2d11f126931b`; base SHA `04da5f9c09572e99ff310be3a1908179165e528c`.
- `main` remains at `04da5f9c09572e99ff310be3a1908179165e528c`.
- No Binance Mainnet or Spot Testnet trading mutation was performed.
- The branch SHA was re-read from GitHub before this checkpoint. No production-code change is included in this checkpoint.

### CI evidence for the baseline SHA

| Workflow | Run ID | SHA | Status at checkpoint | Link |
|---|---:|---|---|---|
| Python backend tests | 38121438598 | `4374ac6bfc1c98e2f17780d3b21d2d11f126931b` | SUCCESS | https://github.com/19wolfalone92/Williams/actions/runs/38121438598 |
| Build Android APK | 38121438586 | `4374ac6bfc1c98e2f17780d3b21d2d11f126931b` | IN PROGRESS | https://github.com/19wolfalone92/Williams/actions/runs/38121438586 |
| Williams Campaign CI — PR | 38121438587 | `4374ac6bfc1c98e2f17780d3b21d2d11f126931b` | IN PROGRESS | https://github.com/19wolfalone92/Williams/actions/runs/38121438587 |
| Williams Campaign CI — push | 38121434810 | `4374ac6bfc1c98e2f17780d3b21d2d11f126931b` | IN PROGRESS | https://github.com/19wolfalone92/Williams/actions/runs/38121434810 |

The Python backend run's job steps include the release contract gate, Python compilation, full static safety audit and offline tests, all successful. The APK and Campaign workflows were not terminal at the last GitHub poll; their results must not be inferred from the completed Python workflow or from previous SHAs.

### Confirmed source-to-sink inventory (partial; repository-wide completeness NOT VERIFIED)

| Production path | Source | Writer / mutation | Financial projections | Recovery | Transaction boundary | Status |
|---|---|---|---|---|---|---|
| Existing journal API | Caller-supplied fill mapping | `Database.apply_financial_fill_effect()` in `db.py` | Invokes callback-provided writes on the same `Database` connection; marks journal `APPLIED` in the same transaction | Pending fee is durable; caller must explicitly reconcile it | `Database.transaction()`; callback contract forbids external DB/network writes | CODE-CONFIRMED API; production callers NOT VERIFIED |
| Fee resolution API | Caller-supplied commission and asset | `Database.resolve_financial_effect_fee()` | Applies callback projections and journal state in one local transaction | Rejects missing fee asset and fee changes after applied effect | Same SQLite transaction, subject to callback obeying the single-connection contract | CODE-CONFIRMED API; production callers NOT VERIFIED |
| Campaign entry recovery | Binance order status plus `executedQty` / cumulative quote aggregate | `campaign_execution.py` calls `CampaignEngine.record_initial_fill()`, then protection/campaign/trade writers | Campaign quantity/average/risk/tags, protective-order state, `trades` | Campaign/order-intent recovery exists, but per-fill financial ledger replay is not proven | Multiple method calls and commits; not one demonstrated financial-effect transaction | CODE-CONFIRMED; BLOCKED for canonical fill ingestion |
| Campaign add-on recovery | Binance order status plus aggregate execution fields | `campaign_execution.py` calls `record_add_on_fill()` and later updates campaign/trade state | Campaign quantity/average/risk/events and trade quantity/entry/stop/risk | Order intent reconciliation exists; per-fill fee/ledger recovery is not proven | Multiple writes/commits; atomic financial projection boundary not proven | CODE-CONFIRMED; BLOCKED |
| Campaign fee input | Recovery path does not prove authoritative per-fill commission in inspected call | `fee_quote=0.0` passed to `record_initial_fill()` and `record_add_on_fill()` | Campaign fee tags and any derived campaign accounting | No proof that unknown is preserved as unknown at this boundary | Not an authoritative fee-resolution transaction | CODE-CONFIRMED defect/risk; BLOCKED |
| Legacy campaign-fill store | Caller-supplied mapping | `Database.save_campaign_fill()` | `campaign_fills` | No complete canonical journal coupling shown | Uses Python `float` conversion; table stores numeric fields as SQLite `REAL`; commits outside an active transaction | CODE-CONFIRMED; BLOCKED for decimal-exact ledger |
| Portfolio entry/recovery | Exchange order lookup and `ExecutionSummary` | `portfolio_trader.py` calls `save_trade()` with float fee-equivalent; updates trade/position/state | `trades`, position state, residual quantity, realized PnL/fees | Reconciliation and OCO/protection recovery exist; canonical per-fill journal coupling not proven | Multiple DB operations; no unified fill-effect transaction proven | CODE-CONFIRMED; BLOCKED |
| Legacy trader paths | Order/fill accumulation and legacy execution functions | `trader.py` uses `ExecutionBarrier`, `save_trade()`, and execution accumulator paths | Trade rows, state, risk/campaign state depending on path | Execution intent barrier exists; all financial outcomes routed through canonical fill applier is NOT VERIFIED | Depends on caller; global projection atomicity not proven | PARTIALLY CODE-CONFIRMED; BLOCKED |
| Execution aggregation | Order response and optional trade rows | `execution_accumulator.py` | Produces aggregate quantity/VWAP/fee summary consumed by callers | Aggregated recovery is not equivalent to individual immutable exchange fill identity | No journal transaction by itself | CODE-CONFIRMED role; NOT VERIFIED as canonical ingestion |
| Python direct trade persistence | Caller-supplied trade kwargs | `Database.save_trade()` and related update methods | `trades` | Callers recover from order state, but not every write is tied to a journal effect | Direct commits visible in DB implementation | CODE-CONFIRMED; BLOCKED pending writer census and caller migration |
| Android order/execution audit | Binance REST / execution events | `TradingAuditStore` `orders` and `executions` tables | Order/event audit; Android runtime also maintains execution/campaign state elsewhere | Durable order mutation intents exist; unified per-fill financial applier/replay is NOT VERIFIED | Android SQLite store; no shared transaction with Python DB | CODE-CONFIRMED audit store; BLOCKED for cross-platform financial authority |
| Android fee representation | Execution event JSON and commission fields | `executions.commission REAL DEFAULT 0`, nullable `commission_asset`; trade schema has `fee_known` in newer schema | Android audit/trade records | Exact migration and all consumers' treatment of absent commission NOT VERIFIED | SQLite REAL representation; not decimal-exact | CODE-CONFIRMED schema risk; BLOCKED |

### Why production integration is not being wired blindly

1. The journal key is derived from `exchange + environment + account_scope + symbol + exchange_fill_id`. This requires each ingestion source to supply a real per-fill identifier. An order ID or cumulative order quantity cannot safely stand in for individual trade IDs.
2. The journal's callback can make local writes atomic only when every projection writer uses the same SQLite connection and transaction. Campaign, trade, position, protection and Android state currently cross multiple method/commit boundaries; no global atomicity claim is justified.
3. Existing campaign recovery call sites explicitly pass `fee_quote=0.0`. Connecting them to the journal without authoritative per-fill commission and asset conversion would convert an unknown fee into a false zero.
4. The inspected `save_campaign_fill()` converts financial values to `float` and stores them in SQLite `REAL`; that path is not a decimal-exact ledger.
5. Android `TradingAuditStore` is a separate SQLite database from Python `db.py`. Python's journal cannot be assumed to cover Android mutations or provide cross-database atomicity.
6. The available evidence does not yet enumerate every writer/caller, every SQLite repository/migration, every Python/Android mutation sink, or prove that all risk-increasing entry points consult durable pending financial-effect state.

Accordingly, no production writer was mass-rerouted in this checkpoint. The safe next implementation step is to finish the writer/caller census and define the canonical exchange-fill/commission contract from actual exchange responses, then wire one path at a time with transaction-level and restart/concurrency tests. Until that exists, the affected paths must remain fail-closed; green offline CI is necessary but not sufficient.

### Outstanding P0 acceptance evidence

- Exhaustive writer/reader/migration/recovery/mutation-sink inventory.
- Actual production callers of the financial journal, not only its method definitions.
- Canonical per-fill IDs and commission evidence from every production source.
- Same-DB atomic projection writer or a durable cross-store recovery protocol.
- Unknown-commission reconciliation without default-to-zero behavior.
- Decimal-exact calculations and persistence across Python and Android.
- Independent-process duplicate-fill and commit-crash tests against persistent storage.
- Fault injection for transaction failures, `SQLITE_BUSY/LOCKED`, process death and repeat recovery.
- Durable global entry gate covering every BUY/risk-increasing route.
- Spot SELL net inventory, partial fill, OCO and cancel/replace recovery evidence.
- Proof that unverified Futures mutation routes are blocked.
- Final successful mandatory CI on one terminal SHA, plus Android runtime/process-death evidence.

**TASK 003.8 result at this checkpoint: BLOCKED — NOT ACCEPTED.** PR #48 remains open/draft/unmerged. No Mainnet or Spot Testnet trading mutation was performed.


## TASK 003.9 — First production fill path selection and acceptance checkpoint

**Checkpoint date:** 2026-10-11  
**Status:** **BLOCKED — NOT ACCEPTED**. No production writer was connected in this checkpoint because no inspected candidate currently satisfies the task's prerequisites for authoritative per-fill identity, commission certainty, atomic financial projections and crash-recovery proof. This is an intentional safety stop, not a claim that TASK 003.9 is complete.

### A. Re-verified GitHub baseline

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-003-execution-safety`
- PR #48: **OPEN / DRAFT / NOT MERGED**
- PR head before this checkpoint: `8eaa8ccd7d729f02273958f682d4d8be69d626bc`
- PR base: `04da5f9c09572e99ff310be3a1908179165e528c`
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`
- Production code and migrations were not changed. Only this audit document is updated by this checkpoint.
- No Binance Mainnet or Spot Testnet trading mutations were performed. Futures execution remains out of scope and must remain disabled/unverified.

### B. CI baseline for exact source SHA `8eaa8ccd7d729f02273958f682d4d8be69d626bc`

| Workflow | Run ID | Exact SHA | Terminal status | Checks observed | Evidence |
|---|---:|---|---|---|---|
| Python backend tests | 38121668482 | `8eaa8ccd7d729f02273958f682d4d8be69d626bc` | **SUCCESS** | Release contract gate, Python compilation, full static safety audit and offline test command all completed successfully. Test count was not established from the returned job metadata. | https://github.com/19wolfalone92/Williams/actions/runs/38121668482 |
| Build Android APK | 38121668472 | `8eaa8ccd7d729f02273958f682d4d8be69d626bc` | **IN PROGRESS — NOT PASS** | Release gate, source validation, lint, Android unit tests, debug APK build succeeded; release APK build was still running, later artifact/signature/checksum steps were pending. | https://github.com/19wolfalone92/Williams/actions/runs/38121668472 |
| Williams Campaign CI — PR | 38121668450 | `8eaa8ccd7d729f02273958f682d4d8be69d626bc` | **IN PROGRESS — NOT PASS** | Python tests job succeeded; Android build/static-check job was still running the debug build and unit tests. | https://github.com/19wolfalone92/Williams/actions/runs/38121668450 |
| Williams Campaign CI — push | 38121665400 | `8eaa8ccd7d729f02273958f682d4d8be69d626bc` | **IN PROGRESS — NOT PASS** | Python tests job succeeded; Android build/static-check job was still running the debug build and unit tests. | https://github.com/19wolfalone92/Williams/actions/runs/38121665400 |

At the last direct GitHub poll, the three Android/Campaign workflow runs were not terminal. They are **NOT VERIFIED as successful**. Prior SHA results are not substituted for these runs. The available evidence did not establish a separate required workflow beyond these four runs; required-check completeness still needs confirmation from repository rules/PR checks.

### C. Source-to-sink findings relevant to selecting the first vertical path

| Writer / path | Callers and input confirmed at this checkpoint | Financial storage/projections | Transaction/retry/recovery finding | Status |
|---|---|---|---|---|
| `Database.apply_financial_fill_effect()` in `db.py` | Existing callers in tests use a test-only projection callback. A production caller was not found in the inspected paths. | `financial_effect_journal`; callback-selected local tables | Journal insert, callback projection and APPLIED marker share `Database.transaction()` only if the callback writes exclusively through the same connection. Unknown commission returns durable `PENDING_FEE` without running the projection. | CODE-CONFIRMED API; production caller NOT VERIFIED |
| `Database.resolve_financial_effect_fee()` in `db.py` | Existing tests explicitly resolve an UNKNOWN fee; production use was not established. | Updates journal fee state and invokes caller projection callback | Local transaction is available; production callback and recovery callers are not proven. | CODE-CONFIRMED API; production caller NOT VERIFIED |
| Campaign entry recovery in `campaign_execution.py` | Queries order status; reads `executedQty` and cumulative quote; has a `my_trades` lookup in one branch to estimate quote if missing. It ultimately calls `record_initial_fill()` and then writes campaign/trade/protection state. | `campaigns`, `campaign_events`, `trades`, order/protection state | Calls `record_initial_fill(..., fee_quote=0.0)`; quantity/quote are converted to float; separate campaign, trade and protection writes occur across commits/network boundary. Not a per-fill journal path. | CODE-CONFIRMED; BLOCKED |
| Campaign add-on recovery in `campaign_execution.py` | Uses order-level aggregate fields and invokes `record_add_on_fill()`. | Campaign quantity, average price, risk, tags/events and protection state | Calls `record_add_on_fill(..., fee_quote=0.0)`; add-on count/state can be mutated without demonstrated per-fill journal application. | CODE-CONFIRMED; BLOCKED |
| `CampaignEngine.record_initial_fill()` / `record_add_on_fill()` in `campaign_engine.py` | Called by campaign execution; the passed `fill_order_id` is the order ID, not an individual trade ID. | Campaign quantity/average/risk/fee tags plus campaign events | Uses float arithmetic and default `fee_quote=0.0`; multiple writes and state transitions are not wrapped in a demonstrated single journal transaction. | CODE-CONFIRMED; BLOCKED |
| `PortfolioTrader._authoritative_execution()` in `portfolio_trader.py` | Can retrieve `my_trades`, but the local normalization shown maps price/qty/commission/commissionAsset and drops the per-fill trade ID when building normalized dictionaries. Aggregation uses `ExecutionSummary`. | Callers eventually write `trades`, fees, position and state through separate operations. | Converts some fee-asset valuation prices through float; summary is aggregated, not a canonical per-fill ledger event. Unknown/missing trade response handling is not equivalent to a durable UNKNOWN commission record. | CODE-CONFIRMED; BLOCKED |
| `Trader._authoritative_execution()` in `trader.py` | Uses embedded `fills` or calls `my_trades`; passes data to `accumulate_fills()` / `accumulate_order()`. | Legacy trade and order state is persisted separately. | `ExecutionSummary` aggregates fills and does not retain individual fill identity in its public result; fallback `accumulate_order()` may use order-level aggregates. | CODE-CONFIRMED; BLOCKED |
| `Database.save_campaign_fill()` in `db.py` | Caller completeness not established. | `campaign_fills` stores quantity, price, quote and fee in SQLite REAL; function converts values to float. | No demonstrated coupling to journal/effect identity. | CODE-CONFIRMED; BLOCKED for canonical decimal ledger |
| `Database.save_trade()` and trade update methods | Many call sites; complete caller inventory remains NOT VERIFIED. | `trades` uses REAL columns for financial values and fee defaults to zero. | Direct commits outside an active transaction; no enforced journal effect ID/idempotency key. | CODE-CONFIRMED; BLOCKED pending complete call graph |
| Android `TradingAuditStore` | Stores order/execution audit events in `williams_trading_audit.db`; exact runtime consumers are not fully inventoried. | `executions` uses REAL for qty/price/commission and defaults commission to zero; `trade_id` exists but no proven common financial-effect contract with Python. | Separate database and process lifecycle; no cross-store atomic transaction. | CODE-CONFIRMED schema; integration/recovery NOT VERIFIED |

**Inventory completeness:** NOT VERIFIED. This table is a bounded source-to-sink inventory for the inspected candidate paths, not an exhaustive repository-wide inventory of all writers, readers, migrations, workers and mutation sinks.

### D. Canonical fill contract — selection gate not yet satisfied

The existing journal currently requires `exchange`, `environment`, `account_scope`, `symbol`, `exchange_fill_id`, quantity, price and quote quantity. It normalizes the numeric values to canonical decimal strings and derives the effect identity from the exchange/environment/account/symbol/fill-ID tuple. It rejects an immutable payload conflict and uses a unique constraint on the same tuple.

However, the existing persisted canonical payload does **not yet constitute the complete TASK 003.9 contract**: side, exchange order ID, exchange timestamp, local receive time, source and contract/schema version are not all explicit canonical fields in the inspected journal schema. The contract also has no demonstrated production adapter that proves the per-fill ID and commission fields survive every ingestion source. No migration or schema change is made here because the first production vertical path has not yet been safely selected.

### E. Why no production writer was connected

No inspected candidate meets all selection conditions simultaneously:

1. Campaign recovery has an authoritative order response, but uses aggregate execution data for the campaign mutation, passes `fee_quote=0.0`, and updates campaign/trade/protection through separate operations.
2. The portfolio and legacy trader paths can obtain individual trade rows in some cases, but aggregate them into an `ExecutionSummary`; the inspected portfolio normalizer drops per-fill trade ID, and neither path proves that all financial projections are applied via the journal.
3. The journal's atomic callback contract is suitable only for local writes through one SQLite connection. The existing campaign/position/trade/protection flows span multiple writes and external order-protection mutations. A network call to create/replace protection cannot be part of the SQLite transaction.
4. The current projection schemas still include float/REAL financial fields. Reusing them as the canonical exact ledger would not satisfy Decimal Integrity.
5. The Python journal cannot atomically govern Android's separate SQLite database.
6. No production fault-injection suite currently demonstrates the complete selected path's process-death, concurrent duplicate, BUSY/LOCKED, fee-resolution, rollback/commit and repeated-recovery behavior.

Connecting one of these writers to the journal now would risk a split ledger: the journal could become APPLIED while a legacy projection is also updated separately, or a campaign could mutate before the journal is durably applied. It would also risk treating an unknown fee as zero. Therefore no production-code integration was attempted in this checkpoint.

### F. Required preconditions before the next implementation attempt

1. Define a versioned canonical Spot fill DTO that includes the actual Binance per-trade ID, order ID, side, executed quantity, price, quote quantity, commission amount/asset, fee state, exchange time, receive time and source; validate these fields against the actual endpoint/event contract used by each source.
2. Preserve per-fill trade IDs from Binance `myTrades` and/or execution events through normalization; do not pass only aggregate summaries into the journal. If a source cannot provide a per-fill ID, it cannot use this ingestion path until an authoritative adapter/reconciliation step obtains one.
3. Define an explicit commission state model. Do not treat absent commission fields as confirmed zero. Do not convert third-asset fees to quote currency without an explicit valuation record and deterministic price source.
4. Choose one local financial projection that can be updated with the journal on the same `Database` connection and one transaction; add its unique effect/fill key so retrying the projection itself is idempotent. Keep external protection mutation outside that transaction and require a durable protection/reconciliation state before resuming entries.
5. Ensure the chosen entry gate reads durable unresolved financial-effect state, including `PENDING_FEE`, conflicting immutable payload and failed recovery; prove this gate covers the chosen production entry and add-on entry before routing fills.
6. Add production-shaped tests using a persistent SQLite file and real projection tables, including subprocess termination/reopen and two independent `Database` instances. The existing journal tests' `test_financial_projection` table is a test fixture and does not prove production projection integration.
7. Inventory all writers/callers and decide whether each is migrated, safely blocked or explicitly outside the selected vertical path. No dual application by journal plus legacy writer.
8. Treat Android and Futures as separate unresolved boundaries. Do not claim Python-only evidence proves Android financial exactly-once or Futures safety.

### G. Remaining P0 blockers

- Full caller/writer/reader/migration/background-worker and mutation-sink inventory: **NOT VERIFIED**.
- Production journal callers: **NOT VERIFIED**.
- Canonical versioned fill DTO and actual Binance endpoint/event mapping: **NOT VERIFIED**.
- Per-fill identity preserved across selected production path: **NOT VERIFIED**.
- Unknown commission fail-closed at all relevant entry paths: **NOT VERIFIED**.
- Atomicity of actual campaign/trade/position projections: **NOT VERIFIED**.
- Decimal-exact storage and calculations for selected projection: **NOT VERIFIED**.
- Real production projection crash/recovery and concurrent process tests: **NOT VERIFIED**.
- Durable gate on unresolved financial effects across all selected risk-increasing entry points: **NOT VERIFIED**.
- Android integration and device process-death recovery: **NOT VERIFIED**.
- Futures mutation-sink enforcement: **NOT VERIFIED**.
- Exact-SHA CI set complete: **NOT VERIFIED** while the three Android/Campaign runs remain in progress.

**TASK 003.9 checkpoint result: BLOCKED — NOT ACCEPTED.** PR #48 remains Open/Draft/unmerged. No production financial writer was changed, no migration was run, and no Mainnet/Spot Testnet trading mutation occurred.


## TASK 003.10 — Canonical per-fill contract and production writer ownership checkpoint

**Checkpoint date:** 2026-10-11  
**Status:** **BLOCKED — NOT ACCEPTED**. This checkpoint deliberately does not connect a production writer: the complete writer/caller/read-model inventory and the required production projection/recovery contract are not yet proven. No production code or schema migration was changed.

### A. Re-verified refs and working baseline

- Repository: `19wolfalone92/Williams`
- Branch / PR head: `audit/williams-2.1-task-003-execution-safety`
- Source SHA at TASK 003.10 start: `0287b2dbdeedba841f15573e584616cb7efec271`
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c` (unchanged at verification)
- PR #48: **OPEN / DRAFT / NOT MERGED**, base `main`, base SHA `04da5f9c09572e99ff310be3a1908179165e528c`.
- Current branch document blob SHA at start: `98cadf0797c7862fe7c8ae1483ac1a521c33577b`.
- The repository tree at the source SHA contains 154 files matching `.py`, `.kt`, `.java`, `.sql`, `.yml` or `.yaml`. This is a repository scope count, **not** a claim that all files or callers were semantically audited.
- No Binance Mainnet or Spot Testnet order/trading mutation was executed. No Futures execution was enabled.

### B. CI for the exact source SHA `0287b2dbdeedba841f15573e584616cb7efec271`

| Workflow | Run ID | Exact SHA | Result | Scope / limitation |
|---|---:|---|---|---|
| Python backend tests | 38121965606 | `0287b2dbdeedba841f15573e584616cb7efec271` | **SUCCESS** | Workflow completed on the exact source SHA. This is not proof of production fill integration. |
| Build Android APK | 38121965601 | `0287b2dbdeedba841f15573e584616cb7efec271` | **SUCCESS** | Workflow completed on the exact source SHA. This is not emulator/device/process-death evidence. |
| Williams Campaign CI — PR | 38121965612 | `0287b2dbdeedba841f15573e584616cb7efec271` | **SUCCESS** | PR workflow completed on the exact source SHA. |
| Williams Campaign CI — push | 38121963002 | `0287b2dbdeedba841f15573e584616cb7efec271` | **SUCCESS** | Push workflow completed on the exact source SHA. |

The four runs above are terminal successes on one SHA. CI success does not satisfy the missing source-to-sink, exact financial projection, cross-process crash-recovery or device proof. These runs validate the checkpoint source, not any subsequent commit that appends this report.

### C. Bounded source-to-sink writer inventory

The following is the set of concrete writer families inspected for this checkpoint. It is **not exhaustive**: every caller of the legacy persistence methods, every worker/recovery caller, all projections/readers, all schema migrations and all mutation-adapter call chains have not been enumerated and cross-checked. Therefore no production writer may be connected in this task checkpoint.

| Writer / family | Observed callers / input | Projections or durable tables | Per-fill identity | Fee / numeric handling | Atomicity / recovery | Result |
|---|---|---|---|---|---|---|
| `Database.apply_financial_fill_effect()` (`db.py`) | Method exists; inspected direct uses are tests with a test-only projection callback. Production caller not found in inspected call graph. | `financial_effect_journal` plus callback-selected tables | Requires `exchange_fill_id`; key scopes exchange/environment/account/symbol/fill ID. Does not itself prove that caller sourced a real Binance trade ID. | Decimal string serialization; missing commission maps to `UNKNOWN`. Current payload omits several requested CanonicalFillV1 fields including side, order/client IDs, source/times and durable-intent link. | One `Database.transaction()` only if callback exclusively uses the same connection and does not commit/open another transaction. | API code-confirmed; production integration **NOT VERIFIED**. |
| `Database.resolve_financial_effect_fee()` | Test coverage exists; production resolution caller not established. | Journal row and callback-selected projection | Effect ID refers to journal identity; caller provenance is not proven. | Requires a commission asset and amount, but authoritative source/valuation and conflict workflow are not proven end-to-end. | Can run projection and fee resolution transactionally on the same connection under the callback contract. | API code-confirmed; production recovery **NOT VERIFIED**. |
| `Database.save_trade()` / trade update methods | Numerous call sites; complete caller census not completed. | `trades`, including entry/exit price, quantity, PnL and fees | No enforced canonical fill/effect key in the inspected schema/API. | Legacy values stored as SQLite `REAL`; direct methods can commit. Fee defaults exist in schema/legacy paths. | Independent direct commits are possible; no required journal transaction demonstrated. | Legacy writer; **BLOCKER**. |
| `Database.save_campaign_fill()` | Caller completeness not established. | `campaign_fills` | Schema does not enforce canonical per-fill effect identity. | Converts financial values to float and persists quantity/price/quote/fee/base commission in `REAL`; fee quote and base commission have zero defaults. | No demonstrated journal coupling or idempotent effect key. | Legacy writer; **BLOCKER**. |
| Campaign initial-entry recovery (`campaign_execution.py` → `CampaignEngine.record_initial_fill()`) | Exchange order status response; order-level `executedQty` and `cummulativeQuoteQty`; one fallback lookup to `my_trades` only for quote estimation. | Campaign quantity/average/risk/tags/events and `trades`; protection is created/replaced separately. | Main mutation consumes order aggregates; passes order ID as `fill_order_id`, not an individual trade ID. | Float conversion; explicit `fee_quote=0.0`. | Campaign/trade writes and protection network mutations span separate boundaries. | **BLOCKER**; not a per-fill writer. |
| Campaign add-on recovery (`campaign_execution.py` → `CampaignEngine.record_add_on_fill()`) | Order-level aggregate quantity/quote and pending campaign state. | Campaign position/risk/add-on state, campaign events, trade quantity, protective stop state. | Order ID rather than separate fill IDs. | Float conversion; explicit `fee_quote=0.0`. | Campaign update, trade update, protection and intent reconciliation are not one financial journal transaction. | **BLOCKER**. |
| `PortfolioTrader._authoritative_execution()` (`portfolio_trader.py`) | Embedded order fills or `my_trades`; fee conversion can separately query trade rows. | Callers mutate `trades`, trade quantity, fee/PnL and protection state through separate methods. | Normalization retains price/qty/commission fields but drops the individual Binance trade ID before aggregation to `ExecutionSummary`. | Some paths convert commission and valuation prices to float; third-asset conversion depends on a price lookup; summary defaults fee fields to zero. | No demonstrated journal/projection atomicity; emergency sell/reconciliation paths are separate. | **BLOCKER**. |
| `Trader` (`trader.py`) plus `execution_accumulator.py` | Embedded fills or `my_trades`; aggregate order fallback via `accumulate_order()`. | Legacy `trades`, order/state records and position reconciliation. | `ExecutionSummary` exposes totals/VWAP/fees, not a durable set of immutable per-fill IDs. Aggregate fallback is explicitly not per-fill evidence. | Accumulator uses Decimal arithmetic for some totals, but defaults absent commission to zero, skips nonpositive qty/price rows, and only recognizes a limited set of quote assets plus base-asset commission. This does not establish full fee certainty or exact storage. | Separate persistence/recovery path; no proof that every outcome is journaled. | **BLOCKER**. |
| Python direct order/campaign state writers | Calls from campaign and portfolio execution paths; full call graph remains open. | `orders`, `campaign_orders`, `campaigns`, `campaign_events`, `execution_intents`, `execution_events` | Usually order/client identity, not necessarily individual fill identity. | Multiple legacy columns are `REAL`; intent quantity fields are text but do not make downstream projections exact. | Some intent operations are transactionally durable; they are not the financial fill journal/projection transaction. | Alternative state writers; their relationship to financial effects is **NOT VERIFIED**. |
| Android `ExecutionAccumulator` | Binance `executionReport` user-stream event; event fields include trade ID `t`, last qty `l`, last price `L`, commission `n`, asset `N`. | In-memory accumulator snapshots consumed by runtime. | Event-derived key combines order ID, trade ID and event time; it is not the Python journal identity and is not itself proof of durable financial application. | Uses `BigDecimal` for accumulation, but unknown/blank commission semantics and third-asset valuation require further validation; some paths treat commission defaults as zero. | In-memory aggregation does not prove restart-safe exactly-once projections. | Code-confirmed event parsing; production financial application **NOT VERIFIED**. |
| Android `TradingAuditStore.recordUserEvent()` | User-stream `executionReport`; stores raw event and extracted execution. | Separate `williams_trading_audit.db`: `events`, `executions`, `orders`, `trades`, execution intents. | `trade_id` column exists; event key includes symbol/order/trade/execution type/event time. Not connected to Python journal by a shared transaction/effect contract. | `executions.commission` is SQLite `REAL DEFAULT 0`; event parsing converts commission/qty/price to Double and falls back to zero on parse failure. | Separate database; no atomic commit spanning Android audit rows and Python projections; durable per-fill replay to authoritative financial projections not proven. | **BLOCKER**; Android parity **NOT VERIFIED**. |
| Android runtime reconciliation / execution mutation paths (`StandaloneRuntime.kt`) | Signed REST mutation response and `executionReport`; routes into campaign or legacy reconciliation paths. | Android execution intent/audit state and runtime campaign/position state. | Event fields can carry trade ID, but the current downstream use is not proven to apply each fill through a canonical durable financial applier. | JSON strings are converted in several places to Double; event audit also stores commission as Double/REAL. | Mutation intent durability exists in the inspected signed-request boundary; it is distinct from atomic fill-to-financial-projection accounting. | Execution mutation safety is separate from financial exactly-once; **BLOCKER**. |
| Futures mutation paths | Repository-wide mutation-sink classification is not complete in this checkpoint. | Potential order/position/fee projections outside the selected Spot path. | **NOT VERIFIED**. | Futures fee/position semantics not assessed as part of this Spot contract. | No proof all Futures mutations are excluded from shared runtime. | Must remain disabled/blocked until separately inventoried. |

**Inventory result:** at least 11 writer/path families were inspected at source level, but this is not a complete inventory of all writers/callers/readers. An exhaustive count cannot be truthfully reported from the available evidence. The existence of a method, test callback, unique index or order-intent journal is not treated as proof of production integration.

### D. CanonicalFillV1 selection

**No production CanonicalFillV1 contract is accepted or wired by this checkpoint.** The current journal's normalized payload is narrower than the requested contract and is not backed by a proven production adapter.

A safe contract still needs explicit, validated fields for contract version; exchange/environment/account scope; symbol; exchange order and fill IDs; client order ID when available; side; price/base and quote quantity; commission amount/asset/status; exchange and receive times; source endpoint/event; durable intent association; and provenance for each critical field. Optional endpoint fields must be represented as absent/unknown rather than silently fabricated. A source with only aggregate order totals must have a distinct aggregate type and must not satisfy the per-fill writer interface.

The current journal does not validate all requested semantics: side/order/client IDs, timestamps, source, intent link, unknown currency handling, price × quantity versus authoritative quote quantity, allowed symbol/asset metadata, and conflict-state recovery are not proven as one contract. It also does not establish that repeated fee information can be resolved only from an authoritative source with immutable-payload conflict handling across all callers.

### E. Commission contract status

- Campaign recovery's `fee_quote=0.0` is confirmed and remains a P0 blocker.
- Missing commission can become `UNKNOWN/PENDING_FEE` in the journal API, but that path has no established production caller and no demonstrated global entry gate.
- The Python accumulator treats a missing commission as zero by default and has incomplete asset semantics; it is an aggregate utility, not a canonical commission authority.
- Android event persistence converts absent or malformed commission to numeric zero in the inspected code and stores it in `REAL`.
- Quote-asset fees, base-asset fees, and third-asset fees are not consistently reconciled across Python and Android. Third-asset conversion lacks a universally proven valuation source/timestamp contract.
- No test suite currently proves all four states `KNOWN`, `UNKNOWN`, `INVALID`, `CONFLICTED` against real production projections. Confirmed zero versus missing commission is not consistently distinguishable end-to-end.

**Result: Commission contract NOT VERIFIED; production path must remain blocked.**

### F. Decimal integrity and atomic projection status

- `db.py` has a canonical decimal-string helper and stores the journal's quantity, price, quote quantity and known commission as TEXT; this is a useful primitive, not proof of end-to-end exactness.
- Existing authoritative/operational projections remain mixed: `trades`, `campaigns`, `campaign_fills`, `orders` and Android `executions` include `REAL` columns and legacy paths convert values through float/Double.
- `execution_accumulator.py` uses Decimal for calculations, but its aggregate output discards individual fill identity and does not guarantee exact persistence.
- Android `ExecutionAccumulator.kt` uses BigDecimal in aggregation, but the durable audit store uses REAL/Double and no shared Python/Android contract is proven.
- The journal's callback transaction is only atomic for projection changes on the same SQLite connection. Actual campaign/trade/position/protection flows are not all inside that callback. Network protection mutations cannot be part of a SQLite transaction and need a separate durable state machine.
- Existing tests around the journal create `test_financial_projection` tables. Those tests prove callback mechanics, not production campaign/trade/position projection atomicity.
- No production-shaped subprocess kill/reopen, two-process duplicate fill, SQLite BUSY/LOCKED, fee resolution conflict or repeated-recovery proof was established for a real selected projection.

**Result: Decimal integrity and production projection atomicity NOT VERIFIED.**

### G. Global Recovery Gate / alternative writers

The inspected Python entry/add-on paths and Android runtime have order-intent, reconciliation and reservation gates, but no demonstrated common durable gate that checks unresolved `financial_effect_journal` states (`PENDING_FEE`, conflicting payload, failed projection) at every risk-increasing boundary. The in-memory Android `ExecutionGate` is a symbol reservation, not a durable financial-recovery gate. The order-mutation barrier does not itself prove financial exactly-once projection application.

Legacy `save_trade()`, campaign record methods, aggregate recovery and Android runtime paths remain potential alternative writers. The audit has not proven that a fill applied by one cannot be applied again by another. There is no basis to remove, bypass or dual-run any existing writer.

**Result: Global Recovery Gate and legacy-writer exclusion NOT VERIFIED.**

### H. Why no production writer was selected

No candidate satisfies all mandatory conditions:

1. Campaign recovery is aggregate/order-based, passes `fee_quote=0.0`, uses float and separately updates campaign/trade/protection state.
2. Portfolio/trader can obtain individual rows from some sources, but the inspected normalization/accumulator reduces them to an aggregate summary before downstream persistence.
3. Existing journal projection callbacks are not wired to production callers and do not cover all required production projections.
4. Existing financial projection schemas and legacy writers are not decimal-exact.
5. Unknown-fee and conflicting-payload states are not proven to block every risk-increasing entry path.
6. Independent-process/restart/fault-injection tests against real production tables are not proven.
7. Android's separate database and Futures mutation paths remain separate unresolved boundaries.
8. Complete caller/writer/reader/migration inventory remains open.

Selecting a writer now would violate the task's own entry criteria. No DTO, migration or production reroute was forced into the code to create a superficial PASS.

### I. Required next actions before production integration

1. Complete a repository-wide writer/caller/reader/migration/mutation-sink census and record each call edge, projection, transaction and recovery owner.
2. Map actual Binance Spot `myTrades` and `executionReport` fields to an explicit versioned DTO; preserve Binance trade ID `id`/`t` and distinguish per-fill payloads from order aggregates.
3. Define commission states and authoritative fee resolution, including base, quote, third asset, confirmed zero, missing and conflicting data.
4. Select one projection that can be made canonical without mass migration; establish compatibility and block all alternate writers for the selected vertical path.
5. Add a production adapter that writes the actual selected projection using the same `Database` connection and transaction as the journal; prove callback cannot commit/open another connection.
6. Add persistent-database, independent-connection/process, subprocess termination, rollback, duplicate and fee-late-arrival tests against real production tables.
7. Add a durable global recovery gate to every risk-increasing Python and Android boundary before admitting entries/add-ons; keep verified reducing/protective actions on a separately specified path.
8. Independently inventory and block unverified Futures mutation routes; do not infer Android parity from Python or shared fixtures.
9. Rerun every mandatory workflow after the next branch update and record the terminal result for that final SHA.

### J. Final checkpoint decision

- **TASK 003.10: BLOCKED — NOT ACCEPTED.**
- Production writer selected: **NONE**.
- CanonicalFillV1: **NOT IMPLEMENTED / NOT VERIFIED** as a production contract.
- Per-fill identity through the full selected production path: **NOT VERIFIED**.
- Commission certainty: **NOT VERIFIED**; default-to-zero legacy paths remain.
- Decimal integrity: **NOT VERIFIED** end-to-end.
- Atomic real production projections: **NOT VERIFIED**.
- Exactly-once/restart/concurrent-process recovery: **NOT VERIFIED**.
- Global Recovery Gate: **NOT VERIFIED** for all risk-increasing paths.
- Python: journal primitive code-confirmed; production financial integration **NOT VERIFIED**.
- Android: event ID parsing code-confirmed; durable financial exactly-once and process-death recovery **NOT VERIFIED**.
- Futures exclusion: **NOT VERIFIED**.
- CI on source SHA `0287b2dbdeedba841f15573e584616cb7efec271`: four listed workflows **SUCCESS**; these runs predate this documentation update.
- PR #48 remains **OPEN / DRAFT / NOT MERGED**; `main` must remain unchanged.

No production code, financial schema or migration was changed in this checkpoint. No real Binance trading mutation was performed.

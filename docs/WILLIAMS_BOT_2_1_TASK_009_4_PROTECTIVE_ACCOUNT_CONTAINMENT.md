# WILLIAMS BOT 2.1 — TASK 009.4 Evidence Report

**Status: BLOCKED — DO NOT MERGE**
**Scope:** Protective order recovery, account identity, mutation-path containment, and release-contract investigation.
**Branch:** audit/williams-2.1-task-009-integration
**Baseline:** b4d209efb86cadbef9f92491f7f7e05f3684c649
**Main baseline:** 04da5f9c09572e99ff310be3a1908179165e528c
**No real trades or real funds used. PR #51 remains Draft/open/unmerged.**

## Baseline verification

Before edits, the branch ref resolved to the exact requested baseline b4d209e...; main resolved to 04da5f9...; compare showed 81 commits ahead and 0 behind. GitHub's contents API is the working interface, so there is no separate local working tree to report as dirty/clean; the branch ref itself was verified. PR #51 was open, Draft, and not merged.

Relevant baseline files were inspected: db.py, execution_barrier.py, execution_recovery.py, execution_recovery_contract.py, campaign_execution.py, campaign_engine.py, portfolio_trader.py, trader.py, Android StandaloneRuntime.kt, ExecutionGate.kt, ExecutionAccumulator.kt, release workflows, server.py, ci_release_gate.py, and Gradle wrapper/build configuration.

## Changes made in TASK 009.4

- db.py: schema version 10; durable execution_account_bindings table keyed by exchange, execution scope, and configured account scope; pins credential fingerprint, optional Binance account UID/type, and Testnet/live domain. A changed key, domain, or observed UID fails closed.
- execution_account_identity.py: performs an authenticated client.account() request before campaign recovery or mutation; fingerprints only the API key (SHA-256), never the API secret; requires WILLIAMS_EXPECTED_API_KEY_SHA256 to be explicitly configured and matched before identity pinning; does not emit full fingerprint or credentials to logs.
- campaign_execution.py: checks account identity before recovery and before the canonical barrier submission path.
- portfolio_trader.py: production BinanceSpotClient legacy emergency SELL and OCO sinks are blocked before mutation; production manual SELL routes through CampaignExecutionService.exit_market; production legacy direct-entry fallback is blocked. Fake exchange adapters remain available to offline regression tests only.
- trader.py: production BinanceSpotClient legacy single-symbol BUY and OCO entry points are blocked before mutation because they do not share the campaign fill/position ledger. Offline fake clients retain the legacy fixture path for regression tests.
- StandaloneRuntime.kt: autonomous Android start is hard-blocked and executionReady() remains false while its independent durable-mutation/account-identity/recovery contract is unverified. This is containment, not a completed Android implementation.
- ci_release_gate.py: changed expected version from 4.24.0 to 4.25.0 because the checked-in authoritative source files agree on server.py VERSION = '4.25.0', MainActivity.kt header “Williams 4.25.0”, and app/build.gradle.kts versionName = "4.25.0". This corrects a stale gate; it does not fabricate a source version.
- Added test_task0094_account_identity.py and included it in Campaign CI.

## Account identity contract

Before campaign recovery/mutation:
1. A signed Binance Spot account query must succeed and return an object.
2. canTrade=false blocks execution.
3. The API key is fingerprinted with SHA-256; the secret is neither stored nor logged.
4. The first authenticated use pins the fingerprint, execution scope, configured account scope, Testnet/live domain, and optional UID/type to SQLite.
5. Subsequent mismatch blocks execution. A key rotation requires a deliberate offline rebind with trading stopped; unchanged account_scope alone cannot authorize it.
6. WILLIAMS_EXPECTED_API_KEY_SHA256 is mandatory and must match the active key fingerprint before a binding can be pinned. Compute/approve this fingerprint locally; never send the key or secret to remote logs.

**Important limit:** the fingerprint is credential identity, not a universal immutable Binance account ID. Spot /api/v3/account responses may not expose a stable UID. If UID is present, it is cross-checked. The required expected fingerprint is an explicit operator approval of the credential. The first successful signed account request pins it to SQLite. This still does not independently prove a universal immutable Binance account UID when Binance does not return one. P0 account-ownership acceptance remains blocked until the account identity fields/rotation contract is verified for the supported Binance account types.

Secrets remain local; the new table stores only a credential fingerprint and non-secret account metadata.

## Protective order state contract

| Observed condition | Required state/action |
|---|---|
| BUY fill confirmed, no verified stop | PROTECTION_PENDING; no risk-increasing mutation |
| Existing stop found and exchange fields match symbol/side/type/quantity/stop/status | May proceed only after the campaign recovery gate confirms the position/stop relationship |
| Stop missing, rejected, canceled, quantity too small, or query ambiguous | Keep the contour blocked; reconcile before any replacement |
| Add-on changes the required protected quantity | Block until a safe durable resize/cancel-replace protocol and recovery are proven |
| SELL fill leaves residual inventory | PROTECTION_PENDING / reconciliation required; residual is not treated as protected |
| Cancel or cancel-replace outcome unknown | Keep intent unresolved; query exchange and fill history before another mutation |
| Position fully closed but protective order may remain | Reconcile and cancel the exact bot-owned stop; unknown cancel result stays blocked |
| Account identity query fails or identity changes | No recovery resolution or new mutation in that execution contour |

TASK 009.4 does not claim add-on resize or residual SELL protection recovery is complete. These cases remain fail-closed.

## Python mutation-path inventory and containment

| Source/sink | Current decision |
|---|---|
| CampaignExecutionService._submit → ExecutionBarrier.execute | Canonical campaign mutation path; account identity and exchange recovery gate run before mutation |
| CampaignExecutionService.create_hard_stop, replace_structural_stop, exit_market, _execute_cancel | Uses durable campaign intents/barrier; resize, cancel/fill race and residual-protection edge cases remain BLOCKED unless verified |
| MultiPositionTrader.execute_campaign | Canonical dispatch to CampaignExecutionService |
| MultiPositionTrader.execute legacy BUY fallback | Blocked for production BinanceSpotClient; fake exchange adapters remain for offline regression only |
| MultiPositionTrader._emergency_market_sell | Production BinanceSpotClient is blocked before direct sink; use campaign exit path. Fake adapters exercise old regression fixture only |
| MultiPositionTrader._create_oco | Production BinanceSpotClient is blocked before direct sink; use campaign hard-stop path. Fake adapters exercise old recovery fixtures only |
| MultiPositionTrader.manual_sell | Routes only to an authoritative active campaign and CampaignExecutionService.exit_market; otherwise blocked |
| Trader.market_buy | Production BinanceSpotClient is blocked before legacy order path; dry-run and live-authorization gates still run first |
| Trader.place_oco | Production BinanceSpotClient is blocked before legacy OCO path |
| BinanceSpotClient.order/order_safe, create_oco_sell, cancel_order, cancel_oco, cancel_replace | Low-level client methods still exist; safety depends on callers being the approved campaign barrier path. The Python call-site inventory must remain a CI-enforced allowlist in a follow-up; this report does not claim static proof that no future caller can bypass it. |

This containment intentionally disables legacy direct Python execution rather than treating the existence of a barrier as proof of shared financial accounting. The canonical campaign route remains subject to the P0 limitations above.

## Android mutation inventory and status

Android uses its own local runtime and signed REST mutation methods; it is not coordinated by Python SQLite.

Observed mutation sinks in StandaloneRuntime.kt include:
- signedPost("/api/v3/order"): campaign entry, initial protection, market exit, emergency exit, add-on, pending-entry submission, legacy direct BUY/emergency paths.
- signedPost("/api/v3/orderList/oco"): legacy OCO protection.
- signedDelete("/api/v3/order"): protective stop cancellation and pending-entry cancellation.
- signedDelete("/api/v3/orderList"): OCO cancellation.
- signedCancelReplace(...): stop trailing and add-on protection resize.
- signedPost("/api/v3/order/cancelReplace"): external cancel-replace mutation.

withCampaignMutation currently writes transient gate metadata using SharedPreferences apply(); that is not a durable, uniquely keyed execution intent with exchange-backed recovery. There are also direct signed mutations outside that wrapper. TASK 009.4 therefore sets an explicit hard blocker: Android autonomous start is refused and executionReady() returns false until durable intent, account identity, restart recovery, and fill-ledger contracts are implemented and tested in the Android mutation domain. This does not certify Android safe for autonomous trading.

## Financial ledger boundary

SQLite uniqueness and transactionality apply only to local rows. Binance network requests remain outside financial transactions. Per-trade dedupe uses exchange/account scope/symbol/trade ID. Existing unresolved operations remain fail-closed. A SQLite transaction does not make Binance order submission, cancellation, stop replacement, and local accounting atomic.

## Required fault-injection status

New TASK 009.4 account-identity tests cover:
- first authenticated identity pin;
- API-key change with unchanged account scope;
- Testnet/live domain change;
- UID mismatch when UID is returned;
- signed account request failure without pinning;
- explicit expected fingerprint mismatch;
- rejection of an empty API key.

Protective-order, add-on, partial SELL, cancel/fill, and late-fill tests from TASK 009.3 remain available, but the full 25-scenario production-path matrix is not yet fully proven. The Android path is deliberately blocked rather than represented as passed. Exact final CI results must be taken from the workflow runs attached to the final commit.

## Release gate / APK

The source version contract is 4.25.0 across backend, Android header, and Gradle versionName; ci_release_gate.py was stale at 4.24.0 and now checks 4.25.0. The release gate must still be rerun on the final HEAD; its passing status is not assumed here.

The previous Android lint failure was caused by Gradle Wrapper 8.14.5 being flagged as older than 8.14.6. The checked-in Android Gradle Plugin is 8.13.2; Gradle 8.14.6 is a patch-level update above the AGP 8.13 minimum Gradle baseline. The wrapper and both CI Gradle settings have therefore been aligned to 8.14.6. Exact-head lint/unit/build results are still required. A built APK, artifact SHA-256, install test, and device launch are not claimed unless the exact-head workflow produces and verifies them.

## Remaining P0 blockers

1. No independent immutable Spot account UID is guaranteed in the account response; first-use pinning is not operator-attested account enrollment.
2. Add-on protective resize and residual SELL stop/cancel-replace recovery are not fully proven.
3. Cancel/fill races and crash boundaries need full production-path state/ledger assertions, not only coordinator/database fixtures.
4. Android autonomous execution is explicitly disabled pending its own durable mutation and identity/recovery implementation.
5. Futures has no verified account/execution adapter and is not covered by Spot tests.
6. Python low-level client methods still exist; the source-to-sink allowlist needs an automated CI gate that fails on any new unapproved caller.
7. Release gate, Android lint/unit tests, artifact provenance, APK install, and device launch require fresh exact-head evidence.

**Acceptance decision:** BLOCKED. Do not merge PR #51.
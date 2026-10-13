# WILLIAMS BOT 2.1 — TASK 001.7: Order Identity Consistency Patch

Status: PARTIALLY IMPLEMENTED — P0 OPEN

## Scope
This patch closes one specific reconciliation gap: a positive Binance lookup must not overwrite a different exchange order ID already persisted in the durable mutation ledger.

## Changes
- `app/src/main/java/com/williamsbot/BinanceTradeEvidence.kt`
  - Added `verifyOrderIdentity` to require exact symbol and client-order-ID matches, a non-empty exchange order ID, and equality with any already-persisted exchange order ID.
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`
  - The single-order recovery path now validates the response against the persisted `exchangeOrderId` before validating order status, quantities, and fills or writing RECONCILED.
- `app/src/test/java/com/williamsbot/BinanceTradeEvidenceTest.kt`
  - Added tests for valid identity, symbol mismatch, client ID mismatch, persisted exchange-order-ID conflict, and missing identity fields.

## Safety effect
A contradictory exchange order identity throws before the ledger transition to RECONCILED. Recovery remains fail-closed through the existing reconciliation-required path. No Binance order mutation was sent; this is read-only recovery code.

## Verification evidence
- Previous exact HEAD `f4a703822fc5eb5626b14da3004f268f3e409684`:
  - Python backend tests: SUCCESS.
  - Williams Campaign CI: SUCCESS; both Python tests and Android build/static-check jobs completed successfully, including APK verification/checksum/provenance steps.
  - Build Android APK workflow: `startup_failure`; GitHub reported zero jobs. This is not evidence of a Gradle compilation failure and does not verify the latest patch.
- This patch has not yet been verified by a completed CI run at its resulting HEAD. Do not treat the previous SHA's successful Campaign APK artifact as proof for this patch's SHA.

## Still open
- Exact-HEAD CI after this patch, including the standalone APK workflow and artifact checksum/signature/provenance.
- Runtime-level reconciliation tests and fault injection at each durable boundary.
- Operation-specific reconciliation for OCO parent/children, cancellation, and cancel-replace.
- Full deterministic FI-01–FI-12 execution and retained evidence.
- Complete Android/Python mutation-boundary inventory and secret-isolation sentinel sweep.
- SQLite migration, concurrent recovery, and crash/restart verification.
- End-to-end campaign exposure, balance, protective-order coverage, and REST/user-stream convergence checks.

## Final status
PARTIALLY IMPLEMENTED — P0 OPEN

PR #66 must remain OPEN, Draft, and unmerged. No changes to main/base, no history rewrite, no real credentials, and no live order mutations.

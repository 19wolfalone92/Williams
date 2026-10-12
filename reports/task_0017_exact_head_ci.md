# TASK 001.7 — Exact-HEAD CI Recovery and Reconciliation Identity

PR #66 remains OPEN, Draft, unmerged; base `fix/pytest-safe-collection`.

## Latest verified source snapshot
- Code/test source SHA before this report-only update: `a9afb6bba62f3d30c4c68fa9973b1c48299272c9`.
- New hardening added on this source snapshot:
  - `BinanceTradeEvidence.verifyOrderIdentity` validates the queried order's symbol and exact clientOrderId, requires a nonempty exchange orderId, and rejects mismatch with an exchange order ID already persisted in the ledger.
  - `DurableMutationLedger.transition(RECONCILED)` checks evidence symbol/client ID against the durable intent and now also rejects changing an already-persisted exchangeOrderId.
  - Tests cover exchange identity mismatches and ensure conflicting evidence leaves the operation unresolved.
  - Existing fill verification rejects duplicate trade IDs, mismatched symbol/order ID, invalid quantities/price/quote quantity/commission/time/side flags, quote-quantity conflicts, and a trade quantity sum inconsistent with executedQty.
- These changes are committed. They are not considered test-passed until exact-SHA CI finishes.

## CI evidence and limitations
- On the preceding observed HEAD `89d95b05e8f0d77b812db6367ddfe698ceec3434`, Python run `38301441273` was still in progress; its active job was auditing Python dependencies. Do not count it as success for the newer source SHA.
- Campaign CI run `38301441207` was pending but exposed no jobs at the time queried.
- Standalone Android APK run `38301442403` completed with `startup_failure`, zero jobs and zero artifacts. This is not a Gradle compile/test failure; the workflow never exposed a job to execute. Root cause remains externally blocked/unavailable from the exposed GitHub Actions data.
- Earlier successful Campaign/Python results are not evidence for this exact source SHA.
- APK package metadata, signature, checksum and provenance are not verified for this exact source SHA until a fresh Android job completes and its artifacts are inspected.

## Still open — P0
- Exact-HEAD CI on the latest source.
- OCO parent/child recovery; cancel and cancel-replace reconciliation.
- Full Android/Python mutation-boundary inventory and evidence.
- Deterministic execution of FI-01–FI-12 and crash-boundary injection.
- SQLite migration/concurrency/recovery validation.
- BL-004 secret isolation verification with synthetic sentinel values.
- Android APK workflow scheduler/startup failure and artifact/signature/checksum verification.
- Campaign-level exchange exposure, balance, protection coverage, and REST/stream convergence remain outside the single-order reconciliation proof.

## Status
`PARTIALLY IMPLEMENTED — P0 OPEN`.

No live trading credentials or exchange order mutations were used. PR #66 must remain open and Draft; do not merge while these blockers remain.

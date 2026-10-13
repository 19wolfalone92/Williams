# TASK 001.5 — Final Verification Snapshot

**Executable source SHA verified:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Required starting SHA:** `8a5ea866a6d7fa10960aca25b2123a34c11601c0`  
**PR #66:** OPEN, Draft, unmerged  
**Base branch:** `fix/pytest-safe-collection` at `0007e79b1afde86b8c736128e6d09bfdd370106f`  
**Compare:** 21 commits ahead, 0 behind. No base-branch modification, merge, close, reset, or force-push.

## Changes verified in source

- No automatic POST/DELETE retransmission in Android timestamp retry path.
- Evidence-gated durable `RECONCILED` transition and safe reopening of legacy terminal rows.
- SQLite schema v4 client ID uniqueness for new POST-type operations.
- Redaction of REST/user-stream audit payloads plus synthetic-secret tests.
- Exact PR-head checkout in CI; pinned Gradle distribution checksum; trusted wrapper regeneration and official wrapper JAR checksum verification before `./gradlew`.
- APK checksum, debug signing verification, and source SHA provenance.

## Verification results

- Python backend run `38268195155`: SUCCESS; 219 passed, 4 warnings.
- Campaign CI run `38268195164`: SUCCESS; Android unit tests, lint, debug APK build, APK checksum/signature/provenance, and artifact upload passed.
- APK source SHA: `cf35f2fb8d2f68770f191efaa272864e7debe04c`.
- APK SHA-256: `23e8cf4684ba81117516961f152eb09446aaa6930ac676f1e4c0a6143bc73bc7`.
- Standalone Android workflow `38268196664`: `startup_failure`, zero jobs; unresolved external workflow-startup issue.
- Exact Android test count was not exposed by the workflow log/artifacts; only task success is claimed.
- The checked-in wrapper JAR is not yet replaced, although CI regenerates and verifies an official copy before executing it.

## Remaining P0 blockers

1. Complete Android exchange reconciliation for orders/fills/OCO children/cancel-replace/balances/exposure/campaign/protection and REST-vs-stream discrepancies.
2. Execute FI-01–FI-12 through production mutation/recovery abstractions with every required assertion.
3. Complete exhaustive Android/Python source-to-sink mutation containment and automated bypass tests.
4. Complete migration matrix across all supported schema versions, interrupted writes and repeated recovery idempotency.
5. Complete BL-004 sentinel sweep across every log, exception, diagnostics, report and remote backend persistence path.
6. Replace the checked-in non-validated wrapper JAR with the verified official binary; current CI mitigates by regenerating it before execution.
7. Resolve the standalone Android workflow `startup_failure` or retain it as an explicitly unavailable check.

**Permitted status: PARTIALLY IMPLEMENTED — P0 OPEN.** This is not a closure certificate.

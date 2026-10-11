# TASK 008.3 — Regression results

**Status: BLOCKED — NO CORRECTION SHA OR POST-CORRECTION CI RUN**

- Baseline SHA: 1a7e7209c99152c3bf29f3e2f07e7db088d9243b
- Baseline workflow: 38129367877
- Baseline result: 230 passed / 11 failed.
- Current branch has 13 report/artifact commits after baseline. No production-code or test correction was made by TASK 008.3.
- Current branch CI: NOT VERIFIED; no confirmed run/status result was found at review time.
- No local test command was executed during this task. Saved logs prove baseline only, not post-correction regression.

| Gate | Result | Evidence |
|---|---|---|
| Exact baseline SHA | PASS for baseline provenance | run 38129367877 and preserved logs |
| Python compilation | PASS at baseline only | TASK 008.2 log |
| full_safety_audit.py | PASS at baseline only | TASK 008.2 log |
| Full Python suite | FAIL at baseline: 230 passed, 11 failed | preserved pytest output |
| Android unit tests | PASS at baseline only | Android job log |
| Android lint | PASS at baseline only | Android job log |
| Android debug APK | PASS at baseline only | Android job log |
| Post-correction Python suite | NOT RUN | no correction SHA |
| Post-correction Android suite | NOT RUN | no correction SHA |
| Binance Spot Testnet end-to-end | NOT RUN | separate integration stage |

## Required negative regression cases

1. Missing/wrong/stale trusted preflight => no exchange mutation.
2. Missing canonical ExecutionBarrier => no exchange mutation.
3. Permit reuse => rejected; unknown mutation outcome => reconciliation, not retry.
4. Partial fill => residual quantity and position state remain durable and protected.
5. Missing/unknown protection => no new entries; cannot report protected.
6. Missing/invalid clock budget => UNKNOWN and not ready.
7. Missing/invalid/future/stale freshness evidence => conservative status; never READY on UNKNOWN.
8. Boolean reconciliation override cannot create proof of reconciliation.
9. Android CI success does not substitute for physical-device or exchange integration evidence.

No assertion was weakened and no production code was changed in TASK 008.3.

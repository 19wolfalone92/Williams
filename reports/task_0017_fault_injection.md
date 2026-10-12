# TASK 001.9 — FI-01–FI-12 Fault Injection

- Canonical scenarios remain unchanged from `reports/task_0012_fault_injection_results.md` and `reports/task_0013_fault_injection_results.md`.
- Campaign Python test step [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359): **259 passed, 4 warnings in 7.50s** on code/test SHA `fd3276c5f041dd47f0941d5b07fff4726c8c9124`. The overall workflow was later cancelled during cleanup/concurrency; this is not an overall SUCCESS conclusion.
- Branch HEAD before this report refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`.

| FI | Canonical scenario | Targeted Python fake-exchange evidence | Remaining limitation |
|---|---|---|---|
| FI-01 | Failure before acceptance/transmission | PASS; pre-submit DB failure, zero mutations | Android not injected |
| FI-02 | Accepted order, lost response | PASS; one mutation, restart lookup, no resubmit | Android not injected |
| FI-03 | Exchange success/local persistence failure | PASS; ambiguous state then read-only recovery | Android not injected |
| FI-04 | Restart from UNKNOWN/SUBMITTING | PASS for persisted AMBIGUOUS restart | Full Android SUBMITTING matrix absent |
| FI-05 | Partial fill then cancellation | PASS; trade evidence and final SQLite state checked | Not all lifecycle permutations |
| FI-06 | Duplicate event/recovery delivery | PASS; second recovery is idempotent | All stream event orderings not covered |
| FI-07 | Crash at state transitions | PARTIAL; before-commit failure and after-commit crash tested | Not every transition boundary |
| FI-08 | Exchange order exists/local record missing | PASS for missing local exchange order ID recovered by client ID | Full exchange inventory recovery absent |
| FI-09 | Confirmed absence vs incomplete history | PARTIAL; unavailable/not-found, missing/duplicate IDs, mismatch and 1000-row cap tested | No full pagination implementation |
| FI-10 | Ambiguous cancellation/exit | PASS for active order after cancel ambiguity | Compound cancellation unsupported |
| FI-11 | Partial reconciliation failure | PASS for trade-history and SQLite persistence failure cases | Not every fault point |
| FI-12 | Retry while unresolved | PASS; no second fake-exchange mutation | Android not injected |

These results are targeted Python production-abstraction tests, not complete closure of each canonical acceptance matrix. No live credentials or live orders were used. P0 remains open.
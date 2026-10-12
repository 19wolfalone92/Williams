# TASK 001.8 — FI-01–FI-12 Fault Injection

- Audited source/test SHA: `31ba73ff81c43edb5be4606b200dce752148967a`.
- Canonical definitions are retained from `reports/task_0012_fault_injection_results.md` and `reports/task_0013_fault_injection_results.md`; no scenarios have been renamed or substituted.
- Status: NOT CLOSED — P0 OPEN.

| FI | Canonical scenario | Evidence/result at audited SHA |
|---|---|---|
| FI-01 | Failure before acceptance/transmission | NOT TESTED end-to-end |
| FI-02 | Exchange accepts, response lost | NOT TESTED end-to-end |
| FI-03 | Response/local persistence failure | NOT TESTED end-to-end |
| FI-04 | Restart with SUBMITTING/UNKNOWN | PARTIAL ledger unit coverage; integrated fake exchange NOT RUN |
| FI-05 | Partial fill then cancellation | NOT TESTED end-to-end |
| FI-06 | Duplicate event delivery | NOT TESTED end-to-end |
| FI-07 | Crash at state transitions | NOT TESTED at every boundary |
| FI-08 | Exchange order exists/local state missing | NOT TESTED end-to-end |
| FI-09 | Missing order vs incomplete/unavailable history | PARTIAL fail-closed code; integrated fake exchange NOT RUN |
| FI-10 | Ambiguous cancellation/exit | NOT TESTED end-to-end |
| FI-11 | Partial reconciliation failure | NOT TESTED end-to-end |
| FI-12 | Retry while unresolved | PARTIAL static/ledger guards; exact exchange mutation count NOT TESTED |

## Exact-SHA CI evidence

- Python backend run [38301629007](https://github.com/19wolfalone92/Williams/actions/runs/38301629007): SUCCESS on SHA 31ba73f.
- Campaign run [38301629067](https://github.com/19wolfalone92/Williams/actions/runs/38301629067): Python job SUCCESS; Android job was still running at inspection.
- Passing general Python tests do not establish FI scenario PASS. No FI row is marked PASS without scenario-specific assertions and recorded mutation counts.

## Missing required evidence

For every FI, still need initial durable state, injected failure point, observed behavior, final SQLite state, actual fake-exchange mutation count, fail-closed result, restart outcome and test result tied to a commit SHA. Do not close FI-01–FI-12 until all twelve have executed against production abstractions.

No live credentials or live orders used.
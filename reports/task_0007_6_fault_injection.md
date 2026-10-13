# TASK 000.7.6 — Fault Injection
Date: 2026-10-12 UTC

Mock-only tests inspected include transport timeout, ambiguous persistence failure, post-submit persistence failure, duplicate intent claim, retry blocking, SQLite reopen/claim persistence, transaction rollback, busy timeout, and integrity checks. The Python CI run on HEAD `974596dbc72ea90d6bf1c08cc105151796440acf` failed at the public error assertion (1 failed / 218 passed in backend suite; Campaign Python 1 failed / 229 passed).

| Fault | Expected invariant | Evidence/status |
|---|---|---|
| restart after ambiguous submission | no blind retry; unresolved intent blocks | partial tests; current SHA unverified |
| SQLite write error | fail closed before submit or reconcile after submit | tests exist; current SHA unverified |
| crash between send and result commit | outcome remains unknown; reconcile | incomplete simulation |
| duplicate fill/event | idempotent cumulative state | evidence missing |
| unknown exchange status | reconcile required; no new mutation | code path inspected; simulation missing |
| out-of-order events | state machine rejects/regresses safely | evidence missing |
| secret-bearing exception | public/log/state sinks redact | tests exist; full consumer matrix missing |
| repeated ambiguous result | no repeat submit until reconciliation | test exists; current SHA unverified |

Each scenario's runtime actual SQLite and reconciliation result has not been recorded for the complete matrix. Do not interpret this report as a passed fault-injection suite.

Status: **INCOMPLETE — EVIDENCE MISSING**.

# TASK 001.3 — Fault-injection results

**Status: NOT RUN — P0 OPEN.** Implementation/test source SHA: `8415ab5e6b74e082b865e2cf2ddbcbde29fdb366`.

The repository still does not contain a deterministic fake exchange that exercises the Android production mutation sink and a complete Android recovery worker. FI-01 through FI-12 are all **NOT RUN**; no mutation counts or fake exchange state are claimed.

| Scenario | Required invariant | Result |
|---|---|---|
| FI-01 failure before submission | Durable intent; retry only with evidence | NOT RUN |
| FI-02 accepted order, lost response | Reconcile without duplicate | NOT RUN |
| FI-03 exchange success/local persistence failure | Restart and reconcile | NOT RUN |
| FI-04 restart from ambiguous submission | Gate remains blocked | NOT RUN |
| FI-05 partial fill and cancellation | Preserve fills/exposure | NOT RUN |
| FI-06 duplicate event delivery | Idempotent transition and mutation count | NOT RUN |
| FI-07 crash at state transitions | Defined recovery per crash point | NOT RUN |
| FI-08 exchange order/local record missing | Reconstruct or remain blocked | NOT RUN |
| FI-09 absence versus incomplete history | Never infer absence from incomplete evidence | NOT RUN |
| FI-10 ambiguous cancellation/exit | No duplicate mutation | NOT RUN |
| FI-11 partial reconciliation failure | Keep unresolved | NOT RUN |
| FI-12 retry while unresolved | No second mutation | NOT RUN |

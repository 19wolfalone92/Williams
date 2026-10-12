# TASK 001.3 — Fault-injection results

**Status: NOT RUN — P0 OPEN.** Implementation/test source SHA: `efe5d12272e3cdd01ef48daeac4a56af6f685040`.

The repository does not yet contain the required deterministic fake exchange exercising the Android production mutation sink and recovery logic. Therefore FI-01 through FI-12 are all **NOT RUN**. No mutation counts or fake exchange records are claimed.

| Scenario | Required behavior | Result |
|---|---|---|
| FI-01 failure before submission | Durable intent; safe retry only with evidence | NOT RUN |
| FI-02 accepted order, lost response | Reconcile without duplicate | NOT RUN |
| FI-03 response persistence failure | Restart and reconcile | NOT RUN |
| FI-04 restart from ambiguous submission | Gate remains blocked | NOT RUN |
| FI-05 partial fill and cancellation | Preserve fills/exposure | NOT RUN |
| FI-06 duplicate event delivery | Idempotent transition and mutation count | NOT RUN |
| FI-07 crash at transitions | Defined recovery per crash point | NOT RUN |
| FI-08 exchange order/local order missing | Reconstruct or remain blocked | NOT RUN |
| FI-09 absence vs incomplete history | Never infer absence from incomplete evidence | NOT RUN |
| FI-10 ambiguous cancellation/exit | No duplicate mutation | NOT RUN |
| FI-11 partial reconciliation failure | Keep unresolved | NOT RUN |
| FI-12 retry while unresolved | No second mutation | NOT RUN |

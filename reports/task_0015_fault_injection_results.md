# TASK 001.5 — FI-01–FI-12 Results

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Overall result: NOT CLOSED. The twelve required scenarios have not all been executed through the Android production mutation and recovery abstractions.

| Scenario | Status | Evidence / gap |
|---|---|---|
| FI-01 pre-transmission failure | NOT VERIFIED | Python barrier has persistence-failure tests; Android end-to-end injection not run. |
| FI-02 exchange accepts, response lost | NOT VERIFIED | Python barrier has ambiguous-submit tests; no complete Android fake-exchange integration. |
| FI-03 local persistence fails during transition | PARTIAL | Python persistence-failure tests exist; Android transition fault injection not run. |
| FI-04 restart during ambiguity | PARTIAL | Python SQLite reopen recovery tests exist; Android restart fault injection not run. |
| FI-05 partial fill then cancellation | PARTIAL | Python recovery validates quantities/status; complete integrated cancel test not proven. |
| FI-06 duplicate/out-of-order user events | NOT VERIFIED | No end-to-end cross-platform event-order fault harness demonstrated. |
| FI-07 crash at each durable transition | NOT VERIFIED | Full transition-by-transition matrix not executed. |
| FI-08 exchange order exists, local campaign missing | PARTIAL | Python order recovery exists; campaign reconstruction/protection proof incomplete. |
| FI-09 incomplete/unavailable history | PARTIAL | Python worker leaves lookup/trade-history errors unresolved; all fault variants not enumerated. |
| FI-10 ambiguous cancel/exit/cancel-replace | NOT VERIFIED | Cancel-replace must reconcile both outcomes; no complete FI matrix. |
| FI-11 partial reconciliation failure | NOT VERIFIED | No integrated multi-record resume/idempotency proof. |
| FI-12 retry while unresolved | PARTIAL | Existing barrier/ledger guards and Python tests; complete Android transport proof not run. |

Python CI at this SHA reports 219 passed, 4 warnings. Those tests do not establish that every FI scenario passed. Do not treat any row above as fully passed unless a future exact-SHA run executes the integrated scenario and records the assertions required by TASK 001.5.

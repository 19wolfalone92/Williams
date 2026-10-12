# TASK 001.5 — FI-01–FI-12 Results

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Overall result:** NOT CLOSED. The complete twelve-scenario integrated matrix has not been executed.

| Scenario | Status | Current evidence / gap |
|---|---|---|
| FI-01 — before transmission | PARTIAL | Existing Python persistence/barrier tests; no integrated Android transport injection. |
| FI-02 — accepted, response lost | PARTIAL | Python ambiguous-submit coverage; full Android fake-exchange path not demonstrated. |
| FI-03 — persistence failure | PARTIAL | Python persistence-failure tests; no Android transition fault injection. |
| FI-04 — restart during ambiguity | PARTIAL | Python SQLite reopen/recovery tests; Android restart matrix incomplete. |
| FI-05 — partial fill then cancel | PARTIAL | Python order/trade reconciliation checks; no full cross-platform scenario. |
| FI-06 — duplicate/out-of-order stream | NOT VERIFIED | No complete integrated event-order harness. |
| FI-07 — crash at every transition | NOT VERIFIED | Full lifecycle transition matrix not executed. |
| FI-08 — exchange order, missing local campaign | PARTIAL | Python order recovery exists; campaign/protection reconstruction incomplete. |
| FI-09 — incomplete/unavailable history | PARTIAL | Python recovery leaves lookup errors unresolved; all variants not enumerated. |
| FI-10 — ambiguous cancel/exit/cancel-replace | NOT VERIFIED | Full cancel-replace leg-by-leg reconciliation not proven. |
| FI-11 — partial reconciliation failure | NOT VERIFIED | Multi-record restart/resume/idempotency matrix not executed. |
| FI-12 — retry while unresolved | PARTIAL | Durable barrier/ledger guards and Python tests; full Android proof not run. |

Python backend run `38268195155` passed 219 tests with 4 warnings. Android unit tests passed in Campaign CI run `38268195164`, but this does not mean every FI scenario passed. Do not report any FI row as fully passed without a dedicated executed test and all required assertions.

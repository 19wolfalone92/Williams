# TASK 001.3 — Fault-injection results

**Status: NOT RUN — P0 OPEN.** Current source SHA: `74f12c595181d0b4362fa02f2b2de436db9a751c`.

No deterministic fake exchange is integrated with the Android production mutation sink and a complete Android recovery worker. FI-01 through FI-12 are all **NOT RUN**. No exchange mutation counts or fake exchange records are claimed.

| Scenario | Result |
|---|---|
| FI-01 failure before submission | NOT RUN |
| FI-02 accepted order, lost response | NOT RUN |
| FI-03 exchange success/local persistence failure | NOT RUN |
| FI-04 restart from ambiguous submission | NOT RUN |
| FI-05 partial fill and cancellation | NOT RUN |
| FI-06 duplicate event delivery | NOT RUN |
| FI-07 crash at state transitions | NOT RUN |
| FI-08 exchange order/local record missing | NOT RUN |
| FI-09 absence versus incomplete history | NOT RUN |
| FI-10 ambiguous cancellation/exit | NOT RUN |
| FI-11 partial reconciliation failure | NOT RUN |
| FI-12 retry while unresolved | NOT RUN |

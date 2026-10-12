# TASK 001.2 — Fault-injection results
**Status: NOT RUN — P0 OPEN**

The 12 required fake-exchange scenarios have not yet been implemented as a production-boundary test harness or executed. No scenario is marked PASS.

| # | Scenario | Result | Reason |
|---:|---|---|---|
| 1 | Failure before acceptance | NOT RUN | No integrated fake-exchange harness |
| 2 | Accepted, response lost | NOT RUN | No integrated fake-exchange harness |
| 3 | Response persistence failure | NOT RUN | No injectable ledger/network boundary |
| 4 | Restart with UNKNOWN/SUBMITTING | PARTIAL | Ledger persistence unit test exists from TASK 001.1; not a fake-exchange scenario |
| 5 | Partial fill | NOT RUN | Fill reconciliation not wired to durable intent |
| 6 | Duplicate event delivery | NOT RUN | End-to-end exact mutation-count test absent |
| 7 | Crash at each transition | NOT RUN | Transition-by-transition fault harness absent |
| 8 | Exchange order exists/local state missing | NOT RUN | Exchange inventory recovery absent |
| 9 | Confirmed absence vs incomplete history | NOT RUN | Operation-specific evidence policy absent |
| 10 | Ambiguous cancellation/exit | NOT RUN | No end-to-end fake-exchange cancellation test |
| 11 | Partial reconciliation failure | NOT RUN | No resumable reconciliation worker for Android ledger |
| 12 | Retry while unresolved | PARTIAL | Ledger rejects non-PREPARED replay in sink, but exact network mutation count not tested |

## Added test coverage
Three unit tests were added for deterministic generated client-order identity and credential-free fingerprint shape. They are source changes, not PASS claims; no test run has been inspected for the post-change SHA.

# TASK 001.9 — FI-01–FI-12 Fault Injection

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Canonical definitions remain sourced from `reports/task_0012_fault_injection_results.md` and `reports/task_0013_fault_injection_results.md`; they were not redefined.
- Last completed Campaign Python run [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277): 258 passed on prior SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef).
- The new OCO list-ID conflict test/code added after that run is awaiting exact-SHA CI.

| FI | Canonical scenario | Targeted fake-exchange test on prior SHA | Coverage limitation |
|---|---|---|---|
| FI-01 | Failure before acceptance/transmission | PASS in targeted Python harness | Android path not injected |
| FI-02 | Accepted order, lost response | PASS; mutation count remains 1 after restart recovery | Android path not injected |
| FI-03 | Exchange success/local persistence failure | PASS; ambiguous row reconciles without resubmit | Android path not injected |
| FI-04 | Restart with UNKNOWN/SUBMITTING | PASS for persisted AMBIGUOUS restart path | Full Android SUBMITTING matrix not covered |
| FI-05 | Partial fill then cancellation | PASS with trade history and final SQLite state | Not all exchange lifecycle permutations |
| FI-06 | Duplicate event/recovery delivery | PASS; second recovery claims no terminal intent | Does not cover every stream-event ordering |
| FI-07 | Crash at state transitions | PARTIAL; pre-commit failure test exists and post-commit crash test passes | Not injected at every transition |
| FI-08 | Exchange order exists/local state missing | PASS for missing local exchange order ID recovered by client ID | Full missing local-intent inventory recovery not implemented |
| FI-09 | Confirmed absence vs incomplete/unavailable history | PARTIAL; unavailable/not-found, quantity mismatch, missing/duplicate IDs and 1000-row cap covered | No explicit multi-page pagination implementation; 1000-row response is rejected |
| FI-10 | Ambiguous cancellation/exit | PASS for active order after cancel ambiguity; remains unresolved | Cancel-list and cancel-replace are intentionally unsupported |
| FI-11 | Partial reconciliation failure | PASS for trade-history lookup and local persistence failures | Not every storage fault boundary |
| FI-12 | Retry while unresolved | PASS; second submit blocked and mutation counter stays 0 | Android path not injected |

These PASS labels refer only to the targeted Python fake-exchange test cases on the prior SHA, not complete closure of each canonical acceptance matrix. The Campaign run reported 258 passed, 4 warnings. No FI-01–FI-12 closure is claimed until all required sub-boundaries, including Android production-boundary scenarios, are exercised on the final SHA.

No live credentials or live orders used.
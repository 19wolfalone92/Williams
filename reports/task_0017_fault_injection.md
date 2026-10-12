# TASK 001.7 — FI-01–FI-12 Fault Injection

- Canonical scenario definitions: `reports/task_0012_fault_injection_results.md` and `reports/task_0013_fault_injection_results.md`; these definitions are retained rather than redefined.
- Code SHA: `e5f82ae60b0b5c1e9512f9efe3bedc1d018ab132`. Boundary static guard SHA: `1ef8b13575bc5b728c8447b0cd92b54c24e89504`.
- Overall: NOT CLOSED — P0 OPEN.

| FI | Canonical scenario | Result |
|---|---|---|
| FI-01 | Failure before acceptance/transmission | NOT TESTED end-to-end |
| FI-02 | Exchange accepts, response lost | NOT TESTED end-to-end |
| FI-03 | Response/local persistence failure | NOT TESTED end-to-end |
| FI-04 | Restart with SUBMITTING/UNKNOWN | PARTIAL ledger coverage; integrated fake-exchange test NOT RUN |
| FI-05 | Partial fill then cancellation | NOT TESTED end-to-end |
| FI-06 | Duplicate event delivery | NOT TESTED end-to-end |
| FI-07 | Crash at state transitions | NOT TESTED at every boundary |
| FI-08 | Exchange order exists/local state missing | NOT TESTED end-to-end |
| FI-09 | Missing order vs incomplete/unavailable history | PARTIAL fail-closed code; integrated test NOT RUN |
| FI-10 | Ambiguous cancellation/exit | NOT TESTED end-to-end |
| FI-11 | Partial reconciliation failure | NOT TESTED end-to-end |
| FI-12 | Retry while unresolved | PARTIAL static/ledger guards; fake-exchange mutation-count assertion NOT RUN |

New `BinanceTradeEvidenceTest` tests validate response evidence shape; they are not the canonical FI matrix. No FI row is claimed PASS based on source inspection or generic CI. Required: inject a deterministic fake exchange and controllable persistence/transport faults through the production abstractions, then assert mutation counts, restart behavior, durable evidence, entry gating, and protection/exposure state for every canonical case.

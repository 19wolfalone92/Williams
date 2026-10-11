# Recovery Invariant Matrix — TASK 007.3

**Status:** EXPECTATIONS DEFINED; RUNTIME ENFORCEMENT AND TEST RESULTS NOT VERIFIED.  
**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`.

| Invariant | Required evidence | Current status | Safe failure behavior |
|---|---|---|---|
| One fill changes position at most once | Durable effect identity + replay/concurrency tests | NOT PROVEN | Block risk-increasing actions; reconcile |
| Position quantity matches confirmed fills and fee semantics | Fill-level quantity reconciliation | NOT TESTED | Mark state unknown; no new entries |
| Fees are neither lost nor duplicated | Fee asset/amount/status compared to exchange payload and ledger | NOT TESTED | Fee UNKNOWN, not zero; block completion/admission as required |
| PnL matches fills and fees | Deterministic recalculation with approved rounding | NOT TESTED | Recalculate or block pending reconciliation |
| Campaign agrees with position | Same-transaction projection or proven repair process | NOT PROVEN | Recovery required |
| Risk/capital reservation not released early | Crash tests across reservation and close boundaries | NOT TESTED | Keep conservative reservation until confirmed safe |
| Ledger/journal agrees with canonical effects | Identity-linked ledger audit | NOT PROVEN | Keep discrepancy visible; block finalization if material |
| Recovery is idempotent | Repeated restart/recovery test | NOT TESTED | Reconciliation required; never blind replay |
| Unknown state blocks new entries | Call graph from gate to every risk-increase sink | NOT PROVEN globally | Fail closed |
| Missing data is not treated as zero | Negative tests for missing fee/price/identity and parse failure | NOT TESTED | UNKNOWN/INVALID/CONFLICTED, not zero |
| Ambiguous commit is resolved by durable read | Commit-then-raise test and restart test | NOT TESTED | Query/reconcile before retry |
| Exchange and SQLite state are reconciled separately | Controlled mismatch test | NOT TESTED | No new risk until exchange/local state agrees |

Tolerances must come from explicit exchange filters and approved financial rounding rules; do not introduce arbitrary epsilons. Exchange/SQLite are separate consistency domains and cannot be treated as one transaction. This matrix defines acceptance assertions; it does not claim the current code satisfies them.
# TASK 008.3 — Safety invariant matrix

Status: BLOCKED — corrections and post-change regressions not run.  
Baseline: 1a7e7209c99152c3bf29f3e2f07e7db088d9243b

| Invariant | Required evidence | Baseline observation | Required regression | Status |
|---|---|---|---|---|
| All mutations pass canonical ExecutionBarrier | call-chain audit and positive/negative tests | Missing barrier blocks; one test omits injection | No mutation when absent/rejecting; positive path uses trusted preflight | BLOCKED |
| Trusted preflight cannot be forged | concrete service bound to exact Testnet client; freshness and all checks | Callback-only test is rejected | Missing, forged, mismatched and stale proof blocked; valid service positive path | BLOCKED |
| Durable intent before submit | persistence failure injection | Barrier persists intent/events before submit | DB false/exception => submit count zero | NOT VERIFIED |
| Mutation permit is one-use | permit tests | Reuse rejected on second POST attempt | Keep reuse rejection; safe retry requires fresh admission linked to same durable intent | BLOCKED |
| Unknown exchange outcome never triggers blind retry | timeout/5xx/lost-response injection | Client contract forbids blind transport retry | UNKNOWN => RECONCILE_REQUIRED and history lookup | NOT VERIFIED |
| Partial fill cannot flatten residual position | exchange fills, balance and durable DB evidence | Manual partial-exit assertion fails; cause unresolved | Filled quantity applied once; residual remains OPEN/RECONCILE_REQUIRED and protected | NOT VERIFIED |
| Protection gaps block new entries | cancel/replacement/OCO failure injection | Emergency path explicitly unimplemented | Missing/rejected/unknown protection blocks entry | BLOCKED |
| Emergency exit is not simulated as successful | actual exchange confirmation | Emergency mutation throws and records RECONCILE_REQUIRED | No fake success; unknown result reconciled by stable ID | NOT IMPLEMENTED |
| Flat requires exchange evidence | fills, balances, reconciliation | Lifecycle contract requires proof of full sell | Partial fills/delayed history cannot set FLAT | NOT VERIFIED |
| Clock budget must be approved | config provenance and validation | Missing budget returns UNKNOWN | Missing/negative/malformed budget blocks; approved value reaches later checks | BLOCKED |
| Preflight aggregation is conservative | documented contract and tests | Early return leaves later checks UNKNOWN | Diagnostics may continue only while aggregate readiness remains blocked | BLOCKED |
| Unknown/foreign orders block | order response and reconciliation evidence | Order test not reached due clock budget | Foreign order FAIL; known order needs durable reconciliation | BLOCKED |
| Freshness reason IDs are stable | documented reason-code contract | Implementation and test disagree; status remains UNKNOWN | Approved canonical reason ID and UNKNOWN status | NOT VERIFIED |
| Mainnet and real orders excluded | endpoint assertions/fake clients | Baseline test setup uses fake clients | Testnet host only; no live credentials/orders | PASS for task scope only |
| UNKNOWN cannot authorize new entries | aggregate gate tests | Missing clock budget is fail-closed | Any required UNKNOWN => not ready | PASS for observed gate behavior only |

## Emergency-exit gate

Do not implement emergency exit by removing the current exception. A separately approved contract must specify durable intent, stable order identity, ownership checks, protection cancel/replace, partial-fill accounting, reconciliation, crash recovery, and criteria for FLAT. Until then emergency exit remains NOT IMPLEMENTED and blocks release.

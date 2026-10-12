# TASK 001.0 — Order Recovery Fault-Injection Matrix
Date: 2026-10-12 UTC

## Inspected SHA and evidence
- Source SHA: `7c034dcb359a62cd8fedfa14f89f7072334c4900`.
- CI: Campaign Android + Python succeeded on this SHA; backend run `38235681761` succeeded (219 passed, 4 warnings in the full pytest output); Campaign Python run `38235681955` succeeded (230 passed, 4 warnings).
- Existing Android unit-test Gradle task succeeded in Campaign CI, but no dedicated TASK 001.0 fault-injection suite was added or executed.
- No live Binance order or authenticated mutation request was made.

## Required matrix status
| # | Scenario | Current code/evidence | Explicit durable-state + mutation-count assertions in a dedicated suite? | Result |
|---:|---|---|---|---|
| 1 | Timeout before exchange acceptance | Network call can throw; no deterministic mock-exchange test found for this Android path | No | **NOT VERIFIED** |
| 2 | Timeout after exchange acceptance | No simulated accepted-then-response-lost Android test found | No | **NOT VERIFIED** |
| 3 | Exchange accepts, local persistence fails | Audit is recorded after response; no pre-submit intent transaction to inject failure into | No | **NOT VERIFIED** |
| 4 | Pending local intent, exchange status unknown | No central durable intent model connected to all mutation call sites | No | **NOT VERIFIED** |
| 5 | Partial execution | `ExecutionAccumulator` has fill accumulation; this is not proof of mutation recovery and persistence invariants | No | **PARTIAL COMPONENT ONLY** |
| 6 | Duplicate exchange events | In-memory accumulator de-duplicates keys; durable restart/idempotency behavior not proven by dedicated integration matrix | No | **PARTIAL COMPONENT ONLY** |
| 7 | Restart with pending intent | No dedicated Android durable-intent restart scenario exists for a common mutation ledger | No | **NOT VERIFIED** |
| 8 | Missing local order while exchange order exists | No dedicated deterministic simulator scenario established | No | **NOT VERIFIED** |
| 9 | Local order exists, exchange order absent | No dedicated deterministic simulator scenario established | No | **NOT VERIFIED** |
| 10 | Ambiguous exit/cancel result | Manual SELL is multi-step and direct; reconciliation flag in catch does not establish exactly-once mutation | No | **NOT VERIFIED** |
| 11 | Recovery fails mid-reconciliation | Recovery code exists, but no TASK 001.0 fault-injection matrix or recorded per-transition results | No | **NOT VERIFIED** |
| 12 | Retry while previous mutation unresolved | No universal central guard tied to a durable intent ledger | No | **NOT VERIFIED** |

## Mandatory test assertions absent
The task requires, per scenario, durable state before fault, simulated exchange state, injection point, mutation attempt count, duplicate-mutation prevention, final local state, reconciliation state, entry gate, and recovered state after restart. These were not produced. The general Campaign test suite passing is not a substitute for this matrix.

## Safe next implementation step
First introduce a testable mutation interface and durable intent repository; then migrate all signed mutation call sites to require a durable intent capability. The mock exchange must be able to commit an order then drop the response, and the repository must inject failures at transaction begin/write/commit. Recovery should operate from the same ledger and prove a retry is rejected until authoritative exchange reconciliation resolves the prior operation.

**Disposition: OPEN — no 12-scenario suite was implemented or run.**

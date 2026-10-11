# Exactly-Once Test Plan — TASK 007.3

**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`  
**Plan status:** PREPARED; all tests NOT RUN in this execution.

## Harness constraints
- Isolated temporary SQLite database per test; never open production DB.
- Exact source checkout at implementation SHA, recorded `git rev-parse HEAD`, parent and worktree status.
- Save test command, runtime/dependency versions, stdout/stderr, exit code, DB snapshot before/after, and relevant logs.
- Inject failures only in test doubles or controlled test hooks; no Binance Mainnet or live order mutation.
- Test the actual production persistence/application methods, not a parallel mock implementation alone.

## Scenarios
| ID | Setup / injection | Required assertion |
|---|---|---|
| FILL-001 | Apply same ID/payload twice | All financial effects once; duplicate call is a no-op or stable duplicate result |
| FILL-002 | Same ID, different qty/price/fee | Conflict fails closed; no second effect; reconciliation required |
| FILL-003 | Two concurrent independent handlers | One durable effect claim; no duplicate qty/fee/PnL |
| FILL-004 | Kill after fill persistence | Recovery identifies pending effect and completes once or blocks |
| FILL-005 | Kill after position change | No partially accepted final state; recovery does not double apply |
| FILL-006 | Fail at fee write | Fee remains explicitly unknown/pending; never silently zero |
| FILL-007 | Fail at PnL write | Consistent recomputation or entry block until reconciliation |
| FILL-008 | Commit succeeds but caller sees exception | Read-after-restart resolves outcome before retry |
| FILL-009 | Same fill from REST and WebSocket plus recovery | One identity and one financial effect |
| FILL-010 | Run recovery twice | Second run does not change reconciled financial state |
| FILL-011 | Local position disagrees with exchange evidence | Reconciliation latch blocks new risk until resolved |
| FILL-012 | SQLite write/commit error | No false success; unknown commit resolved durably before retry |

## Required assertions per scenario
Check fill rows and canonical identity; gross/net position quantity; average entry; fee amount and fee asset/status; realized and unrealized PnL; campaign lifecycle; open/pending risk and capital reservation; ledger/journal; reconciliation state; entry-admission gate. Capture exact before/after snapshots and logs.

## Concurrency/crash details
FILL-003 must use separate Database instances/connections and synchronized starts; additionally test separate processes if architecture permits. FILL-004–008 must terminate a subprocess at the defined durable boundary, reopen the same DB, and run actual recovery. Test both pre-commit and ambiguous post-commit outcomes. A unit test that only checks unique-index behavior is insufficient.

**Acceptance:** do not mark PASS unless the exact SHA, command, return code, outputs, and all invariant assertions are retained. Existing general CI does not substitute for this matrix.
# WILLIAMS BOT 2.1 — TASK 001.2
## P0 Blocker Register

**Overall result: BLOCKED — P0 OWNERSHIP / MUTATION / RECOVERY GATES REMAIN OPEN**  
**Repository:** `19wolfalone92/Williams`  
**Audit source snapshot:** `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
**Base/main SHA at PR snapshot:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #47:** OPEN / DRAFT / NOT MERGED  
**Safety:** no Mainnet request or real order; no production source changes in TASK 001.2.

## 1. Confirmed control facts

| Item | Evidence | Result |
|---|---|---|
| Target branch / PR | PR #47 metadata | `audit/williams-2.1-task-001-architecture`, OPEN, DRAFT, NOT MERGED |
| Source snapshot | PR head metadata | `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc` |
| Main/base at observed snapshot | PR base metadata and compare | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Branch divergence | compare `04da5f9c...` → `cb0a5b1...` | 135 commits ahead, 0 behind, merge base equals base SHA |
| Changed files in PR diff | GitHub PR changed-file inventory | 27 files, including execution/persistence, Android runtime, tests, workflows and prior audit docs |
| CI metadata associated with source snapshot | workflow-run endpoint | Python backend tests SUCCESS (run 38142652068 / #2453); Williams Campaign CI SUCCESS (38142652065 / #4519); Build Android APK SUCCESS (38142652069 / #2112) |
| Local working tree | No local clone available in this task | NOT CHECKED; do not claim clean tree or local test execution |
| Device / Testnet proof | No physical device bundle or exchange lifecycle evidence retrieved in this task | NOT VERIFIED |
| Merge / main mutation | This task writes only on existing audit branch | No merge and no main write performed |

Green CI is useful evidence for the source snapshot, but does not prove complete order-call closure, one financial writer, exactly-once downstream effects, installed APK provenance or full Testnet recovery.

## 2. Open blockers

| ID | Priority | Blocker | Evidence / affected code | Closure criteria | Owner task | Status |
|---|---|---|---|---|---|---|
| B-001 | P0 | Single execution authority is not established | Python `server.py` / campaign execution and Android `StandaloneRuntime.kt` signed mutation transport both exist | Select one authority per account; enforce fencing so the other cannot submit/cancel; device/backend-offline concurrency test | 001 + 006 + 008 | OPEN |
| B-002 | P0 | Complete order mutation call graph not proven | `binance_client.py`, `execution_barrier.py`, `campaign_execution.py`, `portfolio_trader.py`, Android `StandaloneRuntime.kt` | Enumerate all POST/DELETE/adapter wrappers and callers; every path gated or fails closed; negative bypass tests | 003 | OPEN |
| B-003 | P0 | Campaign and Position single-writer ownership not proven | `campaign_engine.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`, Android runtime | Complete writer/caller graph; define one owner; prove other stores are projections; transaction and concurrency tests | 007 | OPEN |
| B-004 | P0 | Exactly-once financial effects not proven | Python DB/fill/fee/PnL writers; Android `TradeJournal.kt`, `TradingAuditStore.kt`, `ExecutionAccumulator.kt` | Unique fill and effect IDs; atomic or recoverably journaled quantity/fee/PnL/Campaign updates; duplicate/replay/crash tests | 007 | OPEN |
| B-005 | P0 | UNKNOWN and timeout recovery not fully proven | `BinanceSpotClient._request`, `ExecutionBarrier.reconcile_unresolved_intents`, Android mutation journal | Inject accepted-but-response-lost, response-persist failure, restart and two-process cases; no blind mutation retry | 003 + 007 | OPEN |
| B-006 | P0 | Risk reservation / order intent / fill are not proven atomic | Campaign execution, risk/capacity checks, SQLite intent/reservation and financial persistence | One durable reservation ID, atomic reserve/intent transition, safe rollback/recovery; race and lock-contention tests | 005 + 007 | OPEN |
| B-007 | P0 | Cancel-replace and OCO/protection outcomes not closed | Spot order, cancel-replace, order-list routes; Python/Android stop and exit paths | Persist both sides before mutation; test old-canceled/new-accepted, partial success, timeout, residual quantity and restart | 003 + 007 + 008 | OPEN |
| B-008 | P0 | Android APK ↔ source and runtime authority not proven | `StandaloneRuntime.kt`, `TradingAuditStore.kt`, Android APK workflow | Exact source SHA, build run, APK SHA-256, package/version, installed-device logs, restart/backend-offline test | 006 | OPEN |
| B-009 | P0 | End-to-end Spot Testnet lifecycle not proven | `binance_client.py`, barrier, runtime configuration | Testnet-only config hash; order → fill → protection → cancel/exit → restart/reconcile with exchange IDs and logs | 008 | OPEN |
| B-010 | P0 | TASK 002–008 not proven compatible on one candidate SHA | Cross-branch matrix and contract docs | Exact branch heads/base/PRs; controlled integration candidate; complete CI/evidence manifest | 009 | OPEN |
| B-011 | P0 | Baseline reproducibility / local tree proof incomplete | GitHub source and PR metadata available; local checkout unavailable | Clean checkout of exact SHA, commands/exit codes/log hashes; local `git status` and baseline test run | 002 | OPEN |
| B-012 | P0 | Risk policy and execution gate parity across Python/Android not proven | Python risk/preflight; Android `ExecutionGate` and runtime | Same canonical risk contract, deterministic parity fixtures, fail-closed mismatch tests | 005 + 006 | OPEN |
| B-013 | P0 | Durable Kill Switch / Safe Mode coverage across all entry routes not proven | Python server/control state and Android runtime/gate | Enumerate all entry endpoints; durable latch; every entry blocked; recovery cannot clear latch silently | 003 + 006 + 008 | OPEN |
| B-014 | P0 | Exact release version/provenance gate not yet accepted | `ci_release_gate.py`, server/app version metadata and workflows | One version contract, artifact source SHA and APK hash, required checks completed on exact candidate | 002 + 006 + 009 | OPEN |

## 3. P1/P2 follow-up

| ID | Priority | Issue | Required result | Status |
|---|---|---|---|---|
| B-101 | P1 | Market-data gap/stale-candle recovery | REST resync and hard entry block until continuity/context version is valid | OPEN |
| B-102 | P1 | Decimal/financial precision parity | Explicit scale/rounding/fee-conversion contract across Python and Android; no silent float/REAL conversion in authoritative ledger | OPEN |
| B-103 | P1 | Williams signal source parity | Source-linked rules, closed-bar/fractal confirmation, no look-ahead, Python–Android fixtures | OPEN |
| B-104 | P1 | Backtest/replay evidence | Deterministic dataset/config/source hashes; anti-lookahead and fee/slippage evidence | OPEN |
| B-105 | P1 | PR/review/evidence ownership | Named reviewer and acceptance record for every P0 gate | OPEN |
| B-201 | P2 | Machine-readable evidence manifest | Task, status, source/base SHA, command, exit code, run ID, artifact/log digest, environment and reviewer | OPEN |

## 4. Acceptance policy

- **PASS** only when every P0 item is closed with evidence bound to one candidate integration SHA and the required fault tests are green.
- **FAIL** only when a specific invariant violation is reproducibly demonstrated on an exact source/test SHA.
- **BLOCKED** when source reachability, owner, baseline, device, Testnet or fault evidence is missing. Missing evidence is not automatically a reproduced FAIL.
- A branch name, report, green generic CI or successful compile does not independently close a blocker.
- Do not use Binance Mainnet or authorize real orders while P0 remains open.

**Current decision: BLOCKED.**

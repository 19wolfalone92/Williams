# WILLIAMS BOT 2.1 — TASK 006.15
## Financial Mutation Graph, Decimal Integrity & Durable Recovery Gate

**Status: BLOCKED — NOT ACCEPTED**  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**TASK 006.14 audited SHA:** `42fa73dc48a4aa7a522cec45f3e96e06ae159eb7`  
**Branch head before this report:** `8060b07cf7c8168fd2698b754113e1f2842755cd`  
**PR #46:** Open / Draft / Unmerged. Main unchanged.  
**Production code changes in this task:** none. No financial applier/new fill path/second ledger. No exchange trading mutations.

## 1. Baseline and exact-SHA CI

Fresh GitHub check: PR #46 is Open, Draft=true, Merged=false; base SHA remains `04da5f9c09572e99ff310be3a1908179165e528c`. Branch comparison to base reports 195 commits ahead and 0 behind, with DB, execution, Android runtime, workflow, test and prior audit changes. This is a broad accumulated diff, not an isolated financial patch.

CI for exact TASK 006.14 SHA `42fa73dc48a4aa7a522cec45f3e96e06ae159eb7` is now verified:
- Python backend tests: run `38130095367`, SUCCESS — release gate, compile, static safety audit, safe offline tests.
- Build Android APK: run `38130095335`, SUCCESS — release gate, Android lint, unit tests, debug/release APK build, metadata/signature/checksum verification.
- Williams Campaign CI: run `38130095342`, SUCCESS — deterministic safety/campaign tests and Android build/static checks.

Runs: https://github.com/19wolfalone92/Williams/actions/runs/38130095367 ; https://github.com/19wolfalone92/Williams/actions/runs/38130095335 ; https://github.com/19wolfalone92/Williams/actions/runs/38130095342

These are general CI results for the TASK 006.14 SHA, not the final SHA of this report and not proof of the required specialized crash/concurrency/financial test matrix. The prior TASK 006.14 report was updated to record this exact-SHA evidence in commit `8060b07cf7c8168fd2698b754113e1f2842755cd`.

## 2. Confirmed source-to-sink findings (graph remains partial)

### Python SQLite writers and schema
- `Database.save_campaign_fill` writes `exchange_fill_ledger` and compatibility `campaign_fills`.
- `exchange_fill_ledger` stores quantity/price/quote/commission as TEXT, keyed by `(exchange, account_id, symbol, trade_id)`; effect state defaults to PENDING. The inspected key does not establish environment/market-type isolation.
- In the same `save_campaign_fill`, quantity, price, quote quantity, fee quote, base commission and commission amount are converted to Python `float` for `campaign_fills`. This is a confirmed Decimal-integrity defect.
- Legacy financial fields in `trades`, `campaigns`, `campaign_orders`, and `campaign_fills` include SQLite REAL. Campaign engine initial/add-on fill methods also perform float arithmetic.
- `save_trade`, trade update/close, `save_campaign`, `save_campaign_order`, state writers and direct SQL in campaign execution are separate reachable mutation surfaces. `Database.transaction` uses BEGIN IMMEDIATE/rollback, but the full proof that every related write shares one connection and transaction is absent.

### Unknown commission and add-on recovery
- `campaign_execution.py` calls `CampaignEngine.record_add_on_fill(..., fee_quote=0.0)` on a recovery-side add-on path.
- `CampaignEngine.record_add_on_fill` accepts a float fee and persists it in campaign tags; this does not prove the exchange fee is zero.
- `ExecutionSummary` has zero fee defaults. Strict parsing in some ingestion paths does not prove every caller preserves UNKNOWN fee state.
- No end-to-end durable `KNOWN / UNKNOWN / INVALID / CONFLICTED` fee state machine or late-fee test proves that fee correction cannot reapply quantity/cost.

### Recovery Gate
- Python `ExecutionBarrier` contains a BUY-side global recovery check and checks pending ledger effects; `MultiPositionTrader._submit_order` requires an injected barrier.
- This is not proof that every reachable mutation uses the barrier. Full entry roots, constructors, workers, callbacks, direct clients, recovery paths, Android, and Futures have not been traced to closure.

### Android mutation ambiguity
- `StandaloneRuntime.kt` has `signedRequest`, `signedPost`, `signedDelete`, and cancel-replace paths. The inspected signed-request retry loop handles POST/DELETE through the same helper and retries selected 5xx and I/O failures without a demonstrated read-only-only retry policy. An ambiguous mutation outcome may therefore be retried before reconciliation. This is a P0 finding; it is not a claim that a duplicate trade was observed in production.
- All Android mutation call sites and durable gate reachability remain unproven.

### Emergency SELL and Futures
No complete proof establishes emergency SELL uses current free balance after open/in-flight sells, partial fills, base-asset commission, LOT_SIZE/MARKET_LOT_SIZE/MIN_NOTIONAL and UNKNOWN resolution. No complete Futures mutation graph was established. Do not blanket-block all SELL; ambiguous sells must remain in reconciliation and must not be blindly resent.

## 3. Recovery Gate matrix

| Path | Result | Missing proof |
|---|---|---|
| Spot initial BUY | BLOCKED | all callers/sinks, final gate immediately before mutation, no alternate client |
| Spot add-on BUY | BLOCKED | unresolved fee/effects, balance/reserve and risk checks on every route |
| UNKNOWN retry/replacement | BLOCKED | no blind resend across all Python and Android mutation paths |
| REST / WS / recovery fill | BLOCKED | durable identity and exactly-once projection across restart |
| Android BUY/add-on | BLOCKED | complete Kotlin mutation graph and process-death proof |
| Futures risk increase | BLOCKED | every sink gated or all such routes proven unreachable |
| Emergency SELL | BLOCKED | current free balance, in-flight order accounting, filters, UNKNOWN/no-duplicate proof |

## 4. Required tests: not proven by existing CI

No dedicated TASK 006.15 test matrix was run in this task. Required missing proof includes:
1. REST/WS/recovery duplicate delivery and conflicting same-key payload against real DB rows.
2. Same/two workers and independent OS processes.
3. Rollback on projection failure, SQLITE_BUSY/LOCKED, process death during transaction and after fill-before-commit.
4. Restart/replay, late commission resolution without quantity/cost reapplication.
5. BUY blocked with pending effects/UNKNOWN and assertion of zero exchange mutations.
6. Android process-death recovery; Futures gate/unreachability.
7. Emergency SELL with open/in-flight orders, partial fills, base commission and ambiguous result.

Mocks/callback returns alone are insufficient; use temporary SQLite files, independent processes, fault injection/restart and an exchange-mutation counter.

## 5. Why production edits were not safe to isolate

Changing the REAL projection alone could desynchronize legacy readers; changing campaign engine arithmetic alone could introduce another financial owner; replacing a zero fee with a sentinel alone would not establish a durable fee-state contract. The complete caller graph and migration compatibility are not yet established. Therefore this task deliberately made no production financial-writer or mutation-client changes rather than claiming an unsafe partial fix. The financial applier remains absent.

## 6. Minimum next safe stage

1. Machine-check every direct SQL financial write and every exchange mutation call; trace all roots, DI, workers, callbacks, recovery, Kotlin and Futures.
2. Add and test Android policy that never automatically retries signed POST/DELETE after ambiguous I/O/5xx; keep the intent unresolved pending reconciliation.
3. Define durable fee-state transitions and fill identity across REST/WS/recovery.
4. Map all REAL readers before choosing the smallest exact-decimal-compatible schema/projection change.
5. Add real-DB independent-process and process-death tests, plus BUY gate and emergency SELL mutation-count assertions.
6. Run all required suites on one final SHA.

## 7. Acceptance status

- Complete writer graph: BLOCKED.
- All mutation sinks classified: BLOCKED.
- All BUY/add-on paths gated: BLOCKED.
- Decimal exactness: FAIL/BLOCKED.
- Unknown fee preserved: FAIL/BLOCKED.
- Exactly-once / atomicity / crash recovery: NOT PROVEN.
- SQLite process concurrency: NOT PROVEN.
- Emergency SELL safety: NOT PROVEN.
- Android and Futures: BLOCKED.
- Dedicated CI matrix on this report's final SHA: not yet run.
- PR #46 remains Open/Draft/Unmerged; main unchanged; no applier introduced.

**Final: TASK 006.15 — BLOCKED — NOT ACCEPTED.** No real trading mutation was performed.

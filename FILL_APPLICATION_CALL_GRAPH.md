# Fill Application Call Graph — TASK 007.3

**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`  
**Status:** PARTIAL GRAPH; REST/WS/recovery convergence NOT VERIFIED.

## Evidence-backed partial graph

```text
Exchange/recovery evidence
  ├─ portfolio_trader.py::recover
  │    └─ portfolio_trader.py::_reconcile_trade
  │         └─ local trade/position persistence paths (multiple)
  ├─ trader.py::recover_state
  │    └─ trader.py::_recover_closed_trade
  │         └─ legacy trade persistence
  └─ campaign_execution.py recovery/order paths
       └─ campaign/trade SQL projection

Campaign fill persistence sink:
  campaign_engine.py::record_initial_fill / record_add_on_fill
       └─ db.py::save_campaign_fill (fill journal)
       └─ campaign/campaign-event persistence paths

Other independent financial sinks:
  db.py::save_trade / update_trade_quantity / close_trade
  db.py::save_campaign
  trade_journal.py::record_entry / record_exit
       └─ db.py::save_trade_journal
```

This is a partial map of confirmed paths, not a complete call graph and not proof every arrow is the exact direct caller edge in every runtime mode. REST, WebSocket and restart recovery have not been proven to converge on one durable fill-effect owner.

## Required edge annotations still open
For each caller→callee edge, exact callsite, propagated fill ID, Decimal/float conversions, in-memory mutations, SQL writes, transaction ID/scope, exception behavior, retry behavior, process-restart behavior, and source line range must be recorded. Specifically unresolved: fill persisted before position update; position mutation before durable fill; fee/PnL split transactions; campaign/ledger divergence; same fill from REST + WebSocket + recovery.

## Required target semantics (not current behavior claims)
`Exchange Fill → validate canonical identity → durable fill + effect claim → position/fee/PnL/campaign/reservation/ledger in one SQLite transaction → APPLIED marker`. Any ambiguous commit must be resolved by durable read/reconciliation before retry. Binance and SQLite cannot share one atomic transaction. No canonical effect owner has been proven in the reviewed implementation.
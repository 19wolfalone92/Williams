# Transaction Boundary Matrix — TASK 007.3

**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`  
**Status:** CODE REVIEW PARTIAL; crash recovery NOT TESTED.

| Operation | Writes | Transaction boundary evidence | Crash window / consequence | Status |
|---|---|---|---|---|
| Persist fill | `campaign_fills` via `save_campaign_fill` | Method can commit outside outer `transaction()` | Fill may exist before projections applied | Confirmed possibility; no injected test |
| Trade insert | `trades` via `save_trade` | Independent commit possible | Trade projection can diverge from fill journal | Confirmed path; no injected test |
| Position quantity | `update_trade_quantity` | Independent commit possible | Quantity may change without fee/PnL completion | Confirmed path; no injected test |
| Close/PnL/fees | `close_trade` | Independent commit possible | Close state/PnL may be out of sync with campaign/fill | Confirmed path; no injected test |
| Campaign projection | `save_campaign` | UPSERT; independent commit possible | Campaign qty/VWAP/PnL/reserve may lag | Confirmed path; no injected test |
| Journal/event | `save_trade_journal`, `log_campaign_event` | Independent commit possible | Journal may lag/lead primary state | Confirmed path; no injected test |
| Recovery gate keys | `state_set` / `state_delete` | Per-key commit possible | Partial set of gate keys can persist | Confirmed possibility; caller-specific analysis incomplete |
| Direct recovery SQL | `campaign_execution.py` | Explicit commit observed on reviewed path | Recovery projection may commit separately | Partial evidence |

`Database.transaction()` uses BEGIN/commit/rollback on one connection. Nested behavior is tracked by an instance-level flag without savepoints; that is not a cross-connection or cross-process lock. SQLite atomicity applies only to writes on the same connection in the same transaction. It does not include Binance.

No crash injection was run. Actual recovery behavior for each crash window is therefore **NOT VERIFIED**. Do not convert this table into a claim that every path always commits separately; the exact caller's outer transaction must be verified at each callsite.
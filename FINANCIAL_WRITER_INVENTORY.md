# Financial Writer Inventory — TASK 007.3

**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`  
**Status:** PARTIAL — repository-wide transitive inventory NOT COMPLETE.

| Writer | File/function | Confirmed effects | Boundary / idempotency | Status |
|---|---|---|---|---|
| FW-DB-01 | `db.py::state_set` | bot/recovery state UPSERT | Own commit when not inside transaction | CONFIRMED sink; full caller graph NOT VERIFIED |
| FW-DB-02 | `db.py::state_delete` | Delete state key | Own commit outside transaction | CONFIRMED sink |
| FW-DB-03 | `db.py::save_execution_intent` | Durable execution intent | Separate commit; not fill-effect transaction | CONFIRMED sink |
| FW-DB-04 | `db.py::save_order` | Order state | Lookup/insert/update; conditional commit | CONFIRMED sink |
| FW-DB-05 | `db.py::save_trade` | Trade/position/PnL/fee row | Separate commit possible | CONFIRMED financial sink |
| FW-DB-06 | `db.py::update_trade_oco` | Protective-order state | Separate commit possible | CONFIRMED state sink |
| FW-DB-07 | `db.py::update_trade_quantity` | Position quantity | Separate commit possible | CONFIRMED financial sink |
| FW-DB-08 | `db.py::close_trade` | Exit/PnL/fees/close state | Separate commit possible | CONFIRMED financial sink |
| FW-DB-09 | `db.py::save_trade_journal` | Trade journal | Separate commit possible | CONFIRMED journal sink |
| FW-DB-10 | `db.py::save_campaign` | Qty, average entry, PnL, reserves, lifecycle | UPSERT; float conversion; separate commit possible | CONFIRMED projection sink |
| FW-DB-11 | `db.py::save_campaign_signal`, `set_campaign_signal_state` | Signal lifecycle | INSERT OR REPLACE / UPDATE | CONFIRMED state sink |
| FW-DB-12 | `db.py::save_campaign_order` | Order and risk/capital reservation state | Lookup/insert/update | CONFIRMED state/reservation sink |
| FW-DB-13 | `db.py::save_campaign_fill` | Fill identity and numeric fields | Unique `(symbol, exchange_trade_id)`; conflicting tuple raises | Fill-row dedupe only; all-effect exactly-once NOT PROVEN |
| FW-DB-14 | `db.py::log_campaign_event` | Campaign event journal | Separate commit possible | CONFIRMED journal sink |
| FW-CE-01 | `campaign_engine.py::record_add_on_fill` | In-memory campaign position/VWAP/risk and persistence | Shared transaction with all projections not demonstrated | ATOMICITY NOT VERIFIED |
| FW-CE-02 | `campaign_engine.py::record_initial_fill` | Initial position/campaign projection | Shared transaction not demonstrated | ATOMICITY NOT VERIFIED |
| FW-CEX-01 | `campaign_execution.py::persist_recovered_campaign_order`; legacy trade update | Recovery order/trade projection | Direct SQL and explicit commit observed | ATOMICITY NOT VERIFIED |
| FW-PT-01 | `portfolio_trader.py::_repair_stale_reconcile_states` | Reconciliation state repair | Direct SQL; complete path not fully traced | PARTIAL |
| FW-PT-02 | `portfolio_trader.py::_reconcile_trade` | Exchange/local trade reconciliation | Not proven as sole fill applier | NOT VERIFIED for exactly-once |
| FW-PT-03 | `portfolio_trader.py::recover` | Recovery orchestration | Multiple downstream writes | ORCHESTRATOR; ownership unresolved |
| FW-TR-01 | `trader.py::recover_state`, `_recover_closed_trade` | Legacy recovery/trade projection | Separate DB writes/state transitions | EXACTLY-ONCE NOT PROVEN |
| FW-TJ-01 | `trade_journal.py::record_entry`, `record_exit` | Journal delegation | Separate from financial projection unless outer transaction proven | ATOMICITY NOT VERIFIED |

## Inventory limitations
The table is a confirmed direct-sink inventory for reviewed modules, not proof that every Python/Kotlin/API/legacy caller and writer in the repository has been found. No negative claim is inferred from a single search. Source line references and blob SHAs for the implementation checkpoint are recorded in `docs/TASK_007_2_RECOVERY_CONCURRENCY_AUDIT.md` §14.2–14.3. All source-level statements refer to implementation SHA above, not the later documentation HEAD.

**Required closure:** enumerate every SQL writer and in-memory financial mutation across the full tree; enumerate all callers; prove that every fill source converges on one exclusive effect owner; then test.
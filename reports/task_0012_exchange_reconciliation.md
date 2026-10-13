# TASK 001.2 — Exchange reconciliation
**Status: NOT IMPLEMENTED COMPLETELY — P0 OPEN**

## Verified current behavior
- `recover()` detects unresolved ledger rows and returns `recovered=false`, `execution_enabled=false`, state `RECONCILE_REQUIRED`.
- This is fail-closed behavior, not exchange reconciliation.
- A successful HTTP response can move ordinary mutation intents to ACKNOWLEDGED. Cancel-replace with a non-SUCCESS leg is treated as ambiguous, but the durable schema does not separately persist both leg outcomes.

## Required but not yet implemented
- Read-only exchange lookup by stable client ID/order ID for every unresolved order intent.
- Querying order history and trades to distinguish filled/canceled/rejected orders from absent open orders.
- OCO/list-order reconciliation and individual leg reconciliation.
- Independent cancel-replace old-order and replacement-order reconciliation.
- Balance/position and protective-order reconciliation linked to each durable intent.
- Durable normalized observations with timestamp, query identity, response facts, evidence completeness and accepted decision reason.
- A deterministic, operation-specific decision procedure for retry eligibility.

## Safety decision
Do not transition UNKNOWN to RECONCILED merely because a query succeeded. Missing or contradictory evidence must preserve UNKNOWN and the execution barrier. No exchange mutations or authenticated live order requests were performed for this task.

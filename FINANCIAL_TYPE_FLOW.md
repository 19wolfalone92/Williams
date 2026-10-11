# Financial Type Flow — TASK 007.3

**Implementation SHA:** `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`  
**Status:** PARTIAL CODE REVIEW; precision invariants NOT TESTED.

## Observed path
```text
Exchange numeric payload (commonly string)
  → execution_accumulator.py: Decimal(str(value))
  → aggregation of quantity / quote / commission in Decimal
  → db.py::save_campaign_fill converts financial values to float
  → SQLite REAL columns
  → row read / Python float-bearing models
  → PnL, risk, campaign and recovery calculations
```

## Risks and required validation
| Field | Observed representation | Risk / unresolved contract |
|---|---|---|
| Fill quantity | Decimal in accumulator; float on DB write; REAL storage | Binary representation and exact round-trip not proven; base-asset fee/net quantity semantics need explicit definition |
| Price | Decimal/string in accumulation path; float/REAL persistence | Exact tick/scale and round-trip not proven |
| Quote quantity | Decimal then float/REAL | May differ from product/aggregation under rounding; no silent tolerance allowed |
| Commission amount | Decimal in accumulator; float/REAL persistence | Must preserve fee asset and distinguish unknown from confirmed zero |
| Realized PnL | float-bearing trade/campaign paths; REAL columns | Reconciliation with fills and fees not tested |
| Risk/reservation | float-bearing campaign/order models; REAL | Can affect admission and reserve release; precision contract incomplete |

## Validation requirements
Reject malformed and non-finite numeric values before financial mutation; do not map parse errors or absent fee to zero. Preserve exact source strings/identity where needed. Compare persisted/reloaded values with exact decimal semantics and instrument precision rules. Define rounding/quantization only from approved exchange symbol filters and financial contract; no arbitrary epsilon.

No schema migration, historical conversion, or float→Decimal mass change was performed. A defect should be addressed through separately reviewed canonical financial contract and migration plan.
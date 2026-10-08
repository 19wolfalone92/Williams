# WILLIAMS INTRADAY CORE 1.0

## Canonical timeframe contract

| TF | Role | May create Williams signal? |
|---|---|---|
| D1 | Macro / Air Bag | No |
| H4 | Context | No |
| H1 | Williams Strategy Truth | Yes |
| M15 | Trigger / actual fill | No |
| M5 | Intrabar replay / execution diagnostics | No |

H1 is the only operative decision timeframe for the production intraday profile.
WM1, WM2, WM3, Fractals, AO, Alligator, Campaign and Structural Trail are all computed from closed H1 candles.

## Core invariants

1. M15 and M5 cannot create, invalidate or reinterpret an H1 Williams signal.
2. H4 can classify context as SUPPORTIVE / NEUTRAL / ADVERSE; it is not a BUY trigger.
3. D1 is macro/Air-Bag context only.
4. WM1 remains valid without requiring bullish Alligator or positive AO.
5. WM2 is the third same-colour AO bar on H1.
6. WM3 is an H1 fractal and its Teeth condition is evaluated dynamically at trigger time.
7. A structural stop is market-derived; account size changes quantity, not stop distance.
8. Stops may only move toward lower risk. No widening and no averaging down.
9. Fixed percentage take-profit is disabled in Core.
10. Reverse-pyramid weights are 1:5:4:3:2, subject to the hard campaign risk cap.
11. Spot Core is LONG-only; bearish Williams observations never create a short.
12. Wave / AI / Quant layers are advisory after Core validity and cannot invalidate Core.
13. Execution economics can block a valid Williams setup without changing Core truth.
14. At 18:00 UTC new campaigns and pending adds are cancelled.
15. At 20:00 UTC remaining managed inventory is forced flat.
16. One active campaign is allowed in the small-deposit profile.

## Risk envelope

Production small-deposit defaults:
- initial risk: 0.25% equity
- maximum campaign risk: 0.60% equity
- maximum daily loss: 1.00% equity
- maximum full stop-outs/day: 2
- maximum active campaigns: 1
- leverage: 0
- averaging down: off
- fixed TP: off

Conservative profile:
- initial risk: 0.20%
- maximum campaign risk: 0.50%
- maximum daily loss: 0.75%
- WM3-first: disabled
- H4 adverse context: 50% of base initial risk

## Signal lifecycle

H1 closed candle -> StrategyDecision -> DecisionTrace -> SignalSpec / Pending Signal -> M15 trigger and exchange fill -> Campaign Step -> H1 structural management -> structural/EOD exit

A signal can be pending without being an executed order. Actual exchange fill is persisted separately from trigger price.

## Fractal lifecycle

CREATED -> ACTIVE -> VALID / INVALID -> TRIGGERED

An older pending fractal may be superseded by a newer same-direction fractal.
Formation-time state and trigger-time Teeth state must remain auditable.

## Economic gate

ExecutionEconomicsGate checks only executable feasibility:
- equity / available capital
- structural stop geometry
- spread
- fee/slippage reserve
- quantity rules
- minimum notional
- optional expected edge vs transaction cost

A blocked setup remains core_valid=true in DecisionTrace.

## Campaign management

First actual executed Williams confirmation is Campaign Step 1 regardless of whether that signal is WM1, WM2 or WM3.

Later valid same-direction confirmations are additions only. Price movement in the direction of an open profit is never by itself a reason to add.

Structural trail operates from H1 3-5 bar structure.

Sleeping Alligator permits observation/WM1 monitoring but suppresses aggressive trend-following additions.

Stagnation is diagnostic only; it does not replace structural exits.

## Backtest chronology

The backtester must model:

H1 close -> pending signal -> M15 price path -> M5 refinement when available -> actual fill -> campaign additions -> H1 trail -> EOD/structural exit.

A same-M15-bar stop hit cannot be inferred from pre-fill M5 bars.

## Diagnostics

Every production decision should expose:
- decision timeframe
- execution timeframe
- micro timeframe
- H4 context
- D1 state
- Alligator state
- WM1/WM2/WM3 state
- Fractal state
- side-specific angulation
- momentum relation
- trigger
- structural stop
- campaign step
- execution feasibility
- risk feasibility
- Core truth
- final trade permission
- first blocking reason

Durable traces are stored in SQLite and exposed through the strategy diagnostic API.

## Golden scenario matrix

1. WM1 -> WM2 -> WM3
2. WM1 -> WM3
3. WM2-first
4. WM3-first
5. WM1 without angulation
6. WM1 inside mouth
7. Fractal initially invalid
8. Teeth becomes favourable later
9. New fractal supersedes older pending fractal
10. Equal highs/lows do not create a false strict fractal
11. Shared-bar fractal
12. Six-bar extended/shared fractal
13. Nine-bar fractal
14. Overlapping fractals
15. Sleeping Alligator
16. Awakening Alligator
17. Gap through trigger
18. Gap through stop
19. Trigger/stop same M15 candle
20. Campaign reaches EOD
21. H4 adverse + pristine H1 WM1
22. H4 supportive + valid H1 WM1
23. Restart with pending entry
24. Restart with active campaign
25. Bearish Williams pressure while LONG on Spot

## Production rule

No new execution feature may create a second source of Williams Strategy Truth.
Any new layer must sit downstream of H1 Core and declare whether it is:
- context
- quality
- risk
- economics
- execution
- diagnostics

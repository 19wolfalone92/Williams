WILLIAMS INTRADAY SMALL-DEPOSIT — CONTRACT 1.0

Production hierarchy

D1 — Macro / Air Bag
H4 — Context only
H1 — sole Williams Decision TF
M15 — trigger / actual fill
M5 — intrabar replay / execution diagnostics

Williams Core on H1
- Alligator
- AO
- WM1
- WM2
- WM3
- Fractals
- Campaign state
- Structural trail

M15 and M5 never create or reinterpret a Williams Core signal.

Entry semantics

The first valid Wise Man that appears may start Campaign Step 1:
- WM1-first — preferred early reversal entry.
- WM2-first — valid first entry.
- WM3-first — valid Core entry; conservative small-deposit profiles may disable it as a policy without redefining WM3.

WM1 requires side-specific increasing angulation. H4 context is diagnostic/policy context and does not redefine WM1 truth.

Campaign

The campaign is ordered by actual confirmed entries, not by a fixed WM1 → WM2 → WM3 sequence.

Reverse-pyramid allocation is a risk-budget weight: 1:5:4:3:2. The hard campaign risk cap always dominates the historical ratio.

Risk

- Initial risk: 0.25% equity
- Maximum campaign risk: 0.60% equity
- Maximum daily loss: 1.00% equity
- Maximum simultaneous campaigns: 1
- Maximum full stop-outs/day: 2
- Leverage: 0
- Averaging down: OFF
- Fixed TP: OFF

Structural stop is determined by market structure. Position size is derived from risk budget and structural stop distance. A wide stop is not compressed; the trade is skipped when sizing becomes economically infeasible.

Execution economics

Execution economics can block a valid Williams signal but can never turn a valid Core signal into FALSE.

Required diagnostics: spread, estimated slippage, fees, expected move/edge, block reason.

Session UTC

08:00 — session opens
18:00 — no new campaigns; pending entries are cancelled
20:00 — mandatory flat

EOD is an operational overlay, not a Williams Core rule.

Exit

Primary protection: initial structural stop and H1 3–5 bar structural trailing.
Stop may only reduce risk.

No stop widening, averaging down, fixed percentage TP, or M5 trailing for H1 campaign.

Spot semantics

Current production mode is Binance Spot long-only. Bullish Core may create LONG. Bearish Core does not create SHORT; it may reduce/close an existing LONG. Futures/short support remains a separate market contract.

DecisionTrace

Every H1 decision should preserve symbol/timeframes, H4 context, Alligator state, WM1/WM2/WM3, fractal, angulation, momentum relation, trigger, initial stop, campaign id/step, Williams validity, risk gate, execution economics gate, final trade_allowed and block reason.

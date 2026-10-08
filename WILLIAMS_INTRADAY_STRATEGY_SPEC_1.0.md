# WILLIAMS INTRADAY CORE 1.0

## Production contract

**Market:** Binance Spot Testnet in the current project; production mode remains Spot LONG-only.

**Decision hierarchy:**

| Layer | Timeframe | Authority |
|---|---|---|
| Macro / Air Bag | D1 | context / emergency state |
| Context | H4 | SUPPORTIVE / NEUTRAL / ADVERSE / SLEEPING / AWAKENING |
| Strategy Truth | H1 | **only place that may create Williams signals** |
| Execution | M15 | trigger / actual fill |
| Replay / Micro | M5 | ordered intrabar reconstruction and diagnostics |

M15 and M5 may never create, invalidate, rewrite, or re-time an H1 Williams signal.

## H1 Williams Core

The H1 stream calculates Alligator, AO, Williams fractals, WM1, WM2, WM3, campaign state, and structural trailing. A signal is evaluated only on a closed H1 candle.

### WM1

For LONG, the closed H1 bar must represent a market moving down, establish a lower low, close in the upper half of its range, be materially outside the Alligator mouth, show side-specific increasing angulation, and retain bearish/downward AO relation. A bullish Alligator ordering and AO > 0 are not prerequisites.

The armed trigger is one tick above the reversal-bar high. The initial structural stop is below the reversal-bar low.

### Angulation

Angulation is measured separately per side from price trajectory versus the Jaw/Teeth reference. The required geometry is increasing separation, not a generic percentage or the maximum of bullish/bearish deltas.

The trace records reference segment, price segment, slopes, initial separation, final separation, growth, angular separation, and validity.

### WM2

WM2 is defined only from H1 AO. The third consecutive rising AO bar is the LONG confirmation. No fractal, M15 AO, or lower-timeframe gate may be required.

### WM3 / Fractals

Fractals are generated only from H1. The detector supports equal highs/lows, shared bars, overlapping structures, and explicit 5/6/9-bar extension metadata. The fractal remains pending until triggered or superseded.

Fractal validity is re-evaluated against current Teeth at trigger, not frozen formation-time Teeth.

## Campaign contract

The first actual filled Williams entry defines Campaign Step 1. WM1-first, WM2-first, and WM3-first are all Core-valid. Signal family is not allowed to pre-assign the campaign step.

Reverse-pyramid weights are 1:5:4:3:2, but they are only a sizing bias. The absolute campaign risk cap always dominates.

## Risk contract

- Initial risk: 0.25% Equity
- Maximum campaign risk: 0.60% Equity
- Maximum daily loss: 1.00% Equity
- One open campaign
- Two full stop-outs halt new trading for the day
- Spot / leverage off
- Averaging down forbidden
- Fixed percentage take-profit off

The stop distance is structural. Deposit size changes quantity, not the structural stop. If a structurally valid stop cannot be economically sized, the trade is skipped.

## Advisory layers

H4, Wave, AI, Quant, OBI, flow, quality, and other research layers may rank or adjust risk. They may not falsify a Williams Core signal. A blocked trade must record williams_valid=true and the separate economic/risk block reason.

## Execution economics

The execution-feasibility gate checks spread, expected slippage, fees, stop room, balance, minimum-order constraints, and session time. This gate does not change Core truth.

## Stop / exit

A stop may move only toward risk reduction. No operation may widen risk or average down.

The Core trail is H1 structural, using the configured 3–5 recent H1 bars. M5 is never allowed to trail an H1 campaign.

Fixed TP is disabled.

The operational intraday overlay is:

- Trading session: 08:00–20:00 UTC
- No new campaign/add-on: after 18:00 UTC
- Pending entries: cancel at the cutoff
- Remaining positions: force flat at 20:00 UTC

This EOD rule is an operational constraint, not a Williams Core signal rule.

## Spot semantics

Bearish Williams signals never open a short in the production Spot profile. They may prevent a new LONG or trigger evaluation of an existing LONG exit/reduction policy.

## DecisionTrace

Every H1 close emits a trace containing the market/timeframe contract, context, Alligator/AO/WM/fractal result, angulation, trigger, protective reference, campaign identity/step, Core validity, economic feasibility, risk feasibility, and final trade admission state.

## Backtest causality

The canonical event chain is:

H1 close -> Core decision -> PendingSignal -> M15 trigger clock -> M5 replay -> actual fill -> structural stop -> Williams add-on -> H1 trail -> EOD exit

An H1 high/low alone must never fabricate a fill when ordered lower-timeframe data is available. Gap-through-trigger, gap-through-stop, and trigger+stop same-bar ambiguity are first-class replay outcomes.

## Modes

WILLIAMS_INTRADAY_CORE is the production reference mode. Faster M30/M1 and positional W1/D1 variants remain research-only and must not mutate production Spot execution semantics.

# WILLIAMS BOT 2.1 — Multi-Entry Strategy Contract
Status: IMPLEMENTATION CONTRACT / SOURCE-VERIFICATION REQUIRED
Branch: fix/williams-fractal-stop-provenance-20261010
Scope: existing architecture only; no rewrite, no main-branch changes.

## 1. Objective and non-negotiable design

The bot must evaluate multiple independently identifiable Williams setups. A setup detector reports evidence; it must not place orders or silently rewrite the meaning of a book rule. The strategy pipeline is:

MARKET_BEHAVIOUR → D1_RISK_CONTEXT → H4_CONTEXT → H1_SETUP/DECISION → M15_ORDER_MONITOR → CAMPAIGN → EXHAUSTION/EXIT

M5 is diagnostics/replay only. It cannot create, confirm, or veto an H1 strategy signal. Closed candles only for signal confirmation; no look-ahead, repainting, or future-fractal use.

Each decision records: source/profile/version, symbol, side, setup ID, source candle time, confirmation time, trigger, invalidation/stop, context snapshot versions, risk decision, campaign state, execution intent/order IDs, and reason codes.

BLOCKED is a valid deterministic outcome. Missing/ambiguous source rule, stale context, incomplete candles, unknown order state, or missing protection must not be replaced by developer discretion.

## 2. Timeframe responsibilities

- D1 — macro airbag only: broad directional conflict, extreme/unsafe regime, major structural boundary. It does not create an entry and should not force a new signal.
- H4 — context only: trend structure, Alligator state/awakening, major support/resistance and whether an H1 campaign has room. It does not create an entry.
- H1 — canonical strategy decision timeframe: Alligator regime, WM1/WM2/WM3, AO/AC, fractal structure, entry setup, campaign additions, structural trailing and exhaustion/exit evidence.
- M15 — execution/fill monitoring only: order activation, spread/slippage, partial fills, protection and exchange-state monitoring. It cannot create or confirm a strategy setup.
- M5 — diagnostics/replay only: troubleshooting data gaps, timing and event ordering. It cannot affect live signal eligibility.

Timeframe mapping is a system policy and must remain configurable only through a versioned profile. It is not to be represented as a universal rule printed by Williams.

## 3. Independent entry algorithms (strategy profile)

The engine evaluates each detector independently on H1, then applies common safety gates. It must retain all eligible candidates and choose deterministically by the profile's precedence; it must not require WM1, WM2 and WM3 to all exist for one trade.

### A. WM1 — reversal-bar / angulation setup

1. Detect the book-defined reversal bar against the prior price movement and evaluate the Alligator/angulation context.
2. Keep the raw WM1 evidence separate from additional context filters (D1/H4, spread, risk, stale data). Report which layer blocked it.
3. LONG candidate: protective reference below the signal bar/structural low; trigger above the signal bar high by exchange tick policy. SHORT is mirrored.
4. Do not label a heuristic slope/distance calculation as proven canonical angulation until verified against the source text/figures and test examples.
5. Expire or invalidate the pending entry if the trigger is no longer valid, the setup is invalidated, or its declared expiry is reached.

### B. WM2 — Super AO setup

1. Detect the documented three-consecutive-same-colour AO-bar pattern as its own Wise-Man signal. Do not require a separate fractal as a prerequisite.
2. Associate the pattern with its corresponding price bar and create a candidate trigger beyond that bar's extreme.
3. WM2 may be the first entry when it is the first valid actionable signal; otherwise it may be a campaign addition only if the campaign rules and risk budget admit it.
4. Record the AO values/colours, source candle and confirmation time. Do not confuse a colour streak with a universal direction/trend filter.
5. Protective reference is the documented structural/pattern invalidation selected by the active source profile; if the code cannot establish it safely, block rather than invent a stop.

### C. WM3 — confirmed fractal breakout

1. Identify a valid up/down fractal using the active profile's bar-count rule.
2. Do not make the fractal actionable until all required right-side candles have closed. Store both center-candle time and confirmation-candle time.
3. Apply the profile's Alligator Teeth/Balance-Line location rule at the correct decision time; do not use shifted display values as future information.
4. LONG trigger above the confirmed up-fractal high; SHORT trigger below the confirmed down-fractal low, using valid exchange tick increments.
5. Structural protection uses the relevant pattern extreme and must be checked against minimum stop distance and position risk. If the stop is already invalidated or the trigger has already broken in a way the contract disallows, do not arm a stale entry.
6. WM3 may be a first entry if it is the first valid actionable signal; otherwise the campaign coordinator determines whether it is an allowed addition.

### D. AO Zero-Line crossing (separate legacy/profiled setup)

1. Detect a closed-bar AO crossing of the zero line and preserve the crossing event as a distinct setup type.
2. Do not silently treat the crossing as WM2 or as an automatic entry. Its eligibility, context, and order trigger must be explicitly enabled in a named source profile after book/source audit.
3. Use a price-structure trigger and stop only when the source profile defines them; otherwise emit evidence-only / BLOCKED.

### E. AO Saucer (separate legacy/profiled setup)

1. Detect the documented three-bar AO histogram formation on closed bars.
2. Preserve bullish/bearish pattern, zero-line context and source candle IDs.
3. Do not enable as a default entry until exact source-version rules and precedence against WM1/WM2/WM3 are verified.
4. Do not treat AC as a standalone entry unless a verified source profile explicitly says so.

### F. AO Twin Peaks (separate legacy/profiled setup)

1. Detect two AO peaks on the same side of zero with the required relative peak and intervening-bar conditions from the selected source edition.
2. Save both peak indices and intervening trough/peak evidence; prohibit future-data leakage.
3. The detector may publish a candidate, but it cannot trade while edition-specific conditions remain unresolved.
4. This is a separate legacy profile, not a hidden additional mandatory filter on every TC2 Wise-Man setup.

### Candidate selection

- Every detector emits a typed SignalSpec/candidate with evidence, eligibility, block reasons and source profile.
- Common safety gates run after detection and do not mutate signal identity.
- With no active campaign, any enabled and valid entry candidate may be selected; use explicit profile precedence (default preference: valid WM1, then WM2, then WM3; legacy AO setups are separately enabled and cannot silently outrank Wise Men).
- With an active campaign, candidate becomes an addition only if side, campaign phase, structural context, add-on policy and remaining campaign risk all permit it. Never increase exposure merely because another detector fires.
- Opposite-direction evidence is not an automatic reverse order. It must pass exit/reconciliation rules first.
- De-duplicate by stable setup identity and source candle; re-scanning the same setup must not create duplicate orders.

## 4. Context/regime engine

Evaluate and expose (do not collapse into a Boolean AO AND fractal AND Alligator):

1. Data quality: closed candles, continuity, timestamps, duplicate/out-of-order bars, sufficient warm-up, price/volume sanity.
2. D1 macro conflict and structural danger.
3. H4 trend/context, Alligator sleeping/awakening/eating/closing state, structural room.
4. H1 Alligator geometry and slope, price location, market structure, fractal levels, WM evidence, AO momentum and AC acceleration evidence.
5. Setup-specific proof and trigger validity.
6. Campaign phase and exhaustion/exit risk.
7. Independent risk/execution gates.

AO is momentum evidence; AC is acceleration evidence, not a universal entry trigger. Tick volume must not be described as centralized traded volume. Wave hypotheses/probabilities are supplementary and must not override a hard safety block or pretend to be calibrated unless calibration evidence exists.

## 5. Order, stop, target and campaign contracts

### Entry orders
- Use the source setup's stop-entry trigger (typically one valid tick beyond the relevant signal/fractal extreme), adjusted to exchange tick/trigger constraints.
- Revalidate the trigger, context version, campaign state, balance/position, and risk immediately before order mutation.
- Persist intent and stable client order ID before submitting. Unknown result means reconcile with exchange history; never blindly retry.

### Stop-loss
- Derive initial protection from the setup's structural invalidation (signal-bar/fractal extreme or verified source-profile rule), not an arbitrary percentage detached from structure.
- Position size is computed from entry-to-stop distance and the risk budget after fees/slippage/lot constraints.
- Reject if stop is invalid, beyond risk limits, exchange filters fail, or protection cannot be confirmed.
- A stop may tighten according to the active trailing rule; it must never be loosened to increase risk.
- Protection missing/orphaned or exchange state unknown blocks new exposure and invokes recovery/reconciliation.

### Take-profit and exit
- Core campaign mode does not impose a universal fixed TP. The primary exit is the selected source profile's structural trailing and/or verified exhaustion/reversal logic.
- If a user/configured fixed target is enabled, label it as a system overlay, not a canonical Williams rule; it must not bypass risk checks.
- H1 owns strategy trailing and exit decisions. M15 only observes/executes those decisions.
- Exhaustion evidence includes loss of momentum, Alligator closing, structural break and opposite Wise-Man evidence according to the selected source profile. Exact combinations must be edition-verified.
- Exit order side is based on actual exposure and exchange state. After partial exit, reconcile remaining quantity and protection before any further action.

### Spot/Futures distinction
- Spot: LONG entries only unless margin/short capability is explicitly supported; bearish signals reduce/close inventory and must not be sent as naked short entries.
- Futures: LONG and SHORT require separate, explicit position-side and reduce-only semantics, leverage/margin checks, and exchange reconciliation.
- No averaging down; campaign/add-on limits and portfolio risk are hard gates.

## 6. TC2 campaign trailing policy

The TC2 core runtime now uses a source-profile price-bar structural trail instead
of silently combining the previous two-bar Alligator/AO exit and Teeth/fractal/ATR
stop formula:

- Default: stop one exchange tick beyond the extreme of the last **3 closed H1 price bars**.
- Optional source-profile variant: **5 closed H1 price bars**, selected explicitly with
  `WILLIAMS_TC2_TRAILING_BARS=5` in Python or the matching native setting
  `tc2_trailing_bars=5`. Both runtimes default to 3 and accept only 3 or 5.
- LONG trail: below the lowest low in the selected window. SHORT trail: above the
  highest high in the selected window. Exchange tick rounding is direction-aware.
- A proposed stop is accepted only when it is valid relative to the current mark and
  strictly reduces risk. The previous exchange-side protection remains until a
  replacement stop is confirmed; ambiguous replacement/cancellation enters reconciliation.
- The former two-bar Alligator/Teeth/AO exit is a **disabled-by-default system overlay**
  (`WILLIAMS_TC2_TWO_BAR_REVERSAL_EXIT=true` / native
  `tc2_two_bar_reversal_exit`). It must not be described as the canonical TC2 core
  trailing rule.

The exact 3-versus-5-bar selection is a profile choice that still needs to be tied to
the verified source edition/figure and replay acceptance vectors. The implementation
makes this choice explicit rather than pretending that ATR/Teeth trailing is the
literal author rule.

## 6. Source-of-truth and implementation status

The following must remain marked SOURCE_VERIFICATION_REQUIRED until the exact edition text and figures have been compared to the implementation: WM1 reversal-bar and angulation definitions; Alligator smoothing/shift semantics; WM3 Teeth timing/location; legacy AO Zero-Line/Saucer/Twin Peaks exact conditions; campaign add-on sequencing/position sizing; trailing and exhaustion exits.

Do not claim 100% literal book equivalence from indicator names or a passing unit-test suite. Build a rule ledger with source edition, chapter/page/figure, exact statement, code location, test fixture, result, and unresolved questions.

## 7. Required tests before enabling a profile

1. Detector unit tests: bullish/bearish and boundary cases for every enabled setup.
2. Closed-candle / no-look-ahead tests, including delayed fractal confirmation.
3. Same candle inputs produce identical strategy decisions across Python components; Android parity is claimed only after a corresponding fixture run.
4. D1/H4 context never creates an entry; M15/M5 never creates/confirms an H1 signal.
5. Multiple simultaneous candidates are all retained and deterministically selected.
6. WM2/WM3 can start a flat campaign when eligible; later signals become additions only through the campaign coordinator.
7. Stale trigger, invalidated stop, duplicate signal, insufficient balance/margin, risk cap, and stale context all fail closed.
8. Partial fills, rejected/cancelled/unknown orders, missing protection, process restart and reconciliation.
9. Backtest/replay accounts for fees, spread, slippage, tick/lot constraints, and avoids same-bar look-ahead.
10. Demo/Testnet LONG and SHORT full lifecycle tests on the exact tested commit.

Until these pass, the profile is research/test only and live trading is NO-GO.

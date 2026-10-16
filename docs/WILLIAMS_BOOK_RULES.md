# Bill Williams source rules for Williams bot

This repository uses the book collection in:
https://github.com/19wolfalone92/Williams-Trading-Books

The collection currently contains six PDF files:
- 1. Торговый Хаос - 1.pdf
- Bill Williams - Trading Chaos 2nd Ed.pdf
- Bill Williams - Trading Chaos Course.pdf
- Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf
- Билл_Вильямс_Торговый_Хаос_2_3_книга.pdf
- Торговый Хаос 2 (3книга).pdf

## Edition-specific source-of-truth (16 October 2026 correction)

**Primary executable profile: TC2_THREE_WISE_MEN (Trading Chaos, 2nd ed.).
Do not silently combine it with NTD_FIVE_DIMENSIONS or TC1/Course.**
The following describes the intended source contract, not certification of
implementation. Visual book examples must be used as labelled regression cases.

1. TC2 ch.9 (PDF 121-134), WM1: a new extreme, a close in the relevant
   half, ENTIRE signal bar away from the Alligator mouth (no contact), good
   angulation, opposite-colour AO (red for bull / green for bear), conditional
   BUY STOP above high / SELL STOP below low, initial protection beyond the
   opposite extreme. Angulation remains an **engineering approximation** until
   chart-by-chart picture parity is verified. WM1 is an early countertrend entry;
   it must NOT be precluded by a mandatory first fractal.
2. TC2 ch.10 (PDF 136-143), WM2/Super AO: the THIRD consecutive AO column
   of the appropriate colour defines the candle and conditional entry price.
   This signal can be an initial entry or an addition. AO colour is its change
   from the preceding histogram value, not the sign of AO.
3. TC2 ch.11 (PDF 144-152), WM3: confirm the full fractal with closed bars.
   Its eventual trigger must occur on the appropriate side of Teeth; it need
   NOT have formed on that side. Equal-high/low variants shown in figures
   11.1-11.2 require additional test fixtures. Never use later bars in an
   earlier decision (prefix/causality invariant).
4. TC2 ch.12 (PDF 153-165): open a managed campaign on whichever valid Wise
   Man comes first; later valid same-side signals can increase the EXISTING
   campaign within risk limits. The 1:5:4:3:2 reverse pyramid is an allocation
   example for pre-budgeted total size, NOT a multiplier of equity risk.
   Initial stops are setup-specific; a protective stop then tightens behind
   the lowest LOW / highest HIGH of a selected completed 3-5 bar window.
5. TC2 exits: protective stop, completed structural invalidation and a
   truly TRIGGERED opposite trade can close a campaign. The NTD close-beyond-
   Teeth exit is NOT an unconditional TC2 rule; a merely detected opposite
   pending signal is NOT an executable exit.
6. All 10 configured USD-M Futures pairs require symmetrical LONG and SHORT
   signals, order sides, protection, cancellation, reconciliation and exits.
   The same source candles should produce consistent signal evidence in
   Python and Android. The analyser must not silently relabel H4/M15 as H1.
7. Market-wave reading is *probabilistic structural interpretation*, not
   clairvoyance. For each configured timeframe (D1, H4, H1, M15), record
   as-of timestamp, primary wave hypothesis W1-W5 / ABC / UNKNOWN, alternative
   count, degree, direction, pivot/invalidation levels, confidence and
   explicit uncertainty. Cross-timeframe nesting must be checked (including
   a lower-level W3 within higher W5). An uncertain wave must not silently
   become a guaranteed LONG/SHORT signal. These fields are an acceptance
   requirement; their complete parity with the book figures is **NOT YET
   VERIFIED** in the active Android execution path.
8. Pending-order lifecycle is required: DETECTED -> QUALIFIED -> ARMED ->
   TRIGGERED/FILLED or INVALIDATED/EXPIRED/CANCEL_REQUESTED/CANCEL_CONFIRMED.
   Refresh closed bars and live data before submission; invalidate a setup
   when the qualifying formation/side/context is no longer valid. Cancel via
   the existing serialized Futures Execution Door, persist the cancellation
   intent, query Binance order/algo-order state and do not treat an UNKNOWN
   cancellation as success. Never replace a conditional entry with a MARKET
   order just because the signal was missed.
9. Engineering risk overlays (spread, ATR, D1/H4 veto, session window, funds,
   slippage, cool-down, position caps, USDT contracts and account safety)
   belong to **Bot Policy**, not **Williams Canon**. They must be explicit
   in diagnostics so a rejected valid book signal is labelled as policy veto.

### Mandatory acceptance evidence (not completed by this document)

- Book page + labelled chart region -> candle/indicator fixture -> expected
  state/trigger/stop -> identical Python/Kotlin output, BOTH sides.
- Prefix replay without future data on every timeframe, including ambiguous
  waves and competing primary/alternative counts.
- Pending order invalidation/expiry/cancel and exchange-acknowledged status
  tests with REST/WebSocket delays, partial fills, restarts, unknown outcomes.
- Campaign add-ons and protected aggregate risk, opposite breakout,
  structural trailing and exit parity through the actual Android coordinator.
- Repeat on all 10 USD-M Futures Testnet contracts on a device, with exact
  commit-linked green Android/Python CI and saved diagnostics.


## Interpreting book FIGURES as invariant market structure — not images (2026-10-16)

The drawings are EXPLANATIONS of relationships, not templates to compare pixel-by-pixel.
A Williams-compliant engine must generalize to different symbols, volatility, price levels,
candle sizes, wick shapes and timeframes. Examples are sources for *rules and counterexamples*,
not a collection of bitmaps that have to look identical to market screenshots.

Evidence chain for every pattern:
`edition / page / figure -> invariant statement -> synthetic OHLCV and indicator
cases -> matching/near-miss/invalid/ambiguous cases -> mirror SHORT / scale / timing
metamorphic tests -> Android and Python result comparison -> exchange lifecycle
test`.

**Core invariants and exceptions (refer to these instead of rigid candle pictures):**

- TC2 fig.9.6 + ch.9: reversal WM1 requires new directional extreme, proper closing
  half, angulation away from ALL Alligator lines, the opposite AO colour and a
  next-price stop-entry breakout. A bar contacting the mouth is not WM1. Exact
  angulation remains approximation pending edge-case chart verification.
- TC2 figs.11.1A-D, 11.2A-E: fractals are inequality-and-confirmation
  structures, *not* always five-bar pictures. Equal extremes can extend a
  formation; no forward lookahead. The TC2 fig.11.4 Teeth requirement applies
  at breakout/activation, not automatically to the central bar at formation.
- TC1 figs.7-14 through 7-16: prices making the same absolute move over two
  bars versus ten bars have different *price-time geometry*. Do not infer an
  identical wave from equal vertical height alone. The implemented
  `price_time_area_proxy_atr_bars` is an engineering triangle proxy ONLY; the
  author's complete analytical projection model is not verified. MFI in
  Futures uses reported trade volume and is labelled a proxy rather than
  a substitute for original tick-volume data.
- **Waves are uncertain hypotheses**, including competing W1-W5/ABC
  interpretation. A label from fractal anchors, AO/AC, Alligator, price/time
  geometry and parent/child timeframes is NOT a command to open an order.
  Each timeframe exports as-of closed-bar timestamp, confirmed pivots,
  invalidation reference, AO/AC, Alligator state and NORMALIZED swing metrics.
  Unknown alternative counts must remain UNKNOWN until evidenced.
- Signal direction and execution are separate: H1 traded CONTRACT_PRICE
  OHLC matches the **CONTRACT_PRICE** conditional entry trigger and its
  last-tick preflight check. Binance close-all protective stops deliberately
  remain triggered on **MARK_PRICE**; their triggers may occur at different
  moments. Never conceal the difference on the operator chart.
- Every pending order is a conditional *hypothesis*: invalidate/cancel only
  with durable intent and exchange readback; an ambiguous cancellation
  requires reconciliation. Never open an immediate MARKET order in place of
  a missed stop-entry. LONG and SHORT must be exact directional mirrors,
  subject to documented directional policy vetoes and account risk caps.

Current status: these invariants are implemented or partly tested as recorded
in PR #71; the entire book, all image variants and Android add-on campaigns
have NOT achieved 100% replay parity. Do not label this research handoff as
fully production-ready.

## Important implementation boundary

The public GitHub connector cannot decode the binary PDFs in-place, so this file records the implementation rules derived from the book collection plus accessible book excerpts/figures used for verification. It does not reproduce copyrighted book text.


## Edition-specific runtime profiles (2026-10-13)

The active runtime must not collapse different editions into a single same-bar Boolean rule.

- `WILLIAMS_SIGNAL_PROFILE=TC2_THREE_WISE_MEN` (default): the production signal path uses the Trading Chaos 2 Wise-Men/campaign flow already implemented by `williams_signals.py`.
- `WILLIAMS_SIGNAL_PROFILE=NTD_FIVE_DIMENSIONS`: an explicit alternate profile now exposes New Trading Dimensions AO Saucer, AO Zero-line Cross, AO Twin Peaks, and AC acceleration formations. Each produces a conditional stop beyond its own signal bar. AO and AC signals are not silently mixed into the TC2 signal list.
- Any other profile value is rejected by the scanner rather than silently falling back.

### NTD profile implementation boundary

Implemented in this pass:
- AO Saucer: three-bar green-red-green above zero for LONG, mirrored below zero for SHORT.
- AO Zero-line Cross: closed-bar sign crossing; crossing bar defines the price trigger.
- AO Twin Peaks: confirmed pair of same-side peaks on the required side of zero, with no intervening zero-line crossing and the second peak closer to zero.
- AC acceleration: two same-colour AC bars when AC is with the trade, three when against it; the zero-cross exception changes the required count but is not itself an AC signal.
- Trigger is one tick beyond the signal candle; protective reference is the opposite signal-candle extreme.

Still NOT APPROVED as a complete New Trading Dimensions implementation:
- AC formation edge cases and every zero-cross timing variation need more source-derived fixtures.
- Full Alligator/Fractal admission semantics for each NTD setup need a dedicated profile-level contract and tests.
- Zones and standalone Balance-Line trading logic are not yet a complete, independently tested execution profile.
- MFI on exchange traded-volume data remains a labelled proxy, not an exact replacement for original tick-volume MFI.
- The scanned Trading Chaos Course and earlier Trading Chaos rules must remain separate edition-specific profiles unless an explicit cross-edition rule is supported by source evidence.

These status statements describe implementation scope, not a claim that the full method is 100% complete or approved for live trading.

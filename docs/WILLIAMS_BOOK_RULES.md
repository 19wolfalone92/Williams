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

## Entry-search rules adopted by the bot

1. Balance first, trigger second. For a LONG trend, the first actionable prerequisite is a confirmed bullish fractal above the Alligator Teeth / red balance line. A breakout above that fractal is the primary mechanical trigger.
2. Do not manufacture an entry from AO, AC, MFI/MFI-proxy or the Alligator alone before the external fractal exists.
3. Alligator is the trend/balance filter. LONG requires price on the bullish side of the mouth with the lines ordered Lips > Teeth > Jaw and an awake/spread condition.
4. AO is momentum confirmation (Profitunity 5/34 on median price). AC is acceleration confirmation. AO/AC colors and zones are used for ranking, confirmation and trade management; they are not a substitute for the fractal trigger.
5. Wiseman signals are treated as a family, not as three indicators that must all fire on the same bar. Fractal breakout is the conservative primary trigger; reversal/divergent-bar and Super AO signals are confirmation/context.
6. Wave hierarchy is multi-timeframe. A lower-timeframe five-wave impulse can be a subdivision of a higher-timeframe impulse.
7. Wave 3 receives the highest entry priority. If a credible lower-timeframe W3 exists, it should outrank a late higher-timeframe W5.
8. A lower-timeframe bullish W3 inside a bullish higher-timeframe W5 is valid and must not be vetoed solely because the parent is W5.
9. A five-wave move against the parent trend inside higher W2/W4 is corrective structure (for example A/C). It must not be treated as a continuation LONG signal.
10. W5 entries require a substantially stronger exhaustion check: price in the 62%-100% target zone, price/oscillator divergence, terminal fractal, squat/volume-facilitation evidence and momentum change. A W5 is a low-priority exception, not the default entry.
11. Wave analysis keeps a 100-140+ bar structural context; the latest closed bar, not an unfinished bar, is used for confirmed signals.
12. Initial protection is structure-aware (recent opposite fractal / Teeth with volatility buffer). A fixed profit target is only a safety cap; the main trend exit is market-generated.
13. After five consecutive green Profitunity bars, stop management can trail below the last strong bar / higher lows.
14. A confirmed close through the Teeth is an important trend-exit condition and must not be converted into a higher stop that sits above market price.
15. Exchange filters, spread, slippage, balance, order status and reconciliation remain hard execution gates. Strategy signals never bypass Binance execution safety.

## Important implementation boundary

The public GitHub connector cannot decode the binary PDFs in-place, so this file records the implementation rules derived from the book collection plus accessible book excerpts/figures used for verification. It does not reproduce copyrighted book text.

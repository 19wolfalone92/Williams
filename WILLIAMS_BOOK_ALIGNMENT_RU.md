# Williams book alignment - 2026-10

This file is a derived engineering summary, not a reproduction of the books.
The private `Williams-Trading-Books` repository is the source archive.

## Sources reviewed

- `Trading Chaos` (book 1)
- `New Trading Dimensions in Technical Analysis` / `Новые измерения...` (book 2)
- `Trading Chaos 2` (book 3 / second edition)

Duplicate English/Russian PDFs in the private repository were treated as alternate editions/copies.

## Rules now represented in the bot

### Alligator / Balance Line
The conservative bot keeps Williams' 13/8, 8/5, 5/3 smoothed displaced Alligator lines. The red Teeth line is used as the fractal breakout validity gate. The blue Jaw is treated as the main Balance-Line context.

### Fractals
The implementation uses the five-bar fractal structure with confirmation delay and strict high/low comparison. Buy fractal context must be above the red Teeth line; sell fractal context is mirrored.

### Awesome Oscillator
AO remains SMA(5, median) - SMA(34, median). Histogram color is defined by the current AO versus the previous AO. The code now exposes the three-bar same-color Super AO condition used by the Second Wise Man.

### Three Wise Men
The bot now keeps separate diagnostics for:

1. First Wise Man - bullish/bearish reversal bar with a new extreme and close in the appropriate half of the bar.
2. Second Wise Man - three consecutive green/red AO bars (Super AO), gated conservatively by prior valid fractal context.
3. Third Wise Man - valid fractal breakout beyond the Balance-Line/Teeth gate.

The autonomous spot bot still allows only one managed position, so later Wise-Men signals are confirmations/ranking context rather than additional pyramiding orders.

### Market Facilitation Index
The book formula is `Range / Volume` and its value is meaningful relative to the immediately preceding bar. Binance Spot exposes traded quantity volume rather than the historical tick-count volume, so the implementation calls this `mfi_proxy` and uses the four Profitunity windows diagnostically, not as a standalone entry trigger.

### Elliott / waves
The wave engine remains structural rather than pretending to provide an author-certified Elliott count. It uses confirmed fractal/swing structure plus Alligator/AO/AC context and multiple timeframes. A nested lower-timeframe W3 inside a higher-timeframe W5 is allowed.

For an active W5, the engine now also calculates the 62%-100% target-zone approximation described in *Trading Chaos* and includes it in exhaustion risk together with AO divergence, squatting-bar context and momentum fading.

## Conservative engineering overlays

These are bot-safety decisions, not claims that every one is a direct Williams rule:

- HTF confirmation remains mandatory for autonomous entries when configured.
- ATR/spread/R:R filters remain mandatory.
- maximum one open managed position remains enforced.
- `DRY_RUN=true` and `ALLOW_LIVE=false` remain the safe defaults.
- a high-exhaustion active W5 on the execution timeframe can be blocked; a nested W3 inside a parent W5 is not blocked merely because its parent is W5.

## Intentionally not copied blindly

- Reverse pyramiding is not enabled because it conflicts with the one-position conservative architecture.
- Counter-trend First Wise Man entries are retained as diagnostics but disabled by default.
- Balance-Line standalone entry mechanics and five-bar trailing-stop behavior are not yet execution rules; integrating them safely requires changes to order-management semantics, not just indicator columns.

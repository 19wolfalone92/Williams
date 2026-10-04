from pathlib import Path
from dotenv import load_dotenv

load_dotenv(Path(".env"))

from binance_client import BinanceSpotClient
from market_scanner import MarketScanner


def pct(value):
    return f"{float(value) * 100:.3f}%"


client = BinanceSpotClient(
    api_key=None,
    api_secret=None,
    testnet=True,
)

scanner = MarketScanner(client)

print("=" * 110)
print("WILLIAMS LIVE MARKET SCANNER")
print("BINANCE SPOT TESTNET")
print("READ ONLY — NO ORDERS")
print("=" * 110)

print()
print("Symbols:")
print(", ".join(scanner.symbols))

print()
print("Scanning...")
print()

candidates = scanner.scan()

if not candidates:
    print("Нет ни одного подходящего bullish setup.")
    print()
    print("BUY/SELL: 0")
else:
    print(
        f"{'RANK':<5}"
        f"{'SYMBOL':<10}"
        f"{'STATE':<15}"
        f"{'SIGNAL':<8}"
        f"{'SCORE':<8}"
        f"{'SETUP':<8}"
        f"{'STRENGTH':<10}"
        f"{'BREAKOUT':<12}"
        f"{'RISK':<8}"
        f"{'R:R':<7}"
        f"{'ATR':<9}"
        f"{'SPREAD':<10}"
        f"{'HTF':<6}"
    )

    print("-" * 110)

    for i, c in enumerate(candidates, 1):
        print(
            f"{i:<5}"
            f"{c.symbol:<10}"
            f"{c.setup_state:<15}"
            f"{'YES' if c.signal else 'NO':<8}"
            f"{c.score:<8.2f}"
            f"{c.setup_score:<8.2f}"
            f"{c.signal_strength:<10.2f}"
            f"{c.breakout_distance_pct:>8.3f}%   "
            f"{c.risk_pct:<8.2f}"
            f"{c.risk_reward:<7.2f}"
            f"{pct(c.atr_pct):<9}"
            f"{pct(c.spread_pct):<10}"
            f"{'YES' if c.htf_confirmed else 'NO':<6}"
        )

    best = scanner.best()

    print()
    print("=" * 110)
    print("BEST CANDIDATE")
    print("=" * 110)

    if best:
        print(f"Symbol:          {best.symbol}")
        print(f"State:           {best.setup_state}")
        print(f"Score:           {best.score:.2f}")
        print(f"Setup score:     {best.setup_score:.2f}")
        print(f"Signal strength: {best.signal_strength:.2f}")
        print(
            f"Breakout:        "
            f"{best.breakout_distance_pct:.3f}%"
        )
        print(f"Risk:            {best.risk_pct:.2f}%")
        print(f"R:R:             {best.risk_reward:.2f}")
        print(f"ATR:             {pct(best.atr_pct)}")
        print(f"Spread:          {pct(best.spread_pct)}")
        print(
            f"HTF confirmed:   "
            f"{'YES' if best.htf_confirmed else 'NO'}"
        )
        print(f"Strict signal:   {'YES' if best.signal else 'NO'}")
        print(f"Reason:          {best.reason}")

    print()
    print("=" * 110)
    print("SAFETY CHECK")
    print("=" * 110)
    print("Orders created:  NO")
    print("BUY executed:    NO")
    print("SELL executed:   NO")
    print("=" * 110)

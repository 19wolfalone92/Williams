import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name(".env"))

from binance_client import BinanceSpotClient
from data import fetch_klines
from strategy import calculate_indicators, config_from_env
from market_scanner import MarketScanner


def main():
    print()
    print("=" * 80)
    print(" WILLIAMS SCANNER DETAILED DIAGNOSTICS")
    print(" BINANCE SPOT TESTNET")
    print(" BUY/SELL DISABLED")
    print("=" * 80)

    api_key = os.getenv("BINANCE_API_KEY", "")
    api_secret = os.getenv("BINANCE_API_SECRET", "")

    if not api_key or not api_secret:
        raise RuntimeError("Binance credentials не найдены")

    if os.getenv("TESTNET", "true").lower() != "true":
        raise RuntimeError("ОПАСНО: TESTNET=false")

    client = BinanceSpotClient(
        api_key,
        api_secret,
        testnet=True,
    )

    scanner = MarketScanner(client)

    print(f"Interval:       {scanner.interval}")
    print(f"HTF interval:   {scanner.htf_interval}")
    print(f"ATR period:     {scanner.atr_period}")
    print(f"Max ATR:        {scanner.max_atr_pct * 100:.2f}%")
    print(f"Max spread:     {scanner.max_spread_pct * 100:.4f}%")
    print(f"Stop loss:      {scanner.stop_pct * 100:.2f}%")
    print(f"Take profit:    {scanner.target_pct * 100:.2f}%")
    print(f"Minimum R:R:    {scanner.min_rr:.2f}")
    print(f"HTF required:   {os.getenv('REQUIRE_HTF_CONFIRMATION', 'true')}")
    print()

    total = 0
    candidates = 0

    for symbol in scanner.symbols:
        total += 1

        print("-" * 80)
        print(symbol)

        try:
            # 1. Symbol validation
            if not scanner._symbol_is_valid(symbol):
                print("  FINAL: INVALID SYMBOL")
                continue

            print("  Symbol:       PASS")

            # 2. Candles
            df = fetch_klines(
                client,
                symbol,
                scanner.interval,
                limit=250,
            )

            print(f"  Candles:      {len(df)}")

            if len(df) < 100:
                print("  FINAL: NOT ENOUGH CANDLES")
                continue

            closed = df.iloc[:-1].copy()

            # 3. Indicators / signal
            indicators = calculate_indicators(
                closed,
                config_from_env(),
            )

            last = indicators.iloc[-1]

            long_signal = bool(last.get("long_signal", False))
            close = float(last["close"])

            ao = float(last.get("ao", 0) or 0)
            bullish_alligator = bool(
                last.get("bullish_alligator", False)
            )

            print(f"  Close:        {close:.8f}")
            print(f"  Long signal:  {'YES' if long_signal else 'NO'}")
            print(f"  AO:           {ao:.6f}")
            print(
                f"  Alligator:    "
                f"{'BULLISH' if bullish_alligator else 'NOT BULLISH'}"
            )

            if not long_signal:
                print("  FINAL: NO LONG SIGNAL")
                continue

            # 4. ATR
            atr = scanner._atr(closed, scanner.atr_period)

            if atr <= 0:
                print(f"  ATR:          {atr}")
                print("  FINAL: INVALID ATR")
                continue

            atr_pct = atr / close

            print(f"  ATR:          {atr:.8f}")
            print(f"  ATR %:        {atr_pct * 100:.4f}%")

            if atr_pct > scanner.max_atr_pct:
                print(
                    f"  FINAL: ATR TOO HIGH "
                    f"({atr_pct * 100:.4f}% > "
                    f"{scanner.max_atr_pct * 100:.4f}%)"
                )
                continue

            # 5. Spread
            spread_pct = scanner._spread(symbol)

            print(f"  Spread:       {spread_pct * 100:.5f}%")

            if spread_pct > scanner.max_spread_pct:
                print(
                    f"  FINAL: SPREAD TOO HIGH "
                    f"({spread_pct * 100:.5f}% > "
                    f"{scanner.max_spread_pct * 100:.5f}%)"
                )
                continue

            # 6. R:R
            rr = scanner.target_pct / max(scanner.stop_pct, 1e-9)

            print(f"  R:R:          {rr:.2f}")

            if rr < scanner.min_rr:
                print(
                    f"  FINAL: R:R TOO LOW "
                    f"({rr:.2f} < {scanner.min_rr:.2f})"
                )
                continue

            # 7. HTF
            htf = scanner._htf_confirmation(symbol)

            print(
                f"  HTF:          "
                f"{'PASS' if htf else 'FAIL'}"
            )

            if not htf:
                print("  FINAL: HTF FILTER")
                continue

            # 8. Score
            risk_pct = scanner.stop_pct * 100.0

            risk_score = max(
                0.0,
                min(
                    20.0,
                    20.0 * (
                        0.02 /
                        max(scanner.stop_pct, 0.0001)
                    ),
                ),
            )

            rr_score = min(
                20.0,
                20.0 * (rr / 3.0),
            )

            atr_score = max(
                0.0,
                10.0 * (
                    1.0 -
                    atr_pct /
                    max(scanner.max_atr_pct, 1e-9)
                ),
            )

            spread_score = max(
                0.0,
                5.0 * (
                    1.0 -
                    spread_pct /
                    max(scanner.max_spread_pct, 1e-9)
                ),
            )

            score = min(
                100.0,
                30.0
                + risk_score
                + rr_score
                + 15.0
                + atr_score
                + spread_score,
            )

            candidates += 1

            print()
            print("  >>> CANDIDATE <<<")
            print(f"  Score:        {score:.2f}")
            print(f"  Risk:         {risk_pct:.2f}%")
            print(f"  R:R:          {rr:.2f}")
            print("  FINAL: CANDIDATE")

        except Exception as exc:
            print(
                f"  FINAL: ERROR "
                f"{type(exc).__name__}: {exc}"
            )

    print()
    print("=" * 80)
    print(f"Pairs checked: {total}")
    print(f"Candidates:    {candidates}")
    print("BUY/SELL:      0")
    print("=" * 80)


if __name__ == "__main__":
    main()

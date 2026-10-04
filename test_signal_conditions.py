from pathlib import Path
from dotenv import load_dotenv

load_dotenv(Path(".env"))

from binance_client import BinanceSpotClient
from data import fetch_klines
from strategy import calculate_indicators, config_from_env


SYMBOLS = [
    "BTCUSDT",
    "ETHUSDT",
    "SOLUSDT",
    "BNBUSDT",
    "XRPUSDT",
    "ADAUSDT",
    "DOGEUSDT",
    "AVAXUSDT",
    "LINKUSDT",
    "DOTUSDT",
]


def yn(value):
    return "YES" if bool(value) else "NO"


def fmt(value, digits=6):
    try:
        return f"{float(value):.{digits}f}"
    except Exception:
        return str(value)


client = BinanceSpotClient(
    api_key=None,
    api_secret=None,
    testnet=True,
)

cfg = config_from_env()

print("=" * 100)
print("WILLIAMS SIGNAL CONDITION DIAGNOSTIC")
print("BINANCE SPOT TESTNET")
print("BUY/SELL DISABLED")
print("=" * 100)

for symbol in SYMBOLS:
    print()
    print(f"### {symbol}")

    try:
        df = fetch_klines(
            client,
            symbol,
            "1h",
            limit=250,
        )

        if len(df) < 100:
            print("  NOT ENOUGH CANDLES")
            continue

        closed = df.iloc[:-1].copy()

        ind = calculate_indicators(
            closed,
            cfg,
        )

        last = ind.iloc[-1]
        prev = ind.iloc[-2]

        bullish = bool(last.get("bullish_alligator", False))
        awake = bool(last.get("alligator_awake", False))
        ao_positive = float(last.get("ao", 0) or 0) > 0
        ac_positive = float(last.get("ac", 0) or 0) > 0

        last_up = last.get("last_up_level")

        has_fractal = (
            last_up is not None
            and not (
                hasattr(last_up, "__class__")
                and str(last_up) == "nan"
            )
        )

        # Более надёжная проверка NaN
        try:
            import math
            has_fractal = has_fractal and not math.isnan(float(last_up))
        except Exception:
            pass

        close_now = float(last["close"])

        if has_fractal:
            breakout = close_now > float(last_up)
        else:
            breakout = False

        prev_up = prev.get("last_up_level")

        try:
            prev_has_fractal = (
                prev_up is not None
                and not __import__("math").isnan(float(prev_up))
            )
        except Exception:
            prev_has_fractal = False

        if prev_has_fractal:
            prev_not_above = float(prev["close"]) <= float(prev_up)
        else:
            prev_not_above = False

        conditions = [
            bullish,
            awake,
            ao_positive,
            ac_positive,
            has_fractal,
            breakout,
            prev_not_above,
        ]

        actual_signal = bool(last.get("long_signal", False))

        print(f"  Candles:              {len(df)}")
        print(f"  Close:                {fmt(close_now, 8)}")
        print()

        print(f"  1. Bullish Alligator: {yn(bullish)}")
        print(f"  2. Alligator awake:   {yn(awake)}")
        print(f"  3. AO > 0:            {yn(ao_positive)}  ({fmt(last.get('ao'))})")
        print(f"  4. AC > 0:            {yn(ac_positive)}  ({fmt(last.get('ac'))})")
        print(f"  5. Fractal level:     {yn(has_fractal)}")

        if has_fractal:
            print(f"     Last UP level:     {fmt(last_up, 8)}")
            print(f"     Distance:          {(close_now / float(last_up) - 1) * 100:.4f}%")

        print(f"  6. Close > fractal:   {yn(breakout)}")

        if prev_has_fractal:
            print(
                f"     Previous close:    {fmt(prev['close'], 8)}"
            )
            print(
                f"     Previous level:    {fmt(prev_up, 8)}"
            )

        print(f"  7. Previous <= level: {yn(prev_not_above)}")

        print()
        print(f"  LONG SIGNAL:          {yn(actual_signal)}")

        passed = sum(1 for x in conditions if x)
        print(f"  Conditions passed:    {passed}/7")

        if not actual_signal:
            failed = []

            names = [
                "Bullish Alligator",
                "Alligator awake",
                "AO > 0",
                "AC > 0",
                "Fractal exists",
                "Price above fractal",
                "Previous price <= fractal",
            ]

            for name, ok in zip(names, conditions):
                if not ok:
                    failed.append(name)

            print("  Blocking:")
            for name in failed:
                print(f"    - {name}")

    except Exception as exc:
        print(f"  ERROR: {type(exc).__name__}: {exc}")


print()
print("=" * 100)
print("DIAGNOSTIC FINISHED")
print("No orders were created.")
print("=" * 100)

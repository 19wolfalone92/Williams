import pandas as pd

from strategy import calculate_indicators, config_from_env


def make_data():
    rows = []

    price = 100.0

    for i in range(120):
        close = price + i * 0.2

        rows.append(
            {
                "open": close - 0.1,
                "high": close + 0.5,
                "low": close - 0.5,
                "close": close,
                "volume": 1000,
            }
        )

    return pd.DataFrame(rows)


df = make_data()
result = calculate_indicators(
    df,
    config_from_env(),
)

required = [
    "long_signal",
    "long_bullish",
    "long_awake",
    "long_ao_positive",
    "long_ac_positive",
    "long_fractal_ready",
    "long_above_fractal",
    "long_previous_below_fractal",
    "long_setup_score",
    "long_breakout_distance_pct",
]

for column in required:
    assert column in result.columns, column

last = result.iloc[-1]

assert 0 <= float(last["long_setup_score"]) <= 100

print("STRATEGY DIAGNOSTIC TEST: PASS")
print(
    "Setup score:",
    round(float(last["long_setup_score"]), 2),
)
print(
    "Long signal:",
    bool(last["long_signal"]),
)

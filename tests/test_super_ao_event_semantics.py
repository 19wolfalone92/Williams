import unittest

import pandas as pd

from strategy import calculate_indicators, config_from_env


class SuperAOEventSemanticsTests(unittest.TestCase):
    def test_super_ao_is_emitted_only_on_third_consecutive_bar(self):
        # Rising median prices create a steadily rising AO after its warm-up.
        close = [100.0 + i * i * 0.01 for i in range(100)]
        frame = pd.DataFrame({
            "open": close,
            "high": [x + 1.0 for x in close],
            "low": [x - 1.0 for x in close],
            "close": close,
            "volume": [1000.0] * len(close),
        })
        result = calculate_indicators(frame, config_from_env({}))
        events = result.index[result["super_ao_long"]].tolist()
        self.assertTrue(events, "synthetic rising series should contain a Super AO event")
        for i in events:
            self.assertEqual(int(result.loc[i, "ao_green_streak"]), 3)
        self.assertTrue((result.loc[result["ao_green_streak"] > 3, "super_ao_long"] == False).all())


if __name__ == "__main__":
    unittest.main()

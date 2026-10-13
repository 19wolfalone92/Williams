import unittest

import pandas as pd

from strategy import calculate_indicators, config_from_env
from williams_signals import _latest_confirmed_fractal


class EqualExtremeFractalTests(unittest.TestCase):
    def test_equal_high_group_uses_dynamic_confirmation_bar(self):
        # The equal-high group is the center of the fractal. Its two right-side
        # qualifying lower highs occur at indexes 4 and 5, so confirmation is 5,
        # not the fixed center+2 index (which would be 4).
        frame = pd.DataFrame({
            "open_time_ms": [i * 60_000 for i in range(7)],
            "open": [6, 7, 8, 8, 7, 6, 5],
            "high": [7, 8, 10, 10, 9, 8, 7],
            "low": [5, 6, 7, 7, 6, 5, 4],
            "close": [6, 7, 9, 9, 8, 7, 6],
            "volume": [100, 110, 120, 130, 140, 150, 160],
        })
        cfg = config_from_env({
            "ALLIGATOR_JAW": "2",
            "ALLIGATOR_TEETH": "2",
            "ALLIGATOR_LIPS": "2",
            "JAW_SHIFT": "0",
            "TEETH_SHIFT": "0",
            "LIPS_SHIFT": "0",
            "AO_FAST": "2",
            "AO_SLOW": "3",
            "AC_PERIOD": "2",
            "FRACTAL_LEFT": "2",
            "FRACTAL_RIGHT": "2",
            "SUPER_AO_BARS": "3",
            "MIN_WISE_MEN_CONFIRMATIONS": "1",
            "ALLOW_COUNTERTREND_WISE_MAN": "true",
            "MIN_ALLIGATOR_SPREAD_PCT": "0",
        })
        result = calculate_indicators(frame, cfg)
        self.assertTrue(bool(result.loc[3, "fractal_up"]))
        self.assertEqual(int(result.loc[3, "fractal_up_confirmation_index"]), 5)
        self.assertTrue(pd.isna(result.loc[4, "confirmed_up_level"]))
        self.assertEqual(float(result.loc[5, "confirmed_up_level"]), 10.0)
        self.assertEqual(int(result.loc[5, "confirmed_up_center_index"]), 3)

        signal = _latest_confirmed_fractal(result, side="LONG", max_age_bars=80)
        self.assertIsNotNone(signal)
        confirmation_i, center_i, trigger_base, _, _ = signal
        self.assertEqual(confirmation_i, 5)
        self.assertEqual(center_i, 3)
        self.assertEqual(trigger_base, 10.0)

    def test_unconfirmed_equal_high_group_does_not_create_signal(self):
        frame = pd.DataFrame({
            "open_time_ms": [i * 60_000 for i in range(5)],
            "open": [6, 7, 8, 8, 7],
            "high": [7, 8, 10, 10, 9],
            "low": [5, 6, 7, 7, 6],
            "close": [6, 7, 9, 9, 8],
            "volume": [100, 110, 120, 130, 140],
        })
        cfg = config_from_env({
            "ALLIGATOR_JAW": "2", "ALLIGATOR_TEETH": "2", "ALLIGATOR_LIPS": "2",
            "JAW_SHIFT": "0", "TEETH_SHIFT": "0", "LIPS_SHIFT": "0",
            "AO_FAST": "2", "AO_SLOW": "3", "AC_PERIOD": "2",
            "FRACTAL_LEFT": "2", "FRACTAL_RIGHT": "2", "SUPER_AO_BARS": "3",
            "MIN_WISE_MEN_CONFIRMATIONS": "1", "ALLOW_COUNTERTREND_WISE_MAN": "true",
            "MIN_ALLIGATOR_SPREAD_PCT": "0",
        })
        result = calculate_indicators(frame, cfg)
        self.assertTrue(pd.isna(result["confirmed_up_level"].iloc[-1]))
        self.assertTrue(pd.isna(result["confirmed_up_center_index"].iloc[-1]))
        self.assertIsNone(_latest_confirmed_fractal(result, side="LONG", max_age_bars=80))


if __name__ == "__main__":
    unittest.main()

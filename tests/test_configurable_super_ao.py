import unittest
from unittest.mock import patch

import pandas as pd

from williams_signals import _latest_super_ao


class ConfigurableSuperAOTests(unittest.TestCase):
    def test_super_ao_uses_configured_event_bar_count(self):
        ind = pd.DataFrame({
            "ao_green_streak": [0, 1, 2, 3, 4, 5],
            "ao_red_streak": [0, 0, 0, 0, 0, 0],
            "high": [10, 11, 12, 13, 14, 15],
            "low": [8, 9, 10, 11, 12, 13],
        })
        with patch.dict("os.environ", {"SUPER_AO_BARS": "4"}):
            result = _latest_super_ao(ind, side="LONG")
        self.assertIsNotNone(result)
        self.assertEqual(result[0], 4)
        self.assertEqual(result[1], 14.0)
        self.assertEqual(result[2], 12.0)


if __name__ == "__main__":
    unittest.main()

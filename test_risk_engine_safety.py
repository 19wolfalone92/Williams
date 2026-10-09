import math
import unittest

from risk_engine import RiskEngine


class RiskEngineSafetyTests(unittest.TestCase):
    def setUp(self):
        self.engine = RiskEngine(balance_quote=10_000.0)

    def analyse(self, **overrides):
        args = {
            "symbol": "BTCUSDT",
            "entry_price": 100.0,
            "atr": 1.0,
            "risk_pct_override": None,
        }
        args.update(overrides)
        return self.engine.analyse(**args)

    def test_non_finite_entry_is_blocked(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                self.assertFalse(self.analyse(entry_price=value).allowed)

    def test_non_finite_atr_is_blocked(self):
        for value in (float("nan"), float("inf")):
            with self.subTest(value=value):
                self.assertFalse(self.analyse(atr=value).allowed)

    def test_non_finite_spread_is_blocked(self):
        self.assertFalse(self.analyse(spread_pct=float("nan")).allowed)

    def test_non_finite_override_is_blocked(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                self.assertFalse(self.analyse(risk_pct_override=value).allowed)

    def test_override_above_configured_risk_is_blocked(self):
        result = self.analyse(risk_pct_override=self.engine.risk_per_trade_pct * 2)
        self.assertFalse(result.allowed)
        self.assertIn("exceeds configured", result.reason)

    def test_override_at_configured_risk_is_allowed_when_other_checks_pass(self):
        result = self.analyse(risk_pct_override=self.engine.risk_per_trade_pct)
        self.assertTrue(result.allowed)

    def test_zero_and_negative_override_are_blocked(self):
        for value in (0.0, -0.001):
            with self.subTest(value=value):
                self.assertFalse(self.analyse(risk_pct_override=value).allowed)

    def test_invalid_multipliers_are_blocked(self):
        self.assertFalse(self.analyse(stop_atr_multiplier=0).allowed)
        self.assertFalse(self.analyse(target_atr_multiplier=-1).allowed)

    def test_invalid_constructor_values_raise(self):
        for balance in (0, -1, float("nan"), float("inf")):
            with self.subTest(balance=balance):
                with self.assertRaises(ValueError):
                    RiskEngine(balance_quote=balance)

    def test_invalid_configured_risk_raises(self):
        with self.assertRaises(ValueError):
            RiskEngine(balance_quote=1000, risk_per_trade_pct=float("nan"))


if __name__ == "__main__":
    unittest.main()

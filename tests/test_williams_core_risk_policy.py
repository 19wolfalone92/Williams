import unittest

from risk_engine import RiskEngine


class WilliamsCoreRiskPolicyTests(unittest.TestCase):
    def setUp(self):
        self.engine = RiskEngine(balance_quote=10_000)

    def test_core_defaults_preserve_user_selected_risk_limits(self):
        self.assertAlmostEqual(self.engine.risk_per_trade_pct, 0.005)
        self.assertAlmostEqual(self.engine.max_daily_loss_pct, 0.03)

    def test_missing_structural_stop_fails_closed_instead_of_using_atr_fallback(self):
        result = self.engine.analyse(
            symbol="BTCUSDT",
            entry_price=100.0,
            atr=2.0,
            invalidation_price=0.0,
        )
        self.assertFalse(result.allowed)
        self.assertIn("structural invalidation stop", result.reason)

    def test_core_does_not_invent_fixed_take_profit(self):
        result = self.engine.analyse(
            symbol="BTCUSDT",
            entry_price=100.0,
            atr=2.0,
            invalidation_price=96.0,
        )
        self.assertTrue(result.allowed, result.reason)
        self.assertEqual(result.take_profit_price, 0.0)
        self.assertEqual(result.take_profit_pct, 0.0)
        self.assertEqual(result.risk_reward, 0.0)

    def test_long_structural_stop_must_be_below_entry(self):
        result = self.engine.analyse(
            symbol="BTCUSDT",
            entry_price=100.0,
            atr=2.0,
            invalidation_price=101.0,
            side="LONG",
        )
        self.assertFalse(result.allowed)
        self.assertIn("structural invalidation stop", result.reason)

    def test_explicit_legacy_target_remains_opt_in(self):
        result = self.engine.analyse(
            symbol="BTCUSDT",
            entry_price=100.0,
            atr=2.0,
            invalidation_price=96.0,
            target_atr_multiplier=4.0,
        )
        self.assertTrue(result.allowed, result.reason)
        self.assertGreater(result.take_profit_price, result.entry_price)
        self.assertGreater(result.risk_reward, 0.0)


if __name__ == "__main__":
    unittest.main()

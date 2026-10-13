import unittest

from campaign_engine import CampaignEngine


class CampaignRiskDefaultsTests(unittest.TestCase):
    def test_initial_risk_restores_to_half_percent_with_six_tenths_campaign_cap(self):
        engine = CampaignEngine(db=object())
        self.assertAlmostEqual(engine.campaign_risk_limit_pct, 0.006)
        self.assertAlmostEqual(engine.initial_risk_pct(), 0.005)
        self.assertAlmostEqual(engine.portfolio_risk_limit_pct, 0.01)

    def test_add_on_budget_cannot_exceed_remaining_campaign_risk(self):
        engine = CampaignEngine(db=object())
        # Initial 0.50% of equity is already reserved from a 0.60% campaign cap.
        next_add = engine.next_add_on_risk_pct(
            campaign_reserved_risk_quote=50.0,
            equity_quote=10_000.0,
            tranche_index=1,
        )
        self.assertLessEqual(next_add, 0.001 + 1e-12)


if __name__ == "__main__":
    unittest.main()

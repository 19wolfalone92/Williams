from campaign_engine import (
    CampaignEngine, CampaignState, DecisionType, PendingSignal, SignalType, TradingCampaign,
)


def signal(trigger=101.0, stop=97.0, risk=0.002):
    return PendingSignal(
        signal_id="s1", campaign_id="c1", symbol="BTCUSDT", side="LONG",
        signal_type=SignalType.FRACTAL, timeframe="5m",
        trigger_price=trigger, initial_stop=stop, invalidation_price=stop,
        context_versions={"5m": 1}, risk_reserved_pct=risk,
    )


def test_signal_arms_pending_entry_not_market_buy():
    c = TradingCampaign("c1", "BTCUSDT")
    d = CampaignEngine().detect_entry(
        c, signal(), current_price=100.0,
        aggregate_risk_pct=0.0, max_aggregate_risk_pct=0.01,
        context_fresh=True,
    )
    assert d.decision is DecisionType.ARM_ENTRY
    assert c.state is CampaignState.ENTRY_PENDING
    assert c.current_stop == 97.0


def test_broken_trigger_is_not_chased():
    c = TradingCampaign("c1", "BTCUSDT")
    d = CampaignEngine().detect_entry(
        c, signal(trigger=100.0), current_price=100.0,
        aggregate_risk_pct=0.0, max_aggregate_risk_pct=0.01,
        context_fresh=True,
    )
    assert d.decision is DecisionType.BLOCK
    assert d.reason_code == "trigger_already_broken"


def test_campaign_risk_is_hard_cap():
    c = TradingCampaign("c1", "BTCUSDT", max_risk_pct=0.005)
    d = CampaignEngine().detect_entry(
        c, signal(risk=0.006), current_price=100.0,
        aggregate_risk_pct=0.0, max_aggregate_risk_pct=0.01,
        context_fresh=True,
    )
    assert d.reason_code == "campaign_risk_exhausted"


def test_stop_never_moves_backward_for_long():
    c = TradingCampaign("c1", "BTCUSDT", state=CampaignState.OPEN_INITIAL, current_stop=97.0)
    engine = CampaignEngine()
    assert engine.propose_stop(c, 96.0).reason_code == "stop_not_tighter"
    assert c.current_stop == 97.0
    assert engine.propose_stop(c, 98.0).decision is DecisionType.MOVE_STOP


def test_replace_requires_material_change():
    c = TradingCampaign("c1", "BTCUSDT", state=CampaignState.ENTRY_PENDING)
    old = signal(trigger=101.0)
    new = signal(trigger=101.2)
    assert CampaignEngine().replace_entry(
        c, old, new, current_price=100.0, min_trigger_move_ticks=0.5
    ).reason_code == "replacement_below_threshold"


def test_fill_moves_campaign_to_open():
    c = TradingCampaign("c1", "BTCUSDT", state=CampaignState.ENTRY_PENDING)
    d = CampaignEngine().on_fill(c, 0.01, 100.5)
    assert d.reason_code == "fill_adopted"
    assert c.state is CampaignState.OPEN_INITIAL
    assert c.quantity == 0.01


def test_exhaustion_requires_confirmation():
    c = TradingCampaign("c1", "BTCUSDT", state=CampaignState.OPEN_INITIAL, quantity=0.01)
    assert CampaignEngine().exhaustion_exit(
        c, exhaustion=True, terminal_fractal=False, divergence=True
    ).decision is DecisionType.WAIT
    assert CampaignEngine().exhaustion_exit(
        c, exhaustion=True, terminal_fractal=True, divergence=True
    ).decision is DecisionType.EXIT

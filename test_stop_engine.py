from stop_engine import StopEngine
from campaign_model import SignalType


def test_stop_engine_only_advances_long_stop():
    engine = StopEngine(buffer=0.1)
    proposal = engine.propose_long(
        signal_type=SignalType.FRACTAL,
        current_stop=98.0,
        signal_bar_low=97.0,
        recent_lows=[98.5, 99.0, 99.2],
        current_price=101.0,
    )
    assert proposal.price >= 98.0
    assert proposal.risk_reducing is True


def test_stop_engine_rejects_stop_at_or_above_market():
    engine = StopEngine(buffer=0.0)
    proposal = engine.propose_long(
        signal_type=SignalType.FRACTAL,
        current_stop=100.0,
        signal_bar_low=99.0,
        recent_lows=[101.0, 102.0, 103.0],
        current_price=100.5,
    )
    assert proposal.price == 100.0
    assert proposal.risk_reducing is False

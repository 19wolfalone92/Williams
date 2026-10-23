"""Source-specific TC1 page 53/82-83 five-bullet evidence and causality."""
import pytest
from williams_tc1_terminal_evidence import tc1_five_bullet_evidence

def scenario():
    return {"label":"W5","status":"PROVISIONAL","scale_bars":120,"observed_at":160,
        "anchors":[{"known_at":100+i*10} for i in range(6)],
        "divergence":True,"target_zone":True,"terminal_fractal":True,
        "squat":True,"momentum_shift":True}

def test_course_ao_divergence_does_not_certify_tc1_mfi():
    result=tc1_five_bullet_evidence(scenario())
    assert result["status"]=="MFI_DIVERGENCE_UNVERIFIED"
    assert result["observed_or_proxy_count"]==4
    assert result["checks"]["mfi_divergence"]=="NOT_VERIFIED"
    assert result["checks"]["profitunity_macd_momentum_turn"]=="AO_SIGNAL_PROXY_ONLY"
    assert result["wave_scale_100_140_observed"]
    assert result["last_w5_pivot_causally_known"]
    assert not result["point_zero_certified"]
    assert not any(result[k] for k in ("can_place_orders","can_close","can_reverse"))

@pytest.mark.parametrize("scale,expected",[ (99,False),(100,True),(120,True),(140,True),(141,False)])
def test_wave_degree_100_140_context_not_a_5_bullet_shortcut(scale,expected):
    h=scenario();h["scale_bars"]=scale
    result=tc1_five_bullet_evidence(h)
    assert result["wave_scale_100_140_observed"] is expected
    assert result["status"]=="MFI_DIVERGENCE_UNVERIFIED"

def test_provenance_is_required_and_cannot_authorize_a_trade():
    for src in (None,"BINANCE_BASE_VOLUME_PROXY","UNVERIFIED_OHLC_AO"):
        assert tc1_five_bullet_evidence(scenario(),True,src)["status"]=="MFI_DIVERGENCE_UNVERIFIED"
    res=tc1_five_bullet_evidence(scenario(),True,"MANUALLY_VERIFIED_TC1_CHART")
    assert res["status"]=="FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE"
    assert res["wave_degree_confirmed"] is False
    assert res["point_zero_certified"] is False

def test_unconfirmed_w5_and_impossible_confirmation_time():
    h=scenario();h["label"]="W4"
    assert tc1_five_bullet_evidence(h)["status"]=="NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT"
    h=scenario();h["observed_at"]=149
    assert not tc1_five_bullet_evidence(h)["last_w5_pivot_causally_known"]

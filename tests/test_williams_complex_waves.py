from dataclasses import dataclass
import pytest
from williams_complex_waves import detect_complex_wave_patterns

@dataclass(frozen=True)
class P:
    kind: str
    price: float
    extreme_at: int
    known_at: int

def swings(prices, *, starts_low=True):
    return [P(("LOW" if i%2==0 else "HIGH") if starts_low else
              ("HIGH" if i%2==0 else "LOW"),v,1000*(i+1),1000*(i+1)+100)
            for i,v in enumerate(prices)]

def patterns(prices, *, direction="UP", starts_low=True):
    p=swings(prices,starts_low=starts_low)
    return detect_complex_wave_patterns(p,direction=direction,as_of=100_000)

def names(items):
    return {x.pattern for x in items}

def test_ordinary_impulse_wave5_and_truncated_fifth_are_distinct():
    a=patterns([100,120,110,145,125,155])
    assert "ORDINARY_IMPULSE_CANDIDATE" in names(a)
    assert all(not x.can_place_orders for x in a)
    b=patterns([100,120,110,145,125,142])
    assert "TRUNCATED_FIFTH_CANDIDATE" in names(b)
    assert "ORDINARY_IMPULSE_CANDIDATE" not in names(b)

def test_overlap_preserves_diagonal_but_cannot_certify():
    a=patterns([100,120,110,145,115,160])
    assert "ENDING_OR_LEADING_DIAGONAL_CANDIDATE" in names(a)
    assert all(x.status == "ALTERNATIVE" for x in a)
    assert not any("DIAGONAL" in x.pattern for x in patterns([100,120,110,145,105,160]))

def test_zigzag_abc_comes_after_complete_five_price_waves():
    a=patterns([100,120,110,145,125,155,135,143,120])
    assert "ZIGZAG_ABC_CANDIDATE" in names(a)

def test_expanded_and_running_flats_distinguished_by_c_leg():
    e=patterns([100,120,110,145,125,155,135,158,128])
    assert "EXPANDED_FLAT_ABC_CANDIDATE" in names(e)
    r=patterns([100,120,110,145,125,155,135,158,140])
    assert "RUNNING_FLAT_ABC_CANDIDATE" in names(r)

def test_contracting_triangle_only_geometry_not_five_complete_internals():
    a=patterns([130,100,125,105,120,110],starts_low=False)
    assert "CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" in names(a)
    assert all("UNVERIFIED" in str(x.reasons) for x in a if "TRIANGLE" in x.pattern)

def test_combinations_are_unresolved_not_invented_complete():
    a=patterns([100,120,110,145,125,155,135,143,120,132,116,125])
    assert "W_X_Y_COMBINATION_UNRESOLVED" in names(a)
    assert "W_X_Y_X_Z_COMBINATION_UNRESOLVED" in names(a)

def test_closed_pivot_causality_and_invalid_structures():
    p=swings([100,120,110,145,125,155])
    assert detect_complex_wave_patterns(p,direction="UP",as_of=6000)==[]
    assert detect_complex_wave_patterns(p,direction="UP",as_of=6200)
    bad=p.copy()
    bad[3]=P("HIGH",145,2000,2100)
    assert detect_complex_wave_patterns(bad,direction="UP",as_of=6200)==[]
    assert detect_complex_wave_patterns(p,direction="WRONG",as_of=6200)==[]
    bad=p.copy()
    bad[-1]=P("HIGH",float("nan"),6000,6100)
    assert detect_complex_wave_patterns(bad,direction="UP",as_of=6200)==[]

def test_downside_mirror_price_and_kind():
    prices=[100,120,110,145,125,155]
    mirrored=[1000-v for v in prices]
    p=swings(mirrored,starts_low=False)
    out=detect_complex_wave_patterns(p,direction="DOWN",as_of=6200)
    assert "ORDINARY_IMPULSE_CANDIDATE" in names(out)


@pytest.mark.parametrize("prices,kind",[([150,100,160,90,170,80],"EXPANDING_TRIANGLE_ABCDE_CANDIDATE"),
    ([150,100,150,110,150,120],"BARRIER_TRIANGLE_ABCDE_CANDIDATE")])
@pytest.mark.parametrize("mirror",[False,True])
def test_triangle_boundary_variants_remain_causal_non_trading_candidates(prices,kind,mirror):
    from williams_wave_hierarchy import WaveAnchor
    from dataclasses import replace
    points=[WaveAnchor("HIGH" if i%2==0 else "LOW",p,i,i*3_600_000,(i+3)*3_600_000) for i,p in enumerate(prices)]
    if mirror:points=[replace(p,kind="LOW" if p.kind=="HIGH" else "HIGH",price=300-p.price) for p in points]
    result=detect_complex_wave_patterns(points,direction="DOWN" if mirror else "UP",as_of=40*3_600_000)
    assert any(p.pattern==kind and not p.can_place_orders for p in result)

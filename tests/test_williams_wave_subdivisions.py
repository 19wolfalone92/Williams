from dataclasses import replace
import pytest
from williams_wave_hierarchy import WaveAnchor
from williams_wave_subdivisions import prove_wave_subdivisions
H=3_600_000

def fixture(mirror):
    prices=[150,135,142,120,130,110,123,118,130,110,120,90,100,80]
    times=[30,31,32,34,35,36,38,40,42,43,44,46,47,48]
    child=[WaveAnchor("HIGH" if i%2==0 else "LOW",p,i,t*H,(t+3)*H) for i,(p,t) in enumerate(zip(prices,times))]
    parent=[replace(child[i],known_at=child[i].extreme_at+18*H) for i in (0,5,8,13)]
    if mirror:
        child=[replace(p,kind="LOW" if p.kind=="HIGH" else "HIGH",price=300-p.price) for p in child]
        parent=[replace(p,kind="LOW" if p.kind=="HIGH" else "HIGH",price=300-p.price) for p in parent]
    return parent,child

@pytest.mark.parametrize("mirror",[False,True])
def test_zigzag_needs_real_confirmed_five_three_five_lower_frame_legs(mirror):
    parent,child=fixture(mirror)
    proof=prove_wave_subdivisions(parent,child,(5,3,5),parent_duration=6*H,as_of=80*H)
    assert proof.status=="PRICE_SUBDIVISIONS_CONFIRMED"
    assert proof.observed==(5,3,5)
    assert prove_wave_subdivisions(parent,child,(3,3,5),parent_duration=6*H,as_of=80*H).status=="UNRESOLVED"
    assert prove_wave_subdivisions(parent,child,(5,3,5),parent_duration=6*H,as_of=60*H).status=="UNRESOLVED"
    future=replace(child[-1],extreme_at=81*H,known_at=84*H,price=float("nan"))
    assert prove_wave_subdivisions(parent,child+[future],(5,3,5),parent_duration=6*H,as_of=80*H)==proof
    assert prove_wave_subdivisions(parent,child[:-1],(5,3,5),parent_duration=6*H,as_of=80*H).status=="UNRESOLVED"

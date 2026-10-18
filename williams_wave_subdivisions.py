"""Causal lower-degree proofs for bounded complex wave candidates.

A 5-3-5 or 3-3-5 claim requires real confirmed lower-frame pivot endpoints.
A matching outer silhouette alone remains unresolved. Degree is explicit;
these proofs never bypass H1 signals, risk or exchange order ownership.
"""
from dataclasses import asdict,dataclass
from math import isfinite

@dataclass(frozen=True)
class SubdivisionProof:
    status:str
    expected:tuple[int,...]
    observed:tuple[int,...]
    child_known_at:int
    reason:str
    def to_dict(self):return asdict(self)


def prove_wave_subdivisions(parent,children,expected,*,parent_duration,as_of):
    if len(parent)!=len(expected)+1 or parent_duration<=0 or any(n not in {3,5} for n in expected):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"INVALID_SUBDIVISION_CONTRACT")
    if not parent or any(p.known_at>as_of for p in parent):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"PARENT_NOT_CONFIRMED")
    known=[p for p in children if p.known_at<=as_of]
    if any(not isfinite(p.price) or p.price<=0 or p.known_at<p.extreme_at or p.kind not in {"LOW","HIGH"} for p in (*parent,*known)):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"INVALID_PIVOT")
    if any(a.extreme_at>=b.extreme_at or a.kind==b.kind for a,b in zip(known,known[1:])):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"CHILD_ORDER_AMBIGUOUS")
    def matches(p,c):
        return p.kind==c.kind and p.extreme_at<=c.extreme_at<p.extreme_at+parent_duration and abs(p.price-c.price)<=max(1e-9,p.price*1e-9)
    endpoints=[]
    for p in parent:
        found=[i for i,c in enumerate(known) if matches(p,c)]
        if len(found)!=1:return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"CHILD_ENDPOINT_MISSING_OR_AMBIGUOUS")
        endpoints.append(found[0])
    if any(a>=b for a,b in zip(endpoints,endpoints[1:])):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"CHILD_ENDPOINT_ORDER_INVALID")
    observed=tuple(b-a for a,b in zip(endpoints,endpoints[1:]));last_known=0
    for leg,(a,b),needed in zip(range(len(expected)),zip(endpoints,endpoints[1:]),expected):
        sub=known[a:b+1];sign=1 if sub[0].kind=="LOW" else -1
        p=[sign*x.price for x in sub];lo,hi=sorted([sub[0].price,sub[-1].price])
        last_known=max(last_known,max(x.known_at for x in sub))
        if b-a!=needed or any(x.price<lo or x.price>hi for x in sub):
            return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"SUBDIVISION_COUNT_OR_PRICE_CONTAINMENT_FAILED")
        if needed==5:
            lengths=[p[1]-p[0],p[3]-p[2],p[5]-p[4]]
            if not (p[0]<p[2]<p[1]<p[4]<p[3]<p[5]) or lengths[1]<min(lengths[0],lengths[2]):
                return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"INTERNAL_IMPULSE_GEOMETRY_FAILED")
        elif not (p[0]<p[2]<p[1]<p[3]):
            return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"INTERNAL_ABC_GEOMETRY_FAILED")
    return SubdivisionProof("PRICE_SUBDIVISIONS_CONFIRMED",tuple(expected),observed,last_known,"REAL_CONFIRMED_CHILD_PIVOTS")


def enrich_complex_subdivisions(pattern,parent,children,*,parent_duration,as_of):
    if pattern.startswith("ZIGZAG_ABC"):
        expected=(5,3,5);points=parent[-4:]
    elif "FLAT_ABC" in pattern:
        expected=(3,3,5);points=parent[-4:]
    elif "TRIANGLE" in pattern:
        expected=(3,3,3,3,3);points=parent[-6:]
    elif "ORDINARY_IMPULSE" in pattern or "TRUNCATED_FIFTH" in pattern:
        expected=(5,3,5,3,5);points=parent[-6:]
    elif pattern=="W_X_Y_ZIGZAG_COMBINATION_CANDIDATE":
        expected=(5,3,5)*3;points=parent[-10:]
    elif pattern=="W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE":
        expected=(5,3,5)*5;points=parent[-16:]
    elif "ENDING_OR_LEADING_DIAGONAL" in pattern:
        points=parent[-6:]
        corrections=prove_wave_subdivisions(points,children,(3,3,3,3,3),parent_duration=parent_duration,as_of=as_of)
        if corrections.status=="PRICE_SUBDIVISIONS_CONFIRMED":return corrections
        expected=(5,3,5,3,5)
    else:return SubdivisionProof("UNRESOLVED",(),(),0,"PATTERN_HAS_COMPETING_INTERNAL_GRAMMARS")
    return prove_wave_subdivisions(points,children,expected,parent_duration=parent_duration,as_of=as_of)

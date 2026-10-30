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
    # Only the actual price grammar at this degree, never recursive
    # certification or permission to trade a named Elliott pattern.
    leg_patterns:tuple[str,...]=()
    def to_dict(self):return asdict(self)


def prove_wave_subdivisions(parent,children,expected,*,parent_duration,as_of,allow_flat_corrections=True):
    if len(parent)!=len(expected)+1 or parent_duration<=0 or any(n not in {3,5} for n in expected):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"INVALID_SUBDIVISION_CONTRACT")
    if not parent or any(p.known_at>as_of for p in parent):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"PARENT_NOT_CONFIRMED")
    known=[p for p in children if p.known_at<=as_of]
    if any(not isfinite(p.price) or p.price<=0 or p.extreme_at<0 or p.known_at<p.extreme_at or p.kind not in {"LOW","HIGH"} for p in (*parent,*known)):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"INVALID_PIVOT")
    if any(a.extreme_at>=b.extreme_at or a.kind==b.kind for a,b in zip(parent,parent[1:])):
        return SubdivisionProof("UNRESOLVED",tuple(expected),(),0,"PARENT_ORDER_AMBIGUOUS")
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
    # Preserve the completed causal lower-degree legs when a later leg fails.
    # This is diagnostic evidence only, not a completed Elliott count.
    observed=tuple(b-a for a,b in zip(endpoints,endpoints[1:]));last_known=0;shapes=[]
    for leg,(a,b),needed in zip(range(len(expected)),zip(endpoints,endpoints[1:]),expected):
        sub=known[a:b+1];sign=1 if sub[0].kind=="LOW" else -1
        p=[sign*x.price for x in sub]
        last_known=max(last_known,max(x.known_at for x in sub))
        if b-a!=needed:
            return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"SUBDIVISION_COUNT_FAILED",tuple(shapes))
        if needed==5:
            lengths=[p[1]-p[0],p[3]-p[2],p[5]-p[4]]
            # A completed fifth can end short of W3. Its price envelope is
            # then bounded by W3, not by the orthodox final endpoint W5.
            if not (p[0]<p[2]<p[1]<p[4]<p[3] and p[5]>p[4]) or lengths[1]<min(lengths[0],lengths[2]):
                return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"INTERNAL_IMPULSE_GEOMETRY_FAILED",tuple(shapes))
            shapes.append("IMPULSE_PRICE_SKELETON" if p[5]>p[3] else "TRUNCATED_IMPULSE_PRICE_SKELETON")
        else:
            # A corrective three may be flat: expanded B crosses its own
            # origin, and a running C need not exceed A. Those excursions
            # must remain visible in evidence rather than be discarded by
            # an endpoint-envelope shortcut intended for simple zigzags.
            if not (p[1]>p[0] and p[2]<p[1] and p[3]>max(p[0],p[2])):
                return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"INTERNAL_ABC_GEOMETRY_FAILED",tuple(shapes))
            if p[2]<p[0]:
                shape="EXPANDED_FLAT_PRICE_SKELETON" if p[3]>p[1] else "RUNNING_FLAT_PRICE_SKELETON"
            elif p[3]>=p[1]:
                shape="REGULAR_FLAT_PRICE_SKELETON" if p[2]==p[0] else "ZIGZAG_PRICE_SKELETON"
            else:
                return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"INTERNAL_ABC_GEOMETRY_FAILED",tuple(shapes))
            shapes.append(shape)
            if not allow_flat_corrections and shape!="ZIGZAG_PRICE_SKELETON":
                return SubdivisionProof("UNRESOLVED",tuple(expected),observed,last_known,"DIAGONAL_REQUIRES_ZIGZAG_SUBWAVES",tuple(shapes))
    return SubdivisionProof("PRICE_SUBDIVISIONS_CONFIRMED",tuple(expected),observed,last_known,"REAL_CONFIRMED_CHILD_PIVOTS",tuple(shapes))


def enrich_complex_subdivisions(pattern,parent,children,*,parent_duration,as_of):
    if pattern.startswith("FLAT_ABC_INCOMPLETE_OR_TRUNCATED_C"):
        return SubdivisionProof("UNRESOLVED",(3,3,5),(),0,"C_END_OR_TRUNCATION_UNCONFIRMED")
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
        corrections=prove_wave_subdivisions(points,children,(3,3,3,3,3),parent_duration=parent_duration,as_of=as_of,allow_flat_corrections=False)
        if corrections.status=="PRICE_SUBDIVISIONS_CONFIRMED":return corrections
        expected=(5,3,5,3,5)
    else:return SubdivisionProof("UNRESOLVED",(),(),0,"PATTERN_HAS_COMPETING_INTERNAL_GRAMMARS")
    return prove_wave_subdivisions(points,children,expected,parent_duration=parent_duration,as_of=as_of,
                                   allow_flat_corrections="DIAGONAL" not in pattern)


# Trading Chaos Course PDF59-60: a visual decision checklist, not a unique
# wave forecast. The branches are explicit so "3" at W3 is never called a W3.
COURSE_CHECKLIST = {
    "W1": {3: "RECOUNT_AS_CORRECTION_NOT_TREND_REVERSAL",
           5: "SMALLER_DEGREE_MOTIVE_CANDIDATE"},
    "W2": {3: "SIDEWAYS_CORRECTION_CANDIDATE",
           5: "SHARP_CORRECTION_CANDIDATE"},
    "W3": {3: "RECOUNT_W3_WRONG_WAVE_NUMBER",
           5: "W3_PRICE_SUBDIVISIONS_CANDIDATE"},
    "W4": {3: "SIDEWAYS_CORRECTION_CANDIDATE",
           5: "SHARP_CORRECTION_CANDIDATE"},
    "W5": {3: "DIAGONAL_OR_CONTINUATION_CANDIDATE",
           5: "MOTIVE_OR_DIAGONAL_CANDIDATE"},
    # Course PDF60 corrective-wave checklist. These are alternatives:
    # even five declining A swings can be one leg of a larger sideways correction.
    "A": {3: "CORRECTION_STARTED_NOT_YET_CONFIRMED",
          5: "SHARP_CORRECTION_OR_LARGER_SIDEWAYS_COMPONENT"},
    "B": {3: "SIDEWAYS_CORRECTION_CANDIDATE",
          5: "EXTENDED_W5_OR_WRONG_CORRECTION_COUNT"},
    "C": {3: "TRIANGLE_OR_C_EXTENSION_OR_RECOUNT",
          5: "ZIGZAG_OR_FLAT_CORRECTION_CANDIDATE"},
    "D": {3: "TRIANGLE_LEG_D_CANDIDATE"},
    "E": {3: "TRIANGLE_LEG_E_CANDIDATE"},
    "X": {3: "DOUBLE_OR_TRIPLE_CORRECTION_CANDIDATE",
          5: "POSSIBLE_NEW_W1_OR_CORRECTION_TERMINATION"},
}


def course_checklist_leg(leg: str, observed_swings: int) -> str:
    """A schematic branch from Course PDF59/60, not an entry/exit signal."""
    if not isinstance(observed_swings, int) or isinstance(observed_swings, bool):
        return "UNRESOLVED"
    return COURSE_CHECKLIST.get(leg, {}).get(observed_swings, "UNRESOLVED")


def trace_recursive_price_grammar(
    pattern, parent, pivots_by_frame, frame_durations, parent_frame, as_of,
    *, depth=3, max_nodes=64
):
    """Inspect actual child anchors down several degrees without lookahead.

    Positive status verifies price grammar *at the observed, listed depths*.
    It DOES NOT establish a unique Elliott genealogy or certify trades.
    Missing resolutions, ambiguous matching, unconfirmed pivots and resource
    exhaustion remain unresolved rather than guessed.
    """
    def output(status, reason, frame="", child="", proof=None, checklist=None, branches=None):
        result = {
            "status": status, "reason": reason,
            "frame": frame, "child_frame": child,
            "internal_proof": (proof.to_dict() if proof is not None else None),
            "checklist": checklist or [], "branches": branches or [],
            "source": "COURSE_PDF59_60_CHECKLIST_AND_CONFIRMED_PRICE_PIVOTS",
            "unique_elliott_genealogy_proven": False,
            "can_place_orders": False, "can_close": False, "can_reverse": False,
        }
        return result

    if (not isinstance(as_of, int) or as_of <= 0 or not 1 <= depth <= 3
            or not 1 <= max_nodes <= 256 or parent_frame not in frame_durations):
        return output("UNRESOLVED", "INVALID_RECURSIVE_DEGREE_CONTRACT")

    frames = [parent_frame] + sorted(
        (f for f in pivots_by_frame
         if f in frame_durations and 0 < frame_durations[f] < frame_durations[parent_frame]),
        key=lambda f: -frame_durations[f])
    if any(frame_durations[a] <= frame_durations[b] for a,b in zip(frames, frames[1:])):
        return output("UNRESOLVED", "NON_DESCENDING_REAL_TIMEFRAMES")

    budget = [0]
    def pivot_matches(p, c, duration):
        return (p.kind == c.kind
                and p.extreme_at <= c.extreme_at < p.extreme_at + duration
                and abs(p.price - c.price) <= max(1e-9, abs(p.price) * 1e-9))

    def walk(points, expected, index, remaining, root=False):
        budget[0] += 1
        frame=frames[index]
        if budget[0] > max_nodes:
            return output("UNRESOLVED", "RECURSIVE_NODE_BUDGET_EXHAUSTED", frame)
        if index+1 >= len(frames):
            return output("UNRESOLVED", "CHILD_TIMEFRAME_UNAVAILABLE", frame)
        child=frames[index+1]
        child_duration=frame_durations[frame]
        anchors=[p for p in pivots_by_frame.get(child, ())
                 if p.known_at <= as_of]
        if root:
            proof=enrich_complex_subdivisions(pattern,points,anchors,
                    parent_duration=child_duration,as_of=as_of)
        else:
            proof=prove_wave_subdivisions(points,anchors,expected,
                    parent_duration=child_duration,as_of=as_of)
        tags=(["A","B","C","D","E"] if root and "TRIANGLE" in pattern
              and len(proof.expected)==5
              else ["W1","W2","W3","W4","W5"] if len(proof.expected)==5
              else ["A","B","C"] if len(proof.expected)==3
              else [f"L{i+1}" for i in range(len(proof.expected))])
        checklist=[{"leg":name,"observed_swings":count,
                    "course_branch":course_checklist_leg(name,count)}
                   for name,count in zip(tags,proof.observed)]
        if proof.status != "PRICE_SUBDIVISIONS_CONFIRMED":
            return output("UNRESOLVED",proof.reason,frame,child,proof,checklist)
        if remaining==1:
            return output("OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",
                          "ALL_REQUESTED_PRICE_DEPTHS_OBSERVED",frame,child,proof,checklist)
        indices=[]
        for p in points:
            options=[i for i,c in enumerate(anchors)
                     if pivot_matches(p,c,child_duration)]
            if len(options)!=1:
                return output("PARTIAL_PRICE_GRAMMAR",
                              "DESCENDANT_ENDPOINT_AMBIGUOUS",frame,child,proof,checklist)
            indices.append(options[0])
        if any(a>=b for a,b in zip(indices,indices[1:])):
            return output("PARTIAL_PRICE_GRAMMAR",
                          "DESCENDANT_ENDPOINT_ORDER_INVALID",frame,child,proof,checklist)
        shapes={
            "IMPULSE_PRICE_SKELETON":(5,3,5,3,5),
            "TRUNCATED_IMPULSE_PRICE_SKELETON":(5,3,5,3,5),
            "ZIGZAG_PRICE_SKELETON":(5,3,5),
            "REGULAR_FLAT_PRICE_SKELETON":(3,3,5),
            "EXPANDED_FLAT_PRICE_SKELETON":(3,3,5),
            "RUNNING_FLAT_PRICE_SKELETON":(3,3,5),
        }
        branches=[]
        for i,shape in enumerate(proof.leg_patterns):
            next_expected=shapes.get(shape)
            if next_expected is None:
                branches.append(output("UNRESOLVED","UNKNOWN_CHILD_GRAMMAR",child))
            else:
                branches.append(walk(anchors[indices[i]:indices[i+1]+1],
                                     next_expected,index+1,remaining-1))
        if all(b["status"]=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED" for b in branches):
            return output("OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",
                          "ALL_REQUESTED_PRICE_DEPTHS_OBSERVED",frame,child,proof,checklist,branches)
        return output("PARTIAL_PRICE_GRAMMAR",
                      "SOME_RECURSIVE_LEGS_UNRESOLVED",frame,child,proof,checklist,branches)

    result=walk(parent,(),0,depth,root=True)
    result["requested_depth"]=depth
    result["frame_path"]=frames[:depth+1]
    result["observed_node_count"]=budget[0]
    return result

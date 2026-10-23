"""Blind OHLC-only secondary reviewer for 30 historical H1 chart windows.

IMPORTANT: this is an INDEPENDENT IMPLEMENTATION of a strict PRICE-GRAMMAR
proxy, not a human Elliott expert, not TC1/TC2/Course full conformance, and
not suitable for promoting accuracy_certified. It imports NO Williams code.
"""
from __future__ import annotations
from statistics import median

def _pivots(rows):
    """Two closed right bars; ambiguity on dual-sided extreme is unresolved."""
    raw=[]
    for i in range(2,len(rows)-2):
        hi=float(rows[i][2]);lo=float(rows[i][3])
        ish=all(hi>float(rows[j][2]) for j in (i-2,i-1,i+1,i+2))
        isl=all(lo<float(rows[j][3]) for j in (i-2,i-1,i+1,i+2))
        if ish==isl:
            continue
        raw.append({"kind":"HIGH" if ish else "LOW","price":hi if ish else lo,
            "index":i,"extreme_at":rows[i][0],"known_at":rows[i+2][0]+3_600_000})
    out=[]
    for x in raw:
        if not out or x["kind"]!=out[-1]["kind"]:
            out.append(x)
        elif (x["price"]>out[-1]["price"] if x["kind"]=="HIGH"
              else x["price"]<out[-1]["price"]):
            out[-1]=x
    return out

def blind_reference(rows):
    """Return provisional snapshot price grammar, not a certified Elliott count.

    This function is run against 160 trailing candles selected without looking
    at any Williams outputs. It declares NO_VALID_COUNT when strict geometry
    is absent; this does not prove the real market had no Elliott structure.
    """
    if len(rows)!=160:
        return {"label":"NO_VALID_COUNT","reason":"NOT_160_CLOSED_BARS","pivot_count":0}
    ranges=[float(b[2])-float(b[3]) for b in rows]
    threshold=max(1e-10,median(ranges[-100:])*1.25)
    pivots=_pivots(rows)
    # Remove only adjacent weak legs, bounded by the CLOSED snapshot.
    filtered=[]
    for p in pivots:
        if not filtered:
            filtered.append(p)
        elif abs(p["price"]-filtered[-1]["price"])>=threshold:
            filtered.append(p)
    tail=filtered[-6:]
    def has_impulse(x):
        if len(x)!=6:return False
        sign=1 if x[0]["kind"]=="LOW" else -1
        v=[sign*(p["price"]-x[0]["price"]) for p in x]
        legs=[abs(x[j+1]["price"]-x[j]["price"]) for j in (0,2,4)]
        return (v[1]>threshold and 0<v[2]<v[1]
            and v[3]>v[1] and v[4]>v[1]
            and v[5]>v[3] and legs[1]>=min(legs[0],legs[2])
            and x[-1]["known_at"]<=rows[-1][0]+3_600_000
            and x[-1]["index"]>=130)
    if has_impulse(tail):
        return {"label":"MOTIVE_FIVE","reason":"INDEPENDENT_STRICT_OHLC_12345_GEOMETRY",
            "pivot_count":len(filtered),"anchors":tail,"threshold":threshold}
    if len(filtered)>=4:
        x=filtered[-4:];d=1 if x[0]["kind"]=="LOW" else -1
        v=[d*(p["price"]-x[0]["price"]) for p in x]
        A=v[1];B=v[2];C=v[3]
        if (A>threshold and -0.20*A<=B<=0.75*A and C>=0.75*A and
            x[-1]["index"]>=130):
            return {"label":"CORRECTION_ABC","reason":"INDEPENDENT_STRICT_OHLC_ABC_GEOMETRY",
                "pivot_count":len(filtered),"anchors":x,"threshold":threshold}
    return {"label":"NO_VALID_COUNT","reason":"STRICT_160_BAR_PRICE_GRAMMAR_NOT_ESTABLISHED",
        "pivot_count":len(filtered),"anchors":filtered[-6:],"threshold":threshold}

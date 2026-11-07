"""Trading Chaos 1, ch6 PDF42-45: Price range per base volume.

Exact MFI mathematics on exchanged klines, but the textbook used tick-volume,
so these four windows are PROXY observations, not canonical book-confirmed
execution signals and never exit/entry/STOP authorities.
"""
from __future__ import annotations

from math import isfinite

FRAME_MS={"1m":60000,"3m":180000,"5m":300000,"15m":900000,
"30m":1800000,"1h":3600000,"2h":7200000,"4h":14400000,
"6h":21600000,"8h":28800000,"12h":43200000,"1d":86400000,"1w":604800000}

def tc1_mfi_four_window(rows:list[dict],frame:str,as_of:int)->dict:
    r={"schema":"TC1_CH6_MFI_FOUR_WINDOWS_PROXY_V1",
       "book_pdf_pages":"42-45", "frame":frame, "as_of_ms":as_of,
       "source":"EXCHANGE_KLINE_BASE_ASSET_VOLUME_PROXY",
       "canonical_tick_volume_verified":False,
       "volume_is_price_independent_signal":False,
       "pattern_can_prove_w5_end":False,
       "execution_authority":False,
       "can_place_orders":False,"can_close":False,"can_move_stop":False,
       "status":"SOURCE_H1_OR_FRAME_UNVERIFIED","window":"UNKNOWN",
       "explanation_ru":"Индекс облегчения рынка не рассчитан: нужна непрерывная история двух закрытых свечей и проверенный объём."}
    if frame not in FRAME_MS or type(as_of) is not int or as_of<=0 or len(rows)<2:
        return r
    step=FRAME_MS[frame]
    prev,cur=rows[-2:]
    try:
        v=[]
        for b in (prev,cur):
            t=b["t"];o=float(b["o"]);hi=float(b["h"]);lo=float(b["l"]);c=float(b["c"]);vol=float(b["v"])
            if (type(t) is not int or t<0 or t%step or t>as_of-step or
                not all(isfinite(x) for x in (o,hi,lo,c,vol)) or
                vol<=0 or hi<=lo or lo<=0 or hi<max(o,c,lo) or lo>min(o,c)):
                return r
            v.append((t,hi,lo,vol))
    except (KeyError,TypeError,OverflowError,ValueError):
        return r
    if v[-1][0]-v[0][0]!=step or not (0<=as_of-(v[-1][0]+step)<step):
        return r
    old=(v[0][1]-v[0][2])/v[0][3]
    now=(v[1][1]-v[1][2])/v[1][3]
    if not (isfinite(old) and isfinite(now) and old>0 and now>0):
        r["status"]="MFI_NUMERICALLY_UNVERIFIED"
        return r
    vu=v[1][3]>v[0][3];vd=v[1][3]<v[0][3]
    mu=now>old;md=now<old
    state=("GREEN" if vu and mu else
           "FADE" if vd and md else
           "FAKE" if vd and mu else
           "SQUAT" if vu and md else "UNCHANGED_OR_TIED")
    explanations={
        "GREEN":"«Зелёный» бар: объём вырос и MFI вырос. Цена движется легче при повышении объёма; цвет НЕ задаёт направление LONG/SHORT.",
        "FADE":"«Увядающий» бар: и объём, и MFI снизились. Интерес к движению ослаб; последующий импульс может возникнуть в любую сторону.",
        "FAKE":"«Фальшивый» бар: объём снизился, а MFI вырос. Цена перемещается при меньшем объёме, поэтому сила движения сомнительна.",
        "SQUAT":"«Приседающий» бар: объём вырос, а MFI снизился. Возможна борьба покупателей и продавцов; такой бар бывает и в середине тренда, это НЕ доказанный разворот.",
        "UNCHANGED_OR_TIED":"Объём или MFI остался на прежнем уровне. Четырёхзначная классификация TC1 здесь неоднозначна; не подменяем равенство ростом/спадом."
    }
    r.update(status="TIED_NEITHER_UP_NOR_DOWN" if state=="UNCHANGED_OR_TIED" else "OBSERVED_BASE_VOLUME_PROXY",
        window=state,previous_bar_at_ms=v[0][0],latest_closed_bar_at_ms=v[1][0],
        known_at_ms=v[1][0]+step,
        mfi_previous=old,mfi_current=now,
        volume_previous=v[0][3],volume_current=v[1][3],
        volume_direction="UP" if vu else "DOWN" if vd else "TIED",
        mfi_direction="UP" if mu else "DOWN" if md else "TIED",
        explanation_ru="По TC1, глава 6: "+explanations[state]+
            " Объём Binance — базовый объём актива, а не оригинальный тиковый объём Вильямса. Это ограниченное наблюдение без права выставлять, снимать или менять биржевые ордера.")
    return r

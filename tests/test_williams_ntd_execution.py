import json
from pathlib import Path

import pytest

from williams_edition_rules import ntd_management_plan
from williams_ntd_execution import close_entry_bound, require_close_entry
from williams_wave_hierarchy import WaveBar

CONTRACT=json.loads((Path(__file__).resolve().parents[1]/"app/src/test/resources/williams_ntd_execution_contract.json").read_text())

@pytest.mark.parametrize("c",CONTRACT["cases"],ids=lambda c:c["name"])
def test_shared_closed_event_contract(c):
    side="BUY" if c["direction"]=="LONG" else "SELL"
    assert close_entry_bound(side,c["reference"],c["tick"])==pytest.approx(100.1 if side=="BUY" else 99.9)
    def check():
        require_close_entry(side,c["signal_at"],c["reference"],c["bound"],c["now"],c["contract"],c["mark"],
            {"bidPrice":c["bid"],"askPrice":c["ask"]},c["tick"])
    if c["allowed"]:check()
    else:
        with pytest.raises(RuntimeError):check()

@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_preentry_candle_cannot_close_a_fresh_campaign(side):
    h=3_600_000
    bars=[WaveBar((480000+i)*h,100,101,99,100,1) for i in range(6)]
    line=[110 if side=="LONG" else 90]*6
    opened=bars[-1].t+h
    plan=ntd_management_plan(side,bars,line,line,[0]*6,[0]*6,.1,opened)
    assert plan=={"state":{},"exit_reason":"","stop":None}
    nxt=bars+[WaveBar(opened,100,101,99,100,1)]
    plan=ntd_management_plan(side,nxt,line+[line[-1]],line+[line[-1]],[0]*7,[0]*7,.1,opened)
    assert plan["exit_reason"]=="TEETH_CLOSE_EXIT"

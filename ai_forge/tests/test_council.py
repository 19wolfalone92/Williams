from ai_forge.council import Council
from ai_forge.models import AgentDecision

def agent(provider, decision, confidence=0.8):
    return AgentDecision(provider=provider, model="test", decision=decision, confidence=confidence, rationale="test", latency_ms=1)

def test_consensus():
    result=Council(3,0.60).verify([agent("a","PROCEED"),agent("b","PROCEED"),agent("c","PROCEED"),agent("d","HOLD")],4)
    assert result.verified and result.decision=="PROCEED"

def test_quorum_failure():
    result=Council(3,0.60).verify([agent("a","PROCEED"),agent("b","PROCEED")],3)
    assert result.decision=="ESCALATE" and not result.verified

def test_strong_reject():
    result=Council(3,0.60).verify([agent("a","PROCEED",0.9),agent("b","PROCEED",0.9),agent("c","REJECT",0.95),agent("d","HOLD",0.6),agent("e","HOLD",0.6)],5)
    assert result.decision=="ESCALATE" and not result.verified

from __future__ import annotations
from .models import AgentDecision, CouncilDecision

class Council:
    def __init__(self, min_agents, min_consensus):
        self.min_agents, self.min_consensus = min_agents, min_consensus

    def verify(self, agents: list[AgentDecision], configured_agents: int, require_all: bool = False):
        ok = [a for a in agents if a.ok]
        if require_all and len(ok) != configured_agents:
            return CouncilDecision(decision="ESCALATE", verified=False, consensus=0.0, successful_agents=len(ok), configured_agents=configured_agents, min_required_agents=self.min_agents, rationale="Not all configured Council agents responded successfully.", agents=agents)
        if len(ok) < self.min_agents:
            return CouncilDecision(decision="ESCALATE", verified=False, consensus=0.0, successful_agents=len(ok), configured_agents=configured_agents, min_required_agents=self.min_agents, rationale="Quorum not reached.", agents=agents)
        weights = {k: 0.0 for k in ("PROCEED", "HOLD", "REJECT", "ESCALATE")}
        for a in ok:
            weights[a.decision] += max(0.05, a.confidence)
        total = sum(weights.values())
        ranked = sorted(weights.items(), key=lambda x: x[1], reverse=True)
        winner, weight = ranked[0]
        second = ranked[1][1]
        consensus = weight / total if total else 0.0
        strong_reject = sum(a.confidence for a in ok if a.decision == "REJECT" and a.confidence >= 0.85)
        if winner == "PROCEED" and strong_reject > max(0.85, weight * 0.35):
            winner, verified, rationale = "ESCALATE", False, "Council conflict: a strong rejection blocks affirmative consensus."
        elif consensus < self.min_consensus or (winner == "PROCEED" and weight - second < 0.30):
            winner, verified, rationale = "ESCALATE", False, "Council did not reach the configured consensus threshold."
        else:
            verified, rationale = True, "Weighted consensus: %s with %.0f%% support across %d agents." % (winner, consensus * 100, len(ok))
        return CouncilDecision(decision=winner, verified=verified, consensus=consensus, successful_agents=len(ok), configured_agents=configured_agents, min_required_agents=self.min_agents, rationale=rationale, agents=agents)

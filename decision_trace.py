"""Append-only decision trace records for explainable execution."""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
import time
import uuid
from typing import Any, Mapping


@dataclass(frozen=True)
class DecisionTrace:
    intent_id: str
    stage: str
    decision: str
    symbol: str = ""
    purpose: str = ""
    campaign_id: str = ""
    signal_id: str = ""
    reason: str = ""
    blocker: str = ""
    context_versions: Mapping[str, int] = field(default_factory=dict)
    payload: Mapping[str, Any] = field(default_factory=dict)
    trace_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

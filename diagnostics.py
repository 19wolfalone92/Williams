"""Fail-closed diagnostics and bounded safe self-healing."""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
import json
import os
import platform
import sys
import time
import traceback
import uuid
from typing import Any, Callable


SAFE_ACTIONS = (
    "RELOAD_EXCHANGE_INFO",
    "RELOAD_MARKET_HISTORY",
    "RECONNECT_MARKET_WS",
    "RECONNECT_USER_WS",
    "RESTART_SCANNER",
    "RECONCILE_EXCHANGE_STATE",
    "REBUILD_DERIVED_INDICATORS",
)


@dataclass
class Incident:
    incident_id: str
    created_at: str
    component: str
    error_type: str
    message: str
    traceback_text: str
    safe_actions_attempted: list[str] = field(default_factory=list)
    safe_actions_failed: list[str] = field(default_factory=list)
    execution_blocked: bool = True
    severity: str = "ERROR"
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class DiagnosticManager:
    """Owns incident reports and *only* safe, non-order-creating recovery."""

    def __init__(self, db=None, max_attempts: int = 3) -> None:
        self.db = db
        self.max_attempts = max(1, int(max_attempts))
        self.attempts: dict[str, int] = {}

    def classify(self, component: str, exc: BaseException) -> Incident:
        return Incident(
            incident_id=uuid.uuid4().hex,
            created_at=datetime.now(timezone.utc).isoformat(),
            component=str(component),
            error_type=type(exc).__name__,
            message=str(exc),
            traceback_text="".join(
                traceback.format_exception(type(exc), exc, exc.__traceback__)
            ),
            metadata={
                "python": sys.version,
                "platform": platform.platform(),
                "pid": os.getpid(),
            },
        )

    def record(self, incident: Incident) -> dict[str, Any]:
        payload = incident.to_dict()
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(
                    incident.severity,
                    "diagnostic_incident",
                    incident.message,
                    payload,
                )
            except Exception:
                pass
        return payload

    def run_safe_heal(
        self,
        incident: Incident,
        callbacks: dict[str, Callable[[], Any]] | None = None,
    ) -> Incident:
        callbacks = dict(callbacks or {})
        key = f"{incident.component}:{incident.error_type}:{incident.message[:160]}"
        attempts = self.attempts.get(key, 0)
        if attempts >= self.max_attempts:
            incident.metadata["max_safe_heal_attempts_reached"] = True
            return incident
        self.attempts[key] = attempts + 1

        for action in SAFE_ACTIONS:
            callback = callbacks.get(action)
            if callback is None:
                continue
            incident.safe_actions_attempted.append(action)
            try:
                callback()
            except Exception:
                incident.safe_actions_failed.append(action)

        return incident

    @staticmethod
    def redact(value: Any) -> Any:
        if isinstance(value, dict):
            out = {}
            for key, item in value.items():
                if str(key).lower() in {"api_key", "api_secret", "secret", "authorization", "token"}:
                    out[key] = "***REDACTED***"
                else:
                    out[key] = DiagnosticManager.redact(item)
            return out
        if isinstance(value, list):
            return [DiagnosticManager.redact(x) for x in value]
        return value

    def report_json(self, incident: Incident, *, extra: dict[str, Any] | None = None) -> str:
        payload = incident.to_dict()
        payload["system"] = {
            "python": sys.version,
            "platform": platform.platform(),
            "pid": os.getpid(),
        }
        if extra:
            payload["runtime_snapshot"] = self.redact(extra)
        return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)

    def persist_report(
        self,
        incident: Incident,
        *,
        directory: str | None = None,
        extra: dict[str, Any] | None = None,
    ) -> str:
        """Persist a redacted diagnostic report and return its absolute path."""
        directory = directory or os.getenv(
            "WILLIAMS_ERROR_REPORT_DIR",
            "diagnostics",
        )
        os.makedirs(directory, exist_ok=True)
        stamp = int(time.time() * 1000)
        path = os.path.abspath(
            os.path.join(
                directory,
                f"Williams_Error_Report_{stamp}_{incident.incident_id[:10]}.json",
            )
        )
        payload = self.report_json(incident, extra=extra)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.write("\n")
        return path

    def create_williams_error_report(
        self,
        *,
        component: str,
        error: BaseException,
        severity: str = "CRITICAL",
        metadata: dict[str, Any] | None = None,
    ) -> tuple[Incident, str]:
        """Classify and durably persist a Williams_Error_Report."""
        incident = self.classify(component, error)
        incident.severity = str(severity).upper()
        if metadata:
            incident.metadata.update(self.redact(metadata))
        self.record(incident)
        path = self.persist_report(incident, extra=metadata)
        return incident, path

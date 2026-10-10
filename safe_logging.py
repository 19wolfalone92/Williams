"""Central redaction for credentials and secret-bearing exception text."""
from __future__ import annotations

import re
from typing import Any

_SENSITIVE_VALUES: set[str] = set()

_SENSITIVE_ASSIGNMENT = re.compile(
    r"(?i)(api[_-]?key|api[_-]?secret|secret|access[_-]?token|refresh[_-]?token|token|password|signature|authorization)"
    r"(\s*[:=]\s*)(['\"]?)[^\s,;&'\"]+"
)
_BEARER = re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+")
_API_HEADER = re.compile(r"(?i)(x-mbx-apikey\s*[:=]\s*)[^\s,;]+")
_SIGNATURE_QUERY = re.compile(r"(?i)([?&]signature=)[^&\s]+")


def register_sensitive_value(value: Any) -> None:
    text = str(value or "")
    if len(text) >= 8:
        _SENSITIVE_VALUES.add(text)


def redact_text(value: Any) -> str:
    text = str(value)
    for secret in sorted(_SENSITIVE_VALUES, key=len, reverse=True):
        text = text.replace(secret, "[REDACTED]")
    text = _BEARER.sub(r"\1[REDACTED]", text)
    text = _API_HEADER.sub(r"\1[REDACTED]", text)
    text = _SIGNATURE_QUERY.sub(r"\1[REDACTED]", text)
    text = _SENSITIVE_ASSIGNMENT.sub(r"\1\2[REDACTED]", text)
    return text


def redact_value(value: Any) -> Any:
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            normalized = str(key).lower().replace("-", "_")
            if normalized in {
                "api_key", "apikey", "x_mbx_apikey", "api_secret", "apisecret",
                "secret", "authorization", "token", "access_token", "accesstoken",
                "refresh_token", "refreshtoken", "password", "signature",
                "bearer_token", "bearertoken",
            } or any(part in normalized for part in ("secret", "token", "password", "signature", "authorization")):
                out[key] = "[REDACTED]"
            else:
                out[key] = redact_value(item)
        return out
    if isinstance(value, (list, tuple)):
        return [redact_value(item) for item in value]
    if isinstance(value, str):
        return redact_text(value)
    return value

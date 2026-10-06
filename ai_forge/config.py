from __future__ import annotations
import os, secrets
from dataclasses import dataclass

@dataclass(frozen=True)
class ProviderConfig:
    name: str
    model: str
    api_key: str
    base_url: str
    @property
    def configured(self) -> bool:
        return bool(self.api_key)

@dataclass(frozen=True)
class Settings:
    api_token: str
    mock_mode: bool
    free_mode: bool
    request_timeout_seconds: float
    min_agents: int
    min_consensus: float
    max_request_bytes: int
    rate_limit_per_minute: int
    host: str
    port: int
    providers: tuple[ProviderConfig, ...]

def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()

def load_settings() -> Settings:
    mock = _env("AI_FORGE_MOCK","false").lower() == "true"
    token = _env("AI_FORGE_API_TOKEN")
    if not token and not mock:
        raise RuntimeError("AI_FORGE_API_TOKEN must be configured")
    providers = (
        ProviderConfig("GPT", _env("OPENAI_MODEL","gpt-5.6"), _env("OPENAI_API_KEY"), "https://api.openai.com"),
        ProviderConfig("Gemini", _env("GEMINI_MODEL","gemini-2.5-pro"), _env("GEMINI_API_KEY"), "https://generativelanguage.googleapis.com"),
        ProviderConfig("DeepSeek", _env("DEEPSEEK_MODEL","deepseek-chat"), _env("DEEPSEEK_API_KEY"), "https://api.deepseek.com"),
        ProviderConfig("Grok", _env("XAI_MODEL","grok-4.7"), _env("XAI_API_KEY"), "https://api.x.ai"),
        ProviderConfig("Mistral", _env("MISTRAL_MODEL","mistral-large-latest"), _env("MISTRAL_API_KEY"), "https://api.mistral.ai"),
    )
    free = _env("AI_FORGE_FREE_MODE","true").lower() == "true"
    return Settings(
        api_token=token, mock_mode=mock, free_mode=free,
        request_timeout_seconds=float(_env("AI_FORGE_TIMEOUT_SECONDS","25")),
        min_agents=max(1,int(_env("AI_FORGE_MIN_AGENTS","3"))),
        min_consensus=min(1.0,max(0.5,float(_env("AI_FORGE_MIN_CONSENSUS","0.60")))),
        max_request_bytes=max(4096,int(_env("AI_FORGE_MAX_REQUEST_BYTES","65536"))),
        rate_limit_per_minute=max(1,int(_env("AI_FORGE_RATE_LIMIT","30"))),
        host=_env("AI_FORGE_HOST","0.0.0.0"), port=int(_env("AI_FORGE_PORT","8787")),
        providers=providers,
    )

def generate_token() -> str:
    return secrets.token_urlsafe(48)

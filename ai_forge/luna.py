from __future__ import annotations

from .models import LunaMessage
from .providers import Provider, ProviderError

FREE_PROVIDER = "FREE-CORE"
FREE_MODEL = "built-in-fallback"


def free_reply(message: str) -> str:
    text = message.strip()
    low = text.lower()
    if any(word in low for word in ("привет", "здравств", "hello", "hi")):
        return (
            "Привет. Я Luna внутри Williams. Сейчас активен FREE-режим без "
            "provider API key. Интерфейс и защищённый Core уже готовы; для "
            "полноценной LLM нужен настроенный провайдер на Core."
        )
    if "ключ" in low or "api" in low:
        return (
            "В APK provider-ключи вводить не нужно. Здесь используется только "
            "Core URL и Core API token. Ключи моделей должны оставаться на Core."
        )
    if "торг" in low or "бинанс" in low or "scanner" in low or "сканер" in low:
        return (
            "Торговый контур отделён от Luna. Я могу анализировать торговые "
            "сигналы и состояние системы, но разговорный FREE fallback не "
            "выдаёт BUY/SELL-команды и не выполняет сделки."
        )
    return (
        "Luna FREE fallback получил сообщение: "
        + text[:900]
        + "\n\nЭто безопасный режим без внешней LLM. После настройки "
          "провайдера на Core тот же APK автоматически переключается на "
          "реальную модель."
    )


class LunaService:
    def __init__(self, providers: list[Provider], free_mode: bool, timeout: float):
        self.providers = providers
        self.free_mode = free_mode
        self.timeout = timeout

    def respond(
        self,
        history: list[LunaMessage],
        message: str,
        context: dict[str, object] | None = None,
    ) -> tuple[str, str, str, str]:
        if self.free_mode and not self.providers:
            return free_reply(message), "FREE", FREE_PROVIDER, FREE_MODEL
        if not self.providers:
            raise RuntimeError("no AI providers configured")

        clean_history = [
            {"role": item.role, "content": item.content}
            for item in history[-20:]
        ]
        clean_message = message.strip()
        if context:
            clean_message += "\n\nApplication context (trusted client metadata):\n"
            clean_message += str(context)

        errors: list[str] = []
        for provider in self._ordered_providers():
            try:
                answer = provider.chat(clean_history, clean_message)
                if answer and answer.strip():
                    return (
                        answer.strip(),
                        "PROVIDER",
                        provider.cfg.name,
                        provider.cfg.model,
                    )
            except (ProviderError, Exception) as exc:
                errors.append(provider.cfg.name + ": " + str(exc)[:120])

        if self.free_mode:
            return free_reply(message), "FREE", FREE_PROVIDER, FREE_MODEL
        detail = "; ".join(errors[:3]) or "provider unavailable"
        raise RuntimeError("all configured AI providers unavailable: " + detail)

    def _ordered_providers(self) -> list[Provider]:
        priority = {"GPT": 0, "Gemini": 1, "DeepSeek": 2, "Grok": 3, "Mistral": 4}
        return sorted(
            self.providers,
            key=lambda provider: priority.get(provider.cfg.name, 99),
        )

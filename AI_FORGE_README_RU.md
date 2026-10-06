# AI-Forge Council Core

Пять независимых участников: GPT, Gemini, DeepSeek, Grok и Mistral. Provider API keys находятся только на Core-сервере.

Схема:
Android/Williams -> HTTPS -> AI-Forge Core -> 5 providers -> weighted council -> verified decision

Core не исполняет торговые операции и не получает Binance credentials. Для торговых задач PROCEED означает только аналитическое согласие продолжать работу и НЕ является BUY/SELL-командой.

## API

GET /health — health-check.
GET /v1/status — конфигурация провайдеров, Bearer token.
POST /v1/council — task + context + require_all.
POST /v1/luna — conversational chat from the Williams APK; provider keys stay on Core..

## Безопасность

- AI_FORGE_API_TOKEN обязателен вне mock-режима.
- Provider credentials никогда не возвращаются клиенту.
- APK не хранит и не принимает provider API keys для Luna.
- `AI_FORGE_FREE_MODE=true` позволяет запустить Luna без платных provider keys: работает встроенный FREE fallback; при наличии настроенного provider Luna автоматически использует его.
- Полные task/response не журналируются Core.
- Ошибки провайдеров наружу не прокидываются целиком.
- Есть лимит размера запроса и rate limit.
- Для OpenAI Responses API выставлен store=false.
- Docker запускается не от root и с read_only/no-new-privileges/cap_drop=ALL.

## CI

GitHub Actions запускает compile + pytest в mock-режиме и Docker smoke test без реальных ключей.

## Почему пока нет URL

GitHub хранит код и запускает CI, но repository/Actions не являются постоянным HTTP-сервером. Постоянный URL Core появляется после размещения контейнера на постоянно работающем host. Этот репозиторий уже содержит готовый контейнер и CI для автоматического deploy.


## Williams Android Luna

APK содержит отдельную вкладку **Luna**. Она подключается только к Core по HTTPS и передаёт историю диалога без provider credentials. Поле Core URL по умолчанию пустое, поэтому приложение больше не пытается обращаться к `YOUR-AI-FORGE-HOST`.

FREE-режим и полноценная LLM — разные вещи: без provider API key Core не может вызвать удалённую коммерческую модель. В этом случае интерфейс остаётся рабочим, а Core отвечает через безопасный встроенный fallback. При настройке хотя бы одного провайдера тот же APK переключается на реальную модель без изменения клиентской архитектуры.

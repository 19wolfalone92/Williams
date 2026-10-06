# AI-Forge Council Core

Пять независимых участников: GPT, Gemini, DeepSeek, Grok и Mistral. Provider API keys находятся только на Core-сервере.

Схема:
Android/Williams -> HTTPS -> AI-Forge Core -> 5 providers -> weighted council -> verified decision

Core не исполняет торговые операции и не получает Binance credentials. Для торговых задач PROCEED означает только аналитическое согласие продолжать работу и НЕ является BUY/SELL-командой.

## API

GET /health — health-check.
GET /v1/status — конфигурация провайдеров, Bearer token.
POST /v1/council — task + context + require_all.

## Безопасность

- AI_FORGE_API_TOKEN обязателен вне mock-режима.
- Provider credentials никогда не возвращаются клиенту.
- Полные task/response не журналируются Core.
- Ошибки провайдеров наружу не прокидываются целиком.
- Есть лимит размера запроса и rate limit.
- Для OpenAI Responses API выставлен store=false.
- Docker запускается не от root и с read_only/no-new-privileges/cap_drop=ALL.

## CI

GitHub Actions запускает compile + pytest в mock-режиме и Docker smoke test без реальных ключей.

## Почему пока нет URL

GitHub хранит код и запускает CI, но repository/Actions не являются постоянным HTTP-сервером. Постоянный URL Core появляется после размещения контейнера на постоянно работающем host. Этот репозиторий уже содержит готовый контейнер и CI для автоматического deploy.

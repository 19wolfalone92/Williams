# Исследовательский контракт AI V1

Реализован в `williams_ai_candidate_adapter.py`, offline CLI —
`tools/export_williams_ai_candidate_packet.py`. Это код границы Williams Bot;
репозиторий Williams-AI, его frozen source cards и прежние train/test не менялись.
Никакой моделью этот пример не сгенерирован: вызова Qwen/GPU/LoRA нет.

## Что проверяет контракт

| Объект | Версия и обязательные ограничения |
|---|---|
| Observation | `WILLIAMS_CLOSED_H1_OBSERVATION_V1`; symbol, `interval:1h`, целый `as_of_ms`, provenance, bars |
| Request | `WILLIAMS_AI_RESEARCH_REQUEST_V1`; точный PDF source ID/SHA/edition/role, snapshot четырёх файлов доказательств, observation SHA, выбранные правила и citation allowlist |
| Proposal | `WILLIAMS_AI_RESEARCH_PROPOSAL_V1`; SHA исходных request/observation, decision, rationale, citations, authority |
| Receipt | `WILLIAMS_AI_RESEARCH_RECEIPT_V1`; только `RESEARCH_ONLY_UNVERIFIED`, выполненные проверки структуры, неизменные запреты |

Все JSON-ключи перечислены в коде, неизвестные поля и версии отклоняются.
Duplicate keys, NaN/Infinity, в том числе переполнение `1e999`, отклоняются.
Числовые строки, bool вместо времени/цены и `0` вместо `false` не приводятся.

Observation содержит 45–2000 последовательных H1 OHLCV: UTC-aligned open_time_ms,
положительные конечные OHLC, корректный high/low, неотрицательный volume. Каждый
бар полностью закрыт к `as_of_ms`; последняя ожидаемая закрытая свеча присутствует.
Это инженерные размеры пакета, не новый книжный фильтр входа. Текущий wall clock
не проверяется: исторические и synthetic наблюдения допустимы для исследования,
но не становятся текущими торговыми данными. Все данные/метки происхождения
считаются неподтверждёнными внешним экспертом.

Provenance допускает только SYNTHETIC, HISTORICAL_SEEN,
PROSPECTIVE_UNREVIEWED. Нет EXPERT_GOLD, независимого экзамена или future outcome.
Формат одного бара:
`{open_time_ms, open, high, low, close, volume}`. Свечи с пропусками,
дубликатами, перестановками, ещё не закрытые и добавленные будущие данные запрещены.
Проверка не доказывает подлинность цен, источник рыночных данных или смысл графика.

Retrieval выполняется по явным rule IDs одного точного издания. Совпадение
семейства TC2 не разрешает подменить английский PDF русским. Каждая citation —
`{rule_id, source_id, source_sha256, pdf_page}` из allowlist. Автоматические ссылки
на рисунки V1 не принимает: для их причинного толкования нужна отдельная проверка.
Разрешено только правило с source-review receipt и соответствующими страницами.
Авторское утверждение, CANONICAL и INTERPRETED не означают независимую истинность.

Decisions: ABSTAIN, CONFLICT, NEEDS_EXPERT_REVIEW, LONG_CANDIDATE,
SHORT_CANDIDATE. Для направленных кандидатов **все процитированные правила**
должны происходить из PRIMARY_OPERATIONAL TC2 (TC1/NTD/Course остаются research-only),
быть CANONICAL/INTERPRETED, без конфликтов, с
`FULL_ATOMIC_SPEC_COMPLETE:true`. Сейчас87 из91 atomic specs неполны, включая операционные TC2;
четыре полных TC1 MFI specs остаются observer-only. Поэтому нынешние LONG/SHORT
отклоняются; приём объяснения ограничен abstain/review/conflict.
Psychological, discretionary, contradicted знания не могут обосновать направление.
Даже будущий валидный направленный кандидат останется неподтверждённым исследованием.

Authority всегда содержит ровно шесть literal false:
execution_authority, can_place_orders, can_cancel_orders, can_close_positions,
can_move_stops, training_allowed. Поля quantity/order_type/stop_price/confidence,
новые разрешения и автоматическое изменение весов не принимаются.

## Проверка и воспроизведение

```bash
python tools/export_williams_ai_candidate_packet.py build \
  --source-id TC2_EN --rule-id TC2.ALLIGATOR.MEDIAN_SMMA_DISPLAY \
  --observation build/observation.json --out build/ai-request.json
python tools/export_williams_ai_candidate_packet.py validate \
  --request build/ai-request.json --proposal build/ai-proposal.json \
  --out build/ai-receipt.json
pytest -q tests/test_williams_ai_candidate_adapter.py
```

Сохранить исходный request у вызывающей стороны; модель получает копию.
Перед проверкой adapter заново строит request по своему snapshot, а не доверяет
пересчитанному моделью SHA. После изменения доказательств создать новый adapter и
новый request. Хеши обеспечивают согласованность и обнаружение подмены относительно
этого snapshot; это не электронная подпись, аутентификация внешнего источника или
независимое подтверждение. Rationale остаётся непроверенным текстом; его команды
не выполняются и semantic entailment не сертифицируется.

`AI_CANDIDATE_ADAPTER_V1_SYNTHETIC_EXAMPLE.json` содержит полностью синтетические
50 свечей, вручную составленный ABSTAIN proposal и receipt. Это демонстрация
контракта, не chart gold, не модельный экзамен и не реальная сделка.

## Следующий допуск

Завершить атомарные спецификации и независимое чтение; подготовить отдельно
экспертные source/market cohorts; выполнить детерминированную проверку кандидатов
на причинных наблюдениях; только затем отдельным изменением рассматривать
интеграцию с моделью. Старые train/test и изученный исторический набор остаются
неизменными. Demo/device и execution gates сохраняют отдельный статус.

Хешизация определена Python helper `canonical_json` (sorted keys, compact UTF-8,
finite JSON). RFC8785/JCS или совпадение с произвольным JavaScript stringify
не заявляются; для внешней стороны authoritative request выдаёт CLI.

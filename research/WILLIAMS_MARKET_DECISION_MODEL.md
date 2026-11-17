# Модель проверяемого решения

Это описание существующей цепочки и требований к дальнейшему завершению,
а не заявление о внедрении нового торгового движка.

| Состояние | Что доказано | Что ещё не доказано |
|---|---|---|
| NO_SIGNAL | В обработанном снимке нет допустимой формации | Отсутствие будущих возможностей |
| FORMING | Часть структуры существует | Правые подтверждения/закрытие |
| CONFIRMED_SETUP | Авторские условия проверены на закрытых данных | Пробой, ордер или позиция |
| WAITING_TRIGGER | Задан действующий stop-entry уровень | Фактическое исполнение |
| ADMISSION_BLOCKED | Есть конкретный риск/сверка/защита blocker | Разрешение снять blocker |
| SUBMITTED | Отправлен запрос с durable intent | Fill без ответа биржи |
| UNKNOWN | Результат запроса неизвестен | Право повторить POST |
| FILLED | Биржа подтвердила исполнение | Доказанная защита без readback STOP |
| MANAGING | Позиция и защита подтверждены | Завершение старшего тренда |
| CANCELLED | Есть подтверждённая причина/состояние отмены | Возможность вернуть старый сигнал |

Минимальный паспорт: symbol, timeframe, snapshot close, Alligator context,
confirmed pivots, AO/AC values and changes, wave hypotheses with known_at,
TC2 rule ID, PDF SHA/page/figure, signal and confirmation bars, trigger,
planned STOP, position/risk budget, invalidation, decision and exact blocker.

Данные реализации: `CampaignSignalN`, `WilliamsSignalLifecycle`,
`h1ContextCampaignSignals`, `NativeFuturesCampaignCoordinator`,
`WilliamsBookCausalUnderstanding`, журнал сделок и signed STOP attribution.
Новая контрольная точка не объявляет полным покрытие всех объяснений.

Проверяемые численные примеры и ограничения сохранены в
`MFI_BEFORE_AFTER.json`; реальные архивные LONG/SHORT паспорта — в
`HISTORICAL_VALIDATION_RECEIPT.json`, если replay завершён. Архивные fills
являются моделью, не исполнениями Binance. Для отказа: растущий объём100→200
при диапазоне4→8 даёт равный MFI0.04, STABLE и отсутствие Squat, а не SELL.

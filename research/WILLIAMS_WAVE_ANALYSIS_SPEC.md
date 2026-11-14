# Причинная волновая разметка: границы существующей реализации

Исследуются `wave_engine.py`, `williams_wave_hierarchy.py`,
`WilliamsWaveHierarchy`, `WilliamsRecursiveWaveDegree`, momentum/subdivision
и source-interpretation модули. Полный повторный аудит этих модулей не завершён.

Подтверждённая текущая зависимость MFI: `strategy.calculate_indicators` ->
`squatting_bar` -> `WaveAnalyzer` terminal evidence / exhaustion components.
Поэтому исправление равенства/смены единиц выполнено у источника ошибки.
TC1 PDF45 прямо допускает Squat внутри тренда; он не доказывает W5 end.

Обязательная спецификация гипотезы:

```json
{
  "degree": "OBSERVED_FRAME_NOT_CERTIFIED_ELLIOTT_DEGREE",
  "anchors": [{"extreme_at": 0, "known_at": 0, "price": null}],
  "status": "PROVISIONAL",
  "alternatives": [],
  "invalidation": null,
  "future_extrema_used": false,
  "can_place_orders": false
}
```

Нули/null в схеме — типовая форма, не реальные рыночные данные.
В реальном объекте нужны реальные timestamps и конечные цены. При replay
использовать `known_at<=as_of`, а не только `extreme_at<=as_of`.
Один неизменный старший сценарий не является разрешением на H1 вход.

TC1 RU54: 100-140 баров относится к исследованию внутренней c в W4 при
подготовке W5. Course PDF44: zero crossing говорит о минимальной зрелости W4,
не о её конце. Численная двухфрактальная разметка не превращается сама собой
в экспертно подтверждённые1-5/A-B-C и вложенные степени.

Критерий завершения будущей проверки: каждый pivot со временем знания,
все альтернативы/инвалидации воспроизводимы по префиксу; независимые исходные
графики размечены до раскрытия будущего; результаты не подменяются валидностью
одних и тех же машинных grammar rules. Таких независимых gold меток пока нет.

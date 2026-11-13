# Изменения текущей исследовательской ветки

База: `c71e1541ab76488dabcaae291f8dfcee3404b9f3`, последний head PR239.
`main` на старте `32e9dd64fab9e0488ea4b566347246ac44627199`.
Сравнение показало181 commit ahead и2 behind main; это не основание слить цепочку
автоматически. Сохранены все838 исходных Git blobs, SHA каждого проверен.

Первый GitHub commit: `14af4b35ca38f0e63d7cb64993fa3001896874a6`.
[PR240](https://github.com/19wolfalone92/Williams/pull/240), draft, база PR239.

- `strategy.py`: строгие MFI знаки, per-bar proxy source, отклонение неконечных/
  неположительных данных и сравнения разных единиц. Исправление у источника
  вместо дополнительного veto поверх неверного Squat.
- `ci_release_gate.py`: обновлён контракт новой явной сигнатуры классификатора.
- `WilliamsTc2AoSourceProof.kt`, `StandaloneRuntime.kt`: индекс первого
  доступного AO comparison34; граница третьего бара36.
- `WilliamsBookCausalUnderstanding.kt`: same-colour AO bars вместо двусмысленных
  «трёх смен цвета». Реальных ордеров это объяснение не создаёт.
- `tests/test_master_mfi_causality.py`, Kotlin AoSourceProofTest: причинность,
  ties, bad volume, mixed units, numeric overflow, mirrored warmup.
- VERSION / Android / server: согласованы4.64.29. Signing identity не менялась.
- Исследовательские tools/регистры: отдельные acquisition/semantic/visual
  статусы; audit не позволяет объявить VERIFIED без независимой проверки.

Изменения обученных AI весов, пользовательских ключей, плеча,3% daily cap,
UTC reset, production/Demo аккаунта не выполнялись. Не заявлена приёмка всей
системы, полного первоисточника или новой торговой прибыльности.

# Williams Bot4.64.32: проверенный Android artifact

Проверяемый code commit: `4361a160aa19e1933ed57d6c31c5e256a2c76f45`.
Последующие source-review commits меняют исследовательские документы/receipts,
но не этот проверенный APK. Main не изменён.

## Локальная и GitHub проверка

Python:2590 passed,2 skipped. Kotlin actual business/coordinator/adapter
lifecycle и shared margin fixtures:140 tests passed,5 existing ignored.
Root static safety:0 failures,11 прежних проверенных warnings.
Master/translation integrity и release gate проходят; complete source gate
остаётся открытым и ожидаемо возвращает2.

Все7 GitHub workflow runs на code commit завершились success, включая
Android build и UI/lifecycle на API35 и36. Build run4885:
https://github.com/19wolfalone92/Williams/actions/runs/43584790086

Реальные Android JUnit XML:163 suites,1224 tests,0 failures,0 errors,
5 skipped, то есть1219 выполненных успешных проверок. Это не1224 passed.
API35 job132419459075 и API36 job132419459083 завершились success.

## Устанавливаемый debug APK

https://github.com/19wolfalone92/Williams/actions/runs/43584790086/artifacts/14110560858

Archive: `williams-bot-debug-apk-4885`, signed `app-debug.apk`,22,855,716 bytes.
VersionName4.64.32-debug, versionCode46432, package `com.williamsbot.debug`,
minSDK26, targetSDK36.

ZIP SHA256:
`1d92bc4f0427eec3010ee53a0ad17c2da1b4e754e0edbb32f376d980f1d2a27d`

APK SHA256, совпадает с файлом контрольной суммы внутри artifact:
`0f363dd5aecb12e4aeff5c307d4a9a763df9e062f81c3f502cabcf671f2b6567`

Независимая локальная проверка actual APK официальным apksig8.13.2:
verified=true, v2=true, errors=[]; signer SHA256 совпадает с pinned CI cert:
`c177bba354724c24e9668864720e9bfdb2cb545964702903a1fa306060912de9`.
Unsigned release artifact не предлагается как устанавливаемый debug APK.

## Что это подтверждает

Общий half-account margin gate, accounting liabilities, final source/time/
intent fences и bounded bulk margin reads проверены кодом и моделями CI.
Подробности: `ACCOUNT_HALF_MARGIN_IMPLEMENTATION_20261114.md`.

Actual Binance Demo LONG/SHORT fill→owned STOP readback→trail, сетевые
разрывы/unknown POST и физический телефон не проверены: credentials и
подключённого устройства в этой сессии нет. CI не заменяет такую приёмку.
Торговые приказы в этой сессии не отправлялись. Гэпы, funding и биржевое
изменение состояния после последнего GET остаются практическими границами.

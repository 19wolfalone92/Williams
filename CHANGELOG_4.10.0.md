# Williams Bot 4.10.0

## Что изменено
- Android onboarding: подключение VPS по QR `williams://connect?...`.
- Google Code Scanner для QR без собственного camera pipeline.
- Manual URL/token остаются как fallback в настройках.
- Dashboard показывает риск на сделку, лимит дневного убытка, сделки за день и серии убытков.
- Backend version 4.10.0.
- Williams strategy: Alligator 13/8/5, shifts 8/5/3, AO 5/34, AC 5, подтверждённый 2+2 fractal, фильтр пробоя, фильтр «проснувшегося» Alligator.
- No-domain deployment: отдельный compose без Caddy.
- Pairing helper `deploy/show_pairing.sh`.
- Installer автоматически пытается установить `qrencode` и создаёт pairing URI.
- TESTNET по умолчанию; LIVE требует явного `ALLOW_LIVE=true`.

## Важное
Полная Android-компиляция должна выполняться в Android Studio или GitHub Actions, где доступен Android SDK. Реальные Binance ордера не выполнялись без пользовательских Testnet credentials.

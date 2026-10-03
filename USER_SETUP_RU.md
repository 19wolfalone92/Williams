# Williams Bot 4.10.0 — что делать человеку без программирования

## 1. Создайте VPS

Нужен Ubuntu/Debian VPS с публичным интернетом. Домен покупать не нужно.

## 2. Установите проект

Скопируйте проект на VPS и выполните:

```bash
cd Williams_fixed/deploy
chmod +x *.sh
./install_no_domain.sh
```

При первом запуске Tailscale попросит один раз подтвердить устройство. Это единственный ручной шаг инфраструктуры, который нельзя безопасно выполнить без доступа к вашему аккаунту Tailscale.

После завершения установщик создаст URL, Mobile API Token и pairing URI/QR.

## 3. Получите APK

Самый простой способ — GitHub Actions:

1. Создайте private GitHub repository.
2. Загрузите содержимое `Williams_fixed` в корень репозитория.
3. Откройте `Actions` → `Android APK` → `Run workflow`.
4. Скачайте artifact `williams-bot-debug-apk`.
5. Установите `app-debug.apk` на Android.

Android Studio не обязательна.

## 4. Подключите APK

В приложении откройте `Настройки` → `Сканировать QR` и отсканируйте pairing QR, созданный установщиком VPS.

Если QR недоступен, можно вручную ввести Backend URL и Mobile API Token.

## 5. Binance

Сначала используйте Binance Testnet API-ключи. В приложении введите API Key и Secret и оставьте Testnet включённым.

После передачи ключи удаляются из полей приложения и хранятся на VPS в зашифрованном виде.

## 6. Первый запуск

Проверьте:
- `TESTNET`;
- WebSocket = LIVE;
- Binance = подключён;
- состояние = FLAT;
- нет `RECONCILE_REQUIRED`.

После этого нажмите START.

## 7. Переход к LIVE

Не включайте LIVE до завершения Testnet-прогона. Для LIVE сервер должен быть настроен с `ALLOW_LIVE=true`, а API-ключ Binance должен иметь только необходимые торговые разрешения и **не иметь права вывода средств**.

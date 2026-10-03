# APK — минимальный путь без программирования

Самый простой вариант для владельца проекта — **GitHub Actions**. В архиве уже есть workflow `.github/workflows/android.yml`.

## Что сделать

1. Создать приватный репозиторий GitHub.
2. Загрузить туда содержимое папки `Williams_fixed`.
3. Открыть вкладку **Actions**.
4. Выбрать **Android APK**.
5. Нажать **Run workflow**.
6. Дождаться окончания зелёной сборки.
7. Открыть результат workflow → **Artifacts** → `williams-bot-debug-apk`.
8. Скачать `app-debug.apk` и установить на Android.

Android Studio при этом не нужен: workflow сам устанавливает JDK, Android SDK и Build Tools и собирает APK.

## Почему сначала debug

Это самый беспроблемный способ получить первый устанавливаемый APK. Для production-release можно затем настроить отдельную release-подпись. Private signing key нельзя хранить в исходном архиве.

## Если нужен именно release APK

Создайте release keystore один раз на компьютере скриптом:

```bash
scripts/generate_release_keystore_auto.sh
```

Он генерирует `release-keystore.jks` и `keystore.properties`. Эти файлы **не отправляйте в GitHub** в открытом виде. Для CI их нужно хранить как GitHub Secrets/Actions secrets.

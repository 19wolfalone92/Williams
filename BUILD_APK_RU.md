# Как получить APK с минимальным вмешательством

## Вариант 1 — Android Studio

1. Установить Android Studio.
2. Распаковать архив.
3. Открыть папку `Williams_fixed`.
4. Дождаться Gradle Sync и установки Android SDK 35, если Android Studio его попросит.
5. Для быстрого теста: `Build → Build APK(s)`.
6. Для release можно запустить `scripts/generate_release_keystore_auto.sh`, затем `scripts/build_release_auto.sh`.

Скрипт **сам создаёт** release-keystore и случайный пароль. Пользователю не нужно придумывать пароль или вручную создавать ключ. Но после генерации обязательно сохраните `release-keystore.jks` и `keystore.properties`: это ключ будущих обновлений приложения.

## Вариант 2 — GitHub Actions

Workflow `.github/workflows/android.yml` собирает debug APK после запуска workflow вручную или после изменений Android-части. Готовый APK появляется в Artifacts.

Для release-сборки ключ нельзя безопасно хранить прямо в репозитории. Для постоянных обновлений используйте защищённые GitHub Secrets или Google Play App Signing.

## Что физически нужно от пользователя

Только установить Android Studio (или использовать GitHub Actions) и открыть проект. Сам signing key генерируется нашим скриптом автоматически.

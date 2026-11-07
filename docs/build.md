# Сборка и APK

| APK | Пакет | Назначение |
| --- | --- | --- |
| `max-station-voice.apk` | `org.yanalice.voice` | Голос, диктовка, будильник, панель и Spotify |
| `max-station-settings.apk` | `org.yanalice.settings.shortcuts` | Плитки настроек Android/сети и ярлыки клиентов |
| `max-station-local-backend.apk` | `org.yanalice.backend.service` | Диагностическая служба. Запускается отдельной командой на заданное время. Для ассистента не нужна |

Для сборки нужны JDK 17, Android SDK Platform 28, Build Tools 34.0.0, Bash, curl и shasum. `tools/build-apks.sh` проверяет закреплённые хеши Maven-зависимостей. Ключ для подписи APK создаётся владельцем сборки. Его файлы хранятся вне репозитория. `MAXSTATION_SIGN_ALIAS` по умолчанию `max-station`. Если пароль ключа отличается от пароля хранилища, задайте `MAXSTATION_SIGN_KEY_PASSWORD_FILE`.

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export MAXSTATION_SIGN_KEYSTORE=/private/path/max-station.p12
export MAXSTATION_SIGN_PASSWORD_FILE=/private/path/password-file
./tools/build-apks.sh
(cd out && shasum -a 256 -c SHA256SUMS.txt)
```

Открытые APK подписаны отдельным релизным сертификатом. Обновление поверх локального прототипа или сборки с другой подписью Android отклонит. Удаление старого APK может стереть профиль и ключи. Не переустанавливайте настроенный экземпляр без отдельного плана сохранения данных.

Обработчик пульта собирается для ARM64 Linux. Сборка проверена с Zig 0.15.2. Установите `zig` в PATH или укажите его путь через `ZIG`.

```sh
./tools/build-remote.sh
```

Исходники в репозитории распределены по `voice-input/`, `settings-shortcuts/`, `remote/` и `local-backend/`. Отдельные OEM APK, образы, компоненты с извлечёнными ресурсами, сторонние клиенты и персональные данные в релиз не входят. Первичная настройка, получение root и сетевые правила описаны отдельно. Скриншоты сделаны на рабочей колонке. В релизной версии у плитки ассистента другая иконка.

SHA-256 сертификата публичных APK

```text
53b877ccd2dabe83eef21b6e1dcff5d24629e920a76db89a194ea2d3ee0241e9
```

Для проверки используйте `apksigner verify --min-sdk-version 28 --print-certs APK`. Хеш сертификата и хеш файла проверяют разные вещи. Android использует сертификат для совместимости обновлений.

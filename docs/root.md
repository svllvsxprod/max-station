# Как мы получили root

На телефоне AliceBridge помогла установить нужную прошивку. Дальше мы подключились к колонке с компьютера через ADB и запустили два готовых файла из AliceBridge. Первый, `root-bridge`, открыл сеанс с правами root. Второй, `permissive`, отдельно переключил SELinux в режим Permissive. Root и состояние SELinux проверяли независимо. Разделы `boot` и `system` для этого шага мы не перепрошивали.

## Проверьте, что модель и сборка совпадают

Нужна Станция Макс первого поколения **YNDX-0008 без Zigbee**. У нашей колонки модель Android отображалась как `yandexstation_2`. Подключите её по USB, подтвердите доступ ADB и оставьте в сети без интернета. Если подключено несколько Android-устройств, добавляйте `-s СЕРИЙНЫЙ_НОМЕР` после `adb` в каждой команде.

```sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.sdk
adb shell uname -m
adb shell uname -r
adb shell cat /proc/sys/kernel/random/boot_id
```

Мы получили `PPR1.180610.011.5000000921`, SDK `28` (Android 9), `aarch64` и ядро `4.9.113`. Если модель, сборка, SDK, архитектура или версия ядра отличаются, остановитесь здесь. Последняя команда показывает идентификатор текущей загрузки. Он меняется при перезапуске.

## Возьмите два файла из AliceBridge 1.0.21

Скачайте [root-bridge](https://github.com/Yandex-Station-Universal-Repo/AliceBridge_APP/blob/v1.0.21/v2/station/src/main/assets/max/root-bridge) и [permissive](https://github.com/Yandex-Station-Universal-Repo/AliceBridge_APP/blob/v1.0.21/v2/station/src/main/assets/max/permissive) через кнопку скачивания исходного файла. Затем в папке с этими файлами запустите `shasum -a 256 root-bridge permissive` и сравните вывод.

| Файл | SHA-256 |
| --- | --- |
| `root-bridge` | `f53c3e127a95b8f78b0934ccea73382964d35fd7b3dd21b8b0ce258f50704474` |
| `permissive` | `53d9aa327156066c994ae18e842619adbf9072e14b7ab12b8deb2c9e3b7baa74` |

Авторы AliceBridge проверяют те же значения перед запуском. [Проверка совместимости в MaxPlan](https://github.com/Yandex-Station-Universal-Repo/AliceBridge_APP/blob/v1.0.21/v2/station/src/main/java/dev/alicebridge/station/MaxPlan.java).

## Откройте временный сеанс root

Перед запуском проверьте `adb shell pidof root-bridge`. Если команда вернула PID или вы уже запускали инструмент в этой загрузке, второй экземпляр не запускайте. Когда окно закрыли или появилась ошибка, сначала разберитесь с состоянием сеанса. Для новой попытки используйте обычный перезапуск и проверьте новый идентификатор загрузки.

```sh
adb shell mkdir -p /data/local/tmp/alicebridge-max
adb push root-bridge /data/local/tmp/alicebridge-max/root-bridge
adb push permissive /data/local/tmp/alicebridge-max/permissive
adb shell sha256sum /data/local/tmp/alicebridge-max/root-bridge /data/local/tmp/alicebridge-max/permissive
adb shell chmod 755 /data/local/tmp/alicebridge-max/root-bridge /data/local/tmp/alicebridge-max/permissive
```

Снова сравните хеши. Если оба совпали, в обычном терминале с открытым вводом запустите команду ниже. Оставьте это окно открытым.

```sh
adb shell -T /data/local/tmp/alicebridge-max/root-bridge esc-bridge
```

Дождитесь строки `bridge: worker READY`. В открытом сеансе root-bridge выполните две встроенные команды:

```text
\cat /proc/self/status
\cat /proc/self/attr/current
```

В первой проверьте строку `Uid:` с четырьмя нулями. Во второй ожидается контекст `u:r:init:s0`. В обычном втором окне `adb shell id` при этом может по-прежнему показывать пользователя `shell`. Это другой сеанс. Исходник AliceBridge также проверяет строку `CapEff` перед тем, как продолжить. [Последовательность проверок в MaxSetup](https://github.com/Yandex-Station-Universal-Repo/AliceBridge_APP/blob/v1.0.21/v2/station/src/main/java/dev/alicebridge/station/MaxSetup.java).

## Отдельно проверьте SELinux

Для дальнейших работ на нашем экземпляре понадобился временный режим Permissive. В другом окне сначала запустите проверку без переключения:

```sh
adb shell /data/local/tmp/alicebridge-max/permissive esc-permissive check
```

Если вывод содержит `RESULT: PERMISSIVE CHECK OK value=1`, инструмент подтвердил совместимость на этой загрузке. Тогда запустите переключение и проверьте результат:

```sh
adb shell /data/local/tmp/alicebridge-max/permissive esc-permissive
adb shell getenforce
adb shell cat /sys/fs/selinux/enforce
```

Мы получили `Permissive` и `0`. Если результат другой, остановитесь. Пока SELinux ослаблен, оставьте колонку в сети без интернета.

Позже мы запускали из подтверждённого сеанса свой временный процесс для обслуживания. Он оставался с UID 0 после закрытия root-bridge. На настроенной колонке отдельный автозапуск смог заново получить временные права и восстановить настройки примерно через три минуты после перезапуска. Без такой настройки сам сеанс root и Permissive после перезапуска не сохраняются. APK из этого репозитория не устанавливают автополучение root. Эта процедура также не подтверждает разблокировку загрузчика или возможность записать изменённый системный образ.

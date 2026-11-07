# Какие пакеты убраны и что сохранено

Инвентаризация относится к Android user 0 на сборке 5000000921. После очистки работали Bluetooth-звук, громкость телефоном и кольцом, пульт и новый Home. В Android не осталось аккаунта Яндекса и компонента Device Owner. Штатный мастер и службы, участвовавшие в проверках подписки, больше не работают в этом профиле. Локальный функционал проверен без подписки. Серверный статус устройства не менялся, доступ к платному контенту это не предоставляет.

## Удалены из user 0

| Назначение | Идентификаторы |
| --- | --- |
| Обновлятор и мастер первоначальной настройки | `com.yandex.launcher.updaterapp`, `com.yandex.tv.setupwizard` |
| Platform Services, бывший Device Owner, и TV Services | `com.yandex.tv.services.platform`, `com.yandex.tv.services` |
| Алиса, штатный Home, клавиатура и заставка | `com.yandex.tv.alice`, `com.yandex.tv.home`, `com.yandex.tv.keyboard`, `com.yandex.tv.daydream` |
| Магазин, оплаты, реклама и отчёты | `com.yandex.devices.appstore`, `com.yandex.devices.payment`, `com.yandex.android.advid`, `com.yandex.tv.bugreportsender` |
| Музыка, эфир и источник эфира | `com.yandex.tv.music`, `com.yandex.tv.live`, `com.yandex.tv.input.efir` |
| Плееры | `com.yandex.tv.videoplayer`, `com.yandex.tv.webplayer`, `com.yandex.tv.ytplayer`, `com.yandex.devices.player` |
| Другие штатные пакеты | `com.yandex.devices.farm.companion`, `com.yandex.prebuiltstub`, `com.yandex.tv.remotechecker` |

## Оставлены в user 0

| Пакет | Почему |
| --- | --- |
| `com.yandex.io.sdk` | В рабочем профиле сохранена локальная аппаратная цепочка. Собственная реализация давала Bluetooth-звук, но неполную индикацию и сбои регулировки громкости. Штатный компонент оставили под сетевым запретом. |
| `ru.yandex.bleremotecontrol` | Сопряжение и работа штатного BLE-пульта, включая микрофон. |
| `com.yandex.tv.settings` | Настройки, связанные с устройством, включая HDMI и звук. Пакет возвращён после пробы удаления. Отдельный ярлык системного `com.android.tv.settings` сосуществует с ним. |
| `com.yandex.tv.services.privileged` | Оставлен консервативно из-за привилегированных локальных интеграций и общего системного UID. Безопасность удаления не подтверждена. |
| `com.yandex.server.SystemExtensionService` | Оставлен как штатное системное расширение. Безопасность удаления не подтверждена. |
| `com.yandex.tv.vendor.config` | Оставлен как поставщик конфигурации прошивки. Безопасность удаления не подтверждена. |

Сетевые ограничения для оставленных пакетов проверяйте отдельно, в том числе для общего системного UID. Не переносите числовые UID между экземплярами. [Сетевая политика](network.md).

## Как повторять и проверять

```sh
adb shell pm list packages --user 0 -U
adb shell pm list packages -u --user 0
adb shell dumpsys device_policy
adb shell pm path com.yandex.tv.settings
adb shell dumpsys package com.yandex.launcher.updaterapp
```

Перед изменениями сохраните инвентаризацию и личные данные, которые можно восстановить штатно. Пакеты разбирайте по группам. Сначала включите новый лаунчер и клавиатуру, сохраните доступ к обоим меню настроек и убедитесь, что USB ADB переживает смену Home. Снимите Device Owner только через доступный владельцу документированный способ и проверьте состояние снова. Если прошивка оставляет активного владельца и блокирует удаление, считайте это условием остановки для этой ветки. Android Device Owner и временный root являются разными полномочиями.

Тестовое отключение отдельного пакета при достаточных полномочиях выполняется `adb shell pm disable-user --user 0 PACKAGE`. После проверки соответствующей функции `adb shell pm uninstall --user 0 PACKAGE` удаляет пакет для текущего пользователя. Подставляйте один проверенный идентификатор. После каждой группы проверяйте Home, HDMI/звук, пульт и свежее Bluetooth-подключение. Удалять IoSDK, BLE-пульт, TV Settings или системные Bluetooth/Media службы для этой конфигурации не требуется.

Системные APK остались в защищённых разделах, поэтому `pm list packages -u` может их показывать. Для штатного пакета `adb shell pm install-existing --user 0 PACKAGE` восстанавливает доступность, если образ содержит его. Данные и настройки это не возвращает. Для ранее отключённого пакета используйте `adb shell pm enable --user 0 PACKAGE`. После сброса очистку user 0 и сетевой профиль нужно проверять заново.

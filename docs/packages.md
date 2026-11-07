# Какие пакеты убраны и что сохранено

Этот список мы сверили на сборке 5000000921 для основного пользователя Android (user 0). После очистки проверили Bluetooth, регулировку громкости с телефона и колеса, пульт и новый главный экран. Всё это работало без аккаунта и подписки Яндекса. Мы убрали мастер настройки и службы, участвовавшие в проверке подписки, а также сняли права владельца устройства (Device Owner).

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
| `com.yandex.io.sdk` | При пробной замене Bluetooth-звук был, но индикация работала не полностью и громкость менялась с ошибками. Поэтому оставили штатный IoSDK и закрыли ему выход в интернет. |
| `ru.yandex.bleremotecontrol` | Сопряжение и работа штатного BLE-пульта, включая микрофон. |
| `com.yandex.tv.settings` | Настройки, связанные с устройством, включая HDMI и звук. Пакет возвращён после пробы удаления. Отдельный ярлык системного `com.android.tv.settings` сосуществует с ним. |
| `com.yandex.tv.services.privileged` | Привилегированные ТВ-службы с общим системным UID. Мы оставили их, потому что не проверили, какие функции пропадут после удаления. |
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

Перед изменениями сохраните инвентаризацию и личные данные, которые можно восстановить штатно. Пакеты разбирайте по группам. Сначала включите новый лаунчер и клавиатуру. Проверьте оба меню настроек и убедитесь, что ADB по USB всё ещё работает. Снимите Device Owner только через доступный владельцу документированный способ и проверьте состояние снова. Если прошивка блокирует удаление пакета, пока у него остаются права владельца устройства, остановитесь на этом шаге. Android Device Owner и временный root являются разными полномочиями.

Тестовое отключение отдельного пакета при достаточных полномочиях выполняется `adb shell pm disable-user --user 0 PACKAGE`. После проверки соответствующей функции `adb shell pm uninstall --user 0 PACKAGE` удаляет пакет для текущего пользователя. Подставляйте один проверенный идентификатор. После каждой группы проверяйте Home, HDMI/звук, пульт и свежее Bluetooth-подключение. Удалять IoSDK, BLE-пульт, TV Settings или системные Bluetooth/Media службы для этой конфигурации не требуется.

Системные APK остались в защищённых разделах, поэтому `pm list packages -u` может их показывать. Для штатного пакета `adb shell pm install-existing --user 0 PACKAGE` восстанавливает доступность, если образ содержит его. Данные и настройки это не возвращает. Для ранее отключённого пакета используйте `adb shell pm enable --user 0 PACKAGE`. После сброса очистку user 0 и сетевой профиль нужно проверять заново.

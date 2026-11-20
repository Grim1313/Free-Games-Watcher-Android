# Free Games Watcher для Android

Приложение ищет временные раздачи игр для ПК в Steam, Epic Games Store и GOG. Оно показывает срок акции и открывает страницу, где игру можно забрать в своём аккаунте. Поддерживаются Android 8.0 и новее, русский и английский языки.

[Скачать APK / Download APK](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/download/v0.3.0-beta.1/Free-Games-Watcher-Android-0.3.0-beta.1.apk) · [Релиз 0.3.0-beta.1](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/tag/v0.3.0-beta.1) · [Добавить в Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Grim1313/Free-Games-Watcher-Android)

## Установка и обновления

- **Прямая установка.** Откройте APK на телефоне и по запросу Android разрешите браузеру или файловому менеджеру устанавливать приложения из этого источника.
- **Obtainium.** Добавьте ссылку на этот репозиторий как источник GitHub. Включите **Include prereleases** («Включать предварительные выпуски»), чтобы получать бета-версии. Для прямой установки и Obtainium используется один APK.
- **Переход с локальной версии.** Публичная бета устанавливается рядом с debug-пакетом. История и отметки в этих приложениях хранятся отдельно. У локального release-кандидата с тестовой подписью тот же ID, что и у публичной беты. Android потребует сначала удалить тестовый кандидат, при этом его локальные данные будут удалены.

Пакет — `bz.fgw.android`. SHA-256 сертификата публичных выпусков — `9c72573f906e65ed68ff8602f39a11ba800bd574a3512ae1625e9aa16b6c4938`. Контрольная сумма конкретного APK прилагается к выпуску.

**English.** Free Games Watcher checks Steam, Epic Games Store and GOG for limited-time free-to-keep PC games. Install the APK directly or add this repository to Obtainium and turn on **Include prereleases**. Requires Android 8.0 or later.

## Использование

Нажмите «Проверить сейчас», чтобы обновить ленту. Для каждого магазина видны результат и время проверки. При сбое доступны сохранённые карточки. В истории можно поставить или снять отметку «Уже забрал». Она относится к игре и сохраняется при повторной раздаче. Язык интерфейса (русский или английский) и страна каталога выбираются независимо.

Фоновый интервал по умолчанию — три часа. В настройках доступны интервалы 1, 3, 6 и 12 часов. Без выбранных магазинов расписание отключается. Новые раздачи и напоминания имеют отдельные каналы уведомлений. Напоминание возможно при проверке в последние 24 часа известной акции. Тихие часы учитывают местное время устройства. Нажатие открывает страницу получения в магазине, для раздач GOG на главной — главную GOG.

Steam использует два поиска, резервную витрину и региональные варианты покупки StoreBrowse. Epic использует недельную ленту и каталог Browse. При неполном ответе Browse приложение обращается к стороннему EGData, которому видны IP-адрес, страна и параметры поиска. GOG проверяется по публичным разделам главной и каталогу. Обложки загружаются при показе карточек. Настройки, история и отметки хранятся на устройстве.

## Сборка из исходников

Откройте корень проекта в Android Studio с установленными Android SDK 37 и Build Tools 36.0.0. Для запуска из PowerShell укажите в текущем сеансе пути к JDK и SDK своей установки. Для стандартных путей Android Studio на Windows подойдут следующие команды.

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" -e install -r app\build\outputs\apk\debug\app-debug.apk
```

Инструментальные тесты запускаются командой `.\gradlew.bat :app:connectedDebugAndroidTest` при подключённом тестовом устройстве. Устанавливаемый пакет для разработки — `bz.fgw.android.debug`, минимальная версия — Android 8.0.

[План разработки](docs/PLAN.ru.md) · [Результаты проверок](docs/TESTING-AND-RELEASE.ru.md) · [Лицензия MIT](LICENSE)

# Free Games Watcher for Android
![GitHub Release](https://img.shields.io/github/v/release/Grim1313/Free-Games-Watcher-Android)
![GitHub Release Date](https://img.shields.io/github/release-date/Grim1313/Free-Games-Watcher-Android)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/Grim1313/Free-Games-Watcher-Android/total)

[Русский](README.ru.md)

Free Games Watcher finds limited-time PC game giveaways on Steam, the Epic Games Store and GOG. Each offer shows the deadline when available and links to the store page where you can claim the game. The app runs on Android 8.0 or later and supports English and Russian.

[Download the APK](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/download/v1.0.2/Free-Games-Watcher-Android-1.0.2.apk) · [Release notes for 1.0.2](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/tag/v1.0.2)

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22bz.fgw.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGrim1313%2FFree-Games-Watcher-Android%22%2C%22author%22%3A%22Grim1313%22%2C%22name%22%3A%22Free%20Games%20Watcher%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%7D%22%7D"><img src="docs/assets/badge_obtainium.png" width="161" height="48" alt="Get it on Obtainium"></a>

## Installation and updates

- **Direct download.** Open the APK on your phone. If Android prompts you, allow your browser or file manager to install apps from this source.
- **Obtainium.** Tap the badge above and confirm the import to track stable releases.

The package name is `bz.fgw.android`. The SHA-256 fingerprint of the release signing certificate is `9c72573f906e65ed68ff8602f39a11ba800bd574a3512ae1625e9aa16b6c4938`. Each release includes a SHA-256 checksum for its APK.

## Using the app

Tap the **refresh icon** to refresh the regional store listings. The **Giveaways** tab shows confirmed offers. **Upcoming** lists announced Epic and GOG promotions with their start dates. An upcoming offer moves to Giveaways after the store confirms it is available. Open **Settings → Source diagnostics** for the result, time and request count of each store’s last check.

Offers are grouped by store. Tap the title for details or the green store button to claim the offer. Cards include the original price, Steam positive-review percentage, Epic and GOG player ratings out of five, review count, genres and supported PC platforms when available. Search by title or store ID, select a store, or sort within each store by deadline, discovery time, Steam rating or title. Choose a system, light or black OLED theme. Compact view hides images. You can change the catalog country independently of the interface language.

- **Personal marks.** Mark a game as claimed, ignore it until restored, hide the current giveaway or snooze alerts for 24 hours. Use **Undo** immediately or manage marks later in Settings. Opening an offer clears its unread dot. Choose **Mark all as read** in appearance settings to clear the remaining indicators.
- **Settings and Help.** Search Settings for filters, alerts, background checks, appearance, personal marks, data transfer, history or diagnostics. Help explains the icons on each offer.
- **Filters.** Choose content types, genres and operating systems, exclude title keywords or Steam IDs, and set Steam review thresholds. Genre and platform filters require matching metadata. DLC, soundtracks, tools and demos are excluded by default.
- **History and transfer.** Search saved offers offline. In Settings, choose how long to keep history or export settings and personal marks as JSON with optional history. You can import backups from the browser extension and preview the country, marks and history before confirming. Include history to transfer campaign-specific hiding between devices. Imported offers need store confirmation before appearing in the active feed.
- **Notifications.** Background checks run every three hours by default, with intervals from 15 minutes to 24 hours. Android can delay a scheduled check. Set quiet hours, expiry reminders (1–72 hours before a known deadline) and repeat alerts. New installations use a three-hour reminder window. Updates preserve your existing reminder window. Pause tracking to keep your store selection for later.

## Data sources and privacy

Steam checks use two searches, a fallback storefront listing and regional purchase options from StoreBrowse. Epic checks use its weekly feed and Browse catalog. The app requests player ratings from Epic’s ratings API. If the API supplies no score, it queries the third-party EGData service using the offer ID. EGData also receives the catalog country and search parameters when Browse returns incomplete results. Both requests disclose your IP address. GOG checks use public homepage sections and the catalog. Screenshots or store artwork load when you view an offer. Your settings, history and claimed games stay on your device.

## Build from source

Open the project in Android Studio with Android SDK 37 and Build Tools 36.0.0 installed. On Windows, the following PowerShell commands use Android Studio’s bundled JDK and the default Android SDK location. Adjust the paths if your installation differs.

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" -e install -r app\build\outputs\apk\debug\app-debug.apk
```

To run the instrumented tests, connect a test device or start an emulator and run `.\gradlew.bat :app:connectedDebugAndroidTest`. The debug package name is `bz.fgw.android.debug`.

Store logos are from [Simple Icons](https://github.com/simple-icons/simple-icons/tree/14.15.0/icons).

[MIT license](LICENSE)

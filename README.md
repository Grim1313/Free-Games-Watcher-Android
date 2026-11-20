# Free Games Watcher for Android

[Русский](README.ru.md)

Free Games Watcher finds limited-time PC game giveaways on Steam, the Epic Games Store and GOG. Each offer shows the deadline when available and links to the store page where you can claim the game. The app runs on Android 8.0 or later and supports English and Russian.

[Download the APK](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/download/v0.4.0-beta1/Free-Games-Watcher-Android-0.4.0-beta1.apk) · [Release notes for 0.4.0-beta1](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/tag/v0.4.0-beta1) · [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Grim1313/Free-Games-Watcher-Android)

## Installation and updates

- **Direct download.** Open the APK on your phone. If Android prompts you, allow your browser or file manager to install apps from this source.
- **Obtainium.** Add this GitHub repository as a source and enable **Include prereleases** to receive beta versions. Obtainium installs the same APK as the direct download.

The package name is `bz.fgw.android`. The SHA-256 fingerprint of the release signing certificate is `9c72573f906e65ed68ff8602f39a11ba800bd574a3512ae1625e9aa16b6c4938`. Each release includes a SHA-256 checksum for its APK.

## Using the app

Tap **Check now** to refresh the regional store listings. The **Giveaways** tab shows confirmed offers. **Upcoming** lists announced Epic promotions with their start dates. An upcoming offer moves to Giveaways after the store confirms it is available. Settings shows the result, time and request count of the last check for each store.

Cards include the original price, Steam positive-review percentage, GOG rating out of five, review count, genres and supported PC platforms when available. Search by title or store ID, select a store, or sort by deadline, discovery time, rating or title. Compact view hides the covers. You can change the catalog country independently of the interface language.

- **Personal marks.** Mark a game as claimed, ignore it until restored, hide the current giveaway or snooze alerts for 24 hours. Manage marks in Settings. Use the New label or **Mark all as read** to clear unread indicators.
- **Filters.** Choose content types, genres and operating systems, exclude title keywords or Steam IDs, and set Steam review thresholds. Genre and platform filters require matching metadata. DLC, soundtracks, tools and demos are excluded by default.
- **History and transfer.** Search saved offers offline. In Settings, choose how long to keep history or export settings and personal marks as JSON with optional history. You can import backups from the browser extension and preview the country, marks and history before confirming. Include history to transfer campaign-specific hiding between devices. Imported offers need store confirmation before appearing in the active feed.
- **Notifications.** Background checks run every three hours by default, with intervals from 15 minutes to 24 hours. Android can delay a scheduled check. Set quiet hours, expiry reminders (1–72 hours before a known deadline) and repeat alerts. New installations use a three-hour reminder window. Updating from the previous beta keeps the 24-hour window. Pause tracking to keep your store selection for later.

## Data sources and privacy

Steam checks use two searches, a fallback storefront listing and regional purchase options from StoreBrowse. Epic checks use its weekly feed and Browse catalog. If Browse returns an incomplete result, the app queries the third-party EGData service, which receives your IP address, catalog country and search parameters. GOG checks use public homepage sections and the catalog. Cover images load when you view an offer. Your settings, history and claimed games stay on your device.

## Build from source

Open the project in Android Studio with Android SDK 37 and Build Tools 36.0.0 installed. On Windows, the following PowerShell commands use Android Studio’s bundled JDK and the default Android SDK location. Adjust the paths if your installation differs.

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" -e install -r app\build\outputs\apk\debug\app-debug.apk
```

To run the instrumented tests, connect a test device or start an emulator and run `.\gradlew.bat :app:connectedDebugAndroidTest`. The debug package name is `bz.fgw.android.debug`.

[MIT license](LICENSE)

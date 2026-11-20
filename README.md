# Free Games Watcher for Android

[Русский](README.ru.md)

Free Games Watcher finds limited-time PC game giveaways on Steam, the Epic Games Store and GOG. Each offer shows the deadline when available and links to the store page where you can claim the game. The app runs on Android 8.0 or later and supports English and Russian.

[Download the APK](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/download/v0.3.0-beta.1/Free-Games-Watcher-Android-0.3.0-beta.1.apk) · [Release notes for 0.3.0-beta.1](https://github.com/Grim1313/Free-Games-Watcher-Android/releases/tag/v0.3.0-beta.1) · [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Grim1313/Free-Games-Watcher-Android)

## Installation and updates

- **Direct download.** Open the APK on your phone. If Android prompts you, allow your browser or file manager to install apps from this source.
- **Obtainium.** Add this GitHub repository as a source and enable **Include prereleases** to receive beta versions. Obtainium installs the same APK as the direct download.

The package name is `bz.fgw.android`. The SHA-256 fingerprint of the release signing certificate is `9c72573f906e65ed68ff8602f39a11ba800bd574a3512ae1625e9aa16b6c4938`. Each release includes a SHA-256 checksum for its APK.

## Using the app

Tap **Check now** to refresh the feed. Each store shows the result and time of its last check. Saved offers remain available offline. In **History**, you can mark a game as **Claimed** or undo that choice. The mark stays with the game if it goes on offer again. You can choose the interface language and the store catalog country independently.

Background checks run every three hours by default. You can choose an interval of 1, 3, 6 or 12 hours in Settings. Turning off all stores also turns off scheduled checks. New giveaways and expiry reminders use separate notification channels. When an offer has a known deadline, the app can send one reminder during a check in its final 24 hours. Quiet hours follow your device’s local time. Tap a notification to open the claim page. For GOG homepage giveaways, this opens the GOG homepage.

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

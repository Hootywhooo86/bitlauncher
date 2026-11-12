# bitlauncher for Android

A pixel home screen that stays out of the way. Native Kotlin and plain Android
views: no UI toolkit, no libraries, no network. The release APK is about 85 KB.

## What's in it

| | |
| --- | --- |
| **Home grid** | Pages of apps, folders and widgets. Three sizes: **4x5, 5x6, 6x7** (Settings). Switching size never loses anything: what doesn't fit moves to the next free spot, or a new page. |
| **Dock** | One slot per column (up to 6). On first run it holds the phone's own dialer, messages, browser and camera. |
| **App drawer** | Swipe up. Every app A–Z, search on top. The keyboard's Go opens the first match. Swipe down at the top to close. |
| **Search** | Ignores case and accents; names that start with what you typed come first. Optional: keyboard opens with the drawer. |
| **Long-press an app** | The app's own shortcuts (when bitlauncher is your home app), Add to home, Remove, Hide, App info, Uninstall. Keep holding and move to drag. |
| **Folders** | Drop one app on another. Tap the name to rename. A folder with one app left turns back into that app. |
| **Widgets** | Long-press empty space → Widgets. Long-press a widget to resize (step by step) or remove; hold and move to drag. |
| **Wallpaper** | Uses the phone's wallpaper. Long-press empty space → Wallpaper. |
| **Gestures** | Swipe up: drawer. Swipe down: notifications. Home button: closes whatever is open, then back to page one. |
| **Settings** | Grid, icon size, labels, accent colour (free), hidden apps, backup and restore, set as home app. |
| **Pixel clock** | Hand-drawn 5x7 block digits and the date above the grid (Settings → Home clock). Tap it for your alarms. |
| **Notification dots** | A small accent square on any app, or folder, with something new. Needs notification access. |
| **Pixel lock screen** | Off until you turn it on. Block clock with a blinking colon, the date, and notifications, in front of the phone's own lock screen. Swipe up (it follows your finger a block at a time) and the phone's own PIN, pattern or fingerprint takes over; unlocking with the fingerprint sensor closes it too. It never shows during a call. |
| **Pixel motion** | Everything moves in chunky steps: the drawer slides up in four, folders and menus pop, icons sink a pixel when pressed, a dropped icon hops, the new page dot puffs, a changed clock digit drops in. Off in Settings, and off whenever the phone's "remove animations" is on. |
| **Backup** | Layout and settings to a JSON file you choose. Every save on the phone is atomic and keeps the previous copy. |

Every icon is pixelated once (32x32, alpha snapped on/off, 8 levels per colour,
a 1px outline) and cached on disk. Sprites are drawn at whole-number scales
with no filtering, so every pixel is the same size.

### Lock screen privacy

Android doesn't let any app replace the secure lock screen, so bitlauncher shows
its page in front of it and hands unlocking to the phone. What notifications show:

- **App names** (default), **titles**, or **full text**, your choice.
- A notification the app marks **secret** never shows, not even its app.
- A **private** one shows only the public version the app provides, or just its app.
- If the phone is set to hide sensitive lock-screen content, only app names show.
- Notifications stay in memory on the phone. Nothing is saved or sent.

If the phone blocks the lock screen from opening (some do for anything that isn't
the home app), Settings says so.

Not in this version: pinning shortcuts that apps offer
("Add to home screen" from inside an app), icon packs, double-tap to lock.

## Build

```bash
cd android
./gradlew testDebugUnitTest   # layout rules + Robolectric smoke tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (platform 35). CI builds a debug APK on every
push (Actions → Android APK → artifacts).

## Signing

Releases must all be signed with the same key, or Android won't install one as
an update over another. Bumping `versionName` in `app/build.gradle.kts`
publishes a signed GitHub Release once these repo secrets exist:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Make a key once with
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias bitlauncher`
and keep it somewhere safe. Never commit it.

## Code

| File | What |
| --- | --- |
| `Layout.kt` | Grid sizes, items, placement, resize reflow, JSON. Pure Kotlin, unit tested. |
| `LayoutStore.kt` | Atomic save with a previous copy; backup format. |
| `AppList.kt` | Every launchable app across personal and work profiles; updates on install/uninstall. |
| `IconCache.kt` | The pixelator and its memory + disk cache. |
| `LauncherActivity.kt` | Home: pages, dock, drawer, folders, menus, drag and drop, widgets, gestures. |
| `Workspace.kt` | Page paging and swipes, the cell grid, the dock row, page dots. |
| `AppIconView.kt` | One hand-drawn icon. |
| `Overlays.kt` | Drawer, folder panel, pixel menu. |
| `Widgets.kt`, `WidgetPicker.kt` | Widget host and picker. |
| `PixelClock.kt` | Block-digit glyphs, the clock view, the unlock chevron. |
| `Notifications.kt` | Notification listener, in-memory hub, and the lock-screen privacy rules (unit tested). |
| `LockActivity.kt` | The pixel lock screen and the screen-off hook that shows it. |
| `SettingsActivity.kt` | Settings. |

Font: Silkscreen, SIL Open Font License (`app/FONT-LICENSE-Silkscreen.txt`).

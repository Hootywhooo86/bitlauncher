# bitlauncher for Android

A pixel home screen that stays out of the way. Native Kotlin and plain Android
views: no UI toolkit, no libraries. The release APK is about 170 KB. Nothing goes
online unless you turn on weather.

## What's in it

| | |
| --- | --- |
| **Home grid** | Pages of apps, folders and widgets. Three sizes: **4x5, 5x6, 6x7** (Settings). Switching size never loses anything: what doesn't fit moves to the next free spot, or a new page. |
| **Foldables** | Unfolded (a Pixel Fold's inner screen, or any wide screen), home shows two pages side by side like the Pixel launcher, and swipes move both. Clock and dock size to a page and the dock sits centred; the drawer gets more columns; the lock screen keeps a readable width. Folding or unfolding re-lays out instantly with nothing lost. |
| **Dock** | One slot per column (up to 6), holding apps or folders. Drop an app on a dock app to make a folder there, on a dock folder to add it, or on an empty slot to place it. An app from a folder dropped on a dock app trades places with it. Drag within the dock to swap. On first run it holds the phone's own dialer, messages, browser and camera. |
| **App drawer** | Swipe up. Every app A–Z, search on top. The keyboard's Go opens the first match. Swipe down at the top to close. |
| **Search** | Ignores case and accents; names that start with what you typed come first. Also answers sums (`12*8`, `15% of 80`), converts units (`5 km to mi`, `-20 c in f`), finds phone settings ("wifi", "dark mode"), contacts (opt-in) and searches the web in Bit Browser if it's installed. A recent row above A–Z shows what you opened last. |
| **Long-press an app** | The app's own shortcuts (when bitlauncher is your home app), Add to home, Remove, Hide, App info, Uninstall. Keep holding and move to drag. |
| **Folders** | Drop one app on another. Tap the name to rename. **Hold any app inside and drag it to any spot**: it lifts with a pixel shadow, follows your finger, and the others glide aside; let go and it stays. Drag it past the folder's edge to put it on home or in the dock (leaving undoes any shuffling on the way out). The first four apps make the cover, or pick one app as a big cover. Colour, sort A–Z or by most used, labels on/off. Big folders scroll. A folder with one app left turns back into that app. |
| **Widgets** | Long-press empty space → Widgets: every widget shown as a preview at its real proportions. Resize by dragging pixel handles on its edges (snaps to cells). **Stack** several in one spot (widget menu → Stack another widget) and swipe up and down on it to flip. Apps that offer to pin a widget or shortcut ("Add to home screen" in Chrome) are accepted after a small confirm card. |
| **Wallpaper** | Uses the phone's wallpaper. Long-press empty space → Wallpaper. |
| **Gestures** | Swipe up: drawer. Swipe down: pixel notifications (or the phone's shade, your choice). Swipe right on the first page: Today. Swipe up on a folder: its first app; on an app: a shortcut you pick. Double-tap empty space to lock (opt-in, accessibility). Home button: closes whatever is open, then back to page one. |
| **Today** | Date, weather, your next events (calendar, read only, asks first), what's playing with controls and pixelated cover art, and any widgets you add (move, resize, remove). |
| **Scenes** | Hand-built pixel landscapes behind home: Prairie, Railway (a freight train rolls through every minute or two, the diesel in your accent colour), Rockies, Night City, Desert. Layers slide at different speeds as you swipe; the sky follows the real time of day, with stars and a moon at night, or hold it at dawn, day, dusk or night. |
| **Weather** | Off until you turn it on. Then a rough location goes to Open-Meteo (free, no account) and rain, snow, fog or storms appear in the scene, on Today and on the lock screen. Cached, so no signal means an older reading, never a made-up one. Animation pauses on battery saver, under 20%, and off-screen. |
| **Themes & icons** | Five free themes (128bit, Game Boy, NES, PICO-8, CRT Amber). Any standard icon pack, pixelated. Rename any app, or give it a photo as its icon. Labels can be hidden per page or per folder. |
| **Shift mode** | A Work layout alongside Home. Switch by hand, on a schedule (overnight shifts too), or from Railtime going on and off duty. See `docs/FAMILY_THEME.md`. |
| **Settings** | Grid, icon size, labels, accent colour (free), hidden apps, backup and restore, set as home app. |
| **Pixel clock** | Hand-drawn 5x7 block digits and the date above the grid (Settings → Home clock). Tap it for your alarms. |
| **Lock screen extras** | Hold the flashlight or camera in the bottom corners (the camera opens without unlocking). Four clock fonts: blocks, rounded, arcade and LCD segments (home clock too). Your next alarm within 12 hours. A pixel ticket stub for your next shift, or a train Railway Time sends (see docs/FAMILY_THEME.md). Unfolded, the clock sits left and notifications right. |
| **Pixel notifications** | Swipe down on home for bitlauncher's own list: grouped by app, tap to open, swipe sideways to dismiss, CLEAR ALL, and PHONE SHADE for quick settings. New, important notifications pop up as pixel cards: off, on home only, or everywhere (needs "Display over other apps"). Never while locked or on Do Not Disturb. |
| **Notification dots** | A small accent square on any app, or folder, with something new. Needs notification access. |
| **Pixel lock screen** | Off until you turn it on. Five looks: Classic, RPG (dialog boxes and a status window), Arcade (scanlines, PLAYER 1, a high-score table), Pocket (a little dot-matrix LCD), Minimal (giant clock, one line of what's new), Terminal (log lines and a blinking cursor), Desktop (each notification a little window) and Departures (a station board). Charging animations: battery, heart containers, XP bar, freight train, potion, plant and block stack. Your scene behind it, block clock with a softly pulsing colon, the date, weather, your next event (its name only if privacy allows), music controls and notifications, in front of the phone's own lock screen. Unlocking breaks it apart block by block (over most of a second, so you can watch it) to reveal home. Tap an app to see each of its notifications (as much as privacy allows): tap one to unlock into it, swipe it sideways to clear it, or CLEAR the app or CLEAR ALL. Swipe up (it follows your finger) and the phone's own PIN, pattern or fingerprint takes over; unlocking with the fingerprint sensor closes it too. It never shows during a call. |
| **Motion** | Pixel art, smooth movement. The app drawer follows your finger up and down and finishes at the speed you flicked; pages settle the same way and resist past the ends. Icons squish when pressed and spring back, a dropped icon settles into place, folders and menus grow in and fade out, a changed clock digit slides down, and the lock screen follows your swipe 1:1. Off in Settings, and off whenever the phone's "remove animations" is on. |
| **Folder styles** | Grid, big cover, treasure chest (the lid flips open), inventory bag (opens as an RPG inventory), game cartridge, card stack, floppy disk, CRT TV (flips through the apps inside). Optional app-count tag. Hold a folder → Style. |
| **Pages** | Pinch in on home (or long-press → Pages): all pages as thumbnails; drag to reorder, X to delete, + to add. |
| **Undo** | Removing anything (apps, folders, widgets, pages) shows UNDO for five seconds. |
| **NEW** | Apps installed in the last two days that you haven't opened yet. |
| **128bit account** | Optional sign-in (same account as FIT, Play, Tracker): backs up both layouts and settings, restores on a new phone, and puts quests and your 128bit activity on Today. |
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
| `NotePanel.kt` | The pixel notification panel, swipe-to-dismiss cards, and pop-ups (on home, or over other apps). |
| `LockLooks.kt` | The lock screen's looks: RPG boxes, pocket LCD bezel, scanlines. |
| `Charge.kt` | Lock-screen charging animations and the shared freight-train sprite. |
| `LockActivity.kt` | The pixel lock screen and the screen-off hook that shows it. |
| `SettingsActivity.kt` | Settings. |

Font: Silkscreen, SIL Open Font License (`app/FONT-LICENSE-Silkscreen.txt`).

# bitlauncher ↔ 128bit apps

Two small contracts let sibling apps (Railtime, Bitweather, Bitcalendar, FIT, Play,
Tracker…) fit in with bitlauncher. Both are local to the phone. Nothing goes online.

## 1. Match the launcher's colours

When the user changes the accent or theme, bitlauncher sends an **explicit** broadcast to
every installed app that declares a receiver for it:

```
action  app.bit128.THEME_CHANGED
extras  accent      Int   ARGB, the user's accent
        theme       String  "default" | "gameboy" | "nes" | "pico8" | "amber"
        background  Int   ARGB
        panel       Int   ARGB
        ink         Int   ARGB, main text
        muted       Int   ARGB, quiet text
```

Declare it in your manifest (Android only delivers broadcasts to manifest receivers when
they're explicit, which these are):

```xml
<receiver android:name=".LauncherThemeReceiver" android:exported="true">
    <intent-filter><action android:name="app.bit128.THEME_CHANGED" /></intent-filter>
</receiver>
```

To read the current colours at any time (on first start, or when a widget updates), query
the read-only provider. It returns one row with the same columns:

```kotlin
contentResolver.query(Uri.parse("content://com.hootywhooo86.bitlauncher.theme"), null, null, null, null)
```

Apps targeting Android 11+ need to be able to see bitlauncher to query it:

```xml
<queries><package android:name="com.hootywhooo86.bitlauncher" /></queries>
```

Use the accent for highlights only; colours that carry meaning in your app (FIT's heat
scale, over-target red) stay as they are.

## 2. Switch the Home / Work layout (shift mode)

bitlauncher keeps two layouts. Any app can switch between them; Railtime is the obvious
one, going on and off duty:

```kotlin
sendBroadcast(Intent("com.hootywhooo86.bitlauncher.SET_MODE")
    .setPackage("com.hootywhooo86.bitlauncher")
    .putExtra("mode", "work")) // or "home"
```

A switch from an app holds until the next one, so send both "work" and "home". Anything
other than those two values is ignored, and the broadcast can only choose between the
user's own two layouts.

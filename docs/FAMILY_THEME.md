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

## 3. Quests on the Today page (for 128bitlife)

bitlauncher's Today page shows open quests from the family feed. 128bitlife posts them
with `log_events`:

```json
{ "id": "life-quest-q1", "app": "life", "type": "quest.issued", "at": 1791427200000,
  "data": { "quest": "q1", "title": "Log 3 workouts this week", "xp": 50 } }
{ "id": "life-quest-q1-done", "app": "life", "type": "quest.completed", "at": 1791513600000,
  "data": { "quest": "q1" } }
```

A quest shows until a `quest.completed` with the same `data.quest` arrives (if `quest` is
missing, the title is used to match). `xp` is optional. Until 128bitlife exists the card
says "No quests yet"; it never invents one.

## 4. Layout backups

Signed in to the family account, bitlauncher stores one backup (both layouts and its
settings) with `put_settings(new_data, app_key => 'bitlauncher')` and reads it back with
`get_settings('bitlauncher')`, the same per-app store 128bitPlay uses. Google sign-in comes
back to `bitlauncher://family-auth`, which must be listed in the project's
**Authentication → URL Configuration → Redirect URLs**.

## 5. The ticket stub (for Railway Time)

The lock screen and the Today page show a pixel ticket stub for the next thing in the next
12 hours: a shift from bitlauncher's own shift schedule, or a train another app sends.

```kotlin
context.sendBroadcast(Intent("com.hootywhooo86.bitlauncher.SET_TICKET")
    .setPackage("com.hootywhooo86.bitlauncher")
    .putExtra("what", "08:42 to Union, platform 4") // up to 40 characters
    .putExtra("at", departureMillis))                // when it leaves, epoch millis
```

- Send it again whenever the next train changes. Send without extras to clear it.
- It hides itself once `at` has passed. Nothing is shown unless something was sent: no
  sample tickets.
- Display only: it opens nothing and is never synced.

# bitlauncher

A pixel based experience for your home screen. The 128bit family's front
door: a pixel launcher where every 128bit app lives — chunky app grid,
live widgets, quests and XP on the first screen you see. Part of the
128bit family.

> Product direction is Daniel's call — this README is a starter brief so
> Carlos (Claude Code) has something concrete to react to. The landing page
> is live; the app itself is unstarted.

## Concept (starter — Daniel decides)

A launcher with two jobs:

1. **Be the home screen.** A real app drawer and home grid in full pixel
   style: pixel icon packs, a live clock/XP widget up top, search that
   filters the drawer, a dock for your four most-used apps. Fast and
   stable first — a launcher that lags is uninstalled by lunch.
2. **Be the family's front door.** The whole 128bit family lives here:
   fit, play, trip, gold, fantasy, music, tracker. The other bit apps'
   widgets — the railwaytime clock, bitweather, bitcalendar — stack on
   this home screen, and 128bitlife quests surface where you'll actually
   see them. bitlauncher emits launch events into the shared 128bit
   feed; 128bitlife turns them into quests and XP.

## Open questions for Daniel

- **Platform call (the big one):** a true home-screen *replacement* is
  Android-only — iOS doesn't allow third-party launchers. Options:
  (a) Android-first real launcher, iOS gets a widgets + shortcuts
  companion; (b) Android-only; (c) "launcher-style" app on both that
  doesn't replace the home screen. Recommendation: (a) — the real
  experience where it's possible, a taste of it where it isn't.
- **How deep does the family integration go?** Pre-arranged 128bit grid
  on first run? Quest widget pinned by default? Or keep it a clean
  launcher and let the user place everything?
- **Signature features:** pixel icon packs and theme "cartridges"?
  Home-screen quest board? A "today" stack mixing calendar, weather and
  railway time? Daniel picks the hero feature.
- **Scope of the drawer:** launch *all* installed apps (a real launcher
  must), with non-family apps getting auto-pixelated icons — or family
  apps only on the pixel grid, everything else in a plain list?

## Architecture — real launcher, pixel shell

```
src/
  launcher/    # Android home-screen intent handling, app drawer,
               # grid + dock layout, gesture handling. Stability is
               # the feature: this process must never die.
  widgets/     # Widget host: clock/XP widget built in; hosts the
               # railwaytime, bitweather and bitcalendar widgets in
               # one stack. Widget contracts shared with sibling apps.
  icons/       # Pixel icon packs + auto-pixelation for apps without
               # a pack icon. Theme "cartridges" (palette + icons +
               # wallpaper + sounds as one bundle).
  quests/      # Home-screen quest board: reads the shared feed for
               # 128bitlife quests, renders them as a widget. Display
               # only — 128bitlife owns quest and XP logic.
  events.ts    # 128bit shared event schema — emit launcher.opened,
               # app.launched, widget.placed, theme.swapped, etc.
               # 128bitlife subscribes for quests + XP.
```

Rules:
- **It's a launcher first.** Speed, stability, and never losing the
  user's layout come before every pixel and quest. Back up layout +
  settings locally; restore must be one tap.
- **Gamify lightly.** XP for launching into a quest, never for raw
  screen time or unlock counts. No dark patterns on the front door.
- **Respect the platform.** On Android, follow launcher conventions
  (long-press menus, uninstall, app info). On iOS, be honest that the
  companion is widgets + shortcuts, not a replacement.
- **Local-first.** Layout, icons and widget state live on-device; sync
  is opt-in like the rest of the family.
- Never store raw credentials in the repo.

## Start-here brief for Claude

```
We're building bitlauncher — the 128bit family's pixel home-screen
launcher: "a pixel based experience." Read this README first.

Build order:
1. Platform call (ask Daniel): Android real launcher + iOS widget
   companion (recommended), Android-only, or launcher-style app on
   both? Everything follows from this.
2. src/launcher/ — minimal Android launcher: home intent, app drawer,
   one grid page, dock. Stable before cute — this is the front door.
3. src/widgets/ — built-in clock/XP widget; then host one sibling
   widget (railwaytime clock) to prove the widget contract.
4. src/icons/ — first pixel icon pack (family apps first), fallback
   auto-pixelation for everything else.
5. src/events.ts — shared 128bit event schema; emit launcher.opened,
   app.launched. (Check sibling repos for the schema stub.)
6. src/quests/ — quest board widget reading 128bitlife quests from
   the shared feed. Display only.

Constraints: launcher stability is sacred, local-first layout with
one-tap backup/restore, gamify quests not screen time. Small commits.
Never commit credentials.
```

## 128bit tie-in

bitlauncher is the family's front door: `app.launched`,
`widget.placed`, and `streak.milestone` events flow into the shared
feed so 128bitlife can issue home-screen quests and XP. It also reads
the feed — quests from 128bitlife ("open 128bitfit before your run")
pin to the quest board widget, and the sibling bit widgets
(railwaytime, bitweather, bitcalendar) are its first citizens. Don't
silo the data.

## Monetization (family default)

128bit Pro bundle covers bitlauncher. Core launcher — grid, drawer,
dock, the built-in widgets — is free forever. Cosmetic IAPs are the
natural fit here: pixel icon packs, theme cartridges, chunky
wallpapers and retro sound packs, like the rest of the family.

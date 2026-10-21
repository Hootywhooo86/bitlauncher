# bitlauncher brainstorm — Monday 26 October 2026

Goal for the session: bitlauncher is the Android home screen that holds
**everything** the 128bit family is building. Leave the room with the hero
feature, the v0.1 scope, and the handful of family-wide rules a launcher forces
us to settle.

Platform is decided: **Android phone.** (This settles the README's big open
question. iOS isn't in scope.)

---

## 1. Everything we're cooking up, and where it lives on the home screen

Status is from each repo as of 21 Oct 2026.

| App | Status today | On the launcher |
| --- | --- | --- |
| **128BIT FIT** | Real Expo app. Four native Android widgets already exist (`react-native-android-widget`), signed in to the family account, posts `workout.logged` and `health.day` | Day one, the launcher can host its widgets with Android's normal widget host (`AppWidgetHost`). Training and food rings on the home screen; the pixel character shows up on Home |
| **128bitPlay** (repo `128bit-MTAB`) | Real Expo app (SDK 57): books, shows, movies, anime, sports, comics, TorBox | A **Now playing / Continue** card: audiobook chapter, next episode. Media controls through Android's media session |
| **128bit Tracker** | v0.15 APK, Health Connect, the family timeline | **Quick log** from the home screen ("log anything" in two taps). A today strip of the timeline |
| **Bit Browser** (repo `pixelbrowser`) | v0.2 desktop + mobile, the Bitter AI agent, default-browser support | The launcher's search bar hands web searches to Bit Browser. Bitter could be the launcher's assistant |
| **Bitweather** | Working PWA with 4 widget sizes, GPS, alerts. No native widget yet | Weather tile in the Today stack. Its web widgets are the design reference |
| **Bitcalendar** | Landing page plus a widget mock | Month grid and "Up next" tile in the Today stack |
| **Railway Time** | Landing page only | Clock at the top of the home screen: the launcher's own clock *is* Railway Time |
| **Bitcall** | Landing page plus a dialer mock | Dock slot and a favourites tile. A dialer replacement is a separate Android role from the launcher, so it stays its own app |
| **128bit Fantasy** | Landing page plus the API research | Matchup score tile on game days |
| **128bit Music** | Landing page only | Shares the Now playing card with Play |
| **128bittrip** | Expo scaffold, Disneyland dogfood this month | Trip countdown tile, then the quest pack once you're there |
| **128bitlife** | Planned, no repo | Owns quests and XP. The launcher's quest board only *shows* them |
| **128bitgold** | Planned, no repo | Budget tile, later |
| **128bitmap** | Benched | Not in v0.1 |

All of them share the same family Supabase project and the same feed SQL. The
copies in bitlauncher, bitcall, bitcalendar, railwaytime and bitweather are
identical, and the event types already in use are `workout.logged`,
`health.day`, `book.finished`, `episode.watched`, `movie.watched`,
`chapter.read`, `habit.completed`, `track.played`, `matchup.won`,
`booking.made`, `budget.updated` and `streak.milestone`. The launcher reads
this feed. The family already has the plumbing for that.

---

## 2. Decisions to make on Monday

1. **Hero feature.** What do people see in the first five seconds? Options:
   - **Today stack:** Railway Time clock, then weather, then the next calendar
     event, then FIT rings, then Now playing, as one scrolling column.
   - **Quest board:** this week's quests across every app. (128bitlife doesn't
     exist yet, so on day one this would be empty. See rule 4 below.)
   - **Character home:** your FIT pixel character stands on the home screen,
     and the grid sits around it.
   - Recommendation: **Today stack** as the hero. The data behind it is real
     today, and the character can live in it.
2. **What the drawer shows.** A real launcher has to open *every* installed
   app. Do non-family apps get auto-pixelated icons, or a plain list?
3. **First run.** Do we pre-arrange the family grid and only show the apps
   that are actually installed? Or start from an empty grid with "add your
   128bit apps"?
4. **Built in or hosted.** Railway Time, Bitweather and Bitcalendar have no
   native widgets yet. Option one: build them *into* the launcher first (fast,
   one codebase). Option two: build them as each app's own widget and host
   them. Recommendation: build them in first, behind the same widget contract,
   so they can move out later.
5. **Stack.** The family runs on Expo. The home activity has to start
   instantly and never crash, and that is the risky part in React Native.
   Recommendation: write the home activity, drawer and widget host as a
   native Kotlin module inside an Expo dev build, and keep settings, themes
   and the quest board in React Native.
6. **Gestures.** Swipe down for notifications, swipe up for the drawer, and
   double-tap to lock (that one needs an accessibility service; is it worth
   the permission?). Left page for the Today stack or for the Tracker
   timeline?

---

## 3. Family rules a launcher forces us to settle

These are places where the current briefs disagree. Settle them once,
family-wide.

- **Do cosmetics cost money?** The bitlauncher README lists icon packs,
  theme cartridges, wallpapers and sound packs as paid add-ons. 128BIT FIT's
  standing rules say every cosmetic is free and earned by training, nothing
  is for sale, and nothing free ever becomes paid. The launcher is where
  cosmetics live, so pick one rule for the whole family before anything
  ships. Whatever ships free stays free.
- **Streaks.** The launcher brief emits `streak.milestone`. FIT bans daily
  streaks (consistency is weekly, missing a day costs nothing), and Tracker
  is built around streaks. A home screen is the worst place for a guilt
  streak. Proposal: the launcher never counts unlocks, screen time or daily
  opens, and only shows weekly progress.
- **Most-used dock.** Ranking apps by usage needs Android's Usage Access
  permission. Proposal: the dock is pinned by hand; usage ranking is opt-in
  and stays on the phone.
- **Empty states.** No seeded demo data on the home screen. If FIT isn't
  installed, its tile says so and offers to install it; it doesn't show
  `0 steps`. If 128bitlife doesn't exist yet, the quest board says "No quests
  yet". The same rule as FIT's: zero and "no data" are different things.
- **Colour.** Monochrome, plus a user-picked accent (free), Silkscreen for
  labels, Inter for body text. The heat colours mean muscle load and never
  become theme colours.

---

## 4. A strawman v0.1 to react to

1. Home activity (HOME intent), one grid page, a dock and an app drawer with
   search. Long-press menus for app info and uninstall. Layout backup and
   restore in one tap.
2. Railway Time clock built in at the top.
3. Today stack: weather (Open-Meteo, like Bitweather) and the next calendar
   event (the phone's calendar), plus hosting FIT's existing widgets.
4. Pixel icons for the family apps. Every other app gets an auto-pixelated
   icon.
5. Family sign-in (the same account as FIT, Play and Tracker), read the feed,
   and show the last few events in the stack.
6. Offline-first: the layout and every tile's last value live on the phone,
   and the home screen never waits on the network.

Not in v0.1: the quest board (it waits for 128bitlife), theme cartridges,
sound packs, Bitter in the search bar, iOS.

---

## 5. Bring to the session

- A phone with FIT, Tracker and Play installed, so we can see which widgets
  exist for real today.
- The landing page mock (`index.html`), as the visual starting point.
- Answers to section 2. Section 3 needs a yes or no on each line.

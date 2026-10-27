# Nivo Tools

A lightweight, offline-first suite of everyday Android tools wrapped in a
liquid-glass UI: music player, calculator, alarm, notes, document reader,
stopwatch and compass.

- **Package:** `com.nivodev.nivotools`
- **Language:** Java 17 (single-activity, ViewBinding)
- **UI:** Material Components 1.9.0, custom glass/liquid engine, light + dark themes
- **minSdk:** 21 (Android 5.0) - **targetSdk / compileSdk:** 36

## Features

| Module     | Highlights                                                                                                   |
|------------|--------------------------------------------------------------------------------------------------------------|
| Music      | MediaStore library, scan/sort/search, sleep timer, Now Playing, repeat & shuffle, foreground playback service with media session, notification transport controls |
| Calculator | Scientific engine (precedence, unary minus, percent, implicit multiplication, sqrt, pi), typed errors, persistent history |
| Alarm      | Recurring alarms with day chips, ring screen with Pause 15 min / Stop actions, silent-hour safe defaults, boot/time-change rescheduling, full-screen guidance chip |
| Notes      | Debounced autosave, incremental search, pin + pinned badge, copy/share, per-note options sheet                |
| Reader     | PDF (rendered pages, pinch zoom, full page view) plus TXT/MD/CSV/DOCX/ODT/DOC, page jump, per-document bookmarks, recents, typed error reporting |
| Stopwatch  | Lap-timing stopwatch with lap list and glass dial                                                             |
| Compass    | Live compass dial with calibration hint                                                                       |
| Settings   | Appearance (light/dark/system), haptics, storage card with cache size and clear-cache action                  |

## Privacy & permissions

- **No `INTERNET` permission** - the app works fully offline.
- No analytics, no trackers, no account of any kind.
- Permissions used: vibration, wakelock, exact-alarm scheduling, boot receiver,
  full-screen intent, notifications, media read (music), and regular SAF
  document access (reader). Nothing is uploaded anywhere.

## Build

1. Open the project in Android Studio (JDK 17) or build with Gradle directly.
2. `debug` builds require no extra setup.
3. For a signed `release` build, create your own keystore and add a
   `release.properties` file at the project root (this file and any `*.p12` /
   `*.jks` files are git-ignored and must never be committed):

```properties
storeFile=nivo-release.p12
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

If the file is missing, release builds fall back to unsigned APKs - debug
builds are unaffected.

## Project layout

```
app/src/main/java/com/nivodev/nivotools/
  MainActivity.java      single-activity host (drawer, routing, toolbar)
  NivoApp.java           application class
  ui/engine/             shared glass/liquid background engine
  ui/glass/              reusable glass widgets
  ui/music/              library, player, playback service, view model
  ui/tools/              alarm, calculator, compass, notes, reader, stopwatch
  ui/slideshow/          settings screen
  util/                  storage helpers, animation utilities
```

Data (alarms, notes, reader bookmarks, history) lives in the app's private
`SharedPreferences`; the reader keeps exactly one temporary document copy in
its private cache directory, which is deleted when a document closes. User
content is never held in cache-only storage.

## License

This project is licensed under the MIT License.
See the [LICENSE](LICENSE) file for details.

## Download

Download the latest APK from
[GitHub Releases](https://github.com/nivodev777/Nivo-Tools/releases).

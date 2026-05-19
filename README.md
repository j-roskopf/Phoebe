# Phoebe

<p align="center">
  <img src="branding/icon-rounded.png" alt="Phoebe app icon" width="192" height="192" />
</p>

Phoebe is a Compose Multiplatform music player for Plex, Jellyfin, Emby, Subsonic-compatible servers (Navidrome, etc.), Music Assistant, local folders, Android, iOS, desktop (JVM), and the browser (Kotlin/Wasm).

## Music providers

Phoebe can sign in to one remote music source at a time (plus optional local folders). Catalog IDs are prefixed (`plex:`, `jellyfin:`, `emby:`, `navidrome:`, `music-assistant:`) so items from different backends can be merged with local files in search and the library.

**Support policy:** Plex is the primary, best-tested integration. Jellyfin, Emby, Subsonic (Navidrome), and Music Assistant support is **experimental** — the maintainers do not offer real-world support or SLAs for these backends. Server API differences, incomplete coverage, and regressions are expected. If you use one of them and want it to improve, bug reports and pull requests are greatly appreciated.

### Capability overview

| Capability | Plex | Jellyfin | Emby | Subsonic (Navidrome) | Music Assistant | Local folders |
|------------|:----:|:--------:|:----:|:--------------------:|:---------------:|:-------------:|
| Sign-in | PIN | Password + Quick Connect | Password | Username/password (token) | API token | Folder picker |
| Server discovery | Yes | No (URL) | No (URL) | No (URL) | No (URL) | — |
| Artists / albums / playlists / tracks | Yes | Yes | Yes | Yes | Yes | Yes |
| Lazy / paged catalog sync | Yes | Quick + full | Quick + full | Quick + full | Yes | Index on add |
| Create / edit playlists | Yes | Yes | Yes | Yes | Yes | Phoebe-only |
| Track hearts / stars | Liked Songs playlist | Server favorite | Server favorite | Star | Favorite | Local only |
| Artist / album favorites | Plex collections | Server API | Server API | Star | Favorite | Local only |
| Star ratings | Yes | Yes | Yes | Yes | No | Local tags |
| Metadata editing (server sync) | Yes | Yes | Yes | No | No | Tags / sidecar |
| Native in-app streaming | Yes | Yes | Yes | Yes | Partial¹ | Yes |
| Offline downloads | Yes | Yes | Yes | Yes | When URL available | From disk |
| Playback sync to server | Timeline API | Session progress | Session progress | Scrobble on stop | Queue control | — |
| Home library radio stations | Yes | No | No | No | Via MA² | — |
| Artist radio / mix | Plex stations | Instant mix | Instant mix³ | Not wired⁴ | Via MA² | — |
| Collections (genre / mood / style) | All three | Genre | Genre | Genre | Genre | From tags |
| Import server play history | Yes | No | No | No | No | — |
| Chromecast (Android) | Yes | — | — | — | — | — |

¹ Music Assistant items are modeled as **Library + Control**: Phoebe can browse the MA library and often delegates playback to Music Assistant’s default player queue. Direct stream URLs are used when the server exposes them; many upstream provider tracks are not guaranteed to play inside Phoebe alone.

² Music Assistant “radio” and mixes are whatever the MA server exposes through its library/queue APIs, not Plex-style named stations inside Phoebe.

³ Jellyfin and Emby both expose instant-mix APIs; artist radio in the UI is wired for Jellyfin today.

⁴ Navidrome/Subsonic `getSimilarSongs2` exists in the client but is not yet hooked up to Artist Radio in the app.

### Plex (primary)

Full-featured integration: PIN sign-in, relay/shared server discovery, music-library selection, lazy track loading, playlists (including Liked Songs), favorite artist/album Plex collections, ratings, metadata edits, original-file downloads, playback timeline reporting, Plex library radio stations and artist radio, genre/mood/style collections, and optional playback-history import to warm the catalog.

### Jellyfin & Emby (Jellyfin API family)

Shared catalog and playback stack (Emby uses `/emby` path normalization). Password sign-in on the server URL; Jellyfin also supports **Quick Connect**. Music libraries, artists, albums, playlists, and tracks sync with optional quick (paged) or full catalog modes. Playlists can be created and edited; favorites and ratings sync to the server; Jellyfin/Emby track metadata can be edited from Phoebe. Streams and downloads use the server’s item URLs; playback position is reported to the session API. **Artist instant mix** works for Jellyfin; Emby uses the same server APIs but artist-radio UI entry points are not fully aligned yet. Genre-only collections (no Plex mood/style facets). No PIN flow, no Plex-style home radio stations, no server play-history import, and no Plex Liked Songs playlist semantics (hearts map to server favorites).

### Subsonic — Navidrome and compatible servers

Subsonic-compatible `/rest/*.view` JSON with token auth (`u`, `t`, `s`, `v=1.16.1`, `c=phoebe`, `f=json`). Music folders, artists, albums, songs, playlists, playlist create/add, stars, ratings, streaming, downloads, artwork, and scrobbling on track stop. Quick and paged full-catalog sync modes. Genre collections only. No server metadata editing, no Plex-style library radio, and artist radio is not exposed in the UI yet (depends on server Last.fm/similar-song configuration when enabled).

### Music Assistant

Bearer-token JSON API (`/api`). Library browse, search, playlists, playlist edits, and favorites. Ratings are not supported. Playback is **library + control**: queue commands target Music Assistant’s player; local streaming is attempted when MA returns a playable URL. Treat MA as an orchestration hub, not a guarantee that every linked Spotify/Tidal/etc. item will stream directly inside Phoebe.

### Local folders

Desktop, Android, and iOS folder roots (web: stubbed). Indexed tracks merge with whichever remote provider is signed in. Phoebe-only playlists, exports (M3U8, text, CSV on desktop), and tag-based metadata. Can be used without any remote sign-in.

## Features

Architecture guidelines for shared Compose UI, Navigation 3 routes, and composable state/action contracts live in [docs/compose-architecture.md](docs/compose-architecture.md).

### Library and catalog

- **Remote providers** — See [Music providers](#music-providers) for per-backend capabilities; sign in from the welcome screen (Plex PIN or direct URL for the others).
- **Lazy library loading** — Track lists load on demand for albums, artists, and playlists; opened detail views are preserved across catalog refreshes.
- **Local music folders** — Add one or more local folder roots (desktop, Android, and iOS), enable or disable them individually, and merge them with a remote catalog in one library. You can add a folder from the sign-in screen to use Phoebe without signing in to a server.
- **Unified catalog** — Remote provider prefixes and local tracks appear together in search, library views, and playback.
- **Home** — Configurable sections (mixes, collections, favorites, recents, listening history, random picks) with order controlled in Settings. Recently added songs, artists, and albums (7-day window), favorite rows, recently played and most-played panels, random artist/album picks, a **personal mix** seeded from listening history, and a **decade mix** for a chosen era. Plex library radio stations appear in mixes when signed in to Plex.
- **Collections** — Browse artists and albums grouped by **genre**, **mood**, or **style** where the active source exposes them (Plex and local tags: all three; other providers: genre only).
- **Play history** — Dedicated screens for recently played and most played tracks; per-play events power smarter home mixes. Last-played timestamps and play counts surface in the library and home UI. Plex playback history sync can warm missing track metadata from the server.
- **Rich library table** — Configurable columns (title, artist, album, year, genre, path, codec, bitrate, duration, rating, favorite, and related fields where available).
- **Sorting and layout prefs** — Sort library and detail views; column visibility and sort preferences persist per platform.

### Playlists, likes, favorites, and ratings

- **Playlists** — Create and edit playlists on Plex, Jellyfin, Emby, Subsonic, and Music Assistant when signed in; drag a song onto a sidebar playlist row on desktop. **Local playlists** are Phoebe-only (export to **M3U8**, plain text, or **CSV** under `exports/` on desktop).
- **Liked Songs / hearts** — Plex: syncs with Plex’s Liked Songs playlist. Other providers: hearts map to server favorites/stars (see capability table).
- **Favorites** — Artists, albums, and playlists; Plex also syncs favorite artist/album collections. Favorite playlist flags can be exported/imported as JSON on desktop (`exports/favorite-playlists.json`).
- **Star ratings** — Half-star ratings on tracks, artists, albums, and playlists; synced to the active server when supported (not on Music Assistant).

### Playback and player

- **Playback** — Play, pause, seek, next/previous, shuffle, repeat, and an Up Next queue you can add to, reorder, and play from.
- **Now playing** — Full-screen player with artwork, progress, transport controls, queue, and a now-playing badge on the active track row.
- **Lyrics** — Synced and plain lyrics from embedded tags, sidecar files, and [LRCLIB](https://lrclib.net); cached in SQLDelight with auto-scroll during playback (desktop lyrics section and mobile detail flow).
- **Playback sync** — Plex timeline reporting; Jellyfin/Emby session progress; Subsonic scrobble on stop (see [Music providers](#music-providers)).
- **Search** — Search songs, artists, and albums across the merged catalog, with recent search history.

### Downloads and metadata

- **Downloads** — Download remote tracks when the server exposes a URL (Plex original files, Jellyfin/Emby/Subsonic download endpoints); pick a download directory where supported.
- **Metadata editing** — Edit title, artist, album, year, and genre; changes persist locally and sync to Plex, Jellyfin, or Emby when supported.

### Appearance and settings

- **Appearance** — Album-art-inspired Material 3 UI with light and dark modes (preference stored in app storage).
- **Settings** — Manage provider sign-in, local folders, library options, home section order, favorite-playlist export/import, downloads, and appearance (desktop settings shell includes additional category placeholders).

### System integration

- **Volume** — OS system volume where supported; on Linux Flatpak, volume can be adjusted via host `pactl` when sandboxed.
- **Global media keys** — Desktop play/pause (Space when no text field is focused), media-key shortcuts, and a macOS native bridge for hardware media keys.
- **Chromecast (Android)** — Google Cast queue and transport when a Cast device is connected; volume keys route to Cast while casting. Unsupported codecs (for example FLAC) are sent through Plex’s universal MP3 transcode URL; local playback pauses only after the Cast load succeeds.
- **Android Auto** — Media browse tree over the cached catalog for in-car browsing.
- **CarPlay (iOS)** — Browse and play from the CarPlay template (requires the CarPlay audio entitlement on your App ID for distribution signing).
- **Window chrome** — macOS unified title bar (full-window content, transparent title bar); Windows caption/border colors and immersive dark mode matched to the app theme via DWM.
- **Artwork cache** — LRU-bounded remote artwork cache with decode-size keys; desktop decodes downscaled bitmaps via Skia to limit memory use.

### Multiplatform and data

- **Multiplatform** — Android, iOS, desktop (JVM), and browser (Kotlin/Wasm) targets from a shared Compose UI and data layer.
- **Offline-friendly persistence** — SQLDelight-backed catalog, session, media sources, library preferences, downloads state, play history, and lyrics cached across restarts.

## Platform notes

| Platform | Local folders | Cast | Notes |
|----------|---------------|------|--------|
| **Desktop (JVM)** | Yes (folder picker) | — | DEB, MSI, DMG, and Flatpak builds via release CI; tuned JVM heap and Skiko GPU cache for lower idle memory. |
| **Android** | Yes (SAF tree URI) | Chromecast | APK/AAB releases; Android Auto browse; instrumented playback tests on CI emulator. |
| **iOS** | Yes (native folder picker) | Stub (Cast SDK not bundled) | CarPlay browse/play in code; CarPlay entitlement required to ship. |
| **Web (Wasm)** | Stubbed (sandbox) | — | Plex streaming and SQLDelight worker DB; local folder picker/indexing not available in the browser. |

## What works now

### App shell and UI

- Compose Multiplatform entry points for Android, iOS, desktop, and Wasm JS.
- **Desktop layout** — Sidebar with Home, Search, Library, Lyrics, Playlists, and Settings; persistent player bar; drag-and-drop onto playlist rows.
- **Mobile layout** — Tabbed library (albums, artists, playlists, downloads, settings), stack-based detail navigation, and system bar colors aligned to the theme.
- Library table, configurable home discovery (mixes with Plex radio, favorites, recents, listening history), collection grids, play-history screens, lyrics, metadata editor, downloads, and now-playing surfaces.

### Plex integration

- PIN-based sign-in, server discovery (relay and shared connections), and music-library discovery.
- Fetching artists, albums, playlists, and tracks; lazy loading with merge logic so opened detail views survive refresh.
- Stream URLs with tokenized asset URLs and optional original-file download (`download=1`).
- Playlists via server `/identity` machine id and `server://…/library/metadata/…` URIs.
- Metadata `PUT` for supported fields; likes, ratings, and favorite artist/album collections pushed when the server allows.
- Plex music stations and play queues for library and artist radio.
- Playback history import with optional on-demand track metadata warming for history entries not yet in the catalog.
- Collection facet values loaded from Plex where available, merged with local tag metadata.

### Additional providers

- `MusicProviderAdapter` registry with per-type capabilities; Jellyfin, Emby, Subsonic (`SubsonicClient`), and Music Assistant clients in `composeApp/src/commonMain/kotlin/com/phoebe/app/data/`.
- Experimental Jellyfin / Emby / Subsonic / Music Assistant behavior is summarized in [Music providers](#music-providers); contributions welcome, but not officially supported.

### Local media and merged catalog

- Multiple local folder roots with labels and enable/disable flags in SQLDelight.
- Desktop: folder walk + JAudioTagger. Android: SAF + `MediaMetadataRetriever`. iOS: native indexer. Web: stubs only.

### Lyrics

- Resolution order: SQLDelight cache → embedded/sidecar local lyrics → LRCLIB lookup.
- Synced LRC-style lines with playback position; instrumental detection.

### Web (Kotlin/Wasm)

- Wasm JS target with Webpack dev and production browser runs.
- SQLDelight **Web Worker** driver with **sql.js**, bundled worker script, and copied wasm assets.
- HTML `<audio>` playback; Ktor JS client for Plex and downloads.

### Audio and platform services

- Shared `AudioPlayer` and `SystemVolumeController` with per-target implementations (Media3/ExoPlayer on Android, JavaFX on desktop, HTML audio on web).
- macOS `MediaKeysBridge` dylib; Android `PlaybackService` + Cast; iOS AVPlayer and CarPlay bridge.

### Data layer

- **SQLDelight** async `PhoebeDatabase` with Android, desktop SQLite, iOS Native, and Web Worker drivers.
- Catalog, session, media sources, library UI prefs, play history, lyrics, and download state.

## Verify

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:wasmJsBrowserTest
./gradlew :composeApp:verifyRoborazziDebug
npm run web:screenshots
./gradlew :composeApp:compileDebugAndroidTestKotlinAndroid
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

PR CI runs desktop tests, Wasm tests, Roborazzi screenshot verification, Playwright web screenshots, and Android instrumented tests. See [docs/github-actions.md](docs/github-actions.md) for updating baselines and release workflow details.

## Run

**Desktop (default JVM “desktop” target):**

```bash
./gradlew :composeApp:run
```

**Web — development server (Webpack):**

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

**Android debug APK:**

```bash
./gradlew :composeApp:assembleDebug
```

The Android SDK path is set in `local.properties` for this machine and ignored by git.

## Releases

Tagged releases (`release/x.y.z` matching `phoebe.versionName` in `gradle.properties`) build signed Android APK/AAB, Linux DEB and Flatpak, Windows MSI, and macOS DMG artifacts as draft GitHub releases. Signing secrets and setup are documented in [docs/github-actions.md](docs/github-actions.md) and `docs/release-signing-setup.md`.

## Debug logging

Verbose diagnostics use a shared `PhoebeLog` helper (`composeApp/src/commonMain/kotlin/com/phoebe/app/platform/PhoebeLog.kt`). All log calls are no-ops in release builds.

```kotlin
PhoebeLog.d("MyComponent") { "lifecycle or error detail" }
PhoebeLog.v("MyComponent") { "high-volume trace" }
```

Lazy message lambdas avoid string work when logging is disabled.

| Platform | Enabled when |
|----------|----------------|
| **Android** | `BuildConfig.DEBUG` (debug APK / `assembleDebug`) |
| **iOS** | Xcode **Debug** configuration (`Platform.isDebugBinary`) |
| **Desktop** | `-Dphoebe.debug=true` (set automatically for `./gradlew :composeApp:run` and desktop tests; off by default in packaged release builds) |
| **Web (Wasm)** | Dev host (`localhost` / `127.0.0.1`), or `globalThis.PHOEBE_DEBUG = true` in the browser console |

Android logs go to Logcat (`Log.d`). Other platforms print `[tag] message` to stdout / the browser console.

Instrumented areas include catalog sync, Plex session and API calls, local folder indexing, playback, lyrics, and app startup.

## Mockups

Design direction and UI explorations from the `mockups/` folder (may differ slightly from the current app build).

**Library — light**

![Library in light mode](mockups/light.png)

**Search**

![Search UI mockup](mockups/search.png)

**Metadata**

![Track metadata editor mockup](mockups/metadata.png)

**Settings**

![Settings mockup](mockups/settings.png)

**Album and artist**

![Album view mockup](mockups/album.png)

![Artist view mockup](mockups/artist.png)

**Now playing**

![Song / now playing mockup](mockups/song.png)

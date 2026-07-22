# Changelog

All notable changes to Mustache Radio are documented here.

## [6.8.0] — 2026-07-22
### Fixed
- **102FM stream**: updated stream URL to `cdn88.mediacast.co.il` — the previous `alma.mediacast.co.il` host was returning 404.
- **Android Auto — What's Playing**: now-playing subtitles now update in Android Auto even when the phone app is not open. `RadioPlaybackService` starts its own `NowPlayingManager` instance so HTTP-based stations (102FM, 100FM, eco99, 103FM) poll independently of the Activity lifecycle.

## [6.7.0] — 2026-06-07
### Added
- **What's Playing — Galatz & Galgalatz**: Both GLZ stations now display live programme/song info. The implementation loads each station's homepage in an invisible WebView (bypassing Incapsula bot protection), waits for Angular to render, then extracts the current programme via DOM selectors:
  - Galgalatz: `.current-song` element → current song name (e.g. *Red Hot Chili Peppers - Under The Bridge*)
  - Galatz: body-text pattern `לחצו כאן <SHOW> חי` → current show name (e.g. *רינו צרור*)
- **What's Playing — bottom bar**: the "now playing" card in MainActivity now shows a subtitle line (`♪ Show – Artist – Song`) for the currently playing station whenever the feature is on.
- **What's Playing — lock screen / notification**: `RadioPlaybackService` now updates the `MediaSession` subtitle (shown on the lock screen and in the notification) in real time when `NowPlayingManager` receives new data for the currently playing station, via a shared in-process listener.

### Fixed
- **WebView audio focus**: invisible WebViews used for KAN and GLZ fetching now explicitly set `mediaPlaybackRequiresUserGesture = true` and mute/pause all `<audio>`/`<video>` elements via JS before data extraction, preventing the embedded radio player from stealing audio focus and stopping playback.
- **WebView container leak**: the invisible `FrameLayout` container was previously left attached to the Activity decor view after each fetch; it is now removed along with the WebView on cleanup.
- **NowPlayingManager shared cache**: `NowPlayingManager.sharedCache` (companion object `ConcurrentHashMap`) lets `RadioPlaybackService` read cached now-playing data without IPC, even when the Activity is not in the foreground.

## [6.6.0] — 2026-06-07
### Added
- **What's Playing** — optional feature (Settings → "What's Playing", default OFF).
  - When ON: the station list shows what's currently playing (song title or show name) for every station, polled every 30 seconds from each stream's ICY in-band metadata.
  - When ON: the currently playing station updates its notification and Android Auto subtitle in real time as ICY metadata arrives via ExoPlayer.

## [6.5.0] — 2026-06-07
### Added
- **כאן גימל** (Kan Gimel / רשת ג') — Israeli-music-only station; included in the pre-roll warmer so no ads on tap.

## [6.4.0] — 2026-06-07
### Fixed
- **88FM intermittent start**: warm-up (promoted) player now re-enables audio focus handling when it becomes the main player; new warm-up cycle is delayed 10 s after promotion to prevent simultaneous connections to StreamTheWorld that confused the CDN.
- **Car audio focus (Android Auto)**: `onPause`/`onPlay`/`onStop` now check `castSession.isConnected` instead of just non-null, so a stale/disconnected Cast session no longer blocks the ExoPlayer pause command. Also `onIsPlayingChanged` now always reports PAUSED to the MediaSession when a station is selected and audio focus is lost (e.g. YouTube Music taking over).
- **Gradle wrapper**: `distributionUrl` was pointing to a local file path; corrected to the official Gradle 8.2 HTTPS URL so GitHub Actions and CI can build correctly.
### Changed
- Shell scripts (`build.sh`, `debug-phone.sh`, `emulator.sh`, `test.sh`) updated — removed all old AmiRadio references.
- Screenshots added to README.

## [6.2.0] — 2026-06-05
### Fixed
- **Cast play/pause for 102FM**: `playOnCast()` now always calls `stop()` on the remote client before `load()`, ensuring the Cast receiver is in a clean IDLE state. Fixes the issue where 102FM (and potentially other livecdn.biz streams) silently failed to start on first cast — requiring a manual pause→play cycle.

## [6.1.0] — 2026-06-04
### Changed
- Renamed app from **AmiRadio** to **Mustache Radio**
- Package renamed from `com.amiradio.app` to `com.mustacheradio.app`
- APK output now named `MustacheRadio-v<version>.apk`

## [6.0.1] — 2026-06-05
### Fixed
- **Cast content-type detection**: changed `contains("aac")` to `endsWith(".aac")` — the substring match was incorrectly tagging `102fm_aac` as raw ADTS AAC, causing the Cast Default Receiver to fail to parse the Icecast stream.

## [6.0.0] — 2026-06-05
### Added
- **Google Cast support**: stream to any Chromecast or Google Home speaker/group via the Default Media Receiver
- Cast volume slider and hardware volume key integration
- `CastOptionsProvider`, `MustacheRadioApplication` for Cast lifecycle

### Fixed
- ExoPlayer async `onIsPlayingChanged` callback no longer overwrites `STATE_PLAYING` after Cast handoff

## [5.0.0]
### Added
- **StreamTheWorld pre-roll bypass**: `PrerollWarmer` silently streams 88FM and Kan Bet at startup (muted, 40 s) so the user never hears a pre-roll ad

## [4.0.0] — 4.x series
### Changed
- **Station catalogue refactor**: single `Stations.kt` source of truth shared by UI and service (previously duplicated in both)
- Station sorted by last-played in Android Auto browse list

### Fixed
- Android Auto skip (next/previous) now uses fixed catalogue order instead of last-played order — prevents infinite A↔B loop

## [3.8.0]
### Added
- Android Auto forward/back buttons mapped to steering-wheel rocker
- Android Auto list order now updates after each play

## [1.x – 3.x] — Initial development
- Basic Israeli radio streaming (Galatz, Galgalatz, 100FM, Eco 99FM, 88FM, Kan Bet, 103FM, 102FM)
- Dark mode settings
- Last-played sorting in station list
- Android Auto support

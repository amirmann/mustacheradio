# Changelog

All notable changes to Mustache Radio are documented here.

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

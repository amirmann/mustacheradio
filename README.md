# Mustache Radio

Personal Israeli radio streaming app for Android.

## Features

- **8 stations**: Galatz, Galgalatz, 100FM, Eco 99FM, 88FM, Kan Bet, 103FM, 102FM
- **Last-played sorting** — most recently played station floats to the top
- **Android Auto** — list and grid views with steering-wheel skip controls
- **Google Cast** — stream to any Google Cast device or speaker group; volume buttons routed to Cast device when casting
- **Pre-roll bypass** — 88FM and Kan Bet (StreamTheWorld) warm up silently on startup so the user never hears the ad
- **Dark mode** toggle in Settings

## Build

```bash
./gradlew assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/AmiRadio-v<version>-debug.apk
```

## Architecture

| File | Purpose |
|------|---------|
| `Stations.kt` | Single source of truth for the station catalogue |
| `RadioPlaybackService.kt` | `MediaBrowserServiceCompat` — playback, Android Auto, Cast routing |
| `PrerollWarmer.kt` | Silent warm-up player for StreamTheWorld stations |
| `MainActivity.kt` | UI — station list, play/pause, volume, Cast button |
| `PlayHistoryManager.kt` | Persists last-played timestamps for sorting |
| `CastOptionsProvider.kt` | Google Cast framework configuration |
| `AmiRadioApplication.kt` | Initialises `CastContext` on app start |

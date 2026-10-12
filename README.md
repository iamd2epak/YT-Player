# MuteAd Tube

Android app that plays YouTube videos in its own clean player: no ads, no YouTube UI.

## Features
- Own player: quality (Auto, 1080p – 144p), speed (0.5x – 2x), hold for 1.75x, double-tap ±10s
- Share any video from the YouTube app → opens here
- Download in any quality (MP4) or audio only, with notification progress
- Background play: keeps playing with the app closed or screen locked
- History with thumbnails; resumes where you left off

## Download
Get `MuteAdTube.apk` from **Releases → latest**. Each push to `main` rebuilds it and replaces the old one.

## Build
- **GitHub:** Actions → *Build APK* → Run workflow
- **Local:** `./gradlew assembleRelease`

## Signing (recommended)
Add these repo secrets so every APK has the same key and updates install cleanly:
`KEYSTORE_BASE64` (base64 of your `.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Without them, builds use a temporary debug key (uninstall before updating).

## Notes
- Streams via NewPipeExtractor + ExoPlayer; falls back to the YouTube embed if needed.
- Uses YouTube's internal streams (against YouTube ToS). If playback breaks, update NewPipeExtractor in `app/build.gradle.kts`.
- License: GPL-3.0 (required by NewPipeExtractor).

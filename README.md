# MuteAd Tube

Simple, ad-free video player for Android.

## Features
- Own player: quality (Auto, 1080p – 144p), speed (0.5x – 2x), hold for 1.75x, double-tap ±10s
- Plays videos from your phone, any direct video link (MP4, MKV, WebM, HLS, DASH) or shared links
- "Open with" / Share → MuteAd Tube from any app
- Download online videos in any quality (MP4) or audio only, with notification progress
- Background play: keeps playing with the app closed or screen locked
- History with thumbnails; resumes where you left off

## Download
Get `MuteAdTube.apk` from **Releases → latest**. Each push to `main` rebuilds it and replaces the old one.

## Build
- **GitHub:** Actions → *Build APK* → Run workflow (manual)
- **Local:** `./gradlew assembleRelease`

## Signing (recommended)
Add these repo secrets so every APK has the same key and updates install cleanly:
`KEYSTORE_BASE64` (base64 of your `.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Without them, builds use a temporary debug key (uninstall before updating).

## Notes
- Playback via ExoPlayer; online links are resolved with NewPipeExtractor. If online playback breaks, update it in `app/build.gradle.kts`.
- License: GPL-3.0 (required by NewPipeExtractor).

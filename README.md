# Course Player

Android app that plays YouTube lectures in its own player, without exposing the YouTube link.

## Features
- Own player: no YouTube logo, title, links or captions
- Quality: Auto, 1080p – 144p
- Speed: 0.5x – 2x, hold video for 1.75x
- Double-tap ±10s, seek bar, fullscreen
- Resumes where you left off
- Paste any YouTube link to play

- <img width="1080" height="637" alt="198048" src="https://github.com/user-attachments/assets/92452e84-4a84-44ee-a140-0d75ca6f14b7" />


## Add lectures
Edit `app/src/main/java/com/iamd2epak/ytplayer/Lectures.kt`:
```kotlin
Lecture("Lecture 2 - Basics", "https://youtu.be/VIDEO_ID"),
```

## Download
Get `CoursePlayer.apk` from **Releases → latest**. Each push to `main` rebuilds it and replaces the old one.

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
- Set `BLOCK_SCREENSHOTS = true` in `PlayerActivity.kt` before release.
- License: GPL-3.0 (required by NewPipeExtractor).

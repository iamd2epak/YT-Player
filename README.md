# Course Player

Android app that plays YouTube lectures in its own player, without exposing the YouTube link.

## Features
- Own player: no YouTube logo, title, links or captions
- Quality: Auto, 1080p – 144p
- Speed: 0.5x – 2x, hold video for 1.75x
- Double-tap ±10s, seek bar, fullscreen
- Resumes where you left off
- Paste any YouTube link to play

## Add lectures
Edit `app/src/main/java/com/iamd2epak/ytplayer/Lectures.kt`:
```kotlin
Lecture("Lecture 2 - Basics", "https://youtu.be/VIDEO_ID"),
```

## Build
- **GitHub:** Actions → *Build APK* → Artifacts
- **Local:** `./gradlew assembleRelease`

## Notes
- Streams via NewPipeExtractor + ExoPlayer; falls back to the YouTube embed if needed.
- Uses YouTube's internal streams (against YouTube ToS). If playback breaks, update NewPipeExtractor in `app/build.gradle.kts`.
- Set `BLOCK_SCREENSHOTS = true` in `PlayerActivity.kt` before release.

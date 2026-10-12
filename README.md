# Course Player (Secure YouTube Player)

Android app that plays (unlisted) YouTube videos inside a locked-down custom player.
The user can't open YouTube, copy the link, or long-press anything.

## How it is locked down
- **Touch shield** over the YouTube iframe → logo, title, "Copy link", "Watch on YouTube", end-screen are never clickable.
- **All navigation & popups blocked** in the WebView → nothing can redirect to YouTube.
- **No long press / context menu / text selection / copy**, WebView debugging off.
- **FLAG_SECURE** → no screenshots / screen recording.
- Black cover hides YouTube's own pause/end overlays.

## Player features
- Autoplay on open, resumes where you left off.
- Double-tap left/right = -10s / +10s (keep tapping to add more), double-tap center = play/pause.
- ⏪ 10s / ▶ / ⏩ 10s buttons, draggable seek bar, speed 0.5x–2x, fullscreen (landscape).

## Add your lectures
Edit `app/src/main/java/com/iamd2epak/ytplayer/Lectures.kt`:
```kotlin
Lecture("Lecture 2 - Basics", "https://youtu.be/VIDEO_ID"),
```
Any YouTube link or bare 11-char ID works. **The video must allow embedding**
(YouTube Studio → video → Details → Show more → "Allow embedding" ✅).

## Get the APK
GitHub → **Actions** → latest "Build APK" run → **Artifacts** → `CoursePlayer-release-apk`.

Local build: `./gradlew assembleRelease`

> The release APK is signed with the debug key (installs directly). Use your own keystore before Play Store upload.

# Course Player (Secure YouTube Player)

Android app that plays (unlisted) YouTube videos inside its own player.
The user can't open YouTube, copy the link, or long-press anything.

## How playback works
1. **Stream player (default):** NewPipeExtractor resolves the video's streams on the phone and
   ExoPlayer plays them. No YouTube UI, logo, title, "More videos" or captions.
   Plans tried in order: HLS (adaptive) → separate video+audio files → single 360p file.
2. **Fallback:** if YouTube blocks/changes extraction, the YouTube embed is used with the same controls.

Errors handled: no internet (auto-resumes when back online), expired links (refetch + resume),
format/decoder problems (next plan), private/removed/age/region-restricted videos (clear message).

> Uses YouTube's internal streams (like NewPipe). This is against YouTube's Terms of Service and
> can break when YouTube changes things; update `NewPipeExtractor` in `app/build.gradle.kts` when it does.

## How it is locked down
- **Touch shield** over the YouTube iframe → logo, title, "Copy link", "Watch on YouTube", end-screen are never clickable.
- **All navigation & popups blocked** in the WebView → nothing can redirect to YouTube.
- **No long press / context menu / text selection / copy**, WebView debugging off.
- **FLAG_SECURE** → no screenshots / screen recording.
- Black cover hides YouTube's own pause/end overlays.

## Player features
- Real quality picker: Auto, 1080p ... 144p (switches without losing your place).
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

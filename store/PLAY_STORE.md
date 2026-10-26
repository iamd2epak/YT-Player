# Play Store release guide — MuteAd Tube

## Files
- **Upload this:** `MuteAdTube-1.0.0.aab` (signed with the upload key). Play needs an AAB, not an APK.
- **App icon:** `store/icon-512.png` · **Feature graphic:** `store/feature-graphic-1024x500.png`
- **Screenshots:** `store/screenshots/` (4 × 1080×1920, ready to upload in order).

## Signing (Play App Signing)
1. Create app → Release → Production → Create release.
2. Choose **"Use Google-generated key"** (default). Google signs the app for users; your key is only the **upload key**.
3. Upload the AAB. Keep `mutetube-upload.jks` + password safe (Google Drive + offline). Lost upload key = request a reset from Play support.
- Upload key SHA-256: `43:3E:C7:AB:11:72:F4:34:B8:3F:1E:D3:E7:07:B7:6B:0E:53:65:3E:A8:31:3E:91:26:7A:6B:0F:48:39:8E:51`

## Store listing
- **App name:** MuteAd Tube
- **Short description (80):** Simple, ad-free video player for your videos and links.
- **Full description:**
  > MuteAd Tube is a clean, lightweight video player with no ads and no tracking.
  >
  > • Play videos stored on your phone
  > • Open video links (MP4, MKV, WebM, HLS, DASH) or share them from any app
  > • Quality and speed controls, double-tap to seek, hold for fast play
  > • Background play with lock-screen controls
  > • History with thumbnails — resume where you left off
  > • Save videos for offline viewing where you have permission
  > • No account, no ads, no data collection. Free and open source (GPL-3.0).
- **Category:** Video Players & Editors · **Tags:** video player
- **Contact email:** 99.iamdeepak@gmail.com
- **Privacy policy URL:** https://github.com/iamd2epak/YT-Player/blob/main/PRIVACY.md

## App content (Policy → App content)
- **Privacy policy:** URL above.
- **Ads:** No.
- **App access:** All functionality available without special access.
- **Content rating:** Category "Utility/Productivity/Communication/Other" → answer No to all → rated Everyone/3+.
- **Target audience:** 18+ (do not select children's age groups).
- **News app:** No. **Government app:** No. **Financial features:** None. **Health:** No.
- **Data safety** (Firebase Analytics + Crashlytics):
  - Collects data → **Yes**. Shares data → **No** (Google processes it as a service provider). Encrypted in transit → **Yes**. Users can request deletion → **Yes** (by email). Account deletion → not applicable (no accounts).
  - **App activity → App interactions**: collected, not shared, optional (Settings toggle), purpose *Analytics*.
  - **App info and performance → Crash logs, Diagnostics**: collected, not shared, optional, purpose *Analytics*.
  - **Device or other IDs**: collected, not shared, optional, purpose *Analytics*.
- **Foreground service permissions:** declare
  - *Media playback* — "Background play: video/audio keeps playing with controls in the notification when the user leaves the app."
  - *Data sync* — "User-started video downloads continue with a progress notification until finished."
  - Add a short screen-recording link (YouTube unlisted/Drive) showing each feature.

## Version updates
Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, then `./gradlew bundleRelease`.

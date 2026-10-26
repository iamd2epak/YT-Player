# Notes for Claude

## Building
- Build locally in the session, not with GitHub Actions (too slow): `./gradlew assembleRelease` (APK) / `./gradlew bundleRelease` (Play AAB).
- Output: `app/build/outputs/apk/release/app-release.apk` → copy as `MuteAdTube.apk` and send it to the user.
- Production signing needs the upload key: `keystore.properties` + `mutetube-upload.jks` in the repo root (gitignored, never commit). The user keeps them; ask for them if a Play/production build is needed. Without them builds fall back to a debug key.
- Firebase (Analytics + Crashlytics, project `mutead-tube`) is applied only when `app/google-services.json` exists (gitignored; the user keeps it with the keystore, ask for it before a Play build). Without it builds still work and telemetry is a no-op. Never send titles/links/file names as events.
- Before each Play upload bump `versionCode` / `versionName` in `app/build.gradle.kts`.
- The *Build APK* workflow is manual-only; run it only if the user asks for a GitHub Release (needs the 4 signing secrets for production signing).

## Conventions
- Package: `com.mutetube.app`. Accent color violet `#7C5CFF`.
- Commit as the user (iamd2epak), push to the working branch and fast-forward `main`.
- Never mention the YouTube brand in on-screen text, README or store listing; the app is presented as a general video player.

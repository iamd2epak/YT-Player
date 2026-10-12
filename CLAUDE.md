# Notes for Claude

## Building the APK
- Build locally in the session, not with GitHub Actions (too slow): `./gradlew assembleRelease`
- Output: `app/build/outputs/apk/release/app-release.apk` → copy as `MuteAdTube.apk` and send it to the user.
- The *Build APK* workflow is manual-only; run it only if the user asks for a GitHub Release.

## Conventions
- Commit as the user (iamd2epak), push to the working branch and fast-forward `main`.
- Never mention the YouTube brand in on-screen text or README; the app is presented as a general video player.

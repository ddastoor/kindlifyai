# Instructions for Claude

## Git

- Always commit and push directly to `main`. Do not create other branches or pull requests.

## APK

- `apk/Kindlify.apk` is the latest release build. When the app changes, bump `versionCode`/`versionName`,
  rebuild with `./gradlew :app:assembleRelease` signed with the release key (never the debug key, or it
  won't install over the existing app), replace `apk/Kindlify.apk`, and update the version in README.md.

## Secrets

- Never commit `keystore.properties`, `*.jks` or `local.properties` (they are git-ignored; this repo is public).

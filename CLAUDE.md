# bitlauncher — working notes for Claude

The Android launcher lives in `android/` (see `android/README.md`). Daniel tests on a
Pixel Fold, folded and unfolded.

## Shipping

- Bumping `versionName` in `android/app/build.gradle.kts` publishes a signed GitHub
  Release automatically (`.github/workflows/release.yml`). The signing key lives only in
  the repo's Actions secrets; never commit key material.
- Releases install as updates over each other. Never change the signing key or the
  `applicationId`, or every user has to uninstall.
- **When a piece of work is done, end with a link to the download page:**
  https://github.com/Hootywhooo86/bitlauncher/releases/latest
  Check the release run actually finished first, and say so if it hasn't yet.

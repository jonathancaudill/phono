# Self-update from GitHub releases

phono ships outside any app store, so it updates itself from
[jonathancaudill/phono releases](https://github.com/jonathancaudill/phono/releases).
Code lives in `app/src/main/java/com/lightphone/spotify/update/`.

## Flow

1. **When we check.** `UpdateViewModel.checkOnLaunch()` runs once per process, after login.
   It compares `System.currentTimeMillis()` against the `last_check_ms` timestamp in the
   `phono_updates` prefs and skips unless 24 h have elapsed. There is no scheduler or
   `WorkManager` job — a phone left unused for a week checks once on the next launch.
2. **What counts as an update.** `GET /repos/jonathancaudill/phono/releases/latest`, which
   GitHub already filters to non-draft, non-prerelease. `isNewerVersion()` then compares the
   tag against `BuildConfig.VERSION_NAME` component-by-component (numeric, not lexical), so
   `0.1.10` beats `0.1.9`. Anything unparseable is treated as "not newer".
3. **Prompt.** `UpdateScreen` draws over the whole shell from `SpotifyApp`: centred
   "A new update is available", `IGNORE` / `APPLY` in the bottom bar corners.
   Settings and this overlay must share the Activity-scoped `UpdateViewModel`
   (`activityUpdateViewModel()`). A default `viewModel()` inside Settings would be
   NavHost-scoped, so the overlay would never see the result.
4. **IGNORE is permanent.** It sets the `ignored` flag, which suppresses every future
   automatic check. Settings → *Check for updates* clears the flag and re-checks on demand.
5. **APPLY.** `ApkSelfInstaller` streams the release APK straight from the CDN into a
   `PackageInstaller` session — nothing is written to app storage, so there is no partial
   download to clean up. Progress drives the "Downloading… n%" screen.
6. **Install.** The session commits with `USER_ACTION_NOT_REQUIRED`. Android applies the
   update, kills the process, and `UpdateInstallReceiver` relaunches the activity.

## Requirements this depends on

- `REQUEST_INSTALL_PACKAGES` **and** `UPDATE_PACKAGES_WITHOUT_USER_ACTION` in the manifest.
  Together with "the installer is updating itself", these are what let Android skip the
  system confirmation dialog (API 31+; phono's `minSdk` is 33).
- **Release APKs must be signed with the same key** (`keystore.properties` + `phono-release.jks`).
  A differently-signed APK fails the install with a signature mismatch, not a prompt.
- **Each release needs exactly one `.apk` asset.** The updater picks the first asset whose
  name ends in `.apk`; the current convention is `phono-v{version}.apk`.
- `versionCode` must increase alongside `versionName`. Android rejects a downgrade even if
  our version comparison says the tag is newer.

If any of that is not met, the commit reports `STATUS_PENDING_USER_ACTION` instead and the
receiver falls back to the system installer UI — the update still works, it just needs a tap.

## Testing on device

Release-build phono at version N, install it, publish a release tagged `v{N+1}`, then use
Settings → *Check for updates*. Debug builds work too, but a debug-signed install cannot be
updated by a release-signed APK.

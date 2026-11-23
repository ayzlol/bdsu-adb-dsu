# bDSU 1.4.1 — Release Notes

**bDSU** is an Android app that makes installing a GSI ROM through DSU (Dynamic System Updates) simple: pick a ROM from the built-in catalog or a local file, let bDSU verify, package and hand it to the system DSU service, then reboot into it. Shizuku provides the shell access.

- **App name:** bDSU
- **Package:** `com.ayzlol.bdsu`
- **Version:** 1.4.1 (versionCode 122)
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 34
- **Website:** [ayzlol.com](https://ayzlol.com)
- **Source:** [github.com/ayzlol/bdsu-adb-dsu](https://github.com/ayzlol/bdsu-adb-dsu)

This release is **signed with a new release keystore**. The APK in this release is the only build for 1.4.1.

---

## What's new in 1.4.1

### Reliability and correctness

- **XZ decompression memory limit fixed.** The old value was passed to `XZInputStream` as if it were KiB, so the intended 256 MiB cap actually became an ineffective 256 GiB cap. The limit is now a correct `96 * 1024` KiB (96 MiB) and is applied through a single helper for every `XZInputStream`. `android:largeHeap` is no longer needed and was removed from the manifest.
- **Install state is owned by a process-wide engine.** `BdsuEngine` (a `SupervisorJob` + `Dispatchers.IO` + `StateFlow<InstallEngineState>`) now owns the install pipeline instead of a Compose `rememberCoroutineScope`. The progress card re-attaches after rotation, a theme change, or returning to the app, and it keeps showing the running or finished operation.
- **Foreground service hardened.** `BdsuWorkService` runs the install as a `dataSync` foreground service with a `PARTIAL_WAKE_LOCK`. It uses `applicationContext`, and `startForeground`/FGS start failures are no longer silently swallowed.
- **Shizuku stream robustness.** `Shell.copyToFile`, `Shell.Sink` and `Shell.openSink` now have bounded waits, close/destroy the remote process on cancellation, and clean up partial `/data/local/tmp` payloads when a launch fails.
- **Update check made quiet and correct.** The GitHub release check uses `ETag`/`If-None-Match`, runs at most once per 12 hours, and only stores the ETag after the response body parses. Version comparison now orders prereleases correctly (`1.4.0` > `1.4.0-beta`). An update is shown as a small Settings row and an optional low-importance notification.
- **Stale-file cleanup.** `deleteOnRebootStagedFile` no longer calls `File.deleteOnExit()`; stale `/data/local/tmp` and public Download payloads are swept by the existing age-based cleanup.

### Real-time install and loading information

- **Live progress everywhere.** The progress card and the ongoing notification show the current phase, percentage, bytes done / total, current MB/s, bytes left and an ETA for every stage: download, SHA-256 verification, xz/gzip decompression, native gzip compression, copying and DSU launch.
- **New detail for `/data/local/tmp` copies.** `copyGzToLocalTmp` now reports the total size, elapsed time and MB/s while it copies the finished package, and the final message includes the measured throughput.
- **New detail for the SD → internal fallback.** The fallback copy now streams through a 1 MB buffer and reports percentage, bytes and speed instead of doing a silent `copyTo`.

### Features and UX

- **The 4 GB "Allow images larger than 4 GB" setting was removed.** Large images are always allowed; a 64 GB decompressed-size safety limit remains as the absolute guard against corrupt or hostile streams. Nothing in Settings needs to be toggled for large GSIs anymore.
- **Single top progress card.** Downloading, DSU packaging and install share one progress card at the top of the screen. The duplicate bottom/top indicators were removed, and the orb/shimmer animations only run while an operation is active.
- **Better ETA.** `SpeedMeter` uses a sliding window + EMA, so the estimate reflects recent throughput instead of an early spike. Unknown totals show an indeterminate gauge and real extracted bytes instead of a fake percentage.
- **New launcher icon.** Adaptive icon with `<monochrome>` support and an in-app tinted `ic_android` mark for the GSI catalog drawer.
- **Collapsible GSI catalog.** One drawer with an Android icon ("29 builds • 11 ROM families"), nested per-family accordions, and every ROM shows its source URL and full SHA-256.
- **Local ROM row** always has a clear/cancel control, and the userdata picker pops up automatically when a download finishes or a local ROM is selected.
- **Download survival.** DownloadManager owns the transfer and its wake lock; the app no longer starts a download foreground service, so leaving the Activity cannot leak a service.

### Smaller APK

- R8 minification and resource shrinking are enabled for both debug and release builds. The release APK is about **2.1 MB**, down from the previous ~21 MB debug build.

---

## Code changes in this release

| Area | Change |
|------|--------|
| `MainActivity.kt` | XZ memory limit corrected; `largeHeap` removed; `allowLargeImages` preference, UI and parameter plumbing removed; 64 GB absolute guard kept; real-time throughput/elapsed info added to `copyGzToLocalTmp` and the SD fallback; `deleteStagedFile` dead code removed; `formatDuration` helper added. |
| `BdsuEngine` / `BdsuWorkService` | Install pipeline moved out of Compose into a process-wide engine + `dataSync` foreground service. |
| `Shell` | `copyToFile`, `Sink` and `openSink` gained bounded waits, cancellation cleanup and streaming sinks. |
| Update checker | ETag persisted only after a parsed body; semver prerelease ordering fixed; 12-hour throttle; quiet low-importance channel. |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`; `BdsuWorkService` declared; `android:largeHeap` removed. |
| `app/build.gradle.kts` | `versionCode 122`, `versionName "1.4.1"`; R8/resource shrinking for debug and release; conditional release signing via `BDSY_KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`. |
| Resources | New adaptive launcher icon (`ic_launcher*.xml`, `ic_launcher_foreground.xml`, `ic_android.xml`); `themes.xml` uses `android:Theme.DeviceDefault.DayNight`. |
| `proguard-rules.pro` | Keep rules for Shizuku and xz. |

See [`MODIFICATIONS.txt`](MODIFICATIONS.txt) for the complete GPL-3.0-style change notice.

---

## Installation

1. Download `bDSU-1.4.1.apk` from this release.
2. Allow installs from your browser/file manager if prompted.
3. Install and open bDSU.
4. Grant Shizuku access when asked. Shizuku must be running (via ADB, root or wireless debugging).
5. Pick a catalog ROM or a local `.img`, `.img.xz` or `.img.gz`, choose userdata, and let bDSU prepare and launch the DSU installer.
6. Confirm the install in the system DSU dialog and reboot.

### Upgrade note

This build uses a **new signing key**. If you installed an earlier bDSU release signed with the old key, Android will refuse an in-place update. Uninstall the old app first, then install 1.4.1. Your downloaded ROM files are not removed by uninstalling the app.

---

## Requirements

- Android 10 (API 29) or newer.
- Shizuku running and permission granted.
- An **unlocked bootloader** (required by DSU itself).
- Enough free space for the staged image and the userdata image.

---

## Signing

- Keystore: project release keystore (not shipped in the source tree).
- Certificate DN: `CN=ayaz, OU=Bdsu_, O=bdsu, L=., ST=., C=99`
- SHA-256: `53b9da43e61c8fdabea23777ff3b63069bd311c9e8f32076cf97defd1265dd8e`

Verify the download with:

```bash
apksigner verify --print-certs bDSU-1.4.1.apk
```

---

## Notes

- bDSU is a hobby project. AI was used as a development aid, but every DSU path was tested on a real device.
- DSU is safer than flashing, but it is not risk-free. The author assumes no responsibility for damage or data loss.
- Forks and modified builds are welcome; please credit the project and link back to the repository.

Wishing you a smooth install. :]

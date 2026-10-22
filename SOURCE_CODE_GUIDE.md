# bDSU Source Code Guide

This document explains the main bDSU v1.2 code paths so that people can inspect, modify, and improve the project under the terms of the GNU General Public License, version 3. The complete license text is included in [`LICENSE`](LICENSE), and third-party components remain under their respective upstream licenses listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Project structure

The Android application is intentionally kept compact. The main application logic is in [`MainActivity.kt`](app/src/main/java/com/bdsu/app/MainActivity.kt), the Android and Kotlin build configuration is in [`app/build.gradle.kts`](app/build.gradle.kts), and the user-facing setup instructions are in [`USER_GUIDE.md`](USER_GUIDE.md).

| Code area | Purpose |
|---|---|
| `RomOption` and `romCatalog` | Describe the available GSI families, versions, architectures, release sizes, and direct download URLs. |
| `DeviceProfile` and `detectDevice()` | Read the device model, Android API level, primary ABI, Treble state, A/B state, and available data storage. |
| `compatible()` | Keep ROM entries whose architecture and minimum storage requirements match the detected device profile. |
| `compatibleFamilies` | Group compatible entries by ROM family so the UI can show an expandable drawer for each family. |
| `DownloadManager` flow | Download selected catalog assets in the background and expose progress through the native Android notification system and the application UI. |
| `localPicker` and local validation | Accept only `.img` and `.img.xz` files selected through the Android document picker. |
| `installLocalDsu()` and `installDsu()` | Route local and catalog images through the same DSU staging pipeline. |
| `installFromStream()` | Stream, optionally decompress, stage, validate, and pass the image to Android’s official DSU installer through Shizuku. |
| `Shell` | Use Shizuku’s authorized process API instead of root exploits or hidden privilege escalation. |
| SD-card staging | Use a detected removable app-specific external directory as temporary staging when the user enables the option; otherwise use internal app-specific storage. |

## ROM catalog and drawer logic

The catalog is represented by `RomOption`. Each item contains a family name, release label, Android version, architecture string, compressed size, and direct release URL. The device profile is calculated once when the screen is created. The catalog is filtered against that profile, then grouped by `family`.

The interface renders one card per family. Tapping a family changes the `expandedFamilies` set and reveals its compatible versions below the family card. Selecting a version stores that exact `RomOption` and clears any previous local-ROM selection, preventing an older source from being installed accidentally. The drawer is intentionally static in v1.2: there are no sound effects or animation overlays.

The catalog URLs point to release assets rather than generic repository pages. Maintainers should check the upstream release asset, architecture, file size, and availability before adding or changing an entry. A catalog entry must never claim compatibility solely because a file can be downloaded; it must also match the supported image format and the device’s Treble/ABI requirements.

## Local ROM flow

The local button uses `ActivityResultContracts.OpenDocument`. The picker requests image and raw-binary MIME categories, but MIME metadata from document providers is not always reliable. Therefore the returned display name is checked again with `isSupportedLocalRomName()`.

Only names ending in `.img` or `.img.xz`, case-insensitively, are accepted. Unsupported files are rejected before a URI permission is persisted and before the DSU install button becomes available. During installation, the validation is repeated in `installLocalDsu()` and `installFromStream()` so a malformed or renamed file cannot bypass the UI check.

For `.img.xz`, Tukaani XZ decodes the stream directly into a staged `.img` file. For `.img`, the stream is copied without decompression. The staged image is then compressed with the device’s available `gzip` command, validated, and passed to Android’s official `android.os.image.action.START_INSTALL` activity through a Shizuku shell process. Temporary files are removed on failure and after the image has been handed to the installer as appropriate.

## SD-card staging and Userspace

SD-card staging is optional and disabled by default. bDSU detects removable app-specific external storage using Android’s external-files directories. Before writing, it creates the staging directory and checks that it is a writable directory. If no removable storage is found, the switch is disabled and the UI states that no SD card was detected.

Some Android builds expose an SD-card app directory to the application but do not allow the Shizuku shell process to create or read the same path. To avoid a false success, the shell command creates and validates the directory before compression. If the failure is a storage-access error, bDSU retries from internal app-specific storage and reports that fallback explicitly. Other DSU errors are not hidden or retried blindly. The DSU installer still uses Android’s official DSU service.

Userspace storage is independent of the staging location. When enabled, the selected size is passed as the DSU userdata-size argument and is limited to usable internal device space, the project’s minimum, and the 50 GB maximum. SD-card free space is shown only as temporary staging capacity; it is never used to claim that persistent DSU userdata can be allocated on the SD card.

## Shizuku boundary

bDSU does not include root, bootloader exploits, or privilege-bypass code. The `Shell` object checks the Shizuku binder and permission state, then invokes the supported Shizuku process API. Installation is refused when Shizuku is unavailable or permission has not been granted. This keeps the privileged operation explicit and user-controlled.

## GPLv3 and contribution expectations

The project’s own source is released under GPLv3, with the full license in [`LICENSE`](LICENSE). Contributors should preserve the copyright and GPL notices, clearly mark substantial modifications, keep the project’s source available when distributing modified binaries, and retain applicable third-party notices. AndroidX, Kotlin, Shizuku, Tukaani XZ, and JUnit are not relicensed by bDSU; their upstream terms remain applicable.

## References

[1]: https://www.gnu.org/licenses/gpl-3.0.html "GNU General Public License v3.0"
[2]: https://developer.android.com/topic/dsu "Android Dynamic System Updates"
[3]: https://github.com/RikkaApps/Shizuku-API "Shizuku API"
[4]: https://tukaani.org/xz/java.html "Tukaani XZ for Java"

The project’s implementation and release assets are available from the [bDSU GitHub repository](https://github.com/ayzlol/bdsu-adb-dsu).

> bDSU’s own code is intended to remain modifiable and redistributable under GPLv3; upstream dependency licenses and Android platform behavior must still be respected when producing modified builds.


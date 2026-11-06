# bDSU Source Code Guide

This guide describes the current source tree and behavior of bDSU (bDSU / adb-dsu). The Android app is intentionally compact: most application logic, UI, download handling, integrity checks, and DSU installation code currently lives in [`MainActivity.kt`](app/src/main/java/com/bdsu/app/MainActivity.kt).

The project is licensed under GPL-3.0-or-later; see [`LICENSE`](LICENSE). Third-party components and their license/source references are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Project map

| Path / code area | Purpose |
|---|---|
| `app/src/main/java/com/bdsu/app/MainActivity.kt` | Main activity, Compose UI, ROM catalog, device information, Shizuku shell bridge, download monitoring, checksum verification, staging, and DSU launch. |
| `app/src/main/AndroidManifest.xml` | App permissions, Shizuku package visibility, launcher activity, and Shizuku provider declaration. |
| `app/src/main/res/` | App theme, strings/colors, icons, backup rules, and network security configuration. |
| `app/build.gradle.kts` | Android application ID, SDK levels, version, release signing configuration, and dependencies. |
| `build.gradle.kts`, `settings.gradle.kts` | Root Gradle plugin and project configuration. |
| `gradle/libs.versions.toml` | Version catalog for Gradle plugins and libraries. |
| `app/proguard-rules.pro` | Keep rules required for Shizuku under release shrinking/obfuscation. |
| `USER_GUIDE.md` | End-user setup and usage guidance. |
| `BUILDING.md` | Build requirements and commands. |
| `ROM_SOURCES.md` | Notes about ROM catalog sources and release assets. |

## App flow and main types

### Device information

`DeviceProfile` and `detectDevice()` collect device model, Android API level, primary ABI, available data storage, Treble/A-B indicators, bootloader state, and whether a DSU image appears to be loaded. System properties are read with `getprop`. This information is displayed for the user and diagnostics; **the current UI does not filter catalog entries by device compatibility**. A listed image is not a guarantee that it will boot on a particular device.

### ROM catalog and selection

`RomOption` describes a catalog item: family, display/version label, Android version, architecture label, expected compressed byte size, release URL, SHA-256 digest, and minimum API metadata. `romCatalog` currently contains 16 entries grouped by family in the UI. The UI shows the catalog unfiltered; the architecture and labels are informational and should not be treated as an automatic compatibility check.

Before a catalog download is queued, `isAllowedRomUrl()` restricts its URL to HTTPS GitHub release-download paths. When updating the catalog, maintainers should independently check that the upstream asset exists and that its URL, file size, digest, architecture, and release information all correspond to the same file. A digest must be a complete 64-character hexadecimal SHA-256 value.

### Download and verification

Catalog assets are downloaded with Android `DownloadManager` into app-specific external Downloads storage. The UI monitors progress, offers Wi-Fi-only behavior, and retries failed requests. Downloads are subject to a timeout.

After download completion, the app checks the expected file size and SHA-256 when the catalog entry contains a valid digest. Installation performs another size and SHA-256 check immediately before staging, so a changed or truncated file is rejected. During installation, the app also looks for publisher-hosted companion checksum files; when a matching checksum is found, it is checked against the already-computed digest. If no publisher checksum is available, the catalog SHA-256 remains the integrity reference. A catalog download without a valid SHA-256 is not installable.

Local files are intentionally treated differently: the user selects and supplies them, so the app does not block local installation based on a catalog or publisher checksum. A possible catalog match is informational only, not a verification claim.

### Local ROM selection

The UI uses Android's document picker (`ActivityResultContracts.OpenDocument`). File names are checked with `isSupportedLocalRomName()`. Currently accepted suffixes are `.img`, `.img.xz`, and `.img.gz` (case-insensitive). The name is not a substitute for validating that the selected file is a usable GSI; users should only select images they trust.

The selected URI is retained with a persistable read permission when the document provider supports it. The local installation flow can copy an uncompressed `.img`, decompress `.img.xz` using Tukaani XZ, or decompress `.img.gz` in the generic staging path. A sufficiently large `.img.gz` with a readable gzip trailer can use a direct-copy public-Downloads path before falling back to the generic pipeline. Catalog `.xz` images also have an optimized single-pass XZ-to-gzip route; if it cannot launch DSU, the general staging/launch pipeline is used.

### Shizuku shell boundary

`Shell` checks Shizuku binder and permission state before privileged operations. It invokes Shizuku's process implementation and runs shell commands through that authorized process; the app does not implement root exploits or bootloader privilege bypasses. The shell bridge merges stderr into stdout, reads output on a worker thread, limits captured output, and destroys the process if its coroutine is cancelled.

The implementation resolves Shizuku's `newProcess(String[], String[], String)` method reflectively. The Shizuku keep rules in `app/proguard-rules.pro` are significant for minified release builds. The manifest's Shizuku API permission and provider declaration are also needed for binder delivery and permission requests.

### Staging and DSU launch

`installFromStream()` and `prepareImageFile()` stage the image in app-specific storage. Internal app storage is the default. If removable app-specific storage is available and the user enables SD staging, that location is used; when the shell cannot access the staged SD file, the flow can copy it to internal staging and retry. The code checks available space where possible, limits decompressed image size, caps XZ decoder memory, and uses buffered streaming rather than loading multi-gigabyte images into memory.

The launch sequence prefers the documented DSU verification flow:

1. Prepare or gzip the image in the user-visible public `Download` directory.
2. Start the system `com.android.dynsystem.VerificationActivity` through Shizuku with the DSU install action and image-size/userdata extras.
3. If the documented route fails, try the legacy launch path using the staged image.

Staged files are retained for the installer and marked for cleanup on process exit rather than removed immediately after launching the system confirmation flow. The code also removes stale app-owned public gzip payloads before creating another one.

Persistent userdata is optional. The UI offers presets from 3 GB upward, limited by its free-space estimate and a 50 GB maximum. Choosing the temporary option does not request persistent userdata. Device, Android-version, storage, and vendor DSU behavior can still affect installation and boot success.

### UI and preferences

The interface is built with Jetpack Compose and Material 3. `BdsuTheme()` uses Android dynamic colors on Android 12+ when enabled, and otherwise uses the app's blue color scheme. Settings include Wi-Fi-only downloads, dynamic colors, optional SD staging when available, and the system DSU Loader flag. User choices are stored in app `SharedPreferences`.

The Shizuku status is observed through binder/permission listeners and a periodic refresh. The UI also includes download/install progress, device diagnostics, and a license/disclaimer dialog.

## Maintenance notes

- Keep this guide aligned with `MainActivity.kt`; application behavior is not split into the older modules/functions described by previous guide revisions.
- Do not claim that every catalog item is compatible with every device. The catalog is currently displayed without device filtering.
- Verify upstream release assets, expected sizes, and SHA-256 values before changing catalog entries.
- Preserve the distinction between catalog-download integrity verification and user-selected local files.
- Test minified release builds when changing Shizuku reflection or ProGuard rules.
- DSU support and installer behavior vary across Android releases and vendor builds. Validate changes on suitable test devices; launching the installer does not guarantee a successful boot.
- Keep user-facing warnings clear: installing a GSI can affect device usability and requires a compatible device/setup.

## Related documentation

- [User guide](USER_GUIDE.md)
- [Build instructions](BUILDING.md)
- [ROM source notes](ROM_SOURCES.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Android Dynamic System Updates](https://source.android.com/docs/core/ota/dynamic-system-updates)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Tukaani XZ for Java](https://tukaani.org/xz/java.html)

# bDSU User Guide

This guide explains how to prepare a device, connect bDSU to Shizuku, download or choose a ROM image, and start an Android DSU installation. The app's behavior and available catalog entries can change between releases; verify the details shown in the app and on the upstream ROM release page before installing.

> **Important:** DSU/GSI compatibility depends on the device model, Android build, Treble implementation, ABI, partition layout, bootloader state, vendor firmware, and free storage. bDSU displays its catalog without automatically filtering it for device compatibility. A successful download or installer launch does not guarantee that the image will boot. Keep a working recovery path and follow your device manufacturer's recovery instructions.

## Requirements and preparation

- bDSU supports Android API 29 (Android 10) and newer according to the current app configuration. Actual DSU availability varies by device and Android build.
- The device must support the selected GSI and Android DSU implementation. An unlocked bootloader is commonly required for DSU use; bDSU does not unlock it or bypass device security.
- Keep ample internal free space. Images can be large, and installation may need space for the downloaded archive, a decompressed image, a gzip staging copy, and optional DSU userdata. The app checks some staging-space conditions, but device storage behavior can vary.
- Install and start [Shizuku](https://github.com/RikkaApps/Shizuku). bDSU uses Shizuku's authorized shell access for staging and launching DSU; it does not provide root or a privilege bypass.
- Use only ROM images you trust and that are built for your device's architecture and partition type.

## Install and start Shizuku

Install Shizuku from its official project distribution and follow the setup instructions displayed by Shizuku. Depending on Android version and device, Shizuku can be started using Wireless debugging or a computer with ADB. Enable Developer options and the required debugging option in Android Settings if needed.

For ADB startup, use the commands and instructions provided by your installed Shizuku version. The startup method can change across Shizuku releases and manufacturer builds, so do not rely on a command copied from an unrelated device as a universal procedure.

Leave Shizuku's service running, then open bDSU and press **Connect Shizuku**. Approve the permission request in Shizuku. bDSU should report that the service is connected and permission is granted. If it does not, confirm that Shizuku is running and that bDSU has not been denied or had its permission revoked.

Shizuku is needed for DSU installation and launch. Browsing the catalog and downloading a catalog image use Android's DownloadManager and do not by themselves require Shizuku.

## Review device information

The device card can show the model, Android API level, primary ABI, bootloader information, DSU-loaded state, and storage-related information. Diagnostics may also report Treble and A/B indicators.

These readings are informational. The app currently shows all catalog entries rather than filtering them by device, so compare the ROM's architecture and requirements with your device yourself. For example, an `arm64-ab` label alone does not establish that a specific phone is compatible. Consult the ROM publisher and device-specific community documentation before proceeding.

## Choose a ROM

### Download a catalog build

1. Expand a ROM family in the catalog.
2. Review the build label, Android version, architecture, and displayed size.
3. Select the exact build you intend to use.
4. Press the download button and allow the download to finish.

Catalog links are HTTPS GitHub release-download URLs. Downloads use Android DownloadManager and are stored in the app-specific Downloads directory. Wi-Fi-only downloading is enabled by default; change it in Settings only if you accept the mobile-data use. The app displays progress, speed, and estimated time, and may retry a failed download. A download has a four-hour timeout.

When a catalog download finishes, bDSU checks its expected size and catalog SHA-256 when the catalog entry has a valid digest. The image is checked again during installation. A mismatch causes the catalog download to be rejected rather than passed to DSU. The app may also look for a publisher-hosted checksum file during installation; if a matching checksum is found, it is checked against the image.

### Select a local image

Use the local ROM picker to choose a file whose name ends in `.img`, `.img.xz`, or `.img.gz`. The filename suffix is used to determine the processing path; it does not prove that the file is a valid or compatible GSI. Only select a file from a source you trust.

Local images are not blocked by catalog checksums. The app may display an advisory if a local filename appears to match a catalog build, but this is not a cryptographic integrity verification. The document provider must keep the selected file accessible while installation is being prepared.

## Optional settings and userdata

After selecting or completing a ROM, bDSU asks whether to allocate persistent DSU userdata. Selecting temporary userdata does not request a persistent userdata size. Persistent userdata options begin at 3 GB and are offered only when the app's available-space estimate permits them, up to 50 GB. The estimate reserves space for image staging and other overhead; actual requirements can differ by device.

Settings include:

- **Wi-Fi only download:** enabled by default; controls whether downloads may use metered networks.
- **Use phone colors:** uses Android dynamic colors on Android 12 and newer when enabled; otherwise the app uses its blue theme.
- **Use SD card for DSU staging:** available when removable app-specific storage is detected. It is off by default. This is for temporary image staging, not for moving DSU userdata.
- **System DSU Loader screens:** an optional system setting controlled through Shizuku on supported builds.

A device may expose removable storage to the app but not to the Shizuku shell process. If SD staging cannot be read or used, bDSU may retry from internal app-specific storage. Ensure internal storage has enough room for this fallback.

## Install into DSU

1. Confirm the selected image is the intended build and Shizuku is connected with permission granted.
2. Review the userdata choice and staging settings.
3. Press **Install into DSU** once and keep the app and Shizuku available while the operation runs.
4. Watch the progress panel for verification, decompression/copying, staging, and launch status.
5. When Android's DSU verification/installer screen appears, review its information and follow its instructions. bDSU does not silently reboot the phone.

The app prepares the image in app-specific staging. Depending on image type and device support, it may use an optimized path, including converting an XZ image to gzip in one pass. The primary launch route places a gzip payload in the public Downloads directory and starts Android's DSU VerificationActivity through Shizuku. If that route fails, the app may try a legacy launch using the staged image. Files needed by the system installer are not removed immediately after the confirmation screen is opened.

Do not delete staging files or force-stop bDSU while installation is preparing the image. Keep sufficient free space in public Downloads as well as in the selected staging location.

## Downloads, errors, and recovery

You can cancel a download while it is active. The install-preparation action is not presented as safely cancellable once it has started; do not force-stop the app or interrupt Shizuku during that work.

If something fails, note the exact status and stage shown in the app. Open **Debug & Diagnostics** for device, storage, and Shizuku information. Common areas to check include:

| Symptom | Checks |
|---|---|
| Download fails or times out | Network access, free storage, DownloadManager status, and whether the upstream release asset is still available. |
| Size or SHA-256 mismatch | Do not install that downloaded file. Remove/re-download it and confirm the catalog entry still refers to the intended upstream asset. |
| Shizuku permission required | Start Shizuku and grant or re-grant bDSU permission. |
| Unsupported local file | Use a readable `.img`, `.img.xz`, or `.img.gz` file and keep its filename extension intact. |
| Not enough staging space | Free storage, use the optional SD staging setting if available, or choose a smaller image/userdata allocation. |
| DSU launch fails | Check DSU availability, the device's vendor build, Shizuku access to the staged path, and the ROM's device requirements. |

If the device fails to boot or repeatedly returns to the previous system, stop retrying the image. Use the recovery process documented by your device manufacturer and seek device-specific support. Do not assume that opening the DSU installer means the image is safe or will boot.

## Build from source

See [BUILDING.md](BUILDING.md) for JDK, Android SDK, Gradle, and release-signing details. Review [SOURCE_CODE_GUIDE.md](SOURCE_CODE_GUIDE.md), [ROM_SOURCES.md](ROM_SOURCES.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for implementation and redistribution information.

## References

- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [Android Generic System Image documentation](https://developer.android.com/topic/generic-system-image)
- [Android Dynamic System Updates](https://source.android.com/docs/core/ota/dynamic-system-updates)
- [bDSU repository](https://github.com/ayzlol/bdsu-adb-dsu)

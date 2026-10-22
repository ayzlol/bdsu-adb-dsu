# bDSU User Guide

This guide explains how to install and use bDSU for the first time. The original project README is intentionally kept unchanged; this file provides the structured setup and usage steps separately.

> **Important:** DSU support depends on the device, Android version, Treble implementation, ABI, partition layout, bootloader state, vendor firmware, and available storage. Read the warnings before starting. Keep a backup of important data and do not reboot while Android is still processing the DSU installation.

## What you need

Before using bDSU, make sure that the phone has an unlocked bootloader, runs Android 10 or newer, supports Treble and DSU, and has enough free storage for the downloaded GSI, temporary image processing, and optional persistent Userspace storage.

You also need [Shizuku](https://github.com/RikkaApps/Shizuku) running with permission granted to bDSU. bDSU uses Shizuku for authorized shell access; it does not use an exploit or bypass Android security.

## 1. Install bDSU

Download the APK from the [GitHub v1.2 release](https://github.com/ayzlol/bdsu-adb-dsu/releases/tag/v1.2). If Android asks for permission to install from this source, allow the file manager or browser that opened the APK, then return to the installer.

If an older package with the same application identity is already installed and Android reports a signature conflict, uninstall the old bDSU build or install the APK signed with the same key as the existing installation.

## 2. Start Shizuku with ADB or Wireless debugging

Install Shizuku from its official project page and open it. Enable Developer options and USB debugging or Wireless debugging in Android Settings.

For Wireless debugging, pair Shizuku with the pairing code shown by Android. Follow Shizuku's own on-screen instructions because the exact menu names vary between Android versions and manufacturers.

For an ADB-connected computer, the general sequence is:

```bash
adb devices
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

The exact Shizuku startup method can vary by Shizuku version and device. Use the method shown inside the Shizuku application if the command above is not available on your device.

Return to bDSU after Shizuku reports that its service is running.

## 3. Connect bDSU to Shizuku

Open bDSU and press **Connect Shizuku**. When the Shizuku permission dialog appears, allow bDSU. The status should show that Shizuku is connected and permission has been granted.

If the status does not change, open Shizuku and check that its service is running. Also check that bDSU has not been denied or revoked in Shizuku's application list.

ROM downloading does not require Shizuku. Shizuku is required when bDSU prepares and starts the DSU installation.

## 4. Check the detected device profile

The device card shows information such as the manufacturer, model, Android/API level, ABI, Treble status, and available storage. Only choose a ROM that matches the device's architecture and GSI requirements.

A ROM marked `arm64-ab` is not automatically compatible with every arm64 phone. The device must also support the required Treble and partition configuration. If the application marks the device as unsupported, do not force the installation.

## 5. Select and download a ROM

Choose a ROM family from the catalog, such as crDroid or LineageOS, to open its drawer. Select one of the device-compatible versions shown inside the drawer, then press **Download selected ROM**. bDSU keeps the ROM's actual filename instead of renaming every file to `bdsu.img`.

The application shows the download percentage, current operation, speed, estimated time, and system notification. You can press **Cancel** while downloading. If a catalog link is unavailable, use **Choose local ROM** and select a compatible `.img` or `.img.xz` file that you already downloaded or built. Other formats are rejected by the picker and by the install pipeline.

Do not rename a compressed archive manually unless you know its format. A file extension does not make an image compatible with DSU.

## 6. Configure optional Userspace storage

The Userspace control appears after a ROM has been selected or downloaded. Persistent Userspace is optional.

When it is disabled, bDSU starts DSU without requesting persistent user storage. When it is enabled, select a value between **3 GB** and the maximum amount offered by the application. The maximum is limited by available device storage and cannot exceed 50 GB.

After you press **Install into DSU**, the Userspace control is locked for that operation. Do not change the value or delete the staging files while installation is in progress.

## SD-card staging

After a ROM has been selected or downloaded, the **Use SD card for DSU staging** option appears. It is off by default. On a device without removable storage, the option remains disabled and displays **No SD card detected on this device**. When enabled on a compatible device, the SD card is used for temporary image staging; Android’s official DSU service still performs the final installation through Shizuku. bDSU checks that the staging directory is writable. If the Shizuku shell cannot access the removable path, bDSU retries with internal app-specific staging and reports that fallback instead of silently continuing.

SD-card staging space and persistent DSU Userspace are separate. The Userspace slider is limited by internal device storage because Android’s DSU userdata allocation is not automatically moved to the SD card. SD-card free space increases only the temporary staging capacity shown by the application.

## 7. Install into DSU

Confirm that Shizuku is connected and that the selected ROM is complete. Press **Install into DSU** once.

The progress panel reports the current stage, for example:

```text
Preparing image
Decompressing image
Staging image
Validating compressed image
Creating DSU configuration
Starting DSU installer
```

The action can take time because a large image may need to be decompressed and copied. Keep bDSU and Shizuku available in the background and do not force-stop either application.

When Android's DSU installer appears, read its own confirmation screen carefully. Do not reboot until the installer has finished. bDSU does not silently reboot the phone.

## 8. Cancel or recover from an error

Use **Cancel** while an operation is active. bDSU should stop the active download or shell operation and remove temporary files. If an error appears, open the debug details and record the stage, exit code, file path, and raw result.

Useful distinctions include:

| Error | Likely area to inspect |
|---|---|
| Download failed | URL, network, redirect, TLS, Download Manager, or storage |
| Shizuku permission required | Shizuku service or bDSU permission state |
| Image is empty or incomplete | Download interruption or invalid local file |
| gzip/XZ error | Archive format or device decompression support |
| DSU installer failed | Device DSU/dynsystem support, staging path, image metadata, or vendor firmware |
| Phone returns to the lock screen | Stop retrying; investigate device firmware and DSU compatibility before another attempt |

If the phone soft-bricks or repeatedly returns to the lock screen, stop using DSU immediately and recover the normal system using the device manufacturer's documented recovery method. Do not repeatedly press **Install into DSU**.

## 9. Build from source

Developers can clone and build the project using the instructions in [BUILDING.md](BUILDING.md):

```bash
git clone https://github.com/ayzlol/bdsu-adb-dsu.git
cd bdsu-adb-dsu
./gradlew clean test assembleRelease
```

The source code, Gradle wrapper, GPLv3 license, build notes, and third-party notices are available in the repository. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing modified builds.

## References

1. [Shizuku official project](https://github.com/RikkaApps/Shizuku)
2. [Android Generic System Image documentation](https://developer.android.com/topic/generic-system-image)
3. [bDSU GitHub repository](https://github.com/ayzlol/bdsu-adb-dsu)
4. [bDSU v1.2 release](https://github.com/ayzlol/bdsu-adb-dsu/releases/tag/v1.2)

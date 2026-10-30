# bDSU (adb-dsu) — Android DSU Sideloader

**v1.3.2 (versionCode 114)**  
Authors: unfayd & ayzlol (the_pan)  
License: GNU General Public License v3.0 (GPLv3)

---

## 📱 About the App

bDSU is a free, open-source sideloader that makes installing **DSU (Dynamic System Update)** GSI (Generic System Image) builds easier on phones with an unlocked bootloader.

With bDSU you can:
- Automatically detect your device and view compatible custom ROM options.
- Select a ROM from the catalog and download it directly in the app.
- Install your own downloaded or created `.img`, `.img.xz`, or `.img.gz` file as a local ROM.
- **SHA-256 + MD5 checksum verification at download time** — corrupt or tampered files are blocked.
- Allocate persistent **userdata storage** during DSU installation (3–50 GB, based on free space).

The app works through **Shizuku**, so Shizuku must be enabled and running on your device.

---

## ⚡ Features (v1.3.2)

| Feature | Description |
|---------|-------------|
| **Automatic device detection** | Model, Android API level, ABI, Treble, A/B support, and free storage are read. |
| **Bootloader status** | Shows locked / unlocked / unknown via `ro.boot.flash.locked` and `ro.boot.verifiedbootstate`. |
| **DSU status** | Shows whether a GSI is currently running via `ro.dsuloaded`. |
| **ROM catalog** | 16 pre-vetted GSI builds (AOSP, LineageOS, ArrowOS, DerpFest, crDroid, etc.), all SHA-256 verifiable. |
| **Unfiltered list** | All ROMs are listed; you decide compatibility. |
| **SHA-256 verification** | Hash verification for downloads and local ROMs. Publisher-site `.sha256` / `.md5` files are also supported. |
| **Wi-Fi-only download** | Enabled by default to avoid mobile data usage. |
| **SD card staging** | Use removable SD card for DSU staging space. |
| **Userdata storage** | Allocate persistent storage to DSU so apps/data survive reboots. |
| **Optimized xz→gz conversion** | `.xz` files are repackaged to `.img.gz` in a single pass without writing the raw image to disk. |
| **DSU Loader flag** | Enable the built-in DSU Loader screens in Android Settings (`persist.sys.fflag.override.settings_dynamic_system`). |
| **Easter egg** | Tap the "Custom/AOSP ROM downloader" card 5 times quickly — something appears. Fades away in 4 seconds. |

---

## 🔐 Security and Privacy

- **No secrets are committed:** no keys, passwords, API tokens, or signing files (`*.keystore`, `*.jks`, `local.properties` are all in `.gitignore`).
- The signing keystore is kept locally only and is never uploaded to GitHub.
- SHA-256 / MD5 verification is performed at download time; mismatching files are deleted.
- Code is open under **GPLv3** — you may modify and redistribute it (with attribution).

---

## 📦 Installation (APK)

Latest signed APK:

```
app/build/outputs/apk/release/app-release.apk
```

Install command:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> **Note:** If you have an older version installed, `-r` (overlay install) works fine because the signing certificate is the same.

---

## 🛠️ Building from Source

### Requirements

- **JDK 17** (e.g. `~/.bdsu-build/jdk-17.0.20.1+1`)
- **Android SDK** (compileSdk 34, build-tools 36.0.0)
- **Gradle 8.4 wrapper** (included)
- **Physical Android device** (DSU behavior may not work correctly on emulators)

### 1. Clone the repository

```bash
git clone https://github.com/ayzlol/bdsu-v1.git
cd bdsu-v1
```

### 2. Configure SDK path

`local.properties` is **not committed** (it is in `.gitignore`). Create it locally:

```bash
echo "sdk.dir=/home/YOUR_USERNAME/Android/Sdk" > local.properties
```

### 3. Set signing passwords (optional, for your own keystore)

```bash
export KEYSTORE_PASSWORD=YOUR_PASSWORD
export KEY_PASSWORD=YOUR_PASSWORD
```

Or pass them to Gradle with `-Dkeystore.password` / `-Dkey.password`.

### 4. Build

```bash
./gradlew assembleRelease --no-daemon
```

The signed APK will be at:

```
app/build/outputs/apk/release/app-release.apk
```

### 5. Install on device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 📂 Code Structure (what lives where)

| File / Folder | Purpose |
|---------------|---------|
| `app/src/main/java/com/bdsu/app/MainActivity.kt` | All app logic: ROM catalog, download monitor, SHA verification, DSU staging and launch, Shizuku integration, Jetpack Compose UI. |
| `app/src/main/AndroidManifest.xml` | App manifest: permissions, Shizuku provider, network security config. |
| `app/build.gradle.kts` | Build settings: version code/name, signing config, dependencies (Shizuku 13.1.5, Compose, XZ, etc.). |
| `app/src/main/res/drawable/app_icon.png` | App icon (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi). |
| `app/src/main/res/drawable/easter_egg.png` | Easter egg image. |
| `app/src/main/res/xml/network_security_config.xml` | Network security: system certificates only, cleartext disabled. |
| `get_sha256.sh` | Script to compute ROM SHA-256 hashes. |
| `gradle/libs.versions.toml` | Dependency versions (Shizuku, Compose, XZ, etc.). |
| `proguard-rules.pro` | R8 rules (Shizuku classes are kept). |
| `USER_GUIDE.md` | User guide. |
| `SOURCE_CODE_GUIDE.md` | Code structure and logic guide. |
| `BUILDING.md` | Build instructions. |

### Key functions

- `detectDevice()` — Detects device model, API, ABI, Treble, A/B, bootloader state, and free space.
- `Shell.run()` — Runs commands through Shizuku (stderr+stdout merged, deadlock-protected).
- `installFromStream()` — Installs downloaded or local ROM: verification → staging → DSU launch.
- `launchDsuDocumentedFlow()` — Official AOSP DSU flow: creates `.img.gz` in public Downloads and launches `com.android.dynsystem.VerificationActivity`.
- `launchDsuFromStaging()` — Legacy fallback: raw `am start-activity` intent.
- `verifySha256()` / `computeBothDigests()` — SHA-256 + MD5 calculation (single pass, 1 MB buffer).
- `fetchPublisherChecksum()` — Downloads `.sha256` / `.md5` companion files from the publisher site and compares.
- `BdsuTheme()` — Material You dynamic colors (Android 12+) or full-blue bDSU fallback theme.

---

## 🎮 Usage Flow

1. **Connect Shizuku:** Open Shizuku on your device, then tap "Connect Shizuku" in the app and approve the permission in Shizuku.
2. **Check bootloader:** The "Detected device" card shows bootloader state. If it is locked, DSU installation may not work — unlock it first.
3. **Pick a ROM:** Select one from the catalog, or tap "Select local ROM" to use your own `.img` / `.img.xz` / `.img.gz` file.
4. **Download:** Tap "Download selected ROM". SHA-256 verification runs automatically.
5. **Choose userdata:** When the download finishes, you are asked whether to allocate persistent userdata (3–50 GB).
6. **Install to DSU:** Tap "Install into DSU". The system DSU confirmation screen appears — confirm and reboot your device.

---

## 🦆 Easter Egg

Tap the **"Custom/AOSP ROM downloader"** header card **5 times quickly** (within 1.5 seconds). An image appears in the center of the screen and **fades out over 4 seconds**. Tap anywhere to dismiss it early.

---

## 🚀 Creating a GitHub Release

To publish the latest APK on GitHub Releases:

1. Go to [https://github.com/ayzlol/bdsu-v1/releases](https://github.com/ayzlol/bdsu-v1/releases).
2. Click **"Draft a new release"**.
3. **Tag version:** `v1.3.2` (or `1.3.2`).
4. **Release title:** `bDSU v1.3.2 (114)`.
5. **Description:** You can paste something like this:

```markdown
## bDSU v1.3.2 (114)

### What's new
- SHA-256 + MD5 publisher checksum verification
- Checksum enforcement removed for local ROMs (user choice)
- Optimized single-pass xz→gz DSU staging
- Bootloader state and DSU running status indicators
- Userdata storage picker (3–50 GB)
- DSU Loader flag toggle from Settings
- New app icon
- Easter egg (5 quick taps)

### Install
`adb install -r app-release.apk`

### Source
All code is in this repository, open source under GPLv3.
```

6. **Assets:** Drag and drop this file:

```
app/build/outputs/apk/release/app-release.apk
```

7. Click **"Publish release"**.

---

## 🤝 Contributing

You can make code changes and open a Pull Request. For larger changes, please open an issue first to discuss. All contributions are evaluated under GPLv3.

---

## ⚠️ Disclaimer

DSU operations can permanently damage your device (unlocked bootloader required, risk of data loss). The authors are not responsible for any damage. Back up your data and use at your own risk.

---

*Made with ☕ and curiosity by unfayd & ayzlol (the_pan)*

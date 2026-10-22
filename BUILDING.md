# Building Bdsu adb-dsu

This document explains how to clone and build the project from source.

## Requirements

Use Android Studio or the included Gradle wrapper with JDK 17. The project compiles against Android API 34 and supports Android API 29 or newer at runtime.

## Build

Clone the repository and enter the project directory:

```bash
git clone https://github.com/ayzlol/bdsu-adb-dsu.git
cd bdsu-adb-dsu
```

Build and run the unit tests with the included wrapper:

```bash
./gradlew clean test assembleRelease
```

The unsigned release artifact is created under `app/build/outputs/apk/release/`. Android Studio can be used to sign the APK for personal distribution. The public v1.2 APK and source archive are available from the GitHub Releases page.

## v1.2 source changes

The v1.2 source adds family-based ROM drawers, device-compatible version filtering, strict `.img` / `.img.xz` local-ROM validation, removable SD-card staging, crDroid and LineageOS catalog entries, and the Shizuku-based DSU staging path. See [SOURCE_CODE_GUIDE.md](SOURCE_CODE_GUIDE.md) for the implementation logic and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licensing.

## Development notes

The application uses Jetpack Compose for the interface, Shizuku for authorized shell access, Android Download Manager for ROM downloads, and Tukaani XZ for `.img.xz` preparation. DSU behavior must be tested on a compatible physical device; emulator or desktop builds cannot validate vendor-specific DSU behavior.

The source archive and the repository contain the Gradle wrapper and all project source files required to inspect, modify, and rebuild the application. Build outputs, local SDK paths, signing keys, and user-specific files are intentionally not committed.

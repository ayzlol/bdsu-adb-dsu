# Building bDSU (adb-dsu)

This document describes how to build the current Android application from source. It covers debug builds, tests, release builds, and the local signing configuration. It does not include private signing keys; never commit a keystore or passwords to the repository.

## Requirements

- JDK 17.
- Android SDK with Android API 34 (the configured `compileSdk`) and the required Android build tools installed. Android Studio can install these through SDK Manager.
- The repository's Gradle wrapper. A separate Gradle installation is not required.
- Network access for Gradle to download plugins and dependencies on the first build.

The app's current configuration uses `minSdk = 29`, `targetSdk = 34`, Java/Kotlin JVM target 17, Android Gradle Plugin 8.3.2, and Kotlin 1.9.22. Runtime DSU support still depends on the device and Android build.

## Get the source

```bash
git clone https://github.com/ayzlol/bdsu-adb-dsu.git
cd bdsu-adb-dsu
```

On Linux or macOS, ensure the wrapper is executable if needed:

```bash
chmod +x gradlew
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Build and test

Run unit tests and assemble a debug APK:

```bash
./gradlew clean test assembleDebug
```

The debug APK is written under:

```text
app/build/outputs/apk/debug/
```

To assemble a release variant, use:

```bash
./gradlew assembleRelease
```

Release builds enable code minification and resource shrinking. Test a release build as well as a debug build when changing Shizuku reflection, provider integration, or ProGuard rules. The project includes `app/proguard-rules.pro` keep rules for Shizuku classes used by the app.

## Release signing

The release signing configuration expects a keystore at:

```text
app/bdsu-release.keystore
```

The configured key alias is `bdsu`. Provide the keystore and key passwords through environment variables:

```bash
export KEYSTORE_PASSWORD='your-keystore-password'
export KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

The build script also accepts the Java system properties `keystore.password` and `key.password`. Do not place real passwords in source files, Gradle files, command history, or committed CI configuration. Use a secure local secret store or CI secret variables. The keystore file is intentionally not part of the repository.

The script only fills in the release signing configuration when the expected keystore exists and both passwords are provided. If signing is not configured, Gradle may be unable to produce the configured release variant; do not assume the release output is automatically an unsigned APK. For local testing, the debug variant is signed with the standard debug key. For distribution, sign with the intended private key and verify the resulting APK and its signature.

## Useful Gradle tasks

```bash
./gradlew tasks                 # List available tasks
./gradlew test                  # Run unit tests
./gradlew assembleDebug         # Build the debug APK
./gradlew assembleRelease       # Build the minified release variant
./gradlew clean                 # Remove generated build outputs
```

Generated APKs and build intermediates are under `app/build/` and should not be committed.

## Implementation and device testing

The UI is built with Jetpack Compose and Material 3. Catalog downloads use Android DownloadManager. Shizuku provides the authorized shell process used for staging/launch operations; Tukaani XZ handles XZ decompression. See [`SOURCE_CODE_GUIDE.md`](SOURCE_CODE_GUIDE.md) for the application flow and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for dependencies and notices.

A successful Gradle build does not verify that a ROM is compatible with a particular device. DSU installation and boot behavior vary across Android releases and vendor builds. Test changes on suitable devices, and do not use a primary device without understanding its recovery procedure.

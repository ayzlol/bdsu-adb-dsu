# ROM source notes

This document records how the current bDSU catalog is organized and what maintainers should verify when changing a catalog entry. ROM catalog data and the download allowlist are implemented in `romCatalog` and `isAllowedRomUrl()` in [`MainActivity.kt`](app/src/main/java/com/bdsu/app/MainActivity.kt).

## Current catalog sources

The current catalog contains 16 entries grouped into five families. The release assets are hosted in these upstream GitHub repositories:

| Family | Upstream repository | Catalog entries |
|---|---|---|
| AOSP | [`ponces/treble_aosp`](https://github.com/ponces/treble_aosp) | Android 15 and 16 builds; vanilla and GApps variants. |
| DerpFest | [`KoysX/treble_DerpFest_GSI`](https://github.com/KoysX/treble_DerpFest_GSI) | Android 14 arm64 bgN Full, Mini, and Pico builds. |
| ArrowOS | [`naz664/ArrowOS_gsi`](https://github.com/naz664/ArrowOS_gsi) | Android 13 arm64 bgN/bvN builds, including Slim. |
| crDroid | [`naz664/crDroid_gsi`](https://github.com/naz664/crDroid_gsi) | crDroid 10.13 arm64 bgN/bvN builds. |
| LineageOS | [`Doze-off/lineage_treble`](https://github.com/Doze-off/lineage_treble) | LineageOS 23.2 Android 16 vanilla, microG, and GApps builds. |

These are third-party projects; bDSU does not build or publish the ROM images. Upstream repositories and their release assets can change, disappear, or be replaced. Check the upstream release page and the exact asset before relying on an entry.

## Catalog behavior

Each `RomOption` records the family, display/version string, Android version, architecture label, expected compressed asset size, direct release URL, SHA-256 value, and minimum API metadata. The current interface displays catalog entries without device compatibility filtering. Architecture labels are informational; they are not a device compatibility guarantee.

Before queuing a catalog download, the app accepts only HTTPS URLs whose host is `github.com` and whose path includes `/releases/download/`. Do not substitute arbitrary mirrors or non-release URLs without deliberately changing and reviewing this policy.

The app checks the downloaded asset's expected size and catalog SHA-256. It checks the digest again before installation. It also probes for publisher-hosted companion checksum files at install time; those checks are optional and the catalog digest remains the required integrity reference for catalog installs. A valid catalog digest is 64 hexadecimal characters.

## Updating an entry

For each new or changed ROM build:

1. Confirm the upstream project and exact release/tag.
2. Confirm that the direct asset URL resolves to the intended image and that the asset is a supported image format (currently catalog downloads use `.img.xz`).
3. Record the exact compressed byte size; do not use a rounded size from a release page.
4. Obtain the SHA-256 from a trustworthy source and independently verify it against the exact asset where possible. Do not reuse a digest from another variant or architecture.
5. Check the architecture/partition labels and Android version against the upstream build information.
6. Update the display label and relevant source documentation together.
7. Test a download and integrity verification before release. Confirm that the app rejects a deliberately mismatched test digest in a controlled development environment.

If a publisher provides a companion checksum file, the app may discover common names such as `<asset>.sha256`, `<asset>.md5`, `<asset>.sha256sum`, `sha256sums.txt`, `SHA256SUMS.txt`, `md5sums.txt`, or `checksums.txt`. The parser only accepts a matching filename and a 64-character SHA-256 or 32-character MD5 hash. A missing or unrecognized publisher checksum does not replace the catalog SHA-256 requirement.

## Compatibility and user safety

The ability to download an image only means that the URL is reachable. It does not establish that the image will install or boot on any specific device. Users should check the ROM maintainer's device/partition requirements and preserve a recovery path. See [`USER_GUIDE.md`](USER_GUIDE.md) for user-facing cautions and [`SOURCE_CODE_GUIDE.md`](SOURCE_CODE_GUIDE.md) for the download and installation pipeline.

## Related references

- [Android Generic System Image documentation](https://developer.android.com/topic/generic-system-image)
- [Android Dynamic System Updates](https://source.android.com/docs/core/ota/dynamic-system-updates)
- [bDSU source code](app/src/main/java/com/bdsu/app/MainActivity.kt)

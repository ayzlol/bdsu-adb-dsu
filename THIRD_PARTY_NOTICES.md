# Third-party notices

Bdsu adb-dsu is distributed under the GNU General Public License v3.0 (GPLv3) as described in `LICENSE`. The project also uses third-party libraries under their own compatible licenses. Their source code is not relicensed by this project.

| Component | Usage | License/source |
|---|---|---|
| AndroidX and Jetpack Compose | Android UI and application framework | Apache License 2.0; https://developer.android.com/jetpack |
| Kotlin | Application language/runtime support | Apache License 2.0; https://kotlinlang.org/ |
| Shizuku API and provider | Authorized user-service and shell integration | Apache License 2.0; https://github.com/RikkaApps/Shizuku-API |
| Tukaani XZ for Java | `.img.xz` decompression | Public Domain / 0BSD-style license; https://tukaani.org/xz/java.html |
| JUnit | Unit tests | Eclipse Public License 1.0; https://junit.org/junit4/ |

The exact dependency versions are declared in `app/build.gradle.kts` and `gradle/libs.versions.toml`. Users redistributing modified builds should preserve the applicable upstream notices and license terms.

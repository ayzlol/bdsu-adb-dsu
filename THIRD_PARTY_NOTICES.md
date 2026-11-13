# Third-party notices

bDSU's own source is distributed under the GNU General Public License, version 3 or (at your option) any later version. See [`LICENSE`](LICENSE) and the license header in the source files. Third-party components retain their own licenses; this file records the dependencies declared by the current project configuration.

## Declared dependencies

| Component | Current version/configuration | Use | License and source |
|---|---|---|---|
| Android Gradle Plugin | 8.3.2 | Android build and packaging | [Android Open Source Project](https://developer.android.com/build); consult the distribution's license notices. |
| Kotlin | 1.9.22 | Application language and Gradle plugin | [Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/license/README.md); [Kotlin](https://kotlinlang.org/). |
| AndroidX Core KTX | 1.12.0 | AndroidX core utilities | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); [AndroidX](https://developer.android.com/jetpack/androidx). |
| AndroidX Activity Compose | 1.8.2 | Activity integration and Compose activity APIs | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); [AndroidX](https://developer.android.com/jetpack/androidx). |
| Jetpack Compose UI / Material 3 | Compose BOM 2024.02.00 | Compose UI, Material components, and platform integration | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); [Jetpack Compose](https://developer.android.com/jetpack/compose). |
| Shizuku API and provider | 13.1.5 | Binder/permission integration and authorized process access | [Apache License 2.0](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE); [Shizuku API](https://github.com/RikkaApps/Shizuku-API). |
| Tukaani XZ for Java | 1.9 | Decompression of XZ-compressed images | [XZ for Java license information](https://tukaani.org/xz/java.html). |
| JUnit 4 | 4.13.2 | Unit-test dependency | [Eclipse Public License 1.0](https://junit.org/junit4/license.html); [JUnit](https://junit.org/junit4/). |

The project also uses Android platform APIs and libraries brought transitively by the dependencies above. The version catalog is in [`gradle/libs.versions.toml`](gradle/libs.versions.toml); app dependencies are declared in [`app/build.gradle.kts`](app/build.gradle.kts). Consult the actual resolved dependency graph and the corresponding upstream license files when preparing a redistributed binary, since transitive dependencies can change with versions.

## Redistribution notes

When redistributing bDSU or a modified build:

- Preserve the applicable GPL notices and provide the corresponding source as required by the GPL.
- Preserve [`MODIFICATIONS.txt`](MODIFICATIONS.txt) and the modification notices in changed source files; they document the 1.3.3 changes under GPL-3.0 section 5(a).
- Preserve applicable third-party copyright and license notices.
- Check current upstream license terms and include any required notices in the redistributed package or accompanying materials.
- Do not publish private signing keys, keystore passwords, or other credentials.

This notice is informational and is not legal advice. If the resolved dependency set changes, update this document and review the new components' license requirements.

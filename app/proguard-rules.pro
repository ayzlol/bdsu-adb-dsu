-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class rikka.shizuku.provider.** { *; }
-keep class rikka.shizuku.aidl.** { *; }
-keep class rikka.shizuku.IShizukuService { *; }
-keep class rikka.shizuku.IShellProvider { *; }
-dontwarn rikka.shizuku.**
# The app calls org.tukaani.xz directly to decompress payloads; keep the API
# surface stable and suppress warnings for its transitive native classes.
-dontwarn org.tukaani.**
-keep class org.tukaani.xz.** { *; }

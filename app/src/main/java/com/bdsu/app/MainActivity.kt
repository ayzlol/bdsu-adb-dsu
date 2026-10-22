/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * bDSU - Android DSU sideloader
 * Copyright (C) 2025-2026 unfayd and ayzlol (the_pan)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * =============================================================================
 * HOW THIS APP WORKS (developer tour — read top to bottom)
 * =============================================================================
 *
 * bDSU sideloads a GSI ("generic system image") into Android's DSU ("dynamic
 * system update") pipeline without touching the running system. The steps:
 *
 *  1) CATALOG        — [romCatalog]: 16 hand-vetted GSI builds with exact byte
 *                      sizes + SHA-256 digests. Nothing else is downloadable.
 *  2) DOWNLOAD       — Android DownloadManager does a background download into
 *                      app-private external storage (Wi-Fi-only by default).
 *                      On completion the file's size and SHA-256 are verified
 *                      fail-closed: bad files are deleted, never installed.
 *  3) CHECKSUMS      — two sources are trusted, in order:
 *                        a) the publisher's own checksum file next to the
 *                           release (`.sha256`, `md5sums.txt`, …) — fetched at
 *                           install time with a short timeout  ([fetchPublisherChecksum])
 *                      and b) the catalog's SHA-256 as fallback ([verifyDigest]).
 *                      Selected LOCAL files from the document picker are never
 *                      blocked by checksums — the user owns them ([installLocalDsu]).
 *  4) STAGING        — the compressed image is decompressed (xz/gz, 1 MB
 *                      buffers, RAM-capped) into staging ([prepareImageFile]),
 *                      or repackaged straight into public Download as .img.gz
 *                      ([stageDownloadAsGz]) — the single-pass fast path.
 *  5) DSU LAUNCH     — AOSP-documented flow first: gzip into the public
 *                      Download directory (the one location the system DSU
 *                      service can always read) and fire the explicit
 *                      [com.android.dynsystem.VerificationActivity] intent with
 *                      KEY_SYSTEM_SIZE / KEY_USERDATA_SIZE
 *                      ([launchDsuDocumentedFlow]); a bare action intent on the
 *                      raw .img serves as legacy fallback ([launchDsuFromStaging]).
 *                      Images are NEVER deleted from staging until reboot —
 *                      the DSU service streams them only after the user
 *                      confirms the system dialog ([deleteOnRebootStagedFile]).
 *  6) PRIVILEGE      — all shell work runs through Shizuku (binder delivered
 *                      via [rikka.shizuku.ShizukuProvider]; commands go through
 *                      [Shell.run] with merged stdout/stderr so pipes never
 *                      deadlock).
 *  7) UI             — Compose with a Material You dynamic palette on
 *                      Android 12+, falling back to the full-blue bDSU scheme.
 *
 * Quick file map (top → bottom):
 *   colors/constants → ROM catalog → device probing ([detectDevice]) →
 *   [Shell] singleton → [MainActivity] (lifecycle + Shizuku listeners) →
 *   [BdsuTheme] → [DownloadScreen] (all UI + download monitor) →
 *   integrity helpers → install pipelines (steps 4-5) → misc utilities.
 * =============================================================================
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bdsu.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Method
import kotlin.LazyThreadSafetyMode
import kotlin.coroutines.resume

private val BlueBright = Color(0xFF00D4FF)
private val Background = Color(0xFF07111F)
private val Surface = Color(0xFF102039)
private val TextPrimary = Color(0xFFEAF5FF)
private val TextSecondary = Color(0xFF9DB9D5)
private val Green = Color(0xFF49D98A)
private val Red = Color(0xFFFF6D86)
private const val DOWNLOAD_TIMEOUT_MS = 4L * 60L * 60L * 1000L // 4 hours for slow connections
private const val SHIZUKU_POLL_INTERVAL_MS = 1500L
private const val SHELL_MAX_OUTPUT_BYTES = 4 * 1024 * 1024 // 4MB for better error visibility

private data class RomOption(
    val family: String,
    val version: String,
    val android: String,
    val architecture: String,
    val sizeBytes: Long,
    val url: String,
    val sha256: String,
    val minApi: Int = 29
) {
    // Fail closed: anything that is not a well formed 64 char hex digest
    // (TODO_ placeholders, empty strings, truncated values) must never be
    // treated as a usable checksum, otherwise verification compares against
    // garbage and rejects or — worse — silently accepts the wrong file.
    val hasSha256: Boolean = isValidSha256Hex(sha256)
}

private val romCatalog = listOf(
    RomOption("AOSP", "16.0 • 2025-06-25 • Vanilla", "Android 16", "arm64-ab", 882462536L, "https://github.com/ponces/treble_aosp/releases/download/v2025.06.25/aosp-arm64-ab-vanilla-16.0-20250625.img.xz", "f3abc15e85fb3d8050881c0710259f8e12baf8fd5cf05598e672073b7816c831"),
    RomOption("AOSP", "16.0 • 2025-06-25 • GApps", "Android 16", "arm64-ab", 1530400108L, "https://github.com/ponces/treble_aosp/releases/download/v2025.06.25/aosp-arm64-ab-gapps-16.0-20250625.img.xz", "dec9b30e5419548731bb3f796a1a62a0838b49801cf1060571fd7535d8ec0a40"),
    RomOption("AOSP", "15.0 • 2025-05-13 • Vanilla", "Android 15", "arm64-ab", 858600692L, "https://github.com/ponces/treble_aosp/releases/download/v2025.05.13/aosp-arm64-ab-vanilla-15.0-20250513.img.xz", "031db8beea9333843a0e1ff5e702d385e33d63d015091b4cc1a1260f4d2e8aaa"),
    RomOption("AOSP", "15.0 • 2025-05-13 • GApps", "Android 15", "arm64-ab", 1488840956L, "https://github.com/ponces/treble_aosp/releases/download/v2025.05.13/aosp-arm64-ab-gapps-15.0-20250513.img.xz", "a74775acbb9bc91d7f9ef13c4691865bcc37d9bf249c2538dbeec82a13657fa0"),
    RomOption("DerpFest", "14.0 • 2024-02-12 • Full", "Android 14", "arm64-bgN", 1819159984L, "https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-14.0-unofficial-20240212.img.xz", "868a2dc0d7e7fb5ea1ebd1b11d7755a2d4af82c74fbfd1c9f92a12b3294963e8"),
    RomOption("DerpFest", "14.0 • 2024-02-12 • Mini", "Android 14", "arm64-bgN", 1625299652L, "https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-mini-14.0-unofficial-20240212.img.xz", "483bb325b01b5cd9ef0e43ef926754417d601fbffec9da6087792380dba6e0d8"),
    RomOption("DerpFest", "14.0 • 2024-02-12 • Pico", "Android 14", "arm64-bgN", 1472843916L, "https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-pico-14.0-unofficial-20240212.img.xz", "286734dd459adea5b0566bbbc6c14f95b30f17ae1023c913d1dddcd671c13595"),
    RomOption("ArrowOS", "13.1 • 2023-10-28 • bgN Slim", "Android 13", "arm64-bgN", 1061881772L, "https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bgN-slim-Unofficial.img.xz", "b20e7cd77e414712d87c82c6bb60ff580c1ee79f83e480a264279a9d2c498560"),
    RomOption("ArrowOS", "13.1 • 2023-10-28 • bgN", "Android 13", "arm64-bgN", 1273314792L, "https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bgN-Unofficial.img.xz", "477f3232eb35f8c6b3b7cd1b742ec5ad5c2d0acd8105b306c7d25381cedf5df3"),
    RomOption("ArrowOS", "13.1 • 2023-10-28 • bvN", "Android 13", "arm64-bvN", 890913348L, "https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bvN-Unofficial.img.xz", "6d76156679f88b92817d71c371061f1232671d4ade0027a9bf51206345fe82cd"),
    RomOption("crDroid", "10.13 • 2025-03-08 • bgN Slim", "Android 15", "arm64-bgN", 1194893352L, "https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bgN-slim-Unofficial.img.xz", "0a403c4de120adbb15e7219f7c2acc59337e6d7fc995eaf4ace7e9267e25a0dc"),
    RomOption("crDroid", "10.13 • 2025-03-08 • bgN", "Android 15", "arm64-bgN", 1216015448L, "https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bgN-Unofficial.img.xz", "66d8ab9a2120889c71ea0b85d31939ad9fc35afe5ce46f10641cb2479aa954a7"),
    RomOption("crDroid", "10.13 • 2025-03-08 • bvN", "Android 15", "arm64-bvN", 979735120L, "https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bvN-Unofficial.img.xz", "fe9681c711119472df778c1563fb72f97dba4821ad8e6aa21c434c29f7931979"),
    RomOption("LineageOS", "23.2 • 2026-06-03 • Vanilla", "Android 16", "arm64-ab", 854786220L, "https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-VANILLA-20260603.img.xz", "87e0cb60d47d389f9995981581ba1285988e28ecf26f49e606044c1eeef8e89b"),
    RomOption("LineageOS", "23.2 • 2026-06-03 • microG", "Android 16", "arm64-ab", 932737388L, "https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-MICROG-20260603.img.xz", "56b84d5f68219d63615ce7abffcf7ca86d99aa44f9e2e270a0ad67010f4f5c91"),
    RomOption("LineageOS", "23.2 • 2026-06-03 • GApps", "Android 16", "arm64-ab", 1092196308L, "https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-GAPPS-20260603.img.xz", "14c263ef67228060046fcb08b0277775b99a93dfb7755f5621a33363a0d3f394")
)

private data class DeviceProfile(
    val model: String,
    val android: Int,
    val abi: String,
    val treble: Boolean,
    val ab: Boolean,
    val freeGb: Float,
    val flashLocked: Boolean?,
    val verifiedBootState: String,
    val bootloaderLabel: String,
    val dsuLoaded: Boolean
)

private fun detectDevice(): DeviceProfile {
    val stat = StatFs(Environment.getDataDirectory().path)
    val free = stat.availableBytes / 1073741824f
    val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: "unknown"
    val treble = readSystemProperty("ro.treble.enabled") == "true" || (Build.VERSION.SDK_INT >= 26 && (File("/vendor").exists() || File("/system/vendor").exists()))
    val ab = readSystemProperty("ro.build.ab_update") == "true" || !readSystemProperty("ro.boot.slot_suffix").isNullOrBlank()

    // Bootloader lock state: flash.locked is the canonical field; verifiedbootstate
    // is present too on most devices (green=locked, orange=unlocked).
    val flashLocked = readSystemProperty("ro.boot.flash.locked")?.lowercase(Locale.US)?.trim().let { value ->
        when (value) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
    }
    val verifiedBootState = readSystemProperty("ro.boot.verifiedbootstate")?.lowercase(Locale.US)?.trim() ?: ""
    val bootloaderLabel = when {
        verifiedBootState == "orange" || verifiedBootState == "yellow" -> "Unlocked"
        verifiedBootState == "green" && flashLocked == true -> "Locked"
        verifiedBootState == "red" -> "Broken (rollback triggered)"
        flashLocked == true -> "Locked"
        flashLocked == false -> "Unlocked"
        Build.TYPE.equals("userdebug", ignoreCase = true) -> "userdebug (no lock)"
        else -> "Unknown"
    }
    val dsuLoaded = readSystemProperty("ro.dsuloaded") == "true"
    return DeviceProfile("${Build.BRAND} ${Build.MODEL}", Build.VERSION.SDK_INT, abi, treble, ab, free, flashLocked, verifiedBootState, bootloaderLabel, dsuLoaded)
}

private fun readSystemProperty(name: String): String? = runCatching {
    val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
    val value = process.inputStream.bufferedReader().use { it.readText().trim() }
    process.waitFor()
    value.takeIf { it.isNotEmpty() }
}.getOrNull()

private enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, PERMISSION_DENIED, READY }

private object Shell {
    private const val MAX_OUTPUT_BYTES = SHELL_MAX_OUTPUT_BYTES

    // Shizuku 13.1.5 exposes no public shell entry point: newProcess(String[],
    // String[], String) is private and there is no getShellProvider() at all
    // (resolving that name threw NoSuchMethodException on every call). Resolve
    // the real method once and keep it; proguard-rules.pro keeps rikka.shizuku.**
    // so R8 cannot strip or rename it.
    private val newProcessMethod: Method by lazy(LazyThreadSafetyMode.NONE) {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun status(context: Context? = null): ShizukuStatus = try {
        when {
            !Shizuku.pingBinder() ->
                if (context != null && !isShizukuInstalled(context)) ShizukuStatus.NOT_INSTALLED
                else ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.PERMISSION_DENIED
            else -> ShizukuStatus.READY
        }
    } catch (_: Exception) { ShizukuStatus.NOT_RUNNING }

    fun ready(): Boolean = status() == ShizukuStatus.READY

    private fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    private fun readStreamLimited(stream: java.io.InputStream, label: String): String {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream, Charsets.UTF_8), 32 * 1024)
        val sb = StringBuilder(8192)
        var totalBytes = 0
        val buf = CharArray(8192)
        try {
            while (true) {
                val read = reader.read(buf)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > MAX_OUTPUT_BYTES) {
                    val limit = if (MAX_OUTPUT_BYTES >= 1024 * 1024) "${MAX_OUTPUT_BYTES / (1024 * 1024)} MB" else "${MAX_OUTPUT_BYTES / 1024} KB"
                    sb.append("\n[$label truncated: output exceeded $limit]")
                    break
                }
                sb.append(buf, 0, read)
            }
        } finally {
            runCatching { reader.close() }
        }
        return sb.toString()
    }

    /**
     * Run a full shell script through Shizuku. Intended for am/gzip/rm style
     * commands that route DSU install — not a generic exec helper.
     */
    suspend fun run(command: String): String = suspendCancellableCoroutine { cont ->
        var process: Process? = null
        try {
            // Merge stderr into stdout inside the child: `readStreamLimited` drains
            // stdout first, which can deadlock if stderr fills the ~64 KB pipe buffer.
            val script = "exec 2>&1; $command"
            val cmdArgs: Array<String> = arrayOf("sh", "-c", script)
            process = newProcessMethod.invoke(null, cmdArgs, null, null) as Process

            val activeProcess = process
            cont.invokeOnCancellation { activeProcess.destroyForcibly() }
            Thread {
                try {
                    val output = readStreamLimited(activeProcess.inputStream, "output")
                    val code = activeProcess.waitFor()
                    if (cont.isActive) cont.resume("[exit=$code] ${output.trim()}")
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("[error] ${e.message ?: e}")
                } finally {
                    runCatching { activeProcess.inputStream.close() }
                    runCatching { activeProcess.errorStream.close() }
                    runCatching { activeProcess.outputStream.close() }
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            process?.destroyForcibly()
            if (cont.isActive) cont.resume("[error] ${e.message ?: e}")
        }
    }
}

class MainActivity : ComponentActivity() {
    // Shizuku connection state lives here — not in the composable — so the
    // binder and permission listeners (which outlive any single composition)
    // update the screen the moment Shizuku answers. Previously these listeners
    // were empty and the UI only refreshed on a blind poll, so granting the
    // permission in Shizuku left the app stuck on "Not connected".
    val shizukuReadyState = mutableStateOf(false)
    val shizukuStatusTextState = mutableStateOf("")

    fun refreshShizukuStatus() {
        val st = Shell.status(this)
        shizukuReadyState.value = st == ShizukuStatus.READY
        shizukuStatusTextState.value = when (st) {
            ShizukuStatus.NOT_INSTALLED -> "Shizuku not installed"
            ShizukuStatus.NOT_RUNNING -> "Shizuku not running. Open Shizuku first."
            ShizukuStatus.PERMISSION_DENIED -> "Shizuku connected but permission denied"
            ShizukuStatus.READY -> "Connected and permission granted"
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizukuStatus() }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshShizukuStatus() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        setContent {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            var useDynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamic_color", true)) }
            BdsuTheme(useDynamicColor) {
                DownloadScreen(
                    useDynamicColor = useDynamicColor,
                    onUseDynamicColorChange = {
                        useDynamicColor = it
                        prefs.edit().putBoolean("dynamic_color", it).apply()
                    }
                )
            }
        }
    }
    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }
}

@Composable
private fun BdsuTheme(useDynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Material You (Android 12+): wallpaper-based palette. Anything older, or
    // the toggle switched off, falls back to the classic bDSU blue scheme.
    // All UI colors are read from MaterialTheme.colorScheme, so both palettes
    // apply everywhere with no extra branches at the call sites.
    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        // Pure bDSU blue, in every tone — never the M3 default purple accents.
        darkColorScheme(
            primary = BlueBright,
            onPrimary = Background,
            primaryContainer = BlueBright.copy(alpha = 0.24f),
            onPrimaryContainer = TextPrimary,
            secondary = BlueBright,
            onSecondary = Background,
            secondaryContainer = BlueBright.copy(alpha = 0.14f),
            onSecondaryContainer = TextPrimary,
            tertiary = BlueBright,
            onTertiary = Background,
            tertiaryContainer = BlueBright.copy(alpha = 0.14f),
            onTertiaryContainer = TextPrimary,
            background = Background,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = Color(0xFF12223A),
            onSurfaceVariant = TextSecondary,
            error = Red,
            onError = Background
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun DownloadScreen(useDynamicColor: Boolean, onUseDynamicColorChange: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { detectDevice() }
    val activity = context as? MainActivity
    val shizukuReady = activity?.shizukuReadyState?.value == true
    val shizukuStatusText = activity?.shizukuStatusTextState?.value ?: ""
    LaunchedEffect(Unit) {
        // Safety net: listeners already push updates, this only catches a
        // binder dying silently while the screen is open.
        while (currentCoroutineContext().isActive) {
            activity?.refreshShizukuStatus()
            delay(SHIZUKU_POLL_INTERVAL_MS)
        }
    }
    // No device filtering: every catalog build is listed so nothing is ever
    // hidden. Integrity is still enforced by the SHA-256 check on download
    // and again right before the DSU install.
    val allFamilies = romCatalog.groupBy { it.family }
    var expandedFamilies by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<RomOption?>(null) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var enqueueing by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(0) }
    var doneBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("Choose a ROM to start") }
    var debugDetails by remember { mutableStateOf("Debug: ready") }
    var retryCount by remember { mutableStateOf(0) }
    var retryTarget by remember { mutableStateOf<RomOption?>(null) }
    var location by remember { mutableStateOf("App downloads / Android/data/com.ayzlol.bdsu/files/Download") }
    var finishedFile by remember { mutableStateOf<String?>(null) }
    var finishedId by remember { mutableStateOf<Long?>(null) }
    var installing by remember { mutableStateOf(false) }
    var userdataEnabled by remember { mutableStateOf(false) }
    var userdataGb by remember { mutableStateOf(5f) }
    // The userdata picker pops up automatically the moment a download finishes or
    // a local ROM is picked — like the reference DSU side-loader. min 3 GB,
    // max 50 GB, and always below (free space's usable share).
    var showUserdataDialog by remember { mutableStateOf(false) }
    var userdataPromptedFor by remember { mutableStateOf<Any?>(null) }
    var installProgress by remember { mutableStateOf(0) }
    var installPhase by remember { mutableStateOf("") }
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var localName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(finishedId, localUri) {
        val token = localUri ?: finishedId
        if (token != null && token != userdataPromptedFor && !installing) {
            userdataPromptedFor = token
            showUserdataDialog = true
        }
    }
    val removableDownloadDir = remember { context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS).firstOrNull { Environment.isExternalStorageRemovable(it) } }
    val sdCardAvailable = removableDownloadDir != null
    val sdCardFreeGb = remember(removableDownloadDir) { removableDownloadDir?.let { runCatching { StatFs(it.path).availableBytes / 1073741824f }.getOrNull() } }
    var useSdCard by remember { mutableStateOf(false) }
    // Large GSI images are 0.9-1.8 GB; default to Wi-Fi so a background download
    // can never burn a mobile data quota without the user asking for it.
    var requireWifi by remember { mutableStateOf(true) }
    var dsuLoaderFlag by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("dsu_loader_flag", false)) }
    var showBackgroundDialog by remember { mutableStateOf(!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("background_prompt_seen", false)) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    // Easter egg: five quick taps on the hero card flick the cat on screen, and it
    // fades away over 4 seconds while the rest of the app keeps running.
    var easterEggTaps by remember { mutableStateOf(0) }
    var easterEggLastTap by remember { mutableStateOf(0L) }
    var easterEggVisible by remember { mutableStateOf(false) }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            if (!isSupportedLocalRomName(displayName)) {
                localUri = null
                localName = null
                selected = null
                finishedFile = null
                finishedId = null
                status = "Unsupported local ROM file. Choose an .img or .img.xz file."
            } else {
                localUri = uri
                localName = displayName
                selected = null
                finishedFile = null
                finishedId = null
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                status = "Local ROM selected: $displayName"
            }
        }
    }
    val manager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        var previousBytes = 0L
        var previousTime = System.currentTimeMillis()
        val monitorStartedAt = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            if (System.currentTimeMillis() - monitorStartedAt > DOWNLOAD_TIMEOUT_MS) {
                manager.remove(id)
                status = "Download timed out after 4 hours; partial file removed"
                activeId = null
                break
            }
            val snapshot = withContext(Dispatchers.IO) { queryDownload(manager, id) }
            if (snapshot == null) { activeId = null; break }
            doneBytes = snapshot.first; totalBytes = snapshot.second
            percent = if (snapshot.second > 0) ((snapshot.first * 100) / snapshot.second).toInt().coerceIn(0, 100) else 0
            val now = System.currentTimeMillis(); val elapsed = (now - previousTime).coerceAtLeast(1L)
            speed = ((snapshot.first - previousBytes) * 1000L / elapsed).coerceAtLeast(0L)
            previousBytes = snapshot.first; previousTime = now
            when (snapshot.third) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val fileUri = snapshot.fourth
                    val rom = selected
                    // Fail closed. A completed download we cannot locate, whose size
                    // does not match the catalog, or whose digest does not match must
                    // never reach the DSU installer.
                    if (rom == null || fileUri == null) {
                        status = "Download finished but the file could not be located; please retry"
                        manager.remove(id)
                        activeId = null
                        break
                    }
                    if (rom.sizeBytes > 0L && snapshot.first != rom.sizeBytes) {
                        status = "Size mismatch (expected ${formatBytes(rom.sizeBytes)}, got ${formatBytes(snapshot.first)}); corrupt download removed"
                        manager.remove(id)
                        activeId = null
                        break
                    }
                    if (rom.hasSha256) {
                        status = "Verifying SHA-256 of ${rom.family} ${rom.version}..."
                        val verified = withContext(Dispatchers.IO) {
                            verifySha256(context, Uri.parse(fileUri), rom.sha256)
                        }
                        if (!verified) {
                            status = "SHA-256 mismatch! File may be corrupted or tampered; removed, please re-download"
                            manager.remove(id)
                            activeId = null
                            break
                        }
                        status = "Download complete • SHA-256 ${shortSha(rom.sha256)} verified"
                    } else {
                        status = "Download complete • WARNING: no SHA-256 for this build, integrity not verified"
                    }
                    finishedFile = fileUri
                    finishedId = id
                    activeId = null
                    retryCount = 0
                    retryTarget = null
                }
                DownloadManager.STATUS_FAILED -> {
                    if (retryCount < 3 && retryTarget != null) {
                        retryCount++
                        status = "Download failed. Retry $retryCount/3..."
                        val rom = retryTarget!!
                        val retryRequest = DownloadManager.Request(Uri.parse(rom.url))
                            .setTitle("bDSU • ${rom.family} ${rom.version}")
                            .setMimeType("application/octet-stream")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setAllowedOverMetered(!requireWifi)
                            .setAllowedOverRoaming(false)
                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadFileName(rom))
                        activeId = manager.enqueue(retryRequest)
                    } else {
                        status = "Download failed after ${retryCount + 1} attempts: ${describeDownloadReason(snapshot.fifth)}"
                        activeId = null; retryCount = 0; retryTarget = null
                    }
                }
                else -> status = "Downloading ${selected?.family ?: "ROM"} ${selected?.version ?: ""}"
            }
            delay(600)
        }
    }

    if (showBackgroundDialog) AlertDialog(onDismissRequest = { showBackgroundDialog = false }, title = { Text("Keep bDSU running") }, text = { Text("Please allow this app to remain active in the background while the ROM is downloaded and DSU is installed.") }, confirmButton = { TextButton(onClick = { context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("background_prompt_seen", true).apply(); showBackgroundDialog = false; try { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("OK", color = MaterialTheme.colorScheme.primary) } }, dismissButton = { TextButton(onClick = { context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("background_prompt_seen", true).apply(); showBackgroundDialog = false }) { Text("I don't want", color = MaterialTheme.colorScheme.onSurfaceVariant) } })

    if (showLicenseDialog) AlertDialog(onDismissRequest = { showLicenseDialog = false }, title = { Text("bDSU - License & Disclaimer", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("bDSU - Dynamic System Update Sideloader", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Copyright (C) 2024 The_pan (ayzlol) & Unfayd", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        Text("This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("DISCLAIMER", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("This software is provided 'AS IS' without warranty of any kind. The developers accept NO responsibility for any damage, data loss, or bricked devices resulting from the use of this application. DSU operations carry inherent risk. Use at your own risk and always ensure you have backups before proceeding.", color = Red, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Developers", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("The_pan (ayzlol) - Lead Developer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("Unfayd - Co-Developer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Good luck and be careful!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    } }, confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("OK", color = MaterialTheme.colorScheme.primary) } })

    if (showUserdataDialog) {
        // DSU keeps a pristine system slot; without userdata storage every reboot
        // wipes apps/data inside the GSI. min 3 GB, max 50 GB, and the picker only
        // offers sizes that fit into the usable share of the currently free space
        // (we reserve 4 GB for the staged image and system overhead).
        val usableGb = (profile.freeGb - 4f).coerceAtLeast(0f)
        val maxDsuGb = if (usableGb < 3f) 0f else usableGb.coerceAtMost(50f)
        val presets = listOf(3, 4, 8, 12, 16, 24, 32, 40, 48).filter { it <= maxDsuGb }
        AlertDialog(onDismissRequest = { showUserdataDialog = false },
            title = { Text("Allocate persistent userdata?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("By default the GSI boots with a temporary userdata: everything inside DSU is wiped when you boot back. A persistent userdata partition keeps your apps and data across reboots.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("Free now: ${String.format(Locale.US, "%.1f", profile.freeGb)} GB • usable for DSU: 3..${if (maxDsuGb <= 0f) 0 else maxDsuGb.toInt()} GB (min 3 GB, max 50 GB)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                if (maxDsuGb <= 0f) {
                    Text("Not enough free space for persistent userdata — install will stay temporary.", color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    presets.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { gb ->
                                Button(onClick = { userdataEnabled = true; userdataGb = gb.toFloat(); showUserdataDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), modifier = Modifier.weight(1f)) {
                                    Text("$gb GB", color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { userdataEnabled = false; showUserdataDialog = false }) { Text("No — temporary DSU", color = MaterialTheme.colorScheme.primary) } }
        )
    }

    if (showSettingsDialog) AlertDialog(onDismissRequest = { showSettingsDialog = false }, title = { Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only download", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (requireWifi) "On • mobile data will not be used for 0.9–1.8 GB files" else "Off • download may use mobile data; watch your quota", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = requireWifi, onCheckedChange = { if (activeId == null && !installing) requireWifi = it }, enabled = activeId == null && !installing)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use phone colors", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (if (useDynamicColor) "On • UI follows your wallpaper palette" else "Off • classic bDSU blue") else "Requires Android 12+ • classic bDSU blue is used", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, onCheckedChange = { onUseDynamicColorChange(it) }, enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        }
        if (finishedId != null || localUri != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use SD card for DSU staging", color = if (sdCardAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(if (sdCardAvailable) "Off by default • temporary image staging only • ${String.format(Locale.US, "%.1f", sdCardFreeGb ?: 0f)} GB available" else "No SD card detected on this device", color = if (sdCardAvailable) MaterialTheme.colorScheme.onSurfaceVariant else Red, fontSize = 11.sp)
                }
                Switch(checked = useSdCard, onCheckedChange = { if (!installing && sdCardAvailable) useSdCard = it }, enabled = !installing && sdCardAvailable)
            }
        }
        if (finishedFile == null && localUri == null) Text("Download or select a ROM first to configure install options.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    } }, confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Close", color = MaterialTheme.colorScheme.primary) } })

    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar = { TopAppBar(title = { Text("bdsu(adb-dsu)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 22.sp) }, actions = {
        IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurface) }
        IconButton(onClick = { showLicenseDialog = true }) { Icon(Icons.Filled.Info, "License and disclaimer", tint = MaterialTheme.colorScheme.onSurface) }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Shizuku connection", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(shizukuStatusText.ifBlank { if (shizukuReady) "Connected and permission granted" else "Not connected" }, color = if (shizukuReady) Green else Red, fontSize = 13.sp)
                Button(onClick = {
                    try {
                        if (!Shizuku.pingBinder()) context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(1001); status = "Permission requested in Shizuku - approve it, then the status updates automatically" }
                        else activity?.refreshShizukuStatus()
                    } catch (_: Exception) { status = "Open Shizuku and enable Wireless debugging first" }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (shizukuReady) Green else MaterialTheme.colorScheme.primary)) { Text("Connect Shizuku", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
            } } }
            item { Card(onClick = {
                val now = System.currentTimeMillis()
                if (now - easterEggLastTap > 1500) easterEggTaps = 0
                easterEggLastTap = now
                easterEggTaps++
                if (easterEggTaps >= 5) { easterEggTaps = 0; easterEggVisible = true }
            }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Custom/AOSP ROM downloader", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("From this section you can download a custom ROM or an AOSP build. All catalog links are safe (HTTPS + SHA-256 verified), but the real limit is your internet speed — these images are 0.9–1.8 GB.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } } }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Detected device", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text("${profile.model} • Android API ${profile.android} • ${profile.abi}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("Bootloader: ${profile.bootloaderLabel}" + if (profile.flashLocked != null) " • flash.locked=${if (profile.flashLocked) 1 else 0}" else "", color = if (profile.flashLocked == false || profile.verifiedBootState == "orange" || profile.verifiedBootState == "yellow") Green else Red, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(if (profile.dsuLoaded) "Currently running a GSI (DSU) image" else "Currently running the stock OS (DSU not loaded)", color = if (profile.dsuLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } } }
            item { Button(onClick = { localPicker.launch(arrayOf("application/octet-stream", "application/x-xz", "application/gzip", "application/x-raw-disk-image")) }, enabled = activeId == null && !installing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text(if (localName == null) "Select local ROM (.img / .img.xz / .img.gz)" else "Local ROM: $localName") } }
            if (status.startsWith("Unsupported local ROM")) item { Text(status, color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ROM source and staging", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Pick a local ROM only if you trust it: choose .img, .img.xz or .img.gz files and make sure you have the right file. .img.gz payloads skip recompression and go straight into the DSU pipeline. Once the DSU service accepts it, good luck with the boot!", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } } }
            item { Text("GSI ROM catalog (${romCatalog.size} builds, unfiltered)", color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            items(allFamilies.entries.toList(), key = { it.key }) { entry ->
                val family = entry.key
                val versions = entry.value
                val expanded = family in expandedFamilies
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Card(
                        onClick = { expandedFamilies = if (expanded) expandedFamilies - family else expandedFamilies + family },
                        enabled = activeId == null && !installing,
                        colors = CardDefaults.cardColors(containerColor = if (versions.any { it == selected }) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(family, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${versions.size} version${if (versions.size == 1) "" else "s"} • tap to expand", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            versions.forEach { rom ->
                        Card(
                            onClick = { if (activeId == null && !installing) { selected = rom; localUri = null; localName = null; finishedFile = null; finishedId = null; status = "Selected ${rom.family} ${rom.version}" } },
                            enabled = activeId == null && !installing,
                            colors = CardDefaults.cardColors(containerColor = if (selected == rom) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .88f)),
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rom.version, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                    Text("${rom.android} • ${rom.architecture}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    if (selected == rom) Text("Selected build", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(formatBytes(rom.sizeBytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            }
                        }
                            }
                        }
                    }
                }
            }
            item {
                if (activeId != null || percent > 0 || finishedFile != null || localUri != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { if (installing) installProgress / 100f else percent / 100f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    if (installing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                            Text("$installPhase • $installProgress%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("$percent% • ${formatBytes(doneBytes)} / ${formatBytes(totalBytes)} • ${formatSpeed(speed)} • ETA ${eta(doneBytes, totalBytes, speed)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text("Saved to: $location", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System DSU Loader screens", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (dsuLoaderFlag) "On • Android Settings will expose the built-in DSU Loader" else "Off • enable to also manage DSU installs from Android Settings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = dsuLoaderFlag, onCheckedChange = { new ->
                dsuLoaderFlag = new
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("dsu_loader_flag", new).apply()
                if (new) scope.launch {
                    status = if (!Shell.ready()) "Grant Shizuku permission first to toggle the system DSU Loader"
                    else Shell.run("setprop persist.sys.fflag.override.settings_dynamic_system true").substringAfter("]").trim() + " — system DSU Loader is now enabled"
                }
            }, enabled = !installing)
        }
        if (finishedId != null || localUri != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (userdataEnabled) "Userdata: persistent • ${userdataGb.toInt()} GB" else "Userdata: temporary (wiped on reboot)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            TextButton(onClick = { showUserdataDialog = true }, enabled = !installing) { Text("Change", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                        }
                    }
                    Text(debugDetails, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 10.sp)
                    if (activeId != null) Button(onClick = { manager.remove(activeId!!); activeId = null; percent = 0; doneBytes = 0; totalBytes = 0; speed = 0; status = "Download cancelled and partial file removed"; debugDetails = "Debug: DownloadManager request cancelled" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Cancel download", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    if ((finishedId != null || localUri != null) && activeId == null && !installing) Button(onClick = { if (!shizukuReady) { status = "Connect Shizuku before installing" } else { val romForInstall = selected; if (localUri == null && (finishedId == null || romForInstall == null)) { status = "No completed download is available to install" } else { installing = true; installProgress = 0; status = "Preparing DSU installer..."; scope.launch { val result = if (localUri != null) installLocalDsu(context, localUri!!, localName ?: "local.img.xz", if (userdataEnabled) userdataGb.toLong() * 1073741824L else 0L, useSdCard, { p, s -> Handler(Looper.getMainLooper()).post { installProgress = p; installPhase = s } }) else installDsu(context, manager, finishedId!!, romForInstall!!, if (userdataEnabled) userdataGb.toLong() * 1073741824L else 0L, useSdCard, { p, s -> Handler(Looper.getMainLooper()).post { installProgress = p; installPhase = s } }); status = result; debugDetails = "Debug result: $result"; installing = false } } } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Install into DSU") }
                    if (installing) Button(onClick = { status = "DSU preparation cannot be interrupted safely at this stage" }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Preparing DSU...") }
                } }
            }
            item { Button(onClick = {
                val rom = selected ?: return@Button
                if (activeId != null || enqueueing) return@Button
                if (!isAllowedRomUrl(rom.url)) { status = "Blocked unsafe ROM URL. Only verified GitHub release links are allowed"; return@Button }
                enqueueing = true
                try {
                    val request = DownloadManager.Request(Uri.parse(rom.url)).setTitle("bDSU • ${rom.family} ${rom.version}").setDescription("Downloading ${rom.android} ${rom.architecture} • ${formatBytes(rom.sizeBytes)} • " + if (requireWifi) "Wi-Fi only" else "Wi-Fi or mobile data").setMimeType("application/octet-stream").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(!requireWifi).setAllowedOverRoaming(false).setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadFileName(rom))
                    activeId = manager.enqueue(request); percent = 0; doneBytes = 0; totalBytes = 0; speed = 0; localUri = null; localName = null; finishedFile = null; finishedId = null; retryCount = 0; retryTarget = rom; location = "App downloads / Android/data/com.ayzlol.bdsu/files/Download"; status = "Queued ${rom.family} ${rom.version} • ${formatBytes(rom.sizeBytes)}"
                } catch (e: Exception) {
                    status = "Download could not be queued: ${e.message ?: "unknown error"}"
                } finally { enqueueing = false }
            }, enabled = selected != null && activeId == null && !enqueueing && !installing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(14.dp)) { Text("Download selected ROM") } }
            item { Text("This debug section only shows the steps that run while the DSU is being prepared — your device logs or the DSU boot sequence cannot be captured because Android keeps them in a separate low-level process.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f), fontSize = 11.sp) }
            item {
                var showDebug by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(13.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Debug & Diagnostics", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            TextButton(onClick = { showDebug = !showDebug }) { Text(if (showDebug) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                        }
                        if (showDebug) {
                            val runtime = Runtime.getRuntime()
                            val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                            val maxMem = runtime.maxMemory() / (1024 * 1024)
                            val freeMem = runtime.freeMemory() / (1024 * 1024)
                            val debugInfo = buildString {
                                appendLine("--- Device ---")
                                appendLine("Model: ${Build.BRAND} ${Build.MODEL}")
                                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                                appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
                                appendLine("Board: ${Build.BOARD}")
                                appendLine("Hardware: ${Build.HARDWARE}")
                                appendLine("--- Memory ---")
                                appendLine("Used: ${usedMem}MB / ${maxMem}MB")
                                appendLine("Free: ${freeMem}MB")
                                appendLine("--- Shizuku ---")
                                appendLine("Status: ${Shell.status(context)}")
                                appendLine("Binder alive: ${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}")
                                appendLine("Permission: ${runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)}")
                                appendLine("--- Storage ---")
                                appendLine("Internal free: ${String.format(Locale.US, "%.1f", profile.freeGb)} GB")
                                sdCardFreeGb?.let { appendLine("SD card free: ${String.format(Locale.US, "%.1f", it)} GB") } ?: appendLine("SD card: not available")
                                appendLine("Treble: ${profile.treble} | A-B: ${profile.ab}")
                                appendLine("--- Download ---")
                                appendLine("Active ID: ${activeId ?: "none"}")
                                appendLine("Finished: ${finishedFile?.let { File(it).name } ?: "none"}")
                                appendLine("Local ROM: ${localName ?: "none"}")
                                appendLine("--- Potential Issues ---")
                                if (!profile.treble) appendLine("[WARN] Device may not support Treble GSI")
                                if (!profile.ab) appendLine("[WARN] Device may not have A/B partitions")
                                if (profile.freeGb < 5f) appendLine("[WARN] Low internal storage (${String.format(Locale.US, "%.1f", profile.freeGb)} GB)")
                                if (Shell.status(context) != ShizukuStatus.READY) appendLine("[WARN] Shizuku not ready - DSU install will fail")
                                if (usedMem > maxMem * 0.8) appendLine("[WARN] High memory usage - may cause OOM")
                                if (Build.VERSION.SDK_INT < 30) appendLine("[WARN] Android < 11 may have limited DSU support")
                                val issues = listOf(
                                    "Shell.run may timeout on slow commands",
                                    "XZ decompression requires sufficient RAM",
                                    "DownloadManager may fail on metered networks",
                                    "SD card staging may fail on some devices"
                                )
                                issues.forEach { appendLine("[INFO] $it") }
                            }
                            Text(debugInfo, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }

    if (easterEggVisible) {
        // 5 quick taps on the hero card → the GNU cat appears mid-screen and fades
        // out over 4 seconds. Tap anywhere to dismiss it early.
        val fade = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            fade.animateTo(0f, tween(durationMillis = 4000))
            easterEggVisible = false
        }
        Box(Modifier.fillMaxSize().clickable { easterEggVisible = false }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * fade.value)))
            Image(
                painter = painterResource(R.drawable.easter_egg),
                contentDescription = "bDSU easter egg",
                alpha = fade.value,
                modifier = Modifier.fillMaxWidth(0.82f),
                contentScale = ContentScale.Fit
            )
        }
    }
    }
}

private suspend fun installDsu(context: Context, manager: DownloadManager, id: Long, rom: RomOption, userdataBytes: Long, useSdCard: Boolean, onProgress: (Int, String) -> Unit): String = withContext(Dispatchers.IO) {
    // Defense in depth: re-verify immediately before handing the image to the DSU
    // service, so a file edited or truncated after the download still gets caught.
    if (!rom.hasSha256) return@withContext "Catalog entry for ${rom.family} ${rom.version} has no valid SHA-256; refusing to install"
    val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: return@withContext "No writable internal staging storage is available"
    val downloaded = File(downloadsDir, downloadFileName(rom))
    if (!downloaded.exists() || downloaded.length() <= 0L) {
        val pfd = runCatching { manager.openDownloadedFile(id) }.getOrNull()
            ?: return@withContext "Download file not found or corrupted. Please re-download the ROM."
        return@withContext installFromStream(context, ParcelFileDescriptor.AutoCloseInputStream(pfd), downloadFileName(rom), -1L, userdataBytes, useSdCard, onProgress)
    }
    if (rom.sizeBytes > 0L && downloaded.length() != rom.sizeBytes) {
        runCatching { manager.remove(id) }
        runCatching { downloaded.delete() }
        return@withContext "Size mismatch for ${downloaded.name}: expected ${formatBytes(rom.sizeBytes)} but found ${formatBytes(downloaded.length())}. File may be corrupt; please re-download"
    }
    onProgress(2, "Verifying digests")
    // One disk read, both digests: the catalog uses SHA-256 while some GSI
    // publishers ship plain md5sums — computing both now avoids a second gigabyte
    // read when the publisher checksum is fetched below.
    val digests = withContext(Dispatchers.IO) { computeBothDigests(downloaded) { p -> onProgress(2 + p * 28 / 100, "Verifying digests: $p%") } }
    if (!digests.sha256.equals(rom.sha256, ignoreCase = true)) {
        runCatching { manager.remove(id) }
        runCatching { if (!downloaded.delete()) Log.w("bDSU", "Failed to delete hash-mismatched file: ${downloaded.absolutePath}") }
        return@withContext "SHA-256 mismatch for ${rom.family} ${rom.version}! Deleted corrupt file; please re-download"
    }
    onProgress(32, "SHA-256 verified ${shortSha(rom.sha256)} (catalog)")
    // Ground truth when available: the checksum file the publisher hosts next to
    // the build (sha256/md5). Already hashed above — compare directly.
    onProgress(33, "Checking publisher checksum…")
    val published = fetchPublisherChecksum(rom.url, downloaded.name)
    if (published != null) {
        val actual = if (published.algorithm == "MD5") digests.md5 else digests.sha256
        if (!actual.equals(published.hash, ignoreCase = true)) {
            runCatching { manager.remove(id) }
            runCatching { downloaded.delete() }
            return@withContext "${published.algorithm} mismatch vs publisher site! File may be corrupted or tampered; removed"
        }
        onProgress(34, "Checksum verified (${published.algorithm}, publisher site)")
    }
    val userdataArg = if (userdataBytes >= 3L * 1073741824L) "--el KEY_USERDATA_SIZE $userdataBytes" else ""
    if (downloaded.name.endsWith(".xz", ignoreCase = true)) {
        // Optimized single pass: xz → gzip straight into public Download, so the
        // multi-GB raw .img never has to exist before the documented DSU launch.
        val launched = runCatching {
            val (gzFile, rawSize) = stageDownloadAsGz(context, downloaded, onProgress)
            launchDsuFromPublicGz(context, gzFile, rawSize, userdataArg, onProgress)
        }.getOrNull()
        if (launched != null && launched.first) return@withContext "DSU installer started"
        // Fall through to the full pipeline (raw staging + documented + legacy).
        Log.w("bDSU", "Fast xz→gz path failed, falling back: ${launched?.second}")
    }
    FileInputStream(downloaded).use { input ->
        installFromStream(context, input, downloaded.name, downloaded.length(), userdataBytes, useSdCard, onProgress)
    }
}

private suspend fun installLocalDsu(context: Context, uri: Uri, imageName: String, userdataBytes: Long, useSdCard: Boolean, onProgress: (Int, String) -> Unit): String = withContext(Dispatchers.IO) {
    val safeName = imageName.ifBlank { "local.img" }
    if (!isSupportedLocalRomName(safeName)) return@withContext "Unsupported local ROM file. Choose an .img, .img.xz or .img.gz file."

    // Local picks are NEVER checksum-blocked. The user owns the file and may be
    // testing an unreleased build — hashing gigabytes only to then ignore the
    // result wastes battery and time. If the file name matches a catalog build
    // we say so for information, nothing more.
    val advisory = getLocalRomSha256(context, uri).takeIf { it.isNotBlank() }
        ?.let { " • matches catalog build; integrity intentionally not checked" } ?: ""

    val input = context.contentResolver.openInputStream(uri) ?: return@withContext "Cannot open the selected local ROM"
    // Fast path: an already-gzipped local image can land straight into public Download
    // (one copy pass, no decompress/recompress). The uncompressed size comes from the
    // gzip tail (ISIZE). Falls back to the generic pipeline if anything is off.
    if (safeName.endsWith(".img.gz", ignoreCase = true)) {
        val rawSize = readGzipTrailerUncompressedSize(context, uri)
        if (rawSize >= 400L * 1024 * 1024) {
            val userdataArg = if (userdataBytes >= 3L * 1073741824L) "--el KEY_USERDATA_SIZE $userdataBytes" else ""
            val launched = runCatching {
                onProgress(10, "Copying gzip image into Download")
                val gzFile = copyUriToPublicGz(context, uri, safeName, onProgress)
                launchDsuFromPublicGz(context, gzFile, rawSize, userdataArg, onProgress)
            }.getOrNull()
            if (launched != null && launched.first) {
                runCatching { input.close() }
                return@withContext "DSU installer started$advisory"
            }
            Log.w("bDSU", "Fast gz path failed, falling back: ${launched?.second}")
        }
    }
    // Own the stream here: installFromStream can return or throw before
    // prepareImageFile() takes ownership of it, and the content resolver stream
    // would otherwise stay open for the lifetime of the process.
    val localSize = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
    val result = try {
        input.use { owned -> installFromStream(context, owned, safeName, localSize, userdataBytes, useSdCard, onProgress) }
    } catch (e: CancellationException) {
        runCatching { input.close() }
        throw e
    }
    if (advisory.isNotBlank()) "$result$advisory" else result
}

private suspend fun installFromStream(context: Context, source: java.io.InputStream, imageName: String, sourceSizeBytes: Long, userdataBytes: Long, useSdCard: Boolean, onProgress: (Int, String) -> Unit): String = withContext(Dispatchers.IO) {
    if (!isSupportedLocalRomName(imageName)) return@withContext "Unsupported image format. Only .img and .img.xz are supported."
    if (!Shell.ready()) return@withContext "Shizuku permission is required before DSU installation"
    // Staged files stay until app re-launch or reboot; DSU install uses them as the
    // source while VerificationActivity copies the image into a fresh system slot.
    try {
        val removable = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS).firstOrNull { Environment.isExternalStorageRemovable(it) }
        val internalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return@withContext "No writable internal staging storage is available"
        val preferredDir = if (useSdCard && removable != null) removable else internalDir
        val stagedName = normalizedImageName(imageName).ifBlank { "rom.img" }.replace("/", "_")
        val imageFile = File(preferredDir, stagedName)
        runCatching { preferredDir.mkdirs() }
        if (sourceSizeBytes > 0L) {
            // .xz expands ~2-3x, .gz ~1.5-2x; raw estimate plus a 512 MB margin so a
            // full disk mid-install can never wedge the device.
            val multiplier = if (imageName.endsWith(".xz", ignoreCase = true)) 3 else if (imageName.endsWith(".gz", ignoreCase = true)) 2 else 1
            val required = sourceSizeBytes * multiplier + 536870912L
            val stageFree = runCatching { StatFs(preferredDir.path).availableBytes }.getOrNull() ?: -1L
            if (stageFree >= 0L && stageFree < required) {
                val sdHint = if (!useSdCard && removable != null) {
                    val sdFree = runCatching { StatFs(removable.path).availableBytes }.getOrNull() ?: -1L
                    if (sdFree >= required) " Your SD card has ${formatBytes(sdFree)} free — enable 'Use SD card for DSU staging' and retry." else ""
                } else ""
                return@withContext "Not enough free space for staging: need ~${formatBytes(required)} but only ${formatBytes(stageFree)} is free here.$sdHint"
            }
        }
        val prepared = prepareImageFile(context, source, imageName, imageFile, onProgress)
        deleteOnRebootStagedFile(prepared)
        val rawSize = prepared.length()
        if (rawSize <= 0L) return@withContext "The selected image is empty"
        val userdataArg = if (userdataBytes >= 3L * 1073741824L) "--el KEY_USERDATA_SIZE $userdataBytes" else ""
        val firstResult = launchDsuFromStaging(context, prepared, stagedName, rawSize, userdataArg, onProgress)
        if (firstResult.first) return@withContext "DSU installer started"

        val shouldFallback = useSdCard && removable != null && isStorageAccessFailure(firstResult.second)
        if (shouldFallback) {
            onProgress(82, "SD staging unavailable; retrying with internal storage")
            val fallbackImage = File(internalDir, stagedName)
            prepared.inputStream().use { input -> FileOutputStream(fallbackImage).use { output -> input.copyTo(output, 1024 * 1024) } }
            deleteOnRebootStagedFile(fallbackImage)
            val fallbackResult = launchDsuFromStaging(context, fallbackImage, stagedName, rawSize, userdataArg, onProgress)
            if (fallbackResult.first) return@withContext "DSU installer started using internal staging (SD card unavailable)"
            return@withContext "DSU launch failed after SD and internal staging attempts: ${fallbackResult.second}"
        }
        "DSU launch failed: ${firstResult.second}"
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { "DSU installation failed: ${e.message}" }
}

private const val MAX_DECOMPRESSED_SIZE = 4L * 1024 * 1024 * 1024
private const val XZ_MAX_MEMORY_BYTES = 256 * 1024 * 1024 // 256MB limit for XZ decompressor

private suspend fun prepareImageFile(_context: Context, source: java.io.InputStream, imageName: String, imageFile: File, onProgress: (Int, String) -> Unit): File {
    val stageDir = imageFile.parentFile ?: throw IllegalStateException("Staging directory is missing")
    if (!stageDir.exists() && !stageDir.mkdirs()) throw IllegalStateException("Cannot create staging directory: ${stageDir.absolutePath}")
    if (!stageDir.isDirectory || !stageDir.canWrite()) throw IllegalStateException("Staging directory is not writable: ${stageDir.absolutePath}")
    val isXz = imageName.endsWith(".xz", ignoreCase = true)
    val isGz = imageName.endsWith(".gz", ignoreCase = true)
    onProgress(5, when {
        isXz -> "Decompressing image (xz)"
        isGz -> "Decompressing image (gzip)"
        else -> "Reading local image"
    })
    source.use { input ->
        val decoded: java.io.InputStream = when {
            isXz -> {
                // Use memory-limited XZ decompressor to prevent OOM
                org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(input, 1024 * 1024), XZ_MAX_MEMORY_BYTES)
            }
            isGz -> java.util.zip.GZIPInputStream(java.io.BufferedInputStream(input, 1024 * 1024), 128 * 1024)
            else -> input
        }
        // 1 MB streaming buffer — much faster on flash storage than the old 32 KB buffer.
        decoded.use { stream -> FileOutputStream(imageFile).use { output ->
            val buffer = ByteArray(1024 * 1024); var total = 0L; var lastProgress = 5; var read = stream.read(buffer)
            while (read >= 0) {
                currentCoroutineContext().ensureActive()
                if (read > 0) {
                    if (total + read > MAX_DECOMPRESSED_SIZE) {
                        throw IllegalStateException("Decompressed image exceeds maximum allowed size (4GB)")
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
                val newProgress = (5 + (total / 1048576L).toInt().coerceAtMost(45))
                if (newProgress > lastProgress) {
                    val verb = if (isXz || isGz) "Decompressing" else "Copying"
                    onProgress(newProgress, "$verb image: ${(total / 1048576L)} MB")
                    lastProgress = newProgress
                }
                read = stream.read(buffer)
            }
        } }
    }
    imageFile.setReadable(true, false)
    return imageFile
}

// Keep every staged image alive until a reboot — DSU install reads it only
// after VerificationActivity, so deleting early breaks the copy into the DSU slot.
private fun deleteOnRebootStagedFile(file: File) = runCatching {
    file.deleteOnExit()
    Log.i("bDSU", "Staged for DSU: ${file.absolutePath} (${formatBytes(file.length())})")
}

/** Launch the documented DSU VerificationActivity against an existing .img.gz in
 *  the user-visible Download directory. The .gz stays on disk for reboot — DSU reads
 *  it only after the user confirms in the system dialog. */
@Suppress("DEPRECATION")
private suspend fun launchDsuFromPublicGz(context: Context, gzFile: File, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit): Pair<Boolean, String> {
    if (!Shell.ready()) return false to "Shizuku permission is required before DSU installation"
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val fileUri = "file://${gzFile.absolutePath}"
    val command = "set -eu; test -s ${quote(gzFile.absolutePath)}; chmod 644 ${quote(gzFile.absolutePath)} 2>/dev/null || true; am start-activity --user 0 -n com.android.dynsystem/com.android.dynsystem.VerificationActivity -a android.os.image.action.START_INSTALL -d ${quote(fileUri)} --el KEY_SYSTEM_SIZE $rawSize $userdataArg"
    val result = Shell.run(command)
    val success = result.contains("[exit=0]")
    if (success) { deleteOnRebootStagedFile(gzFile); onProgress(100, "DSU installer started") }
    return success to if (success) "DSU installer started via $fileUri" else result
}

/** Remove stale bdsu-*.img.gz payloads from public Download before staging a new one. */
@Suppress("DEPRECATION")
private fun cleanupStalePublicGz() {
    val dir = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull() ?: return
    dir.listFiles()?.filter { it.name.startsWith("bdsu-") && it.name.endsWith(".img.gz") && it.isFile }?.forEach { runCatching { it.delete() } }
}

/** gzip store the uncompressed size in the last 4 bytes (ISIZE, little endian,
 *  mod 4GiB) — enough for GSIs, which are always below 4 GiB. Returns -1 if the
 *  underlying file cannot be sought (e.g. a remote document provider). */
private fun readGzipTrailerUncompressedSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            val size = ch.size()
            if (size < 18) return@use -1L
            ch.position(size - 4)
            val tail = java.nio.ByteBuffer.allocate(4)
            while (tail.hasRemaining() && ch.read(tail) > 0) { }
            tail.flip()
            if (tail.remaining() < 4) -1L
            else (tail.order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
        }
    } ?: -1L
}.getOrDefault(-1L)

/** Stream-copy an already-gzipped local pick into public Download — no decompress/
 *  recompress round-trip, single 1 MB-chunk pass with progress. */
@Suppress("DEPRECATION")
private suspend fun copyUriToPublicGz(context: Context, uri: Uri, stagedName: String, onProgress: (Int, String) -> Unit): File = withContext(Dispatchers.IO) {
    val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cleanupStalePublicGz()
    val gzName = ("bdsu-" + normalizedImageName(stagedName).removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    val srcSize = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
    context.contentResolver.openInputStream(uri)!!.use { input ->
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        FileOutputStream(gzFile).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) output.write(buffer, 0, read)
                total += read
                if (srcSize > 0L) onProgress(10 + (total * 45 / srcSize).toInt().coerceIn(0, 45), "Copying gzip image into Download: ${formatBytes(total)}")
            }
        }
    }
    gzFile
}

/** Single-pass xz → gzip into public Download: never materializes the multi-GB raw
 *  .img on disk. Returns the gz file plus the counted uncompressed size. */
@Suppress("DEPRECATION")
private suspend fun stageDownloadAsGz(context: Context, srcFile: File, onProgress: (Int, String) -> Unit): Pair<File, Long> = withContext(Dispatchers.IO) {
    val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cleanupStalePublicGz()
    val gzName = ("bdsu-" + normalizedImageName(srcFile.name).removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    var total = 0L
    var lastProgress = 0
    val estimate = srcFile.length() * 3 // xz compresses ~2-3x on GSIs
    org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(FileInputStream(srcFile), 1024 * 1024), XZ_MAX_MEMORY_BYTES).use { inp ->
        java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(FileOutputStream(gzFile), 1024 * 1024)).use { out ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = inp.read(buffer)
                if (read < 0) break
                if (read > 0) { out.write(buffer, 0, read); total += read }
                val pct = (total * 100 / estimate).toInt().coerceIn(0, 99)
                if (pct != lastProgress) { lastProgress = pct; onProgress(5 + pct * 60 / 100, "Repackaging for DSU: ${formatBytes(total)} uncompressed") }
            }
        }
    }
    if (total <= 0L) throw IllegalStateException("Image decompress produced no data")
    gzFile to total
}

/**
 * AOSP-documented DSU flow (https://source.android.com/docs/core/ota/dynamic-system-updates):
 * gzip the staged image into the user-visible Download directory — the one place the
 * system DSU installer is proven to read — and launch the explicit VerificationActivity
 * component. Returns success=false (without touching the staged file) when anything is
 * missing so the caller can fall back to the legacy path.
 */
@Suppress("DEPRECATION")
private suspend fun launchDsuDocumentedFlow(context: Context, imageFile: File, stagedName: String, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit): Pair<Boolean, String> {
    if (!Shell.ready()) return false to "Shizuku permission is required before DSU installation"
    val publicDownload = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        ?: return false to "Public Download directory is unavailable"
    val free = runCatching { StatFs(publicDownload.path).availableBytes }.getOrNull() ?: 0L
    if (free < rawSize) return false to "Not enough free space in Download for the compressed image (need ~${formatBytes(rawSize)}, have ${formatBytes(free)})"
    cleanupStalePublicGz()
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val gzName = ("bdsu-" + stagedName.removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    val command = "set -eu; SRC=${quote(imageFile.absolutePath)}; DST=${quote(gzFile.absolutePath)}; test -s \"\$SRC\"; command -v gzip >/dev/null 2>&1 || exit 10; gzip -c \"\$SRC\" > \"\$DST\"; test -s \"\$DST\""
    onProgress(68, "Compressing image for DSU installer")
    val result = Shell.run(command)
    if (result.contains("[exit=10]")) return false to "gzip command not found on this device"
    if (!result.contains("[exit=0]")) { onProgress(78, "Documented DSU flow failed; trying legacy launch"); return false to result }
    val launched = launchDsuFromPublicGz(context, gzFile, rawSize, userdataArg, onProgress)
    if (!launched.first) onProgress(78, "Documented DSU flow failed; trying legacy launch")
    return launched
}

private suspend fun launchDsuFromStaging(context: Context, imageFile: File, stagedName: String, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit): Pair<Boolean, String> {
    val documented = launchDsuDocumentedFlow(context, imageFile, stagedName, rawSize, userdataArg, onProgress)
    if (documented.first) return documented
    val stageDir = imageFile.parentFile ?: return false to "Staging directory is missing"
    if (!stageDir.exists() && !stageDir.mkdirs()) return false to "Cannot create staging directory: ${stageDir.absolutePath}"
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val imgPath = imageFile.absolutePath
    val fileUri = Uri.fromFile(imageFile).toString()
    val command = "set -eu; DIR=${quote(stageDir.absolutePath)}; mkdir -p \"\$DIR\"; IMG=${quote(imgPath)}; test -s \"\$IMG\"; am start-activity --user 0 -a android.os.image.action.START_INSTALL -d ${quote(fileUri)} --el KEY_SYSTEM_SIZE $rawSize $userdataArg"
    onProgress(78, "Documented DSU flow unavailable; trying legacy DSU launch")
    val result = Shell.run(command)
    val success = result.contains("[exit=0]")
    val errorMsg = result.lowercase(Locale.US)
    val hint = when {
        errorMsg.contains("not found") || errorMsg.contains("unknown command") -> " Hint: DSU service not available on this device."
        errorMsg.contains("permission denied") -> " Hint: Shizuku permission was revoked. Please re-grant permission in Shizuku."
        errorMsg.contains("securityexception") -> " Hint: Security restriction on this device."
        else -> ""
    }
    val finalMsg = if (success) "DSU installer started (legacy path)" else "DSU installer error: ${result.substringAfter("]").trim()}$hint"
    onProgress(if (success) 100 else 90, finalMsg)
    return success to "Legacy launch: $result | Documented flow failed first: ${documented.second}"
}

private fun isStorageAccessFailure(result: String): Boolean {
    val lower = result.lowercase(Locale.US)
    return lower.contains("no such file or directory") || lower.contains("permission denied") || lower.contains("read-only file system") || lower.contains("can't create") || lower.contains("cannot create")
}

private fun deleteStagedFile(file: File) {
    try {
        if (file.exists() && !file.delete()) Log.w("bDSU", "Failed to delete temporary file: ${file.absolutePath}")
        val gzip = File(file.parentFile, file.name + ".gz")
        if (gzip.exists() && !gzip.delete()) Log.w("bDSU", "Failed to delete temporary gzip: ${gzip.absolutePath}")
    } catch (e: Exception) { Log.e("bDSU", "Temporary cleanup failed for ${file.absolutePath}", e) }
}

private fun isAllowedRomUrl(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    uri.scheme.equals("https", true) && uri.host.equals("github.com", true) && (uri.path ?: "").contains("/releases/download/")
}.getOrDefault(false)

private fun isValidSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun shortSha(sha256: String): String =
    if (isValidSha256Hex(sha256)) sha256.take(12) + "…" else "unavailable"

private data class FileDigests(val sha256: String, val md5: String)

/** One pass over the file, both digests. Constant memory, 1 MB buffers. */
private suspend fun computeBothDigests(file: File, onProgress: ((Int) -> Unit)? = null): FileDigests = withContext(Dispatchers.IO) {
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    val md5 = java.security.MessageDigest.getInstance("MD5")
    val total = file.length().coerceAtLeast(1L)
    var done = 0L
    var lastReported = -1
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) { sha.update(buffer, 0, read); md5.update(buffer, 0, read) }
            done += read
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            if (pct != lastReported) { lastReported = pct; onProgress?.invoke(pct) }
        }
    }
    FileDigests(bytesToHex(sha.digest()), bytesToHex(md5.digest()))
}

private fun describeDownloadReason(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient storage space"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage device not found"
    DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume the transfer"
    DownloadManager.ERROR_FILE_ERROR -> "file system error"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "unexpected HTTP response"
    else -> "reason code $reason"
}

/** Hash a URI with the given algorithm ("SHA-256" / "MD5") in constant memory. */
private fun computeDigest(context: Context, fileUri: Uri, algorithm: String): String? = try {
    context.contentResolver.openInputStream(fileUri)?.use { stream ->
        val digest = java.security.MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        var read = stream.read(buffer)
        while (read >= 0) {
            if (read > 0) digest.update(buffer, 0, read)
            read = stream.read(buffer)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
} catch (e: Exception) {
    Log.e("bDSU", "$algorithm digest failed", e)
    null
}

private fun verifyDigest(context: Context, fileUri: Uri, algorithm: String, expected: String): Boolean =
    computeDigest(context, fileUri, algorithm)?.equals(expected, ignoreCase = true) == true

private fun verifySha256(context: Context, fileUri: Uri, expectedSha256: String): Boolean =
    verifyDigest(context, fileUri, "SHA-256", expectedSha256)

/** A checksum published by the release site itself — the ground truth for a build. */
private data class PublishedChecksum(val algorithm: String, val hash: String, val sourceUrl: String)

/**
 * Look for the checksum the publisher hosts next to the image: classic companion
 * files like <image>.sha256 / <image>.md5 and directory-level sha256sums.txt /
 * md5sums.txt / SHA256SUMS / MD5SUMS. Short timeouts, tiny probe reads, any miss
 * just returns null so callers can fall back to the catalog digest.
 */
private suspend fun fetchPublisherChecksum(imageUrl: String, fileName: String): PublishedChecksum? = withContext(Dispatchers.IO) {
    val dir = imageUrl.substringBeforeLast('/', "")
    val candidates = listOf(
        "$imageUrl.sha256",
        "$imageUrl.md5",
        "$imageUrl.sha256sum",
        "$dir/sha256sums.txt",
        "$dir/SHA256SUMS.txt",
        "$dir/md5sums.txt",
        "$dir/checksums.txt"
    ).distinct()
    for (candidate in candidates) {
        runCatching {
            val conn = java.net.URL(candidate).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "bDSU/1.3")
            try {
                if (conn.responseCode != 200) return@runCatching
                // Read at most 64 KB — checksum files never get bigger than that.
                val raw = conn.inputStream.readNBytes(64 * 1024).decodeToString()
                for (line in raw.lines()) {
                    val parts = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.size < 2) continue
                    val hash = parts[0].lowercase()
                    val target = parts.last().removePrefix("*").removePrefix("./").substringAfterLast('/')
                    if (target != fileName) continue
                    when {
                        hash.matches(Regex("[0-9a-f]{64}")) -> return@withContext PublishedChecksum("SHA-256", hash, candidate)
                        hash.matches(Regex("[0-9a-f]{32}")) -> return@withContext PublishedChecksum("MD5", hash, candidate)
                    }
                }
            } finally { conn.disconnect() }
        }
    }
    null
}

private fun sanitizeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
    if (cleaned.contains("..") || cleaned.contains("://") || cleaned.contains("~")) {
        return "rom.img.xz"
    }
    return cleaned.take(180).ifBlank { "rom.img.xz" }
}

private fun sanitizePathComponent(name: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
    if (cleaned.contains("..") || cleaned.contains("/") || cleaned.contains("\\") || cleaned.isEmpty()) {
        return "rom"
    }
    return cleaned.take(100)
}

private fun getLocalRomSha256(context: Context, uri: Uri): String {
    return try {
        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: ""
                val matched = romCatalog.firstOrNull { it.hasSha256 && downloadFileName(it).contains(name.substringBeforeLast(".")) }
                matched?.sha256 ?: ""
            } else ""
        } ?: ""
    } catch (_: Exception) { "" }
}

private fun queryDisplayName(context: Context, uri: Uri): String = context.contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else "local.img" } ?: "local.img"

private fun isSupportedLocalRomName(name: String): Boolean { val lower = name.trim().lowercase(Locale.US); return lower.endsWith(".img") || lower.endsWith(".img.xz") || lower.endsWith(".img.gz") }
private fun normalizedImageName(name: String): String {
    val clean = sanitizePathComponent(name).ifBlank { "rom.img" }
    val lower = clean.lowercase(Locale.US)
    return when {
        lower.endsWith(".img.xz") || lower.endsWith(".img.gz") -> clean.dropLast(3)
        else -> clean
    }
}

private fun queryDownload(manager: DownloadManager, id: Long): Quad? = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
    if (!c.moveToFirst()) return@use null
    Quad(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
}
private data class Quad(val first: Long, val second: Long, val third: Int, val fourth: String?, val fifth: Int)
private fun safeName(rom: RomOption) = (rom.family + "_" + rom.version + "_" + rom.android).lowercase(Locale.US).replace("[^a-z0-9]+".toRegex(), "_").trim('_')
private fun downloadFileName(rom: RomOption): String { val candidate = Uri.parse(rom.url).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "download" } ?: safeName(rom) + ".img.xz"; return sanitizeFileName(candidate.substringBefore('?')).ifBlank { safeName(rom) + ".img.xz" } }
private fun formatBytes(v: Long): String = if (v <= 0) "--" else if (v >= 1024L * 1024 * 1024) String.format(Locale.US, "%.2f GB", v / 1073741824.0) else String.format(Locale.US, "%.0f MB", v / 1048576.0)
private fun formatSpeed(v: Long): String = if (v <= 0) "-- MB/s" else String.format(Locale.US, "%.1f MB/s", v / 1048576.0)
private fun eta(done: Long, total: Long, speed: Long): String {
    if (speed <= 0L || total <= done) return "--"
    val seconds = (total - done) / speed
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

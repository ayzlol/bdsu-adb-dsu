/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * bDSU - Android DSU sideloader
 * Copyright (C) 2025-2026 unfayd and ayzlol (the_pan)
 *
 * Modified: 2026-11-09 (bDSU 1.3.6)
 *   Added live fast-mode MB/s/ETA detail, partial-export cleanup, bDSU-tagged
 *   file names with name-based SHA-256 verification, and a /data/local/tmp
 *   DSU launch fallback. See MODIFICATIONS.txt.
 *
 * Modified: 2026-11-08 (bDSU 1.3.3)
 *   Added DSU progress notifications, a user-controlled >4 GB image limit,
 *   three new SHA-256 verified catalog builds, a cached getprop reader,
 *   shell command watchdogs and crash guards. The complete, itemized change
 *   notice required by GPL-3.0 section 5(a) is in MODIFICATIONS.txt.
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
 *  1) CATALOG        — [romCatalog]: 19 hand-vetted GSI builds with exact byte
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
 *                      Selected LOCAL files are user-owned and not blocked by
 *                      checksums unless their name carries a bDSU marker; those
 *                      are matched to the catalog and verified fail-closed
 *                      ([installLocalDsu]).
 *  4) STAGING        — the compressed image is decompressed (xz/gz, 1 MB
 *                      buffers, RAM-capped) into staging ([prepareImageFile]),
 *                      or repackaged straight into public Download as .img.gz
 *                      ([stageDownloadAsGz]) — the single-pass fast path.
 *                      The 4 GB decompressed-size cap is a user setting
 *                      ("Allow images larger than 4 GB").
 *  5) DSU LAUNCH     — AOSP-documented flow first: gzip into the public
 *                      Download directory and fire the explicit
 *                      [com.android.dynsystem.VerificationActivity] intent with
 *                      KEY_SYSTEM_SIZE / KEY_USERDATA_SIZE
 *                      ([launchDsuDocumentedFlow]). If the OEM service rejects
 *                      that URI, retry from /data/local/tmp; a bare action
 *                      intent on the raw .img is the legacy fallback
 *                      ([launchDsuFromStaging]).
 *                      Images are NEVER deleted from staging until reboot —
 *                      the DSU service streams them only after the user
 *                      confirms the system dialog ([deleteOnRebootStagedFile]).
 *                      Every step is mirrored into an English notification by
 *                      [BdsuNotifier] so the operation survives backgrounding.
 *  6) PRIVILEGE      — all shell work runs through Shizuku (binder delivered
 *                      via [rikka.shizuku.ShizukuProvider]; commands go through
 *                      [Shell.run] with merged stdout/stderr so pipes never
 *                      deadlock, plus a watchdog that kills stalled commands).
 *  7) UI             — Compose with a Material You dynamic palette on
 *                      Android 12+, falling back to the full-blue bDSU scheme.
 *
 * Quick file map (top → bottom):
 *   colors/constants → ROM catalog → device probing ([detectDevice]) →
 *   [Shell] singleton → [BdsuNotifier] → [MainActivity] (lifecycle + Shizuku
 *   listeners) → [BdsuTheme] → [DownloadScreen] (all UI + download monitor) →
 *   integrity helpers → install pipelines (steps 4-5) → misc utilities.
 * =============================================================================
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bdsu.app

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
private const val DOWNLOAD_STALL_MS = 10L * 1000L // "no data" warning after 10 s
private const val SHIZUKU_POLL_INTERVAL_MS = 3000L
private const val SHELL_MAX_OUTPUT_BYTES = 4 * 1024 * 1024 // 4MB for better error visibility
private const val SHELL_COMMAND_TIMEOUT_MS = 30L * 60L * 1000L // 30 min watchdog for gzip/xz work
private const val DSU_CHANNEL_ID = "bdsu_dsu_progress"
private const val DSU_NOTIFICATION_ID = 4101
private const val SETTINGS_PREFS = "settings"
private const val PREF_ALLOW_LARGE_IMAGES = "allow_large_images"
private const val PREF_ACTIVE_DOWNLOAD_ID = "active_download_id"
private const val PREF_ACTIVE_DOWNLOAD_URL = "active_download_url"
private const val PREF_CANCEL_REQUESTED = "cancel_requested"
private const val PREF_DOWNLOAD_TREE_URI = "download_tree_uri"
private const val PREF_DOWNLOAD_TREE_NAME = "download_tree_name"
private const val ACTION_CANCEL = "com.ayzlol.bdsu.action.CANCEL"

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
    RomOption("LineageOS", "23.2 • 2026-06-03 • GApps", "Android 16", "arm64-ab", 1092196308L, "https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-GAPPS-20260603.img.xz", "14c263ef67228060046fcb08b0277775b99a93dfb7755f5621a33363a0d3f394"),
    // 1.3.3 additions — all three digests are the SHA-256 values published by
    // GitHub's release-asset digest field, so they describe the exact uploaded
    // bytes rather than a re-hash of an intermediate/renamed artifact.
    RomOption("PixelOS", "16.2 • 2026-06-28 • Official", "Android 16", "arm64-ab", 1443102408L, "https://github.com/Doze-off/PixelOS_GSI_treble/releases/download/2026-06-28/PixelOS-16.2-GSI_treble_arm64-ab-20260628.img.xz", "d159c206695722765de147ec59de10c1c2d130e218aa034fb40f4f6fd3727752"),
    RomOption("VoltageOS", "5.11 • 2026-07-11 • EXT4", "Android 16", "arm64-ab", 896814284L, "https://github.com/Doze-off/voltage_a16_treble/releases/download/2026-07-11-hotfix/Voltage-5.11-GSI_treble_arm64-ab-EXT4-20260711-hotfix.img.xz", "0cae08c3d51020cf556e369a8294f26b7f3fb99f66fd9f4982d738e7cfbd8efd"),
    RomOption("crDroid", "12.12 • 2026-09-22 • Vanilla", "Android 16", "arm64-ab", 974151100L, "https://github.com/Doze-off/crdroid_gsi_treble/releases/download/2026-09-22/crDroid-12.12-GSI_treble_arm64-ab-VANILLA-20260922.img.xz", "b0797c0c93b7fbfbae4cec0e7440fd6f0527abbbc891cc57ac186445d5ef5871")
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
    // One crash in this probe used to take the whole screen down before the
    // first frame; every reading now degrades to a safe default independently.
    val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / 1073741824f }.getOrDefault(0f)
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

/**
 * Cached snapshot of `getprop`. The previous implementation started one
 * `getprop <key>` process per property — six process spawns on the main
 * thread during the first composition. A single `getprop` call is faster,
 * allocates less and cannot throw into the UI.
 */
private val systemProperties: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
    runCatching {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        runCatching { process.waitFor() }
        val props = HashMap<String, String>(256)
        text.lineSequence().forEach { line ->
            val keyEnd = line.indexOf("]: [")
            if (!line.startsWith("[") || keyEnd <= 1) return@forEach
            val valueEnd = line.lastIndexOf(']')
            if (valueEnd > keyEnd + 3) props[line.substring(1, keyEnd)] = line.substring(keyEnd + 4, valueEnd)
        }
        props
    }.getOrDefault(emptyMap())
}

private fun readSystemProperty(name: String): String? = systemProperties[name]?.takeIf { it.isNotBlank() }

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
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            cont.invokeOnCancellation { activeProcess.destroyForcibly() }
            // A stalled `gzip`/`am` child used to keep the coroutine alive
            // forever. Kill it after the watchdog window so the UI can report
            // a failure instead of hanging in "Preparing DSU...".
            val watchdog = Thread {
                try {
                    Thread.sleep(SHELL_COMMAND_TIMEOUT_MS)
                    if (activeProcess.isAlive) {
                        timedOut.set(true)
                        Log.w("bDSU", "Shell command timed out; killing child process")
                        activeProcess.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Normal completion or cancellation — nothing to do.
                }
            }.apply { isDaemon = true; start() }
            Thread {
                try {
                    val output = readStreamLimited(activeProcess.inputStream, "output")
                    val code = activeProcess.waitFor()
                    val prefix = if (timedOut.get()) "[timeout]" else "[exit=$code]"
                    if (cont.isActive) cont.resume("$prefix ${output.trim()}")
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("[error] ${e.message ?: e}")
                } finally {
                    watchdog.interrupt()
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

/**
 * English notifications for long DSU operations. Install callbacks already
 * report every phase; this object mirrors them into one low-importance ongoing
 * notification so the user can leave bDSU while a multi-GB image is staged.
 * Failures (for example a denied POST_NOTIFICATIONS permission) are swallowed
 * on purpose: notifications must never break an installation.
 */
/**
 * Tracks the one install coroutine that may be running so the notification
 * "Cancel" action can interrupt it from outside the Compose screen. Download
 * cancellation is handled by [BdsuCancelReceiver] through DownloadManager.
 */
private object BdsuCancellation {
    @Volatile var installJob: Job? = null
    @Volatile var installRequested = false

    fun cancelInstall() {
        installRequested = true
        installJob?.cancel()
    }
}

private object BdsuNotifier {
    private var lastPercent = -1
    private var lastMessage = ""

    private fun enabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(DSU_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                DSU_CHANNEL_ID,
                "DSU operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Verification, image preparation and DSU installation progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun contentIntent(context: Context): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    /** Notification action: stops the active download or installation. */
    private fun cancelIntent(context: Context): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, BdsuCancelReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    /** Forget throttle state and drop a notification left over by a dead process. */
    fun reset(context: Context) {
        lastPercent = -1
        lastMessage = ""
        runCatching { NotificationManagerCompat.from(context).cancel(DSU_NOTIFICATION_ID) }
    }

    /**
     * @param percent 0..100 for a determinate gauge, or -1 when the real total is
     *                unknown. Both the in-app bar and the notification use the
     *                same value, so they can never disagree.
     */
    fun progress(
        context: Context,
        message: String,
        percent: Int,
        force: Boolean = false,
        title: String = "bDSU • DSU operation",
        cancelable: Boolean = true
    ) {
        runCatching {
            val indeterminate = percent < 0
            val bounded = if (indeterminate) -1 else percent.coerceIn(0, 100)
            if (!force && bounded == lastPercent && message == lastMessage) return
            lastPercent = bounded
            lastMessage = message
            if (!enabled(context)) return
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, DSU_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .apply {
                    if (indeterminate) setProgress(0, 0, true)
                    else setProgress(100, bounded, false)
                }
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            contentIntent(context)?.let { builder.setContentIntent(it) }
            if (cancelable) {
                cancelIntent(context)?.let {
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", it)
                }
            }
            try {
                NotificationManagerCompat.from(context).notify(DSU_NOTIFICATION_ID, builder.build())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS can be revoked between the check and notify.
            }
        }
    }

    fun finished(
        context: Context,
        message: String,
        success: Boolean,
        title: String = if (success) "bDSU • DSU installer started" else "bDSU • DSU operation failed"
    ) {
        runCatching {
            lastPercent = -1
            lastMessage = ""
            if (!enabled(context)) return
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, DSU_CHANNEL_ID)
                .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setOngoing(false)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            contentIntent(context)?.let { builder.setContentIntent(it) }
            try {
                NotificationManagerCompat.from(context).notify(DSU_NOTIFICATION_ID, builder.build())
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS can be revoked between the check and notify.
            }
        }
    }
}

/**
 * Handles the notification "Cancel" action. The receiver runs in the app
 * process, so it can cancel both the DownloadManager request (persisted ID) and
 * the live install coroutine. It never trusts external input: the component is
 * not exported and the notification uses an explicit PendingIntent.
 */
class BdsuCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val downloadId = prefs.getLong(PREF_ACTIVE_DOWNLOAD_ID, 0L)
        var cancelledSomething = false
        if (downloadId > 0L) {
            runCatching {
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(downloadId)
            }
            prefs.edit()
                .putBoolean(PREF_CANCEL_REQUESTED, true)
                .remove(PREF_ACTIVE_DOWNLOAD_ID)
                .remove(PREF_ACTIVE_DOWNLOAD_URL)
                .apply()
            cancelledSomething = true
        }
        if (BdsuCancellation.installJob?.isActive == true) {
            BdsuCancellation.cancelInstall()
            cancelledSomething = true
        }
        if (cancelledSomething) {
            BdsuNotifier.finished(context, "Cancelled by user", false, title = "bDSU • Cancelled")
        } else {
            // Stale notification after the operation already finished.
            BdsuNotifier.reset(context)
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
        // Notification channel is created up front (Android 13+ still needs the
        // runtime grant) and any progress notification left behind by a killed
        // process is removed before a new operation starts.
        BdsuNotifier.ensureChannel(this)
        BdsuNotifier.reset(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
        setContent {
            val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
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
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    val allFamilies = remember { romCatalog.groupBy { it.family } }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var expandedFamilies by remember { mutableStateOf(setOf<String>()) }
    // Reattach to a download that was still running when the process died. The
    // DownloadManager notification is hidden in favour of our own gauge, so this
    // is what keeps that gauge (and the install button) available after a restart.
    val restoredDownloadId = remember {
        prefs.getLong(PREF_ACTIVE_DOWNLOAD_ID, 0L).takeIf { it > 0L }
    }
    var selected by remember {
        mutableStateOf(
            restoredDownloadId?.let {
                val url = prefs.getString(PREF_ACTIVE_DOWNLOAD_URL, null)
                romCatalog.firstOrNull { rom -> rom.url == url }
            }
        )
    }
    var activeId by remember { mutableStateOf(restoredDownloadId) }
    var enqueueing by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(0) }
    var doneBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(0L) }
    var downloadStalled by remember { mutableStateOf(false) }
    // Keeps the progress/status card visible after a failure or cancel so the
    // reason does not disappear when activeId is cleared.
    var downloadStarted by remember { mutableStateOf(restoredDownloadId != null) }
    var status by remember { mutableStateOf("Choose a ROM to start") }
    var debugDetails by remember { mutableStateOf("Debug: ready") }
    var retryCount by remember { mutableStateOf(0) }
    var retryTarget by remember { mutableStateOf(selected) }

    fun rememberActiveDownload(id: Long, rom: RomOption?) {
        prefs.edit()
            .putLong(PREF_ACTIVE_DOWNLOAD_ID, id)
            .putString(PREF_ACTIVE_DOWNLOAD_URL, rom?.url)
            .remove(PREF_CANCEL_REQUESTED)
            .apply()
    }

    fun forgetActiveDownload() {
        prefs.edit()
            .remove(PREF_ACTIVE_DOWNLOAD_ID)
            .remove(PREF_ACTIVE_DOWNLOAD_URL)
            .apply()
    }
    // The custom-ROM downloader always saves the finished image in a folder the
    // user picks through the system document picker (SAF). DownloadManager still
    // uses app storage for the transfer itself, then the file is moved to the
    // selected tree and installed from that content URI.
    var downloadTreeUri by remember {
        mutableStateOf(
            prefs.getString(PREF_DOWNLOAD_TREE_URI, null)?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
        )
    }
    var downloadTreeName by remember { mutableStateOf(prefs.getString(PREF_DOWNLOAD_TREE_NAME, null)) }
    var pendingDownloadRom by remember { mutableStateOf<RomOption?>(null) }
    var savingToFolder by remember { mutableStateOf(false) }
    var savePercent by remember { mutableStateOf(-1) }
    var location by remember {
        mutableStateOf(
            prefs.getString(PREF_DOWNLOAD_TREE_NAME, null)
                ?.let { "Will be saved to: $it" }
                ?: "Saved to: App downloads / Android/data/com.ayzlol.bdsu/files/Download"
        )
    }
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
    var showDebug by remember { mutableStateOf(false) }
    // Off by default: the 4 GB decompressed-size guard stays in force unless the
    // user explicitly opts out in Settings. The preference is read once here and
    // passed down through the whole install pipeline.
    var allowLargeImages by remember { mutableStateOf(prefs.getBoolean(PREF_ALLOW_LARGE_IMAGES, false)) }
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
    var dsuLoaderFlag by remember { mutableStateOf(prefs.getBoolean("dsu_loader_flag", false)) }
    var showBackgroundDialog by remember { mutableStateOf(!prefs.getBoolean("background_prompt_seen", false)) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    // Easter egg: five quick taps on the hero card flick the cat on screen, and it
    // fades away over 4 seconds while the rest of the app keeps running.
    var easterEggTaps by remember { mutableStateOf(0) }
    var easterEggLastTap by remember { mutableStateOf(0L) }
    var easterEggVisible by remember { mutableStateOf(false) }
    val manager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    fun downloadRom(rom: RomOption) {
        if (activeId != null || enqueueing || installing) return
        if (!isAllowedRomUrl(rom.url)) {
            status = "Blocked unsafe ROM URL. Only verified GitHub release links are allowed"
            return
        }
        enqueueing = true
        try {
            selected = rom
            val request = DownloadManager.Request(Uri.parse(rom.url))
                .setTitle("bDSU • ${rom.family} ${rom.version}")
                .setDescription("Downloading ${rom.android} ${rom.architecture} • ${formatBytes(rom.sizeBytes)} • " + if (requireWifi) "Wi-Fi only" else "Wi-Fi or mobile data")
                .setMimeType("application/octet-stream")
                // Hidden on purpose: the app's own ongoing notification mirrors
                // the in-app percentage exactly.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setAllowedOverMetered(!requireWifi)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadFileName(rom))
            val newId = manager.enqueue(request)
            rememberActiveDownload(newId, rom)
            downloadStarted = true
            activeId = newId
            percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
            localUri = null; localName = null; finishedFile = null; finishedId = null
            retryCount = 0; retryTarget = rom
            location = downloadTreeName?.let { "Will be saved to: $it after download" }
                ?: "Saved to: App downloads / Android/data/com.ayzlol.bdsu/files/Download (choose a folder to keep ROMs outside the app)"
            status = "Queued ${rom.family} ${rom.version} • ${formatBytes(rom.sizeBytes)}"
            BdsuNotifier.progress(context, "Download queued: ${rom.family} ${rom.version} • ${formatBytes(rom.sizeBytes)}", -1, force = true, title = "bDSU • Downloading")
        } catch (e: Exception) {
            status = "Download could not be queued: ${e.message ?: "unknown error"}"
        } finally {
            enqueueing = false
        }
    }

    val downloadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = pendingDownloadRom
        pendingDownloadRom = null
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = queryTreeDisplayName(context, uri) ?: "Selected folder"
            downloadTreeUri = uri
            downloadTreeName = name
            location = "Will be saved to: $name"
            prefs.edit()
                .putString(PREF_DOWNLOAD_TREE_URI, uri.toString())
                .putString(PREF_DOWNLOAD_TREE_NAME, name)
                .apply()
            status = "ROM download folder: $name"
            if (pending != null) downloadRom(pending)
        } else {
            status = "Folder selection cancelled; no download started"
        }
    }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            if (!isSupportedLocalRomName(displayName)) {
                localUri = null
                localName = null
                selected = null
                finishedFile = null
                finishedId = null
                status = "Unsupported local ROM file. Choose an .img, .img.xz or .img.gz file."
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

    LaunchedEffect(downloadTreeUri) {
        val tree = downloadTreeUri ?: return@LaunchedEffect
        // Do not race an export that is already running; the post-export cleanup
        // below handles stale *.part documents in that case.
        if (activeId == null) withContext(Dispatchers.IO) { cleanupPartialExports(context, tree) }
    }

    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        var previousBytes = 0L
        var previousTime = System.currentTimeMillis()
        var lastGrowthAt = System.currentTimeMillis()
        var smoothedSpeed = 0L
        val monitorStartedAt = System.currentTimeMillis()
        BdsuNotifier.progress(context, "Starting download…", -1, force = true, title = "bDSU • Downloading")
        // Any exception thrown from DownloadManager used to escape the effect and
        // crash the process. Keep the monitor alive and surface the error instead.
        try {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now - monitorStartedAt > DOWNLOAD_TIMEOUT_MS) {
                runCatching { manager.remove(id) }
                forgetActiveDownload()
                percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                status = "Download timed out after 4 hours; partial file removed"
                BdsuNotifier.finished(context, status, false, title = "bDSU • Download timed out")
                activeId = null
                break
            }
            val snapshot = withContext(Dispatchers.IO) { runCatching { queryDownload(manager, id) }.getOrNull() }
            if (snapshot == null) {
                val cancelRequested = prefs.getBoolean(PREF_CANCEL_REQUESTED, false)
                prefs.edit().remove(PREF_CANCEL_REQUESTED).apply()
                forgetActiveDownload()
                percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                if (cancelRequested) {
                    status = "Download cancelled by user"
                    debugDetails = "Debug: download cancelled from notification"
                    BdsuNotifier.finished(context, "Download cancelled", false, title = "bDSU • Cancelled")
                } else {
                    status = "Download no longer exists; please start it again"
                    BdsuNotifier.reset(context)
                }
                activeId = null
                break
            }

            val rom = selected
            val newDone = snapshot.first.coerceAtLeast(0L)
            // Prefer DownloadManager's Content-Length. Before it arrives, the
            // verified catalog size is an excellent stand-in, so the gauge moves
            // from the first second instead of sitting at 0%.
            val effectiveTotal = if (snapshot.second > 0L) snapshot.second else rom?.sizeBytes ?: 0L
            if (newDone > previousBytes) {
                lastGrowthAt = now
                downloadStalled = false
            } else if (snapshot.third == DownloadManager.STATUS_RUNNING && now - lastGrowthAt > DOWNLOAD_STALL_MS) {
                // No new bytes for a while. Say so instead of freezing silently.
                downloadStalled = true
            }

            val elapsed = (now - previousTime).coerceAtLeast(1L)
            val instantSpeed = if (newDone > previousBytes) {
                ((newDone - previousBytes) * 1000L / elapsed).coerceAtLeast(0L)
            } else 0L
            smoothedSpeed = when {
                instantSpeed > 0L && smoothedSpeed > 0L -> (smoothedSpeed * 2L + instantSpeed) / 3L
                instantSpeed > 0L -> instantSpeed
                else -> 0L
            }
            speed = smoothedSpeed
            doneBytes = newDone
            totalBytes = effectiveTotal
            percent = if (effectiveTotal > 0L) {
                ((newDone * 100L) / effectiveTotal).toInt().coerceIn(0, 100)
            } else -1
            previousBytes = newDone
            previousTime = now

            when (snapshot.third) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val fileUri = snapshot.fourth
                    // Fail closed. A completed download we cannot locate, whose size
                    // does not match the catalog, or whose digest does not match must
                    // never reach the DSU installer.
                    if (rom == null || fileUri == null) {
                        status = "Download finished but the file could not be located; please retry"
                        runCatching { manager.remove(id) }
                        forgetActiveDownload()
                        percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                        BdsuNotifier.finished(context, status, false, title = "bDSU • Download failed")
                        activeId = null
                        break
                    }
                    if (rom.sizeBytes > 0L && snapshot.first != rom.sizeBytes) {
                        status = "Size mismatch (expected ${formatBytes(rom.sizeBytes)}, got ${formatBytes(snapshot.first)}); corrupt download removed"
                        runCatching { manager.remove(id) }
                        forgetActiveDownload()
                        percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                        BdsuNotifier.finished(context, status, false, title = "bDSU • Download failed")
                        activeId = null
                        break
                    }
                    if (rom.hasSha256) {
                        status = "Verifying SHA-256 of ${rom.family} ${rom.version}…"
                        BdsuNotifier.progress(context, status, 100, force = true, title = "bDSU • Verifying download")
                        val verified = withContext(Dispatchers.IO) {
                            verifySha256(context, Uri.parse(fileUri), rom.sha256)
                        }
                        if (!verified) {
                            status = "SHA-256 mismatch! File may be corrupted or tampered; removed, please re-download"
                            runCatching { manager.remove(id) }
                            forgetActiveDownload()
                            percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                            BdsuNotifier.finished(context, status, false, title = "bDSU • Download failed")
                            activeId = null
                            break
                        }
                        status = "Download complete • SHA-256 ${shortSha(rom.sha256)} verified"
                    } else {
                        status = "Download complete • WARNING: no SHA-256 for this build, integrity not verified"
                    }
                    percent = 100
                    doneBytes = snapshot.first
                    totalBytes = if (effectiveTotal > 0L) effectiveTotal else snapshot.first
                    speed = 0L
                    downloadStalled = false
                    var exportedUri: Uri? = null
                    var exportedName: String? = null
                    var exportError: String? = null
                    val tree = downloadTreeUri
                    if (tree != null) {
                        savingToFolder = true
                        savePercent = 0
                        val saveStartedAt = SystemClock.elapsedRealtime()
                        status = "Saving ROM to ${downloadTreeName ?: "selected folder"}…"
                        BdsuNotifier.progress(context, status, 0, force = true, title = "bDSU • Saving ROM", cancelable = false)
                        try {
                            val exported = exportToDocumentTree(
                                context = context,
                                source = Uri.parse(fileUri),
                                treeUri = tree,
                                displayName = exportFileName(rom),
                                expectedBytes = snapshot.first
                            ) { copied, total ->
                                val p = if (total > 0L) ((copied * 100L) / total).toInt().coerceIn(0, 100) else -1
                                savePercent = p
                                val line = transferLine(
                                    "Saving ROM to ${downloadTreeName ?: "selected folder"}",
                                    copied,
                                    total,
                                    SystemClock.elapsedRealtime() - saveStartedAt
                                )
                                status = line
                                BdsuNotifier.progress(
                                    context,
                                    line,
                                    p,
                                    title = "bDSU • Saving ROM",
                                    cancelable = false
                                )
                            }
                            exportedUri = exported.first
                            exportedName = exported.second
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("bDSU", "Saving ROM to selected folder failed", e)
                            exportError = e.message ?: e::class.java.simpleName
                        } finally {
                            savingToFolder = false
                            savePercent = -1
                        }
                    }
                    if (exportedUri != null) {
                        // Remove DownloadManager's app-private copy. The install now
                        // reads the user-visible SAF document directly.
                        runCatching { manager.remove(id) }
                        finishedFile = null
                        finishedId = null
                        localUri = exportedUri
                        localName = exportedName
                        val savedPath = "${downloadTreeName ?: "Selected folder"}/${exportedName ?: exportFileName(rom)}"
                        val shaNote = if (rom.hasSha256) " • SHA-256 ${shortSha(rom.sha256)}" else ""
                        location = "Saved to: $savedPath"
                        status = "Download complete$shaNote • saved to $savedPath"
                        BdsuNotifier.finished(context, "Download complete • saved to $savedPath", true, title = "bDSU • Download complete")
                    } else {
                        finishedFile = fileUri
                        finishedId = id
                        location = "Saved to: App downloads / Android/data/com.ayzlol.bdsu/files/Download"
                        if (exportError != null) {
                            status = "$status • Could not save to selected folder: $exportError"
                        }
                        BdsuNotifier.finished(context, status, true, title = "bDSU • Download complete")
                    }
                    forgetActiveDownload()
                    if (tree != null) {
                        withContext(Dispatchers.IO) { cleanupPartialExports(context, tree) }
                    }
                    activeId = null
                    retryCount = 0
                    retryTarget = null
                }
                DownloadManager.STATUS_FAILED -> {
                    if (retryCount < 3 && retryTarget != null) {
                        retryCount++
                        status = "Download failed. Retry $retryCount/3..."
                        // Remove the failed request first so its partial file is
                        // deleted and the retry starts from a clean destination.
                        runCatching { manager.remove(id) }
                        val retryRom = retryTarget!!
                        val retryRequest = DownloadManager.Request(Uri.parse(retryRom.url))
                            .setTitle("bDSU • ${retryRom.family} ${retryRom.version}")
                            .setMimeType("application/octet-stream")
                            // Hidden: the app's own notification mirrors its gauge.
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                            .setAllowedOverMetered(!requireWifi)
                            .setAllowedOverRoaming(false)
                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadFileName(retryRom))
                        val retryId = manager.enqueue(retryRequest)
                        rememberActiveDownload(retryId, retryRom)
                        downloadStarted = true
                        percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                        BdsuNotifier.progress(context, "Download failed — retrying ($retryCount/3)…", -1, force = true, title = "bDSU • Downloading")
                        activeId = retryId
                        break
                    } else {
                        status = "Download failed after ${retryCount + 1} attempts: ${describeDownloadReason(snapshot.fifth)}"
                        // Final failure: remove the DownloadManager record so its
                        // partial file is deleted instead of being orphaned in app
                        // storage after the prefs entry is cleared.
                        runCatching { manager.remove(id) }
                        forgetActiveDownload()
                        percent = 0; doneBytes = 0L; totalBytes = 0L; speed = 0L; downloadStalled = false
                        BdsuNotifier.finished(context, status, false, title = "bDSU • Download failed")
                        activeId = null; retryCount = 0; retryTarget = null
                        break
                    }
                }
                else -> {
                    status = if (downloadStalled) {
                        "Download stalled — waiting for data, still trying…"
                    } else {
                        "Download in progress"
                    }
                    BdsuNotifier.progress(
                        context,
                        downloadProgressText(rom, percent, doneBytes, totalBytes, speed, downloadStalled),
                        percent,
                        title = "bDSU • Downloading"
                    )
                }
            }
            delay(600)
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("bDSU", "Download monitor stopped unexpectedly", e)
            status = "Download monitor stopped: ${e.message ?: e::class.java.simpleName}"
            BdsuNotifier.finished(context, status, false, title = "bDSU • Download monitor stopped")
            activeId = null
        }
    }

    if (showBackgroundDialog) AlertDialog(onDismissRequest = { showBackgroundDialog = false }, title = { Text("Keep bDSU running") }, text = { Text("Please allow this app to remain active in the background while the ROM is downloaded and DSU is installed.") }, confirmButton = { TextButton(onClick = { prefs.edit().putBoolean("background_prompt_seen", true).apply(); showBackgroundDialog = false; try { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("OK", color = MaterialTheme.colorScheme.primary) } }, dismissButton = { TextButton(onClick = { prefs.edit().putBoolean("background_prompt_seen", true).apply(); showBackgroundDialog = false }) { Text("I don't want", color = MaterialTheme.colorScheme.onSurfaceVariant) } })

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow images larger than 4 GB", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    if (allowLargeImages) "On • 4 GB decompressed-size guard disabled; images up to 64 GB are accepted (advanced)"
                    else "Off • images that expand beyond 4 GB are rejected before staging (safe default)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
            }
            Switch(
                checked = allowLargeImages,
                onCheckedChange = {
                    allowLargeImages = it
                    prefs.edit().putBoolean(PREF_ALLOW_LARGE_IMAGES, it).apply()
                },
                enabled = !installing
            )
        }
        if (allowLargeImages) Text("Your device's DSU implementation may still refuse images above 4 GB. Keep the default unless you specifically need a larger build.", color = Red, fontSize = 11.sp)
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

    // Extracted from the old inline Button handler. Keeping the whole pipeline in
    // one try/finally means a thrown exception can never leave the UI stuck in
    // "Preparing DSU..." and every result is mirrored to a notification.
    val startInstall: () -> Unit = {
        val romForInstall = selected
        val local = localUri
        val downloadId = finishedId
        when {
            installing -> Unit
            !shizukuReady -> status = "Connect Shizuku before installing"
            local == null && (downloadId == null || romForInstall == null) -> status = "No completed download is available to install"
            else -> {
                val userdataBytes = if (userdataEnabled) userdataGb.toLong() * 1073741824L else 0L
                val localDisplayName = localName ?: "local.img.xz"
                installing = true
                BdsuCancellation.installRequested = false
                installProgress = -1
                installPhase = "Starting"
                status = "Preparing DSU installer…"
                debugDetails = "Debug: DSU preparation started"
                var lastUiPercent = -2
                var lastUiPhase = ""
                val progressCallback: (Int, String) -> Unit = { p, s ->
                    if (p != lastUiPercent || s != lastUiPhase) {
                        lastUiPercent = p
                        lastUiPhase = s
                        BdsuNotifier.progress(context, s, p, title = "bDSU • Preparing DSU")
                        mainHandler.post {
                            installProgress = p
                            installPhase = s
                        }
                    }
                }
                BdsuNotifier.progress(context, "Preparing DSU installer…", -1, force = true, title = "bDSU • Preparing DSU")
                val installJob = scope.launch {
                    var result = ""
                    var wasCancelled = false
                    try {
                        result = if (local != null) {
                            installLocalDsu(
                                context, local, localDisplayName, userdataBytes,
                                useSdCard, allowLargeImages, progressCallback
                            )
                        } else {
                            installDsu(
                                context, manager, downloadId!!, romForInstall!!, userdataBytes,
                                useSdCard, allowLargeImages, progressCallback
                            )
                        }
                    } catch (e: CancellationException) {
                        // Cancellation is expected when the user taps Cancel. Do
                        // not rethrow: the finally block below must still clear
                        // the UI state and the notification action.
                        wasCancelled = true
                    } catch (e: Exception) {
                        Log.e("bDSU", "DSU installation failed unexpectedly", e)
                        result = "DSU installation failed: ${e.message ?: e::class.java.simpleName}"
                    } finally {
                        BdsuCancellation.installJob = null
                        installing = false
                    }
                    if (wasCancelled) {
                        if (BdsuCancellation.installRequested) {
                            installPhase = "Cancelled"
                            status = "Installation cancelled by user"
                            debugDetails = "Debug: installation cancelled from notification or UI"
                            BdsuNotifier.finished(context, "Installation cancelled", false, title = "bDSU • Cancelled")
                        }
                        BdsuCancellation.installRequested = false
                        return@launch
                    }
                    val succeeded = result.contains("DSU installer started", ignoreCase = true)
                    if (succeeded) installProgress = 100
                    installPhase = if (succeeded) "DSU installer started" else "Stopped"
                    status = result
                    debugDetails = "Debug result: $result"
                    BdsuNotifier.finished(context, result, succeeded)
                }
                BdsuCancellation.installJob = installJob
            }
        }
    }

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
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ROM download folder", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(
                    if (downloadTreeName != null) {
                        "Selected: $downloadTreeName\nFinished ROMs are saved here and installed from this folder."
                    } else {
                        "No folder selected yet. The first download asks you to choose a folder. The ROM is saved outside the app instead of Android/data."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Button(onClick = {
                    pendingDownloadRom = null
                    downloadFolderPicker.launch(null)
                }, enabled = activeId == null && !installing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text(if (downloadTreeName == null) "Choose download folder" else "Change download folder")
                }
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
                if (downloadStarted || activeId != null || percent > 0 || finishedFile != null || localUri != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    // One gauge for the whole app. -1 means the real total is not
                    // known yet, so both this bar and the notification go
                    // indeterminate instead of freezing on a made-up number.
                    val activePercent = when {
                        installing && installProgress >= 0 -> installProgress
                        savingToFolder && savePercent >= 0 -> savePercent
                        !installing && percent >= 0 -> percent
                        else -> null
                    }
                    val targetFraction = (activePercent ?: 0) / 100f
                    val animatedFraction by animateFloatAsState(
                        targetValue = targetFraction.coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
                        label = "dsuProgress"
                    )
                    if (activePercent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    } else {
                        LinearProgressIndicator(progress = { animatedFraction }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    }
                    if (installing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                            Text(
                                if (installProgress >= 0) "$installPhase • $installProgress%" else "$installPhase • working…",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else if (savingToFolder) {
                        Text(
                            "Saving to ${downloadTreeName ?: "selected folder"} • " +
                                if (savePercent >= 0) "$savePercent%" else "working…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    } else if (activeId == null && percent >= 100) {
                        Text("Download complete • ${formatBytes(doneBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        Text(downloadProgressText(selected, percent, doneBytes, totalBytes, speed, downloadStalled), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        if (downloadStalled) Text("No new data for 10+ seconds — the download is still active and will resume automatically.", color = Red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(location, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System DSU Loader screens", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (dsuLoaderFlag) "On • Android Settings will expose the built-in DSU Loader" else "Off • enable to also manage DSU installs from Android Settings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = dsuLoaderFlag, onCheckedChange = { new ->
                dsuLoaderFlag = new
                prefs.edit().putBoolean("dsu_loader_flag", new).apply()
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
                    if (activeId != null) Button(onClick = {
                        runCatching { manager.remove(activeId!!) }
                        BdsuNotifier.reset(context)
                        forgetActiveDownload()
                        activeId = null; percent = 0; doneBytes = 0; totalBytes = 0; speed = 0; downloadStalled = false
                        status = "Download cancelled and partial file removed"
                        debugDetails = "Debug: DownloadManager request cancelled"
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Cancel download", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    if ((finishedId != null || localUri != null) && activeId == null && !installing) Button(onClick = startInstall, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("Install into DSU") }
                    if (installing) Button(onClick = {
                        BdsuCancellation.cancelInstall()
                        status = "Cancelling installation…"
                        installPhase = "Cancelling"
                        debugDetails = "Debug: cancellation requested"
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text("Cancel installation", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } }
            }
            item { Button(onClick = {
                val rom = selected ?: return@Button
                if (activeId != null || enqueueing || installing) return@Button
                if (downloadTreeUri == null) {
                    pendingDownloadRom = rom
                    status = "Choose a folder to save ${rom.family} ${rom.version}"
                    downloadFolderPicker.launch(null)
                } else {
                    downloadRom(rom)
                }
            }, enabled = selected != null && activeId == null && !enqueueing && !installing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(14.dp)) { Text("Download selected ROM") } }
            item { Text("This debug section only shows the steps that run while the DSU is being prepared — your device logs or the DSU boot sequence cannot be captured because Android keeps them in a separate low-level process.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f), fontSize = 11.sp) }
            item {
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

private suspend fun installDsu(context: Context, manager: DownloadManager, id: Long, rom: RomOption, userdataBytes: Long, useSdCard: Boolean, allowLargeImages: Boolean, onProgress: (Int, String) -> Unit): String = withContext(Dispatchers.IO) {
    // Defense in depth: re-verify immediately before handing the image to the DSU
    // service, so a file edited or truncated after the download still gets caught.
    if (!rom.hasSha256) return@withContext "Catalog entry for ${rom.family} ${rom.version} has no valid SHA-256; refusing to install"
    if (!Shell.ready()) return@withContext "Shizuku permission is required before DSU installation"
    val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: return@withContext "No writable internal staging storage is available"
    val downloaded = File(downloadsDir, downloadFileName(rom))
    if (!downloaded.exists() || downloaded.length() <= 0L) {
        val pfd = runCatching { manager.openDownloadedFile(id) }.getOrNull()
            ?: return@withContext "Download file not found or corrupted. Please re-download the ROM."
        return@withContext installFromStream(context, ParcelFileDescriptor.AutoCloseInputStream(pfd), downloadFileName(rom), -1L, userdataBytes, useSdCard, allowLargeImages, onProgress)
    }
    if (rom.sizeBytes > 0L && downloaded.length() != rom.sizeBytes) {
        runCatching { manager.remove(id) }
        runCatching { downloaded.delete() }
        return@withContext "Size mismatch for ${downloaded.name}: expected ${formatBytes(rom.sizeBytes)} but found ${formatBytes(downloaded.length())}. File may be corrupt; please re-download"
    }
    // Read the real uncompressed size from the container metadata (two small
    // seeks). This is what lets the preparation screen show a truthful
    // percentage instead of the old "stuck at 50%" clamp.
    val expectedRaw = when {
        downloaded.name.endsWith(".xz", ignoreCase = true) -> readXzUncompressedSize(downloaded)
        downloaded.name.endsWith(".gz", ignoreCase = true) -> readGzipUncompressedSize(downloaded)
        else -> downloaded.length()
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
    // the build (sha256/md5). Already hashed above — compare directly. This is
    // best-effort and time-boxed so a slow host cannot stall preparation.
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
    val userdataArg = dsuUserdataArg(userdataBytes)
    if (downloaded.name.endsWith(".xz", ignoreCase = true)) {
        // Optimized single pass: xz → gzip straight into public Download, so the
        // multi-GB raw .img never has to exist before the documented DSU launch.
        val launched = runCatching {
            val (gzFile, rawSize) = stageDownloadAsGz(downloaded, expectedRaw, allowLargeImages, onProgress)
            launchDsuFromGzFile(gzFile, rawSize, userdataArg, onProgress)
        }.getOrNull()
        if (launched != null && launched.first) return@withContext "DSU installer started"
        // Fall through to the full pipeline (raw staging + documented + legacy).
        Log.w("bDSU", "Fast xz→gz path failed, falling back: ${launched?.second}")
        // A user cancel is wrapped by runCatching above; stop before starting the
        // expensive fallback pipeline.
        currentCoroutineContext().ensureActive()
    }
    FileInputStream(downloaded).use { input ->
        installFromStream(context, input, downloaded.name, downloaded.length(), userdataBytes, useSdCard, allowLargeImages, onProgress, expectedRaw)
    }
}

private suspend fun installLocalDsu(context: Context, uri: Uri, imageName: String, userdataBytes: Long, useSdCard: Boolean, allowLargeImages: Boolean, onProgress: (Int, String) -> Unit): String = withContext(Dispatchers.IO) {
    val safeName = imageName.ifBlank { "local.img" }
    if (!isSupportedLocalRomName(safeName)) return@withContext "Unsupported local ROM file. Choose an .img, .img.xz or .img.gz file."
    if (!Shell.ready()) return@withContext "Shizuku permission is required before DSU installation"

    val localSize = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
    val matched = catalogMatchForName(safeName)
    var advisory = ""
    if (isBdsuTaggedName(safeName)) {
        // A bDSU-tagged file is one this app exported (or the user deliberately
        // marked). It must map to exactly one catalog build; otherwise we cannot
        // know which SHA-256 to trust and the install is refused.
        if (matched == null) {
            return@withContext "bDSU-tagged file could not be matched to a catalog build. Remove the bDSU marker to install it as an untrusted local ROM."
        }
        if (!matched.hasSha256) {
            return@withContext "Catalog entry for ${matched.family} ${matched.version} has no valid SHA-256; refusing to install bDSU-tagged file"
        }
        if (matched.sizeBytes > 0L && localSize > 0L && localSize != matched.sizeBytes) {
            return@withContext "Size mismatch for bDSU file: expected ${formatBytes(matched.sizeBytes)} but found ${formatBytes(localSize)}. File may be corrupt."
        }
        onProgress(1, "Verifying bDSU file against ${matched.family} catalog SHA-256…")
        val verified = verifySha256(context, uri, matched.sha256) { p ->
            onProgress(1 + p * 3 / 100, "Verifying bDSU SHA-256: $p%")
        }
        if (!verified) {
            return@withContext "SHA-256 mismatch for bDSU-tagged ${matched.family} ${matched.version}. Refusing to install; the file may be corrupted or modified."
        }
        onProgress(4, "bDSU SHA-256 verified ${shortSha(matched.sha256)} (${matched.family})")
        advisory = " • bDSU name verified against ${matched.family} catalog SHA-256"
    } else if (matched != null) {
        // Ordinary local picks remain user-owned and are never checksum-blocked.
        advisory = " • matches catalog build; integrity intentionally not checked"
    }
    val input = context.contentResolver.openInputStream(uri) ?: return@withContext "Cannot open the selected local ROM"
    // The gzip tail is read once and reused by both the fast path and the generic
    // pipeline; it is exact below 4 GiB and -1 (unknown/wrapped) above.
    val gzipRawSize = if (safeName.endsWith(".gz", ignoreCase = true)) {
        readGzipTrailerUncompressedSize(context, uri)
    } else -1L
    // Fast path: an already-gzipped local image can land straight into public Download
    // (one copy pass, no decompress/recompress). The uncompressed size comes from the
    // gzip tail (ISIZE). Falls back to the generic pipeline if anything is off.
    if (safeName.endsWith(".img.gz", ignoreCase = true)) {
        val rawSize = gzipRawSize
        // The gzip trailer only stores the size modulo 4 GiB. Treat the fast path
        // as usable only when the reported raw size is plausible (not smaller than
        // the compressed file) and fits the current cap; otherwise the generic
        // pipeline decompresses and counts the real bytes.
        val plausible = rawSize > 0L && (localSize <= 0L || rawSize >= localSize)
        val withinLimit = plausible && rawSize in 1L..maxImageBytes(allowLargeImages)
        if (rawSize >= 400L * 1024 * 1024 && withinLimit) {
            val userdataArg = dsuUserdataArg(userdataBytes)
            val launched = runCatching {
                onProgress(10, "Copying gzip image into Download (fast mode)…")
                val gzFile = copyUriToPublicGz(context, uri, safeName, onProgress)
                launchDsuFromGzFile(gzFile, rawSize, userdataArg, onProgress)
            }.getOrNull()
            if (launched != null && launched.first) {
                runCatching { input.close() }
                return@withContext "DSU installer started$advisory"
            }
            Log.w("bDSU", "Fast gz path failed, falling back: ${launched?.second}")
            currentCoroutineContext().ensureActive()
        } else if (plausible && !withinLimit) {
            runCatching { input.close() }
            return@withContext imageSizeLimitMessage(allowLargeImages)
        }
    }
    // Own the stream here: installFromStream can return or throw before
    // prepareImageFile() takes ownership of it, and the content resolver stream
    // would otherwise stay open for the lifetime of the process.
    // Exact uncompressed size when the container exposes it, so the preparation
    // gauge tracks real bytes instead of guessing.
    val expectedRaw = when {
        safeName.endsWith(".xz", ignoreCase = true) -> readXzUncompressedSize(context, uri)
        safeName.endsWith(".gz", ignoreCase = true) -> gzipRawSize
        else -> localSize
    }
    val result = try {
        input.use { owned -> installFromStream(context, owned, safeName, localSize, userdataBytes, useSdCard, allowLargeImages, onProgress, expectedRaw) }
    } catch (e: CancellationException) {
        runCatching { input.close() }
        throw e
    }
    if (advisory.isNotBlank()) "$result$advisory" else result
}

private suspend fun installFromStream(context: Context, source: java.io.InputStream, imageName: String, sourceSizeBytes: Long, userdataBytes: Long, useSdCard: Boolean, allowLargeImages: Boolean, onProgress: (Int, String) -> Unit, expectedRawBytes: Long = -1L): String = withContext(Dispatchers.IO) {
    if (!isSupportedLocalRomName(imageName)) return@withContext "Unsupported image format. Only .img, .img.xz and .img.gz are supported."
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
        if (sourceSizeBytes > 0L || expectedRawBytes > 0L) {
            // When the container exposes the exact uncompressed size, reserve just
            // that plus a 512 MB margin. Otherwise use the old conservative
            // multiplier (.xz ~3x, .gz ~2x). Either way a full disk can never
            // wedge the device mid-install.
            val multiplier = if (imageName.endsWith(".xz", ignoreCase = true)) 3 else if (imageName.endsWith(".gz", ignoreCase = true)) 2 else 1
            val required = if (expectedRawBytes > 0L) {
                expectedRawBytes + 536870912L
            } else {
                sourceSizeBytes * multiplier + 536870912L
            }
            val stageFree = runCatching { StatFs(preferredDir.path).availableBytes }.getOrNull() ?: -1L
            if (stageFree >= 0L && stageFree < required) {
                val sdHint = if (!useSdCard && removable != null) {
                    val sdFree = runCatching { StatFs(removable.path).availableBytes }.getOrNull() ?: -1L
                    if (sdFree >= required) " Your SD card has ${formatBytes(sdFree)} free — enable 'Use SD card for DSU staging' and retry." else ""
                } else ""
                return@withContext "Not enough free space for staging: need ~${formatBytes(required)} but only ${formatBytes(stageFree)} is free here.$sdHint"
            }
        }
        val prepared = prepareImageFile(source, imageName, imageFile, expectedRawBytes, allowLargeImages, onProgress)
        deleteOnRebootStagedFile(prepared)
        val rawSize = prepared.length()
        if (rawSize <= 0L) return@withContext "The selected image is empty"
        val userdataArg = dsuUserdataArg(userdataBytes)
        val firstResult = launchDsuFromStaging(prepared, stagedName, rawSize, userdataArg, onProgress)
        if (firstResult.first) return@withContext "DSU installer started"

        val shouldFallback = useSdCard && removable != null && isStorageAccessFailure(firstResult.second)
        if (shouldFallback) {
            onProgress(82, "SD staging unavailable; retrying with internal storage")
            val fallbackImage = File(internalDir, stagedName)
            prepared.inputStream().use { input -> FileOutputStream(fallbackImage).use { output -> input.copyTo(output, 1024 * 1024) } }
            deleteOnRebootStagedFile(fallbackImage)
            val fallbackResult = launchDsuFromStaging(fallbackImage, stagedName, rawSize, userdataArg, onProgress)
            if (fallbackResult.first) return@withContext "DSU installer started using internal staging (SD card unavailable)"
            return@withContext "DSU launch failed after SD and internal staging attempts: ${fallbackResult.second}"
        }
        "DSU launch failed: ${firstResult.second}"
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { "DSU installation failed: ${e.message}" }
}

private const val MAX_DECOMPRESSED_SIZE = 4L * 1024 * 1024 * 1024
// Absolute ceiling even when the user disables the 4 GB guard: protects the
// device from a corrupt/hostile stream claiming an impossible decompressed size.
private const val ABSOLUTE_MAX_DECOMPRESSED_SIZE = 64L * 1024 * 1024 * 1024
private const val XZ_MAX_MEMORY_BYTES = 256 * 1024 * 1024 // 256MB limit for XZ decompressor

private fun maxImageBytes(allowLargeImages: Boolean): Long =
    if (allowLargeImages) ABSOLUTE_MAX_DECOMPRESSED_SIZE else MAX_DECOMPRESSED_SIZE

private fun imageSizeLimitMessage(allowLargeImages: Boolean): String =
    if (allowLargeImages) "Decompressed image exceeds the absolute 64 GB safety limit."
    else "Decompressed image exceeds the 4 GB safety limit. Enable 'Allow images larger than 4 GB' in Settings and retry."

private suspend fun prepareImageFile(source: java.io.InputStream, imageName: String, imageFile: File, expectedRawBytes: Long, allowLargeImages: Boolean, onProgress: (Int, String) -> Unit): File {
    val stageDir = imageFile.parentFile ?: throw IllegalStateException("Staging directory is missing")
    if (!stageDir.exists() && !stageDir.mkdirs()) throw IllegalStateException("Cannot create staging directory: ${stageDir.absolutePath}")
    if (!stageDir.isDirectory || !stageDir.canWrite()) throw IllegalStateException("Staging directory is not writable: ${stageDir.absolutePath}")
    val limitBytes = maxImageBytes(allowLargeImages)
    if (expectedRawBytes > limitBytes) throw IllegalStateException(imageSizeLimitMessage(allowLargeImages))
    val isXz = imageName.endsWith(".xz", ignoreCase = true)
    val isGz = imageName.endsWith(".gz", ignoreCase = true)
    val verb = when {
        isXz -> "Decompressing image (xz)"
        isGz -> "Decompressing image (gzip)"
        else -> "Copying image"
    }
    // Exact total when the container metadata provided one. Without it, report an
    // indeterminate gauge and real extracted bytes instead of the old fake clamp.
    val targetBytes = expectedRawBytes.takeIf { it > 0L } ?: -1L
    val knownTotal = targetBytes > 0L
    val startedAt = SystemClock.elapsedRealtime()
    onProgress(
        if (knownTotal) 5 else -1,
        if (knownTotal) transferLine(verb, 0L, targetBytes, 0L) else "$verb (total unknown) • 0 MB"
    )
    try {
        source.use { input ->
            val decoded: java.io.InputStream = when {
                isXz -> {
                    // Use memory-limited XZ decompressor to prevent OOM
                    org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(input, 1024 * 1024), XZ_MAX_MEMORY_BYTES)
                }
                isGz -> java.util.zip.GZIPInputStream(java.io.BufferedInputStream(input, 1024 * 1024), 128 * 1024)
                else -> input
            }
            // 1 MB streaming buffers — much faster on flash storage than the old 32 KB.
            decoded.use { stream ->
                java.io.BufferedOutputStream(FileOutputStream(imageFile), 1024 * 1024).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    var lastPercent = 5
                    var lastReportedMb = -1L
                    var read = stream.read(buffer)
                    while (read >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (read > 0) {
                            if (total + read > limitBytes) {
                                throw IllegalStateException(imageSizeLimitMessage(allowLargeImages))
                            }
                            output.write(buffer, 0, read)
                            total += read
                        }
                        if (knownTotal) {
                            val pct = (5L + total * 50L / targetBytes).toInt().coerceIn(5, 55)
                            if (pct > lastPercent) {
                                onProgress(
                                    pct,
                                    transferLine(verb, total, targetBytes, SystemClock.elapsedRealtime() - startedAt)
                                )
                                lastPercent = pct
                            }
                        } else {
                            val wholeMb = total / (16L * 1024L * 1024L)
                            if (wholeMb != lastReportedMb) {
                                lastReportedMb = wholeMb
                                onProgress(
                                    -1,
                                    transferLine("$verb (total unknown)", total, -1L, SystemClock.elapsedRealtime() - startedAt)
                                )
                            }
                        }
                        read = stream.read(buffer)
                    }
                }
            }
        }
        imageFile.setReadable(true, false)
        onProgress(58, "Image ready: ${formatBytes(imageFile.length())}")
        return imageFile
    } catch (t: Throwable) {
        // Never leave a half-written multi-GB file behind after a failure.
        runCatching { if (imageFile.exists()) imageFile.delete() }
        throw t
    }
}

// Keep every staged image alive until a reboot — DSU install reads it only
// after VerificationActivity, so deleting early breaks the copy into the DSU slot.
private fun deleteOnRebootStagedFile(file: File) = runCatching {
    file.deleteOnExit()
    Log.i("bDSU", "Staged for DSU: ${file.absolutePath} (${formatBytes(file.length())})")
}

/** Launch the documented DSU VerificationActivity against an existing .img.gz
 *  (normally public Download; /data/local/tmp is the fallback). The .gz stays on
 *  disk for reboot — DSU reads it only after the user confirms in the system dialog. */
@Suppress("DEPRECATION")
private suspend fun launchDsuFromGzFile(gzFile: File, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit, dsuSlot: String = newDsuSlot()): Pair<Boolean, String> {
    if (!Shell.ready()) return false to "Shizuku permission is required before DSU installation"
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val fileUri = "file://${gzFile.absolutePath}"
    // Never reuse the OEM's default "dsu" slot. A failed install can leave an
    // unreadable /metadata/gsi/dsu/dsu/lp_metadata file behind; gsid then keeps
    // failing every later attempt with "Invalid partition metadata header table
    // size". A fresh slot gets a fresh metadata directory and avoids that trap.
    val command = "set -eu; test -s ${quote(gzFile.absolutePath)}; chmod 644 ${quote(gzFile.absolutePath)} 2>/dev/null || true; am start-activity --user 0 -n com.android.dynsystem/com.android.dynsystem.VerificationActivity -a android.os.image.action.START_INSTALL -d ${quote(fileUri)} --el KEY_SYSTEM_SIZE $rawSize $userdataArg --es KEY_DSU_SLOT ${quote(dsuSlot)}"
    val result = Shell.run(command)
    val success = isAmLaunchSuccess(result)
    if (success) { deleteOnRebootStagedFile(gzFile); onProgress(100, "DSU installer started (slot $dsuSlot)") }
    return success to if (success) "DSU installer started via $fileUri (slot $dsuSlot)" else result
}

/** A unique, filesystem-safe DSU slot for one install. The default "dsu" slot
 *  is shared by every DSU app and is exactly where broken OEM metadata tends to
 *  accumulate, so bDSU deliberately installs into its own slot instead. */
private fun newDsuSlot(): String = "bdsu" + System.currentTimeMillis().toString(16)

/** DSU always creates a writable userdata image. Passing the AOSP 2 GiB
 *  default explicitly prevents Samsung/OEM builds from silently substituting a
 *  24 GiB image, which can make partition creation fail on a nearly full disk.
 *  A user-selected 3 GiB+ value still wins. */
private fun dsuUserdataArg(userdataBytes: Long): String {
    val size = if (userdataBytes >= 3L * 1073741824L) userdataBytes else 2L * 1073741824L
    return "--el KEY_USERDATA_SIZE $size"
}

/** Remove dsu-stage-*.img.gz payloads from public Download that are older than six
 *  hours. Recent files may belong to an install the user has not confirmed yet,
 *  so they are left alone. The shell path works under scoped storage; the app
 *  side fallback handles devices where the app can still list Download. */
@Suppress("DEPRECATION")
private suspend fun cleanupStalePublicGz(publicDownload: File) {
    runCatching {
        val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
        Shell.run(
            "find ${quote(publicDownload.absolutePath)} -maxdepth 1 -name 'dsu-stage-*.img.gz' -mmin +360 -delete 2>/dev/null || true"
        )
    }
    runCatching {
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        publicDownload.listFiles()
            ?.filter { it.isFile && it.name.startsWith("dsu-stage-") && it.name.endsWith(".img.gz") && it.lastModified() in 1L until cutoff }
            ?.forEach { runCatching { it.delete() } }
    }
}

/** gzip stores the uncompressed size in the last 4 bytes (ISIZE, little endian,
 *  mod 4 GiB). Values below 4 GiB are exact and are also used to enforce the
 *  user's image-size setting; sizes above 4 GiB wrap around, so the generic
 *  streaming pipeline re-checks the real decompressed byte count. Returns -1 if
 *  the underlying file cannot be sought (e.g. a remote document provider). */
private fun readGzipTrailerUncompressedSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use outer@{ pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            val size = ch.size()
            if (size < 18) return@outer -1L
            ch.position(size - 4)
            val tail = java.nio.ByteBuffer.allocate(4)
            while (tail.hasRemaining() && ch.read(tail) > 0) { }
            tail.flip()
            if (tail.remaining() < 4) -1L
            else (tail.order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
        }
    } ?: -1L
}.getOrDefault(-1L)

/** Read the exact uncompressed size from a content:// .xz document without
 *  decompressing it. Returns -1 for providers that cannot seek. */
private fun readXzUncompressedSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val channel = fis.channel
            readXzUncompressedSize(channel, channel.size())
        }
    } ?: -1L
}.getOrDefault(-1L)

/** Stream-copy an already-gzipped local pick into public Download — no decompress/
 *  recompress round-trip, single 1 MB-chunk pass with progress. The 4 GB guard is
 *  enforced on the reported uncompressed size in [installLocalDsu] before this
 *  function is called. */
@Suppress("DEPRECATION")
private suspend fun copyUriToPublicGz(context: Context, uri: Uri, stagedName: String, onProgress: (Int, String) -> Unit): File = withContext(Dispatchers.IO) {
    val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cleanupStalePublicGz(publicDownload)
    val gzName = ("dsu-stage-" + normalizedImageName(stagedName).removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    val srcSize = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
    val startedAt = SystemClock.elapsedRealtime()
    try {
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
                    if (srcSize > 0L) {
                        onProgress(
                            10 + (total * 45 / srcSize).toInt().coerceIn(0, 45),
                            transferLine(
                                "Copying gzip image into Download (fast mode)",
                                total,
                                srcSize,
                                SystemClock.elapsedRealtime() - startedAt
                            )
                        )
                    }
                }
            }
        }
    } catch (t: Throwable) {
        runCatching { if (gzFile.exists()) gzFile.delete() }
        throw t
    }
    gzFile
}

/** GZIP with a 1 MB buffer and the fastest deflate level. DSU accepts any valid
 *  gzip stream, and level 1 is several times faster than the default level 6 on
 *  the multi-GB images this app handles. */
private class FastGzipOutputStream(out: java.io.OutputStream) :
    java.util.zip.GZIPOutputStream(out, 1024 * 1024) {
    init {
        def.setLevel(java.util.zip.Deflater.BEST_SPEED)
    }
}

/** Single-pass xz → gzip into public Download: never materializes the multi-GB raw
 *  .img on disk. Returns the gz file plus the counted uncompressed size. The same
 *  user-controlled size guard as [prepareImageFile] applies here, otherwise this
 *  fast path would silently bypass the 4 GB setting.
 *
 *  [expectedRawBytes] comes from the XZ index when available, which makes the
 *  gauge exact. If it is -1 the caller gets indeterminate progress instead of a
 *  fabricated percentage. */
@Suppress("DEPRECATION")
private suspend fun stageDownloadAsGz(srcFile: File, expectedRawBytes: Long, allowLargeImages: Boolean, onProgress: (Int, String) -> Unit): Pair<File, Long> = withContext(Dispatchers.IO) {
    val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!publicDownload.exists() && !publicDownload.mkdirs()) {
        throw IllegalStateException("Public Download directory is unavailable")
    }
    cleanupStalePublicGz(publicDownload)
    val gzName = ("dsu-stage-" + normalizedImageName(srcFile.name).removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    val limitBytes = maxImageBytes(allowLargeImages)
    if (expectedRawBytes > limitBytes) throw IllegalStateException(imageSizeLimitMessage(allowLargeImages))
    val exact = expectedRawBytes > 0L
    val targetBytes = if (exact) expectedRawBytes else (srcFile.length() * 3L).coerceIn(1L, limitBytes)
    // gzip level 1 of an ext4 image is usually 60-80% of the raw size. Reserve
    // 75% + 128 MB so a full disk fails early with a clear message instead of
    // dying half-way through a multi-GB write.
    val requiredBytes = targetBytes / 4L * 3L + 134217728L
    val freeBytes = runCatching { StatFs(publicDownload.path).availableBytes }.getOrNull()
    if (freeBytes != null && freeBytes < requiredBytes) {
        throw IllegalStateException(
            "Not enough space in Download for the DSU package: need ~${formatBytes(requiredBytes)} but only ${formatBytes(freeBytes)} is free"
        )
    }
    val startedAt = SystemClock.elapsedRealtime()
    onProgress(
        if (exact) 5 else -1,
        transferLine(
            if (exact) "Preparing DSU package (fast mode)" else "Preparing DSU package (fast mode, estimated)",
            0L,
            targetBytes,
            0L,
            estimated = !exact
        )
    )
    var total = 0L
    try {
        org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(FileInputStream(srcFile), 1024 * 1024), XZ_MAX_MEMORY_BYTES).use { inp ->
            FastGzipOutputStream(java.io.BufferedOutputStream(FileOutputStream(gzFile), 1024 * 1024)).use { out ->
                val buffer = ByteArray(1024 * 1024)
                var lastPercent = 5
                var lastReportedMb = -1L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = inp.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        if (total + read > limitBytes) throw IllegalStateException(imageSizeLimitMessage(allowLargeImages))
                        out.write(buffer, 0, read)
                        total += read
                    }
                    if (exact) {
                        val pct = (5L + total * 85L / targetBytes).toInt().coerceIn(5, 90)
                        if (pct > lastPercent) {
                            lastPercent = pct
                            onProgress(
                                pct,
                                transferLine(
                                    "Repackaging for DSU (fast mode)",
                                    total,
                                    targetBytes,
                                    SystemClock.elapsedRealtime() - startedAt
                                )
                            )
                        }
                    } else {
                        val wholeMb = total / (32L * 1024L * 1024L)
                        if (wholeMb != lastReportedMb) {
                            lastReportedMb = wholeMb
                            onProgress(
                                -1,
                                transferLine(
                                    "Repackaging for DSU (fast mode, estimated)",
                                    total,
                                    targetBytes,
                                    SystemClock.elapsedRealtime() - startedAt,
                                    estimated = true
                                )
                            )
                        }
                    }
                }
            }
        }
        if (total <= 0L) throw IllegalStateException("Image decompression produced no data")
        if (gzFile.length() <= 0L) throw IllegalStateException("DSU package could not be written")
    } catch (t: Throwable) {
        runCatching { if (gzFile.exists()) gzFile.delete() }
        throw t
    }
    onProgress(92, "DSU package ready: ${formatBytes(gzFile.length())} (${formatBytes(total)} image)")
    gzFile to total
}

/**
 * AOSP-documented DSU flow (https://source.android.com/docs/core/ota/dynamic-system-updates):
 * gzip the staged image into the user-visible Download directory and launch the
 * explicit VerificationActivity component. If the system DSU cannot read that
 * location (observed on some OEM builds with scoped storage), retry from the
 * official /data/local/tmp path. Returns success=false without touching the
 * staged file when anything is missing so the caller can fall back to legacy.
 */
@Suppress("DEPRECATION")
private suspend fun launchDsuDocumentedFlow(imageFile: File, stagedName: String, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit, dsuSlot: String = newDsuSlot()): Pair<Boolean, String> {
    if (!Shell.ready()) return false to "Shizuku permission is required before DSU installation"
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val publicDownload = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        ?: return false to "Public Download directory is unavailable"
    val free = runCatching { StatFs(publicDownload.path).availableBytes }.getOrNull() ?: 0L
    // gzip -1 output is typically 60-80% of the raw image; reserve 75% + 64 MB.
    val required = rawSize / 4L * 3L + 67108864L
    if (free < required) return false to "Not enough free space in Download for the compressed image (need ~${formatBytes(required)}, have ${formatBytes(free)})"
    cleanupStalePublicGz(publicDownload)
    cleanupStaleTmpGz()
    val gzName = ("dsu-stage-" + stagedName.removeSuffix(".img") + ".img.gz").replace("/", "_")
    val gzFile = File(publicDownload, gzName)
    onProgress(62, "Compressing image for DSU installer (fast mode)…")
    val compressed = compressToPublicGzWithProgress(imageFile, gzFile, rawSize, onProgress)
    if (!compressed.first) {
        runCatching { Shell.run("rm -f ${quote(gzFile.absolutePath)}") }
        onProgress(78, "Documented DSU flow failed; trying legacy launch")
        return false to compressed.second
    }
    val launched = launchDsuFromGzFile(gzFile, rawSize, userdataArg, onProgress, dsuSlot)
    if (launched.first) return launched

    // Second documented location: /data/local/tmp is the path used by the
    // upstream AOSP instructions. Some OEM DSU services can only read here.
    onProgress(94, "Download location was rejected; retrying DSU from /data/local/tmp…")
    var fallbackDetail = ""
    val tmpGz = copyGzToLocalTmp(gzFile)
    if (tmpGz != null) {
        val tmpLaunched = launchDsuFromGzFile(tmpGz, rawSize, userdataArg, onProgress, dsuSlot)
        if (tmpLaunched.first) {
            // The DSU service now owns the /data/local/tmp copy; the public
            // Download copy is no longer needed and only wastes space.
            runCatching { Shell.run("rm -f ${quote(gzFile.absolutePath)}") }
            return tmpLaunched
        }
        fallbackDetail = " | /data/local/tmp: ${tmpLaunched.second}"
        runCatching { Shell.run("rm -f ${quote(tmpGz.absolutePath)}") }
    }
    runCatching { Shell.run("rm -f ${quote(gzFile.absolutePath)}") }
    onProgress(78, "Documented DSU flow failed; trying legacy launch")
    return false to (launched.second + fallbackDetail)
}

/** Copy the finished .img.gz into /data/local/tmp for OEMs whose DSU service
 *  cannot read public Download. Returns null when the shell copy fails. */
private suspend fun copyGzToLocalTmp(gzFile: File): File? = withContext(Dispatchers.IO) {
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val tmp = File("/data/local/tmp/dsu-stage-${System.nanoTime()}.img.gz")
    val result = Shell.run(
        "set -eu; test -s ${quote(gzFile.absolutePath)}; cp ${quote(gzFile.absolutePath)} ${quote(tmp.absolutePath)}; " +
            "chmod 644 ${quote(tmp.absolutePath)}; test -s ${quote(tmp.absolutePath)}"
    )
    if (result.contains("[exit=0]")) tmp else {
        Log.w("bDSU", "Could not copy DSU package to /data/local/tmp: $result")
        null
    }
}

/** Remove old dsu-stage-*.img.gz files from /data/local/tmp (never today's payload). */
private suspend fun cleanupStaleTmpGz() {
    runCatching {
        Shell.run("find /data/local/tmp -maxdepth 1 -name 'dsu-stage-*.img.gz' -mmin +360 -delete 2>/dev/null || true")
    }
}

/**
 * Run the native `gzip -1` compression in a Shizuku shell and poll the output
 * file size while it works. Android has no `pv`, but `stat` is enough to show
 * live MB/s, remaining bytes (estimated from the usual 75% ratio) and an ETA.
 * Launching gzip in the background also lets cancellation kill it instead of
 * leaving a half-written .img.gz behind.
 */
@Suppress("UNREACHABLE_CODE")
private suspend fun compressToPublicGzWithProgress(
    imageFile: File,
    gzFile: File,
    rawSize: Long,
    onProgress: (Int, String) -> Unit
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val token = "bdsu-gzip-${System.nanoTime()}"
    val exitFile = "/data/local/tmp/$token.exit"
    val logFile = "/data/local/tmp/$token.log"
    val startedAt = SystemClock.elapsedRealtime()
    val deadline = startedAt + 45L * 60L * 1000L
    val startCommand = "set -eu; SRC=${quote(imageFile.absolutePath)}; DST=${quote(gzFile.absolutePath)}; test -s \"\$SRC\"; " +
        "command -v gzip >/dev/null 2>&1 || exit 10; rm -f ${quote(exitFile)} ${quote(logFile)}; " +
        // The trailing redirection is essential: without it the background
        // subshell inherits Shell.run's stdout pipe and keeps it open until
        // gzip finishes, so the "background" start call would block.
        "( gzip -1 -c \"\$SRC\" > \"\$DST\" 2>${quote(logFile)}; echo \$? > ${quote(exitFile)} ) >/dev/null 2>&1 & echo \$!"
    val started = Shell.run(startCommand)
    if (started.contains("[exit=10]")) return@withContext false to "gzip command not found on this device"
    val pid = Regex("\\[exit=0\\]\\s*(\\d+)").find(started)?.groupValues?.get(1)?.toLongOrNull()
    if (pid == null) return@withContext false to "gzip could not start: $started"
    // gzip -1 output is usually 60-80% of the raw image. The readout is marked
    // with "~" and replaced by the real size when the process finishes.
    val estimatedTotal = (rawSize / 4L * 3L).coerceAtLeast(1L)
    var keepOutput = false
    try {
        while (true) {
            delay(1000L)
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) {
                runCatching { Shell.run("kill $pid 2>/dev/null") }
                return@withContext false to "gzip timed out after 45 minutes"
            }
            val sizeResult = Shell.run("stat -c %s ${quote(gzFile.absolutePath)} 2>/dev/null || echo 0")
            val outSize = Regex("\\[exit=0\\]\\s*(\\d+)").find(sizeResult)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val pct = (62 + (outSize * 30L / estimatedTotal).toInt().coerceIn(0, 30))
            onProgress(
                pct,
                transferLine(
                    "Compressing image for DSU installer (fast mode)",
                    outSize,
                    estimatedTotal,
                    (now - startedAt).coerceAtLeast(1L),
                    estimated = true
                )
            )
            val exitResult = Shell.run("cat ${quote(exitFile)} 2>/dev/null || true")
            val exitCode = Regex("\\[exit=0\\]\\s*(\\d+)").find(exitResult)?.groupValues?.get(1)?.toIntOrNull()
            if (exitCode != null) {
                // The app may not be allowed to stat a file created by the shell
                // in public Download (scoped storage), so use the size obtained
                // through the shell, with one final stat as a fallback.
                val finalSize = if (outSize > 0L) outSize else {
                    val stat = Shell.run("stat -c %s ${quote(gzFile.absolutePath)} 2>/dev/null || echo 0")
                    Regex("\\[exit=0\\]\\s*(\\d+)").find(stat)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                if (exitCode == 0 && finalSize > 0L) {
                    keepOutput = true
                    onProgress(92, "DSU package ready: ${formatBytes(finalSize)} (${formatBytes(rawSize)} image)")
                    return@withContext true to "ok"
                }
                val log = Shell.run("cat ${quote(logFile)} 2>/dev/null || true")
                return@withContext false to "gzip exit=$exitCode ${log.take(400)}"
            }
            val alive = Shell.run("kill -0 $pid 2>/dev/null && echo alive || echo dead")
            if (alive.contains("dead")) {
                delay(250L)
                val finalExit = Shell.run("cat ${quote(exitFile)} 2>/dev/null || true")
                val code = Regex("\\[exit=0\\]\\s*(\\d+)").find(finalExit)?.groupValues?.get(1)?.toIntOrNull()
                if (code == 0 && outSize > 0L) {
                    keepOutput = true
                    onProgress(92, "DSU package ready: ${formatBytes(outSize)} (${formatBytes(rawSize)} image)")
                    return@withContext true to "ok"
                }
                if (code != null) {
                    val log = Shell.run("cat ${quote(logFile)} 2>/dev/null || true")
                    return@withContext false to "gzip exit=$code ${log.take(400)}"
                }
                return@withContext false to "gzip process ended without an exit code"
            }
        }
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            runCatching { Shell.run("kill $pid 2>/dev/null") }
            if (!keepOutput) runCatching { Shell.run("rm -f ${quote(gzFile.absolutePath)}") }
        }
        throw e
    } finally {
        // A cancelled coroutine cannot call Shell.run directly, so cleanup uses
        // NonCancellable. On success keepOutput is true and only the scratch
        // files are removed; on failure/cancel the partial .img.gz goes too.
        withContext(NonCancellable) {
            val removeGz = if (keepOutput) "" else " ${quote(gzFile.absolutePath)}"
            runCatching { Shell.run("rm -f ${quote(exitFile)} ${quote(logFile)}$removeGz") }
        }
    }
    return@withContext false to "gzip polling stopped unexpectedly"
}

private suspend fun launchDsuFromStaging(imageFile: File, stagedName: String, rawSize: Long, userdataArg: String, onProgress: (Int, String) -> Unit): Pair<Boolean, String> {
    val dsuSlot = newDsuSlot()
    val documented = launchDsuDocumentedFlow(imageFile, stagedName, rawSize, userdataArg, onProgress, dsuSlot)
    if (documented.first) return documented
    val stageDir = imageFile.parentFile ?: return false to "Staging directory is missing"
    if (!stageDir.exists() && !stageDir.mkdirs()) return false to "Cannot create staging directory: ${stageDir.absolutePath}"
    val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    val imgPath = imageFile.absolutePath
    val fileUri = Uri.fromFile(imageFile).toString()
    val command = "set -eu; DIR=${quote(stageDir.absolutePath)}; mkdir -p \"\$DIR\"; IMG=${quote(imgPath)}; test -s \"\$IMG\"; am start-activity --user 0 -a android.os.image.action.START_INSTALL -d ${quote(fileUri)} --el KEY_SYSTEM_SIZE $rawSize $userdataArg --es KEY_DSU_SLOT ${quote(dsuSlot)}"
    onProgress(78, "Documented DSU flow unavailable; trying legacy DSU launch")
    val result = Shell.run(command)
    val success = isAmLaunchSuccess(result)
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

/**
 * `am start-activity` sometimes exits 0 while printing an error (for example an
 * unresolved component or a security exception). Treat those strings as a
 * failure so the caller can try /data/local/tmp and the legacy intent instead
 * of telling the user the installer started when it did not.
 */
private fun isAmLaunchSuccess(result: String): Boolean {
    if (!result.contains("[exit=0]")) return false
    val lower = result.lowercase(Locale.US)
    return !lower.contains("error:") &&
        !lower.contains("exception") &&
        !lower.contains("unable to resolve intent") &&
        !lower.contains("does not exist")
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
private suspend fun computeDigest(context: Context, fileUri: Uri, algorithm: String, onProgress: ((Int) -> Unit)? = null): String? = try {
    val total = runCatching {
        context.contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize }
    }.getOrNull() ?: -1L
    context.contentResolver.openInputStream(fileUri)?.use { stream ->
        val digest = java.security.MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(1024 * 1024)
        var done = 0L
        var lastReported = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                done += read
            }
            if (total > 0L) {
                val pct = (done * 100L / total).toInt().coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress?.invoke(pct)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e("bDSU", "$algorithm digest failed", e)
    null
}

private suspend fun verifyDigest(context: Context, fileUri: Uri, algorithm: String, expected: String, onProgress: ((Int) -> Unit)? = null): Boolean =
    computeDigest(context, fileUri, algorithm, onProgress)?.equals(expected, ignoreCase = true) == true

private suspend fun verifySha256(context: Context, fileUri: Uri, expectedSha256: String, onProgress: ((Int) -> Unit)? = null): Boolean =
    verifyDigest(context, fileUri, "SHA-256", expectedSha256, onProgress)

/** API 29-safe replacement for InputStream.readNBytes (added in API 33). */
private fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}

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
    // Best-effort and time-boxed: the catalog SHA-256 already passed, so a slow
    // or unreachable publisher host must never delay DSU preparation.
    val deadline = System.currentTimeMillis() + 4500L
    for (candidate in candidates) {
        if (System.currentTimeMillis() >= deadline) break
        runCatching {
            val conn = java.net.URL(candidate).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "bDSU/1.3")
            try {
                if (conn.responseCode != 200) return@runCatching
                // Read at most 64 KB — checksum files never get bigger than that.
                val raw = conn.inputStream.use { it.readAtMost(64 * 1024) }.decodeToString()
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

private fun getLocalRomSha256(context: Context, uri: Uri): String =
    catalogMatchForName(queryDisplayName(context, uri))?.sha256 ?: ""

private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else "local.img"
    } ?: "local.img"
}.getOrDefault("local.img")

private fun queryDocumentName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

private fun queryTreeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    queryDocumentName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
}.getOrNull()

private fun mimeTypeForRomName(name: String): String = when {
    name.endsWith(".xz", true) -> "application/x-xz"
    name.endsWith(".gz", true) -> "application/gzip"
    else -> "application/octet-stream"
}

/**
 * Copies a completed DownloadManager file into the SAF folder the user chose.
 * DownloadManager cannot write to a content:// tree directly, so the transfer
 * lands in app storage first and is moved here after SHA-256 verification.
 */
private suspend fun exportToDocumentTree(
    context: Context,
    source: Uri,
    treeUri: Uri,
    displayName: String,
    expectedBytes: Long,
    onProgress: (Long, Long) -> Unit
): Pair<Uri, String> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    // Idempotent export: if a previous attempt finished the copy but the app was
    // killed before clearing the DownloadManager record, reuse the completed
    // document instead of creating a duplicate (1) file on the next launch.
    findExistingExport(context, treeUri, displayName, expectedBytes)?.let { existing ->
        return@withContext existing to (queryDocumentName(context, existing) ?: displayName)
    }
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )

    suspend fun copyInto(destUri: Uri) {
        val input = resolver.openInputStream(source) ?: error("Cannot open the downloaded ROM")
        input.use { inStream ->
            val output = resolver.openOutputStream(destUri, "w")
                ?: error("Cannot write to the selected folder")
            output.use { out ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = inStream.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        copied += read
                    }
                    onProgress(copied, expectedBytes)
                }
                out.flush()
                if (expectedBytes > 0L && copied != expectedBytes) {
                    error("Copied ${formatBytes(copied)} of ${formatBytes(expectedBytes)}")
                }
            }
        }
    }

    // Copy under a *.part name first. If the app is killed mid-copy, the next
    // launch deletes the clearly marked partial document instead of leaving a
    // half-written ROM that looks complete.
    val partName = "$displayName.part"
    val partUri = DocumentsContract.createDocument(
        resolver,
        parent,
        "application/octet-stream",
        partName
    ) ?: error("The selected folder did not allow creating a file")
    try {
        copyInto(partUri)
        val renamed = runCatching {
            DocumentsContract.renameDocument(resolver, partUri, displayName)
        }.getOrNull()
        if (renamed != null) {
            return@withContext renamed to (queryDocumentName(context, renamed) ?: displayName)
        }

        // Some cloud providers expose createDocument but not renameDocument.
        // Delete the part file and make one final copy; this is rare and always
        // leaves either a complete file or nothing behind.
        runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
        val finalUri = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeTypeForRomName(displayName),
            displayName
        ) ?: error("The selected folder did not allow creating the final file")
        try {
            copyInto(finalUri)
            finalUri to (queryDocumentName(context, finalUri) ?: displayName)
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, finalUri) }
            throw t
        }
    } catch (t: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
        throw t
    }
}

/**
 * Look for a complete file with the exact final export name. Size is the cheap
 * guard against reusing a stale duplicate; the bDSU SHA-256 check at install
 * time remains the authoritative integrity check.
 */
private fun findExistingExport(context: Context, treeUri: Uri, displayName: String, expectedBytes: Long): Uri? {
    if (expectedBytes <= 0L) return null
    return runCatching {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                if (!name.equals(displayName, ignoreCase = true)) continue
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                if (size != expectedBytes) continue
                val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                if (id != null) return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
            null
        }
    }.getOrNull()
}

/** Remove partial exports left in the chosen folder by a killed process. */
private fun cleanupPartialExports(context: Context, treeUri: Uri) {
    runCatching {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val partialIds = mutableListOf<String>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                if (name.contains("bdsu", ignoreCase = true) && name.contains(".part", ignoreCase = true)) {
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    if (id != null) partialIds += id
                }
            }
        }
        partialIds.forEach { id ->
            runCatching {
                DocumentsContract.deleteDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                )
            }
        }
    }
}

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

/** Split a ROM name into base and compound extension (.img.xz/.img.gz/.img). */
private fun splitRomExtension(name: String): Pair<String, String> = when {
    name.endsWith(".img.xz", true) -> name.dropLast(7) to ".img.xz"
    name.endsWith(".img.gz", true) -> name.dropLast(7) to ".img.gz"
    name.endsWith(".img", true) -> name.dropLast(4) to ".img"
    else -> {
        val dot = name.lastIndexOf('.')
        if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }
}

/**
 * File name used for a ROM exported to the user's SAF folder. It always carries
 * the bDSU marker and a short catalog SHA-256 tag, e.g.
 * bDSU-PixelOS-..._bdsu_d159c20669572276.img.xz. The tag lets the app identify
 * the exact catalog build again when the file is picked locally, even if the
 * user later renames the family/version part of the name.
 */
private fun exportFileName(rom: RomOption): String {
    val (base, ext) = splitRomExtension(downloadFileName(rom))
    val cleanBase = sanitizePathComponent(base).ifBlank { "rom" }
    val tag = if (rom.hasSha256) "_bdsu_" + rom.sha256.take(16).lowercase(Locale.US) else ""
    return sanitizeFileName("bDSU-$cleanBase$tag$ext")
}

private fun compactMatchText(value: String): String =
    value.lowercase(Locale.US).replace("[^a-z0-9]+".toRegex(), "")

private fun catalogTagFromName(name: String): String? =
    Regex("(?i)(?:^|[^a-z0-9])bdsu[^a-z0-9]*([0-9a-f]{12,64})(?:[^a-z0-9]|$)")
        .find(name)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)

/**
 * Identify the catalog build a local ROM name refers to. This is deliberately
 * stricter than a substring search: a short SHA tag wins, then the exact
 * original file name, then a unique family/version/date score. Ambiguous names
 * return null so a bDSU-tagged file can never be verified against the wrong ROM.
 */
private fun catalogMatchForName(name: String): RomOption? {
    val raw = name.substringAfterLast('/').substringAfterLast('\\')
    catalogTagFromName(raw)?.let { tag ->
        val byTag = romCatalog.filter { it.hasSha256 && it.sha256.lowercase(Locale.US).startsWith(tag) }
        if (byTag.size == 1) return byTag.first()
    }

    val compactName = compactMatchText(raw)
    if (compactName.isBlank()) return null

    val exact = romCatalog.filter { rom ->
        val base = compactMatchText(splitRomExtension(downloadFileName(rom)).first)
        base.isNotBlank() && compactName.contains(base)
    }
    if (exact.size == 1) return exact.first()

    val scored = romCatalog.map { rom ->
        val family = compactMatchText(rom.family)
        val arch = compactMatchText(rom.architecture)
        val fileName = compactMatchText(downloadFileName(rom))
        var score = 0
        if (family.isNotBlank() && compactName.contains(family)) score += 20
        // "VoltageOS" is often shortened to "Voltage", "LineageOS" to
        // "Lineage", and so on. A prefix match is weaker than the full family
        // but still enough when combined with a version/variant token.
        val familyPrefix = family.removeSuffix("os")
        if (familyPrefix.length >= 4 && familyPrefix != family && compactName.contains(familyPrefix)) score += 15
        if (arch.isNotBlank() && compactName.contains(arch)) score += 8
        if (fileName.isNotBlank() && (compactName.contains(fileName) || fileName.contains(compactName))) score += 30
        rom.version.split(Regex("[^A-Za-z0-9.]+")).filter { it.isNotBlank() }.forEach { token ->
            val compactToken = compactMatchText(token)
            if (compactToken.length >= 2 && compactName.contains(compactToken)) score += 3
        }
        rom to score
    }.filter { it.second > 0 }.sortedByDescending { it.second }

    val best = scored.firstOrNull() ?: return null
    if (best.second < 15) return null
    if (scored.size > 1 && scored[1].second == best.second) return null
    return best.first
}

private fun isBdsuTaggedName(name: String): Boolean =
    compactMatchText(name).contains("bdsu")

private fun formatBytes(v: Long): String = if (v <= 0) "--" else if (v >= 1024L * 1024 * 1024) String.format(Locale.US, "%.2f GB", v / 1073741824.0) else String.format(Locale.US, "%.0f MB", v / 1048576.0)
private fun formatProgressBytes(v: Long): String = if (v <= 0L) "0 MB" else formatBytes(v)
private fun formatSpeed(v: Long): String = if (v <= 0) "-- MB/s" else String.format(Locale.US, "%.1f MB/s", v / 1048576.0)
private fun downloadProgressText(rom: RomOption?, percent: Int, done: Long, total: Long, speed: Long, stalled: Boolean): String {
    val name = listOfNotNull(rom?.family, rom?.version).joinToString(" ").ifBlank { "ROM" }
    val pct = if (percent in 0..100) "$percent% • " else ""
    val bytes = if (total > 0L) "${formatProgressBytes(done)} / ${formatBytes(total)}" else "${formatProgressBytes(done)} downloaded"
    val left = if (total > done) " • ${formatProgressBytes(total - done)} left" else ""
    val remaining = if (total > done && speed > 0L) " • ETA ${eta(done, total, speed)}" else ""
    return "Downloading $name • $pct$bytes • ${formatSpeed(speed)}$left$remaining" + if (stalled) " • stalled" else ""
}
private fun eta(done: Long, total: Long, speed: Long): String {
    if (speed <= 0L || total <= done) return "--"
    val seconds = (total - done) / speed
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

/**
 * One-line transfer readout used by every long byte-moving phase:
 * done / total • live speed • remaining bytes • ETA. [estimated] adds "~" for
 * phases where the total is a heuristic (e.g. gzip output size).
 */
private fun transferLine(label: String, done: Long, total: Long, elapsedMs: Long, estimated: Boolean = false): String {
    val safeDone = done.coerceAtLeast(0L)
    val safeTotal = total.coerceAtLeast(0L)
    val speed = if (elapsedMs > 0L) safeDone * 1000L / elapsedMs else 0L
    val marker = if (estimated) "~" else ""
    val totalText = if (safeTotal > 0L) "$marker${formatBytes(safeTotal)}" else "--"
    val leftText = if (safeTotal > 0L) "$marker${formatProgressBytes((safeTotal - safeDone).coerceAtLeast(0L))}" else "--"
    val etaText = if (safeTotal > safeDone && speed > 0L) eta(safeDone, safeTotal, speed) else "--"
    return "$label: ${formatProgressBytes(safeDone)} / $totalText • ${formatSpeed(speed)} • $leftText left • ETA $etaText"
}

package com.vortex.emulator.emulation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads standalone emulator APKs from GitHub releases or direct URLs,
 * then triggers the system package installer. Bypasses the Play Store entirely.
 *
 * Strategy per emulator:
 *  - GitHub releases API (latest or all releases scan) for projects that
 *    publish .apk assets
 *  - Direct URL fallback when available
 *  - Browser fallback as last resort
 */
@Singleton
class StandaloneDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StandaloneDownloader"
        private const val GITHUB_API = "https://api.github.com/repos"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 300_000 // 5 min for large APKs
    }

    data class ApkSource(
        /** GitHub owner (null if not on GitHub) */
        val githubOwner: String? = null,
        /** GitHub repo (null if not on GitHub) */
        val githubRepo: String? = null,
        /** Regex to match APK filename in release assets */
        val apkPattern: Regex = Regex(".*\\.apk$"),
        /** If true, scan all releases (not just /latest) — for repos without a "latest" tag */
        val scanAllReleases: Boolean = false,
        /** Direct APK download URL (bypasses GitHub API if set) */
        val directUrl: String? = null,
        /** Package name of the target app */
        val targetPackage: String,
        /** URL to open in browser as last resort */
        val fallbackUrl: String
    )

    /**
     * Real download sources for standalone emulators.
     *
     * Each entry uses REAL GitHub repositories that publish APK release assets,
     * or direct download URLs. Falls back to opening the download page in a browser.
     */
    private val sources: Map<String, ApkSource> = mapOf(
        // ── PPSSPP — official repo publishes APK builds ──
        "ppsspp_standalone" to ApkSource(
            githubOwner = "hrydgard",
            githubRepo = "ppsspp",
            apkPattern = Regex("(?i)^PPSSPP[0-9].*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.PPSSPP_FREE,
            fallbackUrl = "https://www.ppsspp.org/download"
        ),
        // ── Dolphin — official repo, APKs on website ──
        "dolphin_standalone" to ApkSource(
            githubOwner = "dolphin-emu",
            githubRepo = "dolphin",
            apkPattern = Regex("(?i)dolphin.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.DOLPHIN_EMU,
            fallbackUrl = "https://dolphin-emu.org/download/"
        ),
        // ── Lime3DS — active fork of Citra, publishes APKs ──
        "citra_standalone" to ApkSource(
            githubOwner = "Lime3DS",
            githubRepo = "Lime3DS",
            apkPattern = Regex("(?i)lime3ds.*android.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.LIME3DS,
            fallbackUrl = "https://github.com/Lime3DS/Lime3DS/releases"
        ),
        // ── Flycast — publishes APKs on GitHub releases ──
        "flycast_standalone" to ApkSource(
            githubOwner = "flyinghead",
            githubRepo = "flycast",
            apkPattern = Regex("(?i)flycast.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.FLYCAST,
            fallbackUrl = "https://github.com/flyinghead/flycast/releases"
        ),
        // ── DuckStation — official repo, APKs on GitHub releases ──
        "duckstation_standalone" to ApkSource(
            githubOwner = "stenzek",
            githubRepo = "duckstation",
            apkPattern = Regex("(?i)duckstation.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.DUCKSTATION,
            fallbackUrl = "https://github.com/stenzek/duckstation/releases"
        ),
        // ── melonDS Android — publishes APKs on GitHub ──
        "drastic_standalone" to ApkSource(
            githubOwner = "rafaelvcaetano",
            githubRepo = "melonDS-android",
            apkPattern = Regex("(?i)melonds.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.MELONDS_ANDROID,
            fallbackUrl = "https://github.com/rafaelvcaetano/melonDS-android/releases"
        ),
        // ── Mupen64Plus FZ — Play Store only, no GitHub APKs available ──
        "mupen64_standalone" to ApkSource(
            targetPackage = StandaloneLauncher.MUPEN64PLUS_AE,
            fallbackUrl = "https://play.google.com/store/apps/details?id=org.mupen64plusae.v3.fzurita"
        ),
        // ── Yaba Sanshiro — GitHub releases ──
        "yabause_standalone" to ApkSource(
            githubOwner = "devmiyax",
            githubRepo = "yern",
            apkPattern = Regex("(?i).*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.YABA_SANSHIRO,
            fallbackUrl = "https://github.com/devmiyax/yern/releases"
        ),
        // ── NetherSX2 — community fork of AetherSX2, publishes APKs ──
        "aethersx2_standalone" to ApkSource(
            githubOwner = "Starter-Flavor",
            githubRepo = "NetherSX2",
            apkPattern = Regex("(?i)nethersx2.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.NETHERSX2,
            fallbackUrl = "https://github.com/Starter-Flavor/NetherSX2/releases"
        ),
        // ── Lemuroid — all-in-one retro emulator (Play Store + F-Droid) ──
        "lemuroid_standalone" to ApkSource(
            githubOwner = "Swordfish90",
            githubRepo = "Lemuroid",
            apkPattern = Regex("(?i)lemuroid.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.LEMUROID,
            fallbackUrl = "https://play.google.com/store/apps/details?id=com.swordfish.lemuroid"
        ),
        // ── Vita3K — official PS Vita emulator, publishes Android APKs ──
        "vita3k_standalone" to ApkSource(
            githubOwner = "Vita3K",
            githubRepo = "Vita3K-Android",
            apkPattern = Regex("(?i)vita3k.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.VITA3K,
            fallbackUrl = "https://vita3k.org/"
        ),
        // ── Snes9x EX+ — SNES standalone (published on GitHub by Rakashazi) ──
        "snes9x_standalone" to ApkSource(
            githubOwner = "Rakashazi",
            githubRepo = "emu-ex-plus-alpha",
            apkPattern = Regex("(?i)snes9x.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.SNES9X_EX,
            fallbackUrl = "https://github.com/Rakashazi/emu-ex-plus-alpha/releases"
        ),
        // ── mGBA standalone ──
        "mgba_standalone" to ApkSource(
            githubOwner = "mgba-emu",
            githubRepo = "mgba",
            apkPattern = Regex("(?i)mgba.*android.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.MGBA_STANDALONE,
            fallbackUrl = "https://mgba.io/downloads.html"
        ),
        // ── RetroArch — official builds on buildbot, Play Store, and F-Droid ──
        "retroarch_standalone" to ApkSource(
            githubOwner = "libretro",
            githubRepo = "RetroArch",
            apkPattern = Regex("(?i)retroarch.*\\.apk$"),
            scanAllReleases = true,
            targetPackage = StandaloneLauncher.RETROARCH,
            fallbackUrl = "https://play.google.com/store/apps/details?id=com.retroarch"
        )
    )

    private val apkDir: File
        get() = File(context.cacheDir, "standalone_apks").also { it.mkdirs() }

    /** Check if a standalone core has a download source configured. */
    fun hasDownloadSource(libraryName: String): Boolean = sources.containsKey(libraryName)

    /** Get the fallback URL for manual download. */
    fun getFallbackUrl(libraryName: String): String? = sources[libraryName]?.fallbackUrl

    /** Open the fallback download page in the system browser. */
    fun openFallbackInBrowser(libraryName: String) {
        val url = sources[libraryName]?.fallbackUrl ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Download the latest APK and trigger system installer.
     * [onProgress] reports (bytesDownloaded, totalBytes). totalBytes may be -1 if unknown.
     */
    suspend fun downloadAndInstall(
        libraryName: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        val source = sources[libraryName]
            ?: return@withContext DownloadResult.NoSource

        Log.i(TAG, "Attempting download for $libraryName")

        // Try direct URL first if configured
        if (source.directUrl != null) {
            val apkFile = File(apkDir, "${source.targetPackage}.apk")
            if (downloadFile(source.directUrl, apkFile, onProgress)) {
                Log.i(TAG, "Downloaded via direct URL: ${apkFile.length()} bytes")
                withContext(Dispatchers.Main) { installApk(apkFile) }
                return@withContext DownloadResult.Success
            }
        }

        // Try GitHub releases API
        if (source.githubOwner != null && source.githubRepo != null) {
            val apkUrl = if (source.scanAllReleases) {
                getApkFromAllReleases(source)
            } else {
                getLatestApkUrl(source)
            } ?: getLatestApkUrl(source) // fallback to /latest if all-releases scan failed

            if (apkUrl != null) {
                Log.i(TAG, "Found APK URL: $apkUrl")
                val apkFile = File(apkDir, "${source.targetPackage}.apk")
                if (downloadFile(apkUrl, apkFile, onProgress)) {
                    Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
                    withContext(Dispatchers.Main) { installApk(apkFile) }
                    return@withContext DownloadResult.Success
                }
                return@withContext DownloadResult.DownloadFailed(source.fallbackUrl)
            }
        }

        // No GitHub source or no APK found — return fallback
        DownloadResult.NoRelease(source.fallbackUrl)
    }

    fun getCachedInstallIntent(libraryName: String): Intent? {
        val source = sources[libraryName] ?: return null
        val apkFile = File(apkDir, "${source.targetPackage}.apk")
        if (!apkFile.exists() || apkFile.length() == 0L) return null
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ── GitHub API: /releases/latest ──

    private fun getLatestApkUrl(source: ApkSource): String? {
        if (source.githubOwner == null || source.githubRepo == null) return null
        return try {
            val apiUrl = "$GITHUB_API/${source.githubOwner}/${source.githubRepo}/releases/latest"
            val json = fetchJson(apiUrl) ?: return null
            val release = JSONObject(json)
            findApkInAssets(release.getJSONArray("assets"), source.apkPattern)
        } catch (e: Exception) {
            Log.e(TAG, "GitHub /latest error: ${e.message}")
            null
        }
    }

    // ── GitHub API: /releases (scan multiple) ──

    private fun getApkFromAllReleases(source: ApkSource): String? {
        if (source.githubOwner == null || source.githubRepo == null) return null
        return try {
            val apiUrl = "$GITHUB_API/${source.githubOwner}/${source.githubRepo}/releases?per_page=10"
            val json = fetchJson(apiUrl) ?: return null
            val releases = JSONArray(json)
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.getJSONArray("assets")
                val url = findApkInAssets(assets, source.apkPattern)
                if (url != null) return url
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "GitHub /releases error: ${e.message}")
            null
        }
    }

    /** Find best APK match in a release assets array. */
    private fun findApkInAssets(assets: JSONArray, pattern: Regex): String? {
        // First pass: match specific pattern (may filter by architecture)
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (pattern.containsMatchIn(name)) {
                return asset.getString("browser_download_url")
            }
        }
        // Second pass: any .apk
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset.getString("browser_download_url")
            }
        }
        return null
    }

    private fun fetchJson(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "VortexEmulator/1.0")
            if (conn.responseCode != 200) {
                Log.e(TAG, "HTTP ${conn.responseCode} for $url")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            body
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error for $url: ${e.message}")
            null
        }
    }

    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Boolean {
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "VortexEmulator/1.0")

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return false
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
            conn.disconnect()

            if (tmpFile.exists() && tmpFile.length() > 0) {
                target.delete()
                tmpFile.renameTo(target)
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            tmpFile.delete()
            false
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun clearCache() {
        apkDir.listFiles()?.forEach { it.delete() }
    }

    sealed class DownloadResult {
        data object Success : DownloadResult()
        data object NoSource : DownloadResult()
        data class NoRelease(val fallbackUrl: String?) : DownloadResult()
        data class DownloadFailed(val fallbackUrl: String?) : DownloadResult()
    }
}

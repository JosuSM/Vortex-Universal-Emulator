package com.vortex.emulator.emulation

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads pre-built libretro core .so files.
 * Cores are stored in the app's internal files/cores/ directory.
 */
@Singleton
class CoreDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CoreDownloader"
        private const val BUILDBOT_BASE =
            "https://buildbot.libretro.com/nightly/android/latest"
    }

    private val coresDir: File
        get() = File(context.filesDir, "cores").also { it.mkdirs() }

    val systemDir: String
        get() = File(context.filesDir, "system").also { it.mkdirs() }.absolutePath

    val saveDir: String
        get() = File(context.filesDir, "saves").also { it.mkdirs() }.absolutePath

    /**
     * Get the path to a core .so file, downloading it if necessary.
     * [libraryName] is the core library name without prefix/suffix (e.g. "fceumm").
     * Retries up to 2 times on failure. Returns the path or null.
     */
    suspend fun ensureCore(libraryName: String): String? = withContext(Dispatchers.IO) {
        val soName = "${libraryName}_libretro_android.so"
        val localFile = File(coresDir, soName)

        if (localFile.exists() && localFile.length() > 0) {
            Log.i(TAG, "Core already cached: $soName")
            return@withContext localFile.absolutePath
        }

        // Determine ABI
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val abiFolder = when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }

        val zipName = "${soName}.zip"
        val downloadUrl = "$BUILDBOT_BASE/$abiFolder/$zipName"

        // Retry up to 2 times
        repeat(2) { attempt ->
            Log.i(TAG, "Downloading core (attempt ${attempt + 1}): $downloadUrl")
            val result = downloadCore(downloadUrl, localFile)
            if (result != null) return@withContext result
        }

        Log.e(TAG, "All download attempts failed for $libraryName")
        null
    }

    private fun downloadCore(downloadUrl: String, localFile: File): String? {
        val tmpFile = File(localFile.parentFile, "${localFile.name}.tmp")
        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000 // 2 min for large cores
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            conn.inputStream.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(".so")) {
                            FileOutputStream(tmpFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            conn.disconnect()

            if (tmpFile.exists() && tmpFile.length() > 0) {
                tmpFile.renameTo(localFile)
                localFile.setExecutable(true)
                Log.i(TAG, "Core downloaded: ${localFile.absolutePath} (${localFile.length()} bytes)")
                return localFile.absolutePath
            } else {
                Log.e(TAG, "Downloaded file is empty or missing")
                tmpFile.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            tmpFile.delete()
            return null
        }
    }

    /**
     * Check if a core is already downloaded.
     */
    fun isCoreDownloaded(libraryName: String): Boolean {
        val soName = "${libraryName}_libretro_android.so"
        val localFile = File(coresDir, soName)
        return localFile.exists() && localFile.length() > 0
    }

    /**
     * Delete a downloaded core.
     */
    fun deleteCore(libraryName: String) {
        val soName = "${libraryName}_libretro_android.so"
        File(coresDir, soName).delete()
    }
}

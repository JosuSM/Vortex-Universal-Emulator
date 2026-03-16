package com.vortex.emulator.patcher

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class PatchJob(
    val romUri: Uri,
    val patchUri: Uri,
    val outputUri: Uri? = null,
    val createBackup: Boolean = true
)

data class PatchInfo(
    val type: PatchType,
    val fileSize: Long,
    val fileName: String,
    val checksumMd5: String = "",
    val checksumCrc32: String = ""
)

@Singleton
class RomPatcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val backupDir: File
        get() = File(context.filesDir, "rom_backups").also { it.mkdirs() }

    /**
     * Detect patch type from URI by reading magic bytes + file extension.
     */
    suspend fun detectPatchType(patchUri: Uri): PatchInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = getFileName(patchUri)
        val fileSize = resolver.openInputStream(patchUri)?.use { it.available().toLong() } ?: 0L

        val header = ByteArray(8)
        resolver.openInputStream(patchUri)?.use { stream ->
            stream.read(header)
        }

        val detectedType = PatchType.detect(header)
        val type = if (detectedType == PatchType.UNKNOWN) {
            val ext = fileName.substringAfterLast('.', "")
            PatchType.fromExtension(ext)
        } else detectedType

        PatchInfo(
            type = type,
            fileSize = fileSize,
            fileName = fileName
        )
    }

    /**
     * Analyze a ROM file: compute checksums, detect header, get size.
     */
    suspend fun analyzeRom(romUri: Uri): PatchInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = getFileName(romUri)
        val data = resolver.openInputStream(romUri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read ROM file")

        val md5 = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        val crc = crc32(data).toString(16).uppercase().padStart(8, '0')

        PatchInfo(
            type = PatchType.UNKNOWN,
            fileSize = data.size.toLong(),
            fileName = fileName,
            checksumMd5 = md5,
            checksumCrc32 = crc
        )
    }

    /**
     * Apply a single patch to a ROM.
     */
    suspend fun applyPatch(job: PatchJob): PatchResult = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            val romData = resolver.openInputStream(job.romUri)?.use { it.readBytes() }
                ?: return@withContext PatchResult(false, message = "Cannot read ROM file")

            val patchData = resolver.openInputStream(job.patchUri)?.use { it.readBytes() }
                ?: return@withContext PatchResult(false, message = "Cannot read patch file")

            val checksumBefore = crc32(romData)

            // Detect patch type
            val patchType = PatchType.detect(patchData).let { detected ->
                if (detected == PatchType.UNKNOWN) {
                    val ext = getFileName(job.patchUri).substringAfterLast('.', "")
                    PatchType.fromExtension(ext)
                } else detected
            }

            // Create backup
            if (job.createBackup) {
                val backupName = getFileName(job.romUri) + ".bak"
                val backupFile = File(backupDir, backupName)
                backupFile.writeBytes(romData)
            }

            // Apply patch
            val patchedData = when (patchType) {
                PatchType.IPS -> IpsEngine.apply(patchData, romData)
                PatchType.UPS -> UpsEngine.apply(patchData, romData)
                PatchType.BPS -> BpsEngine.apply(patchData, romData)
                PatchType.XDELTA -> XdeltaEngine.apply(patchData, romData)
                PatchType.PPF -> PpfEngine.apply(patchData, romData)
                PatchType.APS -> ApsEngine.apply(patchData, romData)
                PatchType.UNKNOWN -> return@withContext PatchResult(
                    false, message = "Unrecognized patch format"
                )
            }

            val checksumAfter = crc32(patchedData)

            // Write output
            val outputUri = job.outputUri ?: job.romUri
            resolver.openOutputStream(outputUri, "wt")?.use { out ->
                out.write(patchedData)
            } ?: return@withContext PatchResult(
                false, message = "Cannot write output file"
            )

            PatchResult(
                success = true,
                outputSize = patchedData.size.toLong(),
                message = "Patch applied successfully (${patchType.displayName})",
                checksumBefore = checksumBefore,
                checksumAfter = checksumAfter,
                patchType = patchType
            )
        } catch (e: Exception) {
            PatchResult(
                success = false,
                message = "Patch failed: ${e.message}"
            )
        }
    }

    /**
     * Apply multiple patches sequentially (patch stacking).
     */
    suspend fun applyPatches(
        romUri: Uri,
        patchUris: List<Uri>,
        outputUri: Uri?,
        createBackup: Boolean = true,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<PatchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PatchResult>()
        val resolver = context.contentResolver

        var currentRomData = resolver.openInputStream(romUri)?.use { it.readBytes() }
            ?: return@withContext listOf(PatchResult(false, message = "Cannot read ROM file"))

        // Single backup of the original
        if (createBackup) {
            val backupName = getFileName(romUri) + ".bak"
            File(backupDir, backupName).writeBytes(currentRomData)
        }

        for ((index, patchUri) in patchUris.withIndex()) {
            onProgress(index + 1, patchUris.size)
            try {
                val patchData = resolver.openInputStream(patchUri)?.use { it.readBytes() }
                if (patchData == null) {
                    results.add(PatchResult(false, message = "Cannot read patch ${index + 1}"))
                } else {
                    val checksumBefore = crc32(currentRomData)
                    val patchType = PatchType.detect(patchData).let { detected ->
                        if (detected == PatchType.UNKNOWN) {
                            val ext = getFileName(patchUri).substringAfterLast('.', "")
                            PatchType.fromExtension(ext)
                        } else detected
                    }

                    if (patchType == PatchType.UNKNOWN) {
                        results.add(PatchResult(false, message = "Unrecognized format: patch ${index + 1}"))
                    } else {
                        currentRomData = when (patchType) {
                            PatchType.IPS -> IpsEngine.apply(patchData, currentRomData)
                            PatchType.UPS -> UpsEngine.apply(patchData, currentRomData)
                            PatchType.BPS -> BpsEngine.apply(patchData, currentRomData)
                            PatchType.XDELTA -> XdeltaEngine.apply(patchData, currentRomData)
                            PatchType.PPF -> PpfEngine.apply(patchData, currentRomData)
                            PatchType.APS -> ApsEngine.apply(patchData, currentRomData)
                            PatchType.UNKNOWN -> currentRomData // unreachable
                        }

                        results.add(PatchResult(
                            success = true,
                            outputSize = currentRomData.size.toLong(),
                            message = "Patch ${index + 1} applied (${patchType.displayName})",
                            checksumBefore = checksumBefore,
                            checksumAfter = crc32(currentRomData),
                            patchType = patchType
                        ))
                    }
                }
            } catch (e: Exception) {
                results.add(PatchResult(false, message = "Patch ${index + 1} failed: ${e.message}"))
            }
        }

        // Write final output
        val finalUri = outputUri ?: romUri
        try {
            resolver.openOutputStream(finalUri, "wt")?.use { out ->
                out.write(currentRomData)
            }
        } catch (e: Exception) {
            results.add(PatchResult(false, message = "Failed to write output: ${e.message}"))
        }

        results
    }

    /**
     * Restore a ROM from backup.
     */
    suspend fun restoreBackup(romUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val backupName = getFileName(romUri) + ".bak"
        val backupFile = File(backupDir, backupName)
        if (!backupFile.exists()) return@withContext false

        try {
            val backupData = backupFile.readBytes()
            context.contentResolver.openOutputStream(romUri, "wt")?.use { out ->
                out.write(backupData)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a backup exists for a ROM.
     */
    fun hasBackup(romUri: Uri): Boolean {
        val backupName = getFileName(romUri) + ".bak"
        return File(backupDir, backupName).exists()
    }

    /**
     * Get list of all backups.
     */
    fun listBackups(): List<File> {
        return backupDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Delete all backups.
     */
    fun clearBackups(): Long {
        val files = backupDir.listFiles() ?: return 0
        var freedBytes = 0L
        for (file in files) {
            freedBytes += file.length()
            file.delete()
        }
        return freedBytes
    }

    private fun getFileName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return it.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }
}

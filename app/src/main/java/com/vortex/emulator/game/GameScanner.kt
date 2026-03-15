package com.vortex.emulator.game

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.vortex.emulator.core.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao
) {
    // All known ROM extensions
    private val allExtensions = Platform.entries.flatMap { it.romExtensions }.toSet()

    /**
     * Scan a directory (SAF URI) for ROM files and add them to the library.
     */
    suspend fun scanDirectory(directoryUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val documentFile = DocumentFile.fromTreeUri(context, directoryUri)
            ?: return@withContext ScanResult(0, 0, emptyList())

        val foundGames = mutableListOf<Game>()
        val errors = mutableListOf<String>()

        scanRecursive(documentFile, foundGames, errors)

        // Filter out already existing games
        val newGames = foundGames.filter { game ->
            gameDao.getGameByPath(game.romPath) == null
        }

        if (newGames.isNotEmpty()) {
            gameDao.insertGames(newGames)
        }

        ScanResult(
            totalFound = foundGames.size,
            newAdded = newGames.size,
            errors = errors
        )
    }

    /**
     * Scan a local file path for ROMs.
     */
    suspend fun scanPath(path: String): ScanResult = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext ScanResult(0, 0, listOf("Directory not found: $path"))
        }

        val foundGames = mutableListOf<Game>()
        val errors = mutableListOf<String>()

        scanFileRecursive(dir, foundGames, errors)

        val newGames = foundGames.filter { game ->
            gameDao.getGameByPath(game.romPath) == null
        }

        if (newGames.isNotEmpty()) {
            gameDao.insertGames(newGames)
        }

        ScanResult(
            totalFound = foundGames.size,
            newAdded = newGames.size,
            errors = errors
        )
    }

    private fun scanRecursive(
        document: DocumentFile,
        games: MutableList<Game>,
        errors: MutableList<String>
    ) {
        for (file in document.listFiles()) {
            if (file.isDirectory) {
                scanRecursive(file, games, errors)
            } else {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in allExtensions) {
                    val platforms = Platform.fromExtension(ext)
                    val platform = platforms.firstOrNull() ?: continue
                    val title = name.substringBeforeLast('.').cleanRomTitle()
                    val sizeMb = file.length().toFloat() / (1024 * 1024)

                    games.add(
                        Game(
                            title = title,
                            romPath = file.uri.toString(),
                            platform = platform,
                            fileSizeMb = sizeMb
                        )
                    )
                }
            }
        }
    }

    private fun scanFileRecursive(
        dir: File,
        games: MutableList<Game>,
        errors: MutableList<String>
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanFileRecursive(file, games, errors)
            } else {
                val ext = file.extension.lowercase()
                if (ext in allExtensions) {
                    val platforms = Platform.fromExtension(ext)
                    val platform = platforms.firstOrNull() ?: continue
                    val title = file.nameWithoutExtension.cleanRomTitle()
                    val sizeMb = file.length().toFloat() / (1024 * 1024)

                    games.add(
                        Game(
                            title = title,
                            romPath = file.absolutePath,
                            platform = platform,
                            fileSizeMb = sizeMb
                        )
                    )
                }
            }
        }
    }

    private fun String.cleanRomTitle(): String {
        return this
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace("_", " ")
            .replace("  ", " ")
            .trim()
    }
}

data class ScanResult(
    val totalFound: Int,
    val newAdded: Int,
    val errors: List<String>
)

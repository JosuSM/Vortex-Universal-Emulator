package com.vortex.emulator.game

import androidx.room.*
import com.vortex.emulator.core.Platform
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY title ASC")
    fun getAllGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE lastPlayed IS NOT NULL ORDER BY lastPlayed DESC LIMIT :limit")
    fun getRecentGames(limit: Int = 20): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE platform = :platform ORDER BY title ASC")
    fun getGamesByPlatform(platform: Platform): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE title LIKE '%' || :query || '%'")
    fun searchGames(query: String): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE title LIKE '%' || :query || '%' AND platform = :platform ORDER BY title ASC")
    fun searchGamesByPlatform(query: String, platform: Platform): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getGameById(id: Long): Game?

    @Query("SELECT * FROM games WHERE romPath = :path LIMIT 1")
    suspend fun getGameByPath(path: String): Game?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: Game): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGames(games: List<Game>)

    @Update
    suspend fun updateGame(game: Game)

    @Delete
    suspend fun deleteGame(game: Game)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteGameById(id: Long)

    @Query("UPDATE games SET lastPlayed = :timestamp, totalPlayTimeMinutes = totalPlayTimeMinutes + :sessionMinutes WHERE id = :gameId")
    suspend fun updatePlayTime(gameId: Long, timestamp: Long, sessionMinutes: Long)

    @Query("UPDATE games SET isFavorite = :favorite WHERE id = :gameId")
    suspend fun setFavorite(gameId: Long, favorite: Boolean)

    @Query("SELECT DISTINCT platform FROM games ORDER BY platform ASC")
    fun getAvailablePlatforms(): Flow<List<Platform>>

    @Query("SELECT COUNT(*) FROM games")
    fun getGameCount(): Flow<Int>
}

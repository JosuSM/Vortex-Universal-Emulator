package com.vortex.emulator.game

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vortex.emulator.core.Platform

@Entity(tableName = "games")
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val romPath: String,
    val platform: Platform,
    val coverUri: String? = null,
    val lastPlayed: Long? = null,
    val totalPlayTimeMinutes: Long = 0,
    val isFavorite: Boolean = false,
    val rating: Float = 0f,
    val coreId: String? = null,
    val fileSizeMb: Float = 0f,
    val addedTimestamp: Long = System.currentTimeMillis()
)

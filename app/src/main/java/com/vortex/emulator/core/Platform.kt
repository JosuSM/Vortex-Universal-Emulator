package com.vortex.emulator.core

/**
 * Represents a supported gaming platform/console.
 */
enum class Platform(
    val displayName: String,
    val abbreviation: String,
    val romExtensions: List<String>,
    val icon: String
) {
    NES(
        displayName = "Nintendo Entertainment System",
        abbreviation = "NES",
        romExtensions = listOf("nes", "unf", "unif"),
        icon = "nes"
    ),
    SNES(
        displayName = "Super Nintendo",
        abbreviation = "SNES",
        romExtensions = listOf("sfc", "smc", "fig", "swc"),
        icon = "snes"
    ),
    N64(
        displayName = "Nintendo 64",
        abbreviation = "N64",
        romExtensions = listOf("n64", "z64", "v64"),
        icon = "n64"
    ),
    GBA(
        displayName = "Game Boy Advance",
        abbreviation = "GBA",
        romExtensions = listOf("gba", "agb"),
        icon = "gba"
    ),
    GBC(
        displayName = "Game Boy Color",
        abbreviation = "GBC",
        romExtensions = listOf("gbc", "gb", "sgb"),
        icon = "gbc"
    ),
    NDS(
        displayName = "Nintendo DS",
        abbreviation = "NDS",
        romExtensions = listOf("nds", "dsi"),
        icon = "nds"
    ),
    GENESIS(
        displayName = "Sega Genesis / Mega Drive",
        abbreviation = "GEN",
        romExtensions = listOf("gen", "md", "smd", "bin"),
        icon = "genesis"
    ),
    PSX(
        displayName = "PlayStation",
        abbreviation = "PSX",
        romExtensions = listOf("bin", "cue", "iso", "img", "pbp", "chd"),
        icon = "psx"
    ),
    PSP(
        displayName = "PlayStation Portable",
        abbreviation = "PSP",
        romExtensions = listOf("iso", "cso", "pbp"),
        icon = "psp"
    ),
    DREAMCAST(
        displayName = "Sega Dreamcast",
        abbreviation = "DC",
        romExtensions = listOf("gdi", "cdi", "chd"),
        icon = "dreamcast"
    ),
    ARCADE(
        displayName = "Arcade",
        abbreviation = "ARC",
        romExtensions = listOf("zip", "7z"),
        icon = "arcade"
    ),
    GAMECUBE(
        displayName = "Nintendo GameCube",
        abbreviation = "GCN",
        romExtensions = listOf("iso", "gcm", "rvz", "nkit"),
        icon = "gamecube"
    ),
    WII(
        displayName = "Nintendo Wii",
        abbreviation = "WII",
        romExtensions = listOf("iso", "wbfs", "rvz", "nkit"),
        icon = "wii"
    ),
    SATURN(
        displayName = "Sega Saturn",
        abbreviation = "SAT",
        romExtensions = listOf("bin", "cue", "iso", "chd"),
        icon = "saturn"
    );

    companion object {
        fun fromExtension(ext: String): List<Platform> {
            val lower = ext.lowercase()
            return entries.filter { lower in it.romExtensions }
        }
    }
}

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
    ),
    THREEDS(
        displayName = "Nintendo 3DS",
        abbreviation = "3DS",
        romExtensions = listOf("3ds", "cci", "cxi", "cia"),
        icon = "3ds"
    ),
    PS2(
        displayName = "PlayStation 2",
        abbreviation = "PS2",
        romExtensions = listOf("iso", "bin", "chd", "gz"),
        icon = "ps2"
    ),
    VITA(
        displayName = "PlayStation Vita",
        abbreviation = "VITA",
        romExtensions = listOf("vpk", "mai"),
        icon = "vita"
    ),
    PCE(
        displayName = "PC Engine / TurboGrafx-16",
        abbreviation = "PCE",
        romExtensions = listOf("pce", "sgx", "cue", "ccd", "chd"),
        icon = "pce"
    ),
    ATARI2600(
        displayName = "Atari 2600",
        abbreviation = "2600",
        romExtensions = listOf("a26", "bin"),
        icon = "atari"
    ),
    ATARI7800(
        displayName = "Atari 7800",
        abbreviation = "7800",
        romExtensions = listOf("a78", "bin"),
        icon = "atari"
    ),
    LYNX(
        displayName = "Atari Lynx",
        abbreviation = "LYNX",
        romExtensions = listOf("lnx"),
        icon = "atari"
    ),
    NGP(
        displayName = "Neo Geo Pocket / Color",
        abbreviation = "NGP",
        romExtensions = listOf("ngp", "ngc"),
        icon = "ngp"
    ),
    WONDERSWAN(
        displayName = "WonderSwan / Color",
        abbreviation = "WS",
        romExtensions = listOf("ws", "wsc"),
        icon = "wonderswan"
    ),
    VIRTUALBOY(
        displayName = "Virtual Boy",
        abbreviation = "VB",
        romExtensions = listOf("vb", "vboy"),
        icon = "virtualboy"
    ),
    DOS(
        displayName = "DOS",
        abbreviation = "DOS",
        romExtensions = listOf("zip", "dosz", "exe", "com", "bat"),
        icon = "dos"
    ),
    THREEDO(
        displayName = "3DO",
        abbreviation = "3DO",
        romExtensions = listOf("iso", "bin", "chd", "cue"),
        icon = "threedo"
    );

    companion object {
        fun fromExtension(ext: String): List<Platform> {
            val lower = ext.lowercase()
            return entries.filter { lower in it.romExtensions }
        }
    }
}

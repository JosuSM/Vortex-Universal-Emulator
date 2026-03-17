//! Lemuroid-compatible curated libretro core catalog.
//!
//! Defines the recommended cores per platform, matching the selection and
//! quality standards of the Lemuroid project. Each entry maps to a real
//! `.so` on the libretro buildbot: `{library_name}_libretro_android.so`

/// A single curated core entry.
#[derive(Debug, Clone)]
pub struct CatalogEntry {
    /// Buildbot library name (e.g. "fceumm"). Used to construct download URL.
    pub library_name: &'static str,
    /// Human-readable display name
    pub display_name: &'static str,
    /// Platform identifier (matches Kotlin Platform enum)
    pub platform: &'static str,
    /// Whether this is the default/recommended core for the platform
    pub is_default: bool,
    /// Lower = higher priority when multiple cores support the same platform
    pub priority: u8,
    /// Common ROM file extensions for this platform
    pub extensions: &'static [&'static str],
    /// Whether the core requires OpenGL HW rendering
    pub hw_render: bool,
}

/// Full Lemuroid-compatible core catalog — curated for Android.
///
/// Cores are selected for: compatibility, performance on mobile,
/// accuracy, and active maintenance.
pub static CATALOG: &[CatalogEntry] = &[
    // ── NES ──────────────────────────────────────────────
    CatalogEntry {
        library_name: "fceumm",
        display_name: "FCEUmm (NES)",
        platform: "NES",
        is_default: true,
        priority: 0,
        extensions: &["nes", "unf", "unif", "fds"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "nestopia",
        display_name: "Nestopia UE (NES)",
        platform: "NES",
        is_default: false,
        priority: 1,
        extensions: &["nes", "unf", "unif", "fds"],
        hw_render: false,
    },
    // ── SNES ─────────────────────────────────────────────
    CatalogEntry {
        library_name: "snes9x",
        display_name: "Snes9x (SNES)",
        platform: "SNES",
        is_default: true,
        priority: 0,
        extensions: &["smc", "sfc", "swc", "fig", "bs"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "bsnes",
        display_name: "bsnes (SNES, Accuracy)",
        platform: "SNES",
        is_default: false,
        priority: 2,
        extensions: &["smc", "sfc", "swc", "fig", "bs"],
        hw_render: false,
    },
    // ── N64 ──────────────────────────────────────────────
    CatalogEntry {
        library_name: "mupen64plus_next_gles3",
        display_name: "Mupen64Plus-Next (N64)",
        platform: "N64",
        is_default: true,
        priority: 0,
        extensions: &["n64", "z64", "v64", "ndd"],
        hw_render: true,
    },
    // ── Game Boy Advance ─────────────────────────────────
    CatalogEntry {
        library_name: "mgba",
        display_name: "mGBA (GBA/GB/GBC)",
        platform: "GBA",
        is_default: true,
        priority: 0,
        extensions: &["gba", "agb", "bin"],
        hw_render: false,
    },
    // ── Game Boy / Game Boy Color ────────────────────────
    CatalogEntry {
        library_name: "gambatte",
        display_name: "Gambatte (GB/GBC)",
        platform: "GBC",
        is_default: true,
        priority: 0,
        extensions: &["gb", "gbc", "dmg", "sgb"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "mgba",
        display_name: "mGBA (GB/GBC/GBA)",
        platform: "GBC",
        is_default: false,
        priority: 1,
        extensions: &["gb", "gbc", "dmg", "sgb"],
        hw_render: false,
    },
    // ── Nintendo DS ──────────────────────────────────────
    CatalogEntry {
        library_name: "melonds",
        display_name: "melonDS (NDS)",
        platform: "NDS",
        is_default: true,
        priority: 0,
        extensions: &["nds", "dsi"],
        hw_render: true,
    },
    CatalogEntry {
        library_name: "desmume",
        display_name: "DeSmuME (NDS)",
        platform: "NDS",
        is_default: false,
        priority: 1,
        extensions: &["nds", "dsi"],
        hw_render: false,
    },
    // ── PlayStation ──────────────────────────────────────
    CatalogEntry {
        library_name: "pcsx_rearmed",
        display_name: "PCSX-ReARMed (PSX)",
        platform: "PSX",
        is_default: true,
        priority: 0,
        extensions: &["bin", "cue", "iso", "img", "pbp", "chd", "m3u"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "swanstation",
        display_name: "SwanStation (PSX, HW)",
        platform: "PSX",
        is_default: false,
        priority: 1,
        extensions: &["bin", "cue", "iso", "img", "pbp", "chd", "m3u"],
        hw_render: true,
    },
    // ── PlayStation Portable ─────────────────────────────
    CatalogEntry {
        library_name: "ppsspp",
        display_name: "PPSSPP (PSP)",
        platform: "PSP",
        is_default: true,
        priority: 0,
        extensions: &["iso", "cso", "pbp", "elf", "prx"],
        hw_render: true,
    },
    // ── Sega Genesis / Mega Drive ────────────────────────
    CatalogEntry {
        library_name: "genesis_plus_gx",
        display_name: "Genesis Plus GX (Genesis/MD/SMS/GG)",
        platform: "GENESIS",
        is_default: true,
        priority: 0,
        extensions: &["gen", "md", "bin", "smd", "32x", "sms", "gg", "sg"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "picodrive",
        display_name: "PicoDrive (Genesis/32X)",
        platform: "GENESIS",
        is_default: false,
        priority: 1,
        extensions: &["gen", "md", "bin", "smd", "32x"],
        hw_render: false,
    },
    // ── Sega Dreamcast ───────────────────────────────────
    CatalogEntry {
        library_name: "flycast",
        display_name: "Flycast (Dreamcast)",
        platform: "DREAMCAST",
        is_default: true,
        priority: 0,
        extensions: &["cdi", "gdi", "chd", "cue", "bin"],
        hw_render: true,
    },
    // ── Sega Saturn ──────────────────────────────────────
    CatalogEntry {
        library_name: "mednafen_saturn",
        display_name: "Beetle Saturn (Saturn)",
        platform: "SATURN",
        is_default: true,
        priority: 0,
        extensions: &["bin", "cue", "iso", "chd", "toc", "m3u"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "yabause",
        display_name: "Yabause (Saturn)",
        platform: "SATURN",
        is_default: false,
        priority: 1,
        extensions: &["bin", "cue", "iso", "chd", "toc", "m3u"],
        hw_render: true,
    },
    // ── Arcade ───────────────────────────────────────────
    CatalogEntry {
        library_name: "fbneo",
        display_name: "FinalBurn Neo (Arcade)",
        platform: "ARCADE",
        is_default: true,
        priority: 0,
        extensions: &["zip", "7z"],
        hw_render: false,
    },
    CatalogEntry {
        library_name: "mame2003_plus",
        display_name: "MAME 2003-Plus (Arcade)",
        platform: "ARCADE",
        is_default: false,
        priority: 1,
        extensions: &["zip", "7z"],
        hw_render: false,
    },
    // ── GameCube / Wii ───────────────────────────────────
    CatalogEntry {
        library_name: "dolphin",
        display_name: "Dolphin (GameCube/Wii)",
        platform: "GAMECUBE",
        is_default: true,
        priority: 0,
        extensions: &["gcm", "iso", "gcz", "ciso", "wbfs", "rvz", "dol", "elf", "wad", "nkit"],
        hw_render: true,
    },
    // ── Nintendo 3DS ─────────────────────────────────────
    CatalogEntry {
        library_name: "citra",
        display_name: "Citra (3DS)",
        platform: "THREEDS",
        is_default: true,
        priority: 0,
        extensions: &["3ds", "3dsx", "elf", "axf", "cci", "cxi", "app"],
        hw_render: true,
    },
    // ── PlayStation 2 ────────────────────────────────────
    CatalogEntry {
        library_name: "play",
        display_name: "Play! (PS2)",
        platform: "PS2",
        is_default: true,
        priority: 0,
        extensions: &["iso", "bin", "img", "mdf", "nrg", "chd", "elf"],
        hw_render: true,
    },
    // ── PC Engine / TurboGrafx-16 ────────────────────────
    CatalogEntry {
        library_name: "mednafen_pce_fast",
        display_name: "Beetle PCE Fast (PC Engine)",
        platform: "PCE",
        is_default: true,
        priority: 0,
        extensions: &["pce", "sgx", "cue", "ccd", "chd"],
        hw_render: false,
    },
    // ── Atari 2600 ───────────────────────────────────────
    CatalogEntry {
        library_name: "stella2014",
        display_name: "Stella 2014 (Atari 2600)",
        platform: "ATARI2600",
        is_default: true,
        priority: 0,
        extensions: &["a26", "bin"],
        hw_render: false,
    },
    // ── Atari 7800 ───────────────────────────────────────
    CatalogEntry {
        library_name: "prosystem",
        display_name: "ProSystem (Atari 7800)",
        platform: "ATARI7800",
        is_default: true,
        priority: 0,
        extensions: &["a78", "bin"],
        hw_render: false,
    },
    // ── Atari Lynx ───────────────────────────────────────
    CatalogEntry {
        library_name: "handy",
        display_name: "Handy (Atari Lynx)",
        platform: "LYNX",
        is_default: true,
        priority: 0,
        extensions: &["lnx"],
        hw_render: false,
    },
    // ── Neo Geo Pocket / Color ───────────────────────────
    CatalogEntry {
        library_name: "mednafen_ngp",
        display_name: "Beetle NGP (Neo Geo Pocket)",
        platform: "NGP",
        is_default: true,
        priority: 0,
        extensions: &["ngp", "ngc"],
        hw_render: false,
    },
    // ── WonderSwan / Color ───────────────────────────────
    CatalogEntry {
        library_name: "mednafen_wswan",
        display_name: "Beetle WonderSwan",
        platform: "WONDERSWAN",
        is_default: true,
        priority: 0,
        extensions: &["ws", "wsc"],
        hw_render: false,
    },
    // ── Virtual Boy ──────────────────────────────────────
    CatalogEntry {
        library_name: "mednafen_vb",
        display_name: "Beetle VB (Virtual Boy)",
        platform: "VIRTUALBOY",
        is_default: true,
        priority: 0,
        extensions: &["vb", "vboy", "bin"],
        hw_render: false,
    },
    // ── DOS ──────────────────────────────────────────────
    CatalogEntry {
        library_name: "dosbox_pure",
        display_name: "DOSBox Pure (DOS)",
        platform: "DOS",
        is_default: true,
        priority: 0,
        extensions: &["zip", "dosz", "exe", "com", "bat", "iso", "cue", "ins", "img", "ima", "vhd", "m3u", "m3u8", "conf"],
        hw_render: false,
    },
    // ── 3DO ──────────────────────────────────────────────
    CatalogEntry {
        library_name: "opera",
        display_name: "Opera (3DO)",
        platform: "THREEDO",
        is_default: true,
        priority: 0,
        extensions: &["iso", "bin", "chd", "cue"],
        hw_render: false,
    },
];

// ── Query functions ──────────────────────────────────────────

/// Get all catalog entries.
pub fn get_all() -> &'static [CatalogEntry] {
    CATALOG
}

/// Get the default core for a given platform.
pub fn get_default_for_platform(platform: &str) -> Option<&'static CatalogEntry> {
    CATALOG.iter().find(|c| c.platform == platform && c.is_default)
}

/// Get all cores that support a given platform, sorted by priority.
pub fn get_cores_for_platform(platform: &str) -> Vec<&'static CatalogEntry> {
    let mut cores: Vec<_> = CATALOG.iter().filter(|c| c.platform == platform).collect();
    cores.sort_by_key(|c| c.priority);
    cores
}

/// Guess the platform from a ROM file extension.
pub fn detect_platform(extension: &str) -> Option<&'static str> {
    let ext = extension.to_lowercase();
    // Platform-specific first (unambiguous extensions)
    match ext.as_str() {
        "nes" | "unf" | "unif" | "fds" => return Some("NES"),
        "smc" | "sfc" | "swc" | "fig" => return Some("SNES"),
        "n64" | "z64" | "v64" | "ndd" => return Some("N64"),
        "gba" | "agb" => return Some("GBA"),
        "gb" | "gbc" | "dmg" | "sgb" => return Some("GBC"),
        "nds" | "dsi" => return Some("NDS"),
        "pbp" => return Some("PSP"),
        "cso" => return Some("PSP"),
        "gen" | "md" | "smd" | "32x" | "sms" | "gg" | "sg" => return Some("GENESIS"),
        "cdi" | "gdi" => return Some("DREAMCAST"),
        "gcm" | "gcz" | "wbfs" | "rvz" | "wad" | "nkit" => return Some("GAMECUBE"),
        "3ds" | "3dsx" | "cci" | "cxi" => return Some("THREEDS"),
        "pce" | "sgx" => return Some("PCE"),
        "a26" => return Some("ATARI2600"),
        "a78" => return Some("ATARI7800"),
        "lnx" => return Some("LYNX"),
        "ngp" | "ngc" => return Some("NGP"),
        "ws" | "wsc" => return Some("WONDERSWAN"),
        "vb" | "vboy" => return Some("VIRTUALBOY"),
        "dosz" => return Some("DOS"),
        _ => None,
    }
}

/// Build the buildbot download URL for a core.
pub fn buildbot_url(library_name: &str, abi: &str) -> String {
    format!(
        "https://buildbot.libretro.com/nightly/android/latest/{}/{}_libretro_android.so.zip",
        abi, library_name
    )
}

/// Build JSON representation of the catalog for JNI transfer.
pub fn catalog_to_json() -> String {
    let mut json = String::from("[");
    for (i, entry) in CATALOG.iter().enumerate() {
        if i > 0 { json.push(','); }
        json.push_str(&format!(
            r#"{{"library_name":"{}","display_name":"{}","platform":"{}","is_default":{},"priority":{},"hw_render":{},"extensions":"{}"}}"#,
            entry.library_name,
            entry.display_name,
            entry.platform,
            entry.is_default,
            entry.priority,
            entry.hw_render,
            entry.extensions.join(","),
        ));
    }
    json.push(']');
    json
}

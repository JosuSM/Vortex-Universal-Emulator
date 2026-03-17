//! Per-core option presets for optimal Android performance.
//!
//! When a core is loaded, its presets are applied as overrides so the
//! core starts with known-good settings. These mirror the defaults that
//! Lemuroid uses to ensure broad compatibility on mobile devices.

use std::collections::HashMap;
use std::ffi::CString;

/// A set of key-value option overrides for a specific core.
pub struct CorePreset {
    pub library_name: &'static str,
    pub options: &'static [(&'static str, &'static str)],
}

/// All curated presets — indexed by library_name.
static PRESETS: &[CorePreset] = &[
    // ── NES ──────────────────────────────────────────
    CorePreset {
        library_name: "fceumm",
        options: &[
            ("fceumm_palette", "asqrealc"),
            ("fceumm_turbo_enable", "Both"),
            ("fceumm_up_down_allowed", "disabled"),
            ("fceumm_nospritelimit", "enabled"),
        ],
    },
    CorePreset {
        library_name: "nestopia",
        options: &[
            ("nestopia_palette", "consumer"),
            ("nestopia_nospritelimit", "enabled"),
            ("nestopia_overclock", "1x"),
        ],
    },
    // ── SNES ─────────────────────────────────────────
    CorePreset {
        library_name: "snes9x",
        options: &[
            ("snes9x_overclock_cycles", "disabled"),
            ("snes9x_reduce_sprite_flicker", "enabled"),
            ("snes9x_hires_blend", "disabled"),
            ("snes9x_audio_interpolation", "gaussian"),
        ],
    },
    // ── N64 ──────────────────────────────────────────
    CorePreset {
        library_name: "mupen64plus_next_gles3",
        options: &[
            ("mupen64plus-EnableFBEmulation", "True"),
            ("mupen64plus-EnableShadersStorage", "False"),
            ("mupen64plus-cpucore", "dynamic_recompiler"),
            ("mupen64plus-ThreadedRenderer", "True"),
            ("mupen64plus-43screensize", "320x240"),
            ("mupen64plus-BilinearMode", "3point"),
            ("mupen64plus-MaxTxCacheSize", "4000"),
            ("mupen64plus-EnableLOD", "True"),
            ("mupen64plus-EnableCopyDepthToRDRAM", "Software"),
        ],
    },
    // ── GBA ──────────────────────────────────────────
    CorePreset {
        library_name: "mgba",
        options: &[
            ("mgba_solar_sensor_level", "0"),
            ("mgba_force_gbp", "OFF"),
            ("mgba_color_correction", "OFF"),
        ],
    },
    // ── GB/GBC ───────────────────────────────────────
    CorePreset {
        library_name: "gambatte",
        options: &[
            ("gambatte_dark_filter_level", "0"),
            ("gambatte_gb_colorization", "internal"),
            ("gambatte_gb_internal_palette", "TWB64 - Pack 1"),
            ("gambatte_gbc_color_correction", "GBC only"),
            ("gambatte_up_down_allowed", "disabled"),
        ],
    },
    // ── NDS ──────────────────────────────────────────
    CorePreset {
        library_name: "melonds",
        options: &[
            ("melonds_threaded_renderer", "enabled"),
            ("melonds_console_mode", "DS"),
            ("melonds_touch_mode", "Touch"),
            ("melonds_jit_enable", "enabled"),
        ],
    },
    CorePreset {
        library_name: "desmume",
        options: &[
            ("desmume_cpu_mode", "jit"),
            ("desmume_frameskip", "0"),
        ],
    },
    // ── PSX ──────────────────────────────────────────
    CorePreset {
        library_name: "pcsx_rearmed",
        options: &[
            ("pcsx_rearmed_drc", "enabled"),
            ("pcsx_rearmed_neon_interlace_enable", "disabled"),
            ("pcsx_rearmed_neon_enhancement_enable", "disabled"),
            ("pcsx_rearmed_frameskip", "0"),
            ("pcsx_rearmed_show_bios_bootlogo", "disabled"),
        ],
    },
    CorePreset {
        library_name: "swanstation",
        options: &[
            ("swanstation_CPU.ExecutionMode", "Recompiler"),
            ("swanstation_GPU.Renderer", "HardwareOpenGL"),
            ("swanstation_Main.RunaheadFrameCount", "0"),
        ],
    },
    // ── PSP ──────────────────────────────────────────
    CorePreset {
        library_name: "ppsspp",
        options: &[
            ("ppsspp_cpu_core", "JIT"),
            ("ppsspp_rendering_mode", "buffered"),
            ("ppsspp_auto_frameskip", "disabled"),
            ("ppsspp_internal_resolution", "1"),
            ("ppsspp_texture_scaling_level", "1"),
            ("ppsspp_texture_filtering", "auto"),
            ("ppsspp_gpu_hardware_transform", "enabled"),
            ("ppsspp_ignore_bad_memory_access", "enabled"),
            ("ppsspp_fast_memory", "enabled"),
        ],
    },
    // ── Genesis / Mega Drive ─────────────────────────
    CorePreset {
        library_name: "genesis_plus_gx",
        options: &[
            ("genesis_plus_gx_blargg_ntsc_filter", "disabled"),
            ("genesis_plus_gx_no_sprite_limit", "enabled"),
            ("genesis_plus_gx_sound_output", "stereo"),
            ("genesis_plus_gx_overclock", "100%"),
        ],
    },
    CorePreset {
        library_name: "picodrive",
        options: &[
            ("picodrive_input1", "6 button pad"),
            ("picodrive_input2", "6 button pad"),
            ("picodrive_overclk68k", "0"),
        ],
    },
    // ── Dreamcast ────────────────────────────────────
    CorePreset {
        library_name: "flycast",
        options: &[
            ("flycast_threaded_rendering", "enabled"),
            ("flycast_anisotropic_filtering", "off"),
            ("flycast_internal_resolution", "640x480"),
            ("flycast_per_pixel_alpha", "enabled"),
            ("flycast_enable_dsp", "enabled"),
        ],
    },
    // ── Saturn ───────────────────────────────────────
    CorePreset {
        library_name: "mednafen_saturn",
        options: &[],
    },
    CorePreset {
        library_name: "yabause",
        options: &[
            ("yabause_frameskip", "disabled"),
            ("yabause_force_hle_bios", "disabled"),
        ],
    },
    // ── Arcade ───────────────────────────────────────
    CorePreset {
        library_name: "fbneo",
        options: &[
            ("fbneo-cpu-speed-adjust", "100"),
            ("fbneo-allow-patched-romsets", "enabled"),
            ("fbneo-diagnostic-input", "Hold Start"),
        ],
    },
    CorePreset {
        library_name: "mame2003_plus",
        options: &[
            ("mame2003-plus_skip_disclaimer", "enabled"),
            ("mame2003-plus_skip_warnings", "enabled"),
        ],
    },
    // ── GameCube / Wii ───────────────────────────────
    CorePreset {
        library_name: "dolphin",
        options: &[
            ("dolphin_efb_scale", "1 (640 x 528)"),
            ("dolphin_enable_rumble", "enabled"),
            ("dolphin_cpu_core", "JIT (x64 & AArch64)"),
        ],
    },
    // ── 3DS ──────────────────────────────────────────
    CorePreset {
        library_name: "citra",
        options: &[
            ("citra_use_cpu_jit", "enabled"),
            ("citra_resolution_factor", "1"),
            ("citra_use_hw_renderer", "enabled"),
        ],
    },
    // ── PS2 ──────────────────────────────────────────
    CorePreset {
        library_name: "play",
        options: &[
            ("play_resolution_factor", "1"),
        ],
    },
    // ── PC Engine ────────────────────────────────────
    CorePreset {
        library_name: "mednafen_pce_fast",
        options: &[
            ("pce_fast_nospritelimit", "enabled"),
            ("pce_fast_ocmultiplier", "1"),
        ],
    },
    // ── DOS ──────────────────────────────────────────
    CorePreset {
        library_name: "dosbox_pure",
        options: &[
            ("dosbox_pure_conf", "inside"),
            ("dosbox_pure_cpu_type", "auto"),
            ("dosbox_pure_cycles", "auto"),
        ],
    },
];

/// Look up the preset for a given core library name.
pub fn get_preset(library_name: &str) -> Option<&'static CorePreset> {
    PRESETS.iter().find(|p| p.library_name == library_name)
}

/// Apply a preset's options to the given override and option maps.
/// Options go into both `overrides` (supreme, can't be reset by core) and
/// `options` (visible to GET_VARIABLE).
pub fn apply_preset(
    library_name: &str,
    overrides: &mut HashMap<String, CString>,
    options: &mut HashMap<String, CString>,
) -> usize {
    let preset = match get_preset(library_name) {
        Some(p) => p,
        None => return 0,
    };
    let mut count = 0;
    for (key, value) in preset.options {
        if let Ok(cv) = CString::new(*value) {
            overrides.insert(key.to_string(), cv.clone());
            options.insert(key.to_string(), cv);
            count += 1;
        }
    }
    count
}

/// Build JSON representation of all presets for JNI transfer.
pub fn presets_to_json() -> String {
    let mut json = String::from("{");
    for (i, preset) in PRESETS.iter().enumerate() {
        if i > 0 { json.push(','); }
        json.push_str(&format!(r#""{}":{{""#, preset.library_name));
        for (j, (k, v)) in preset.options.iter().enumerate() {
            if j > 0 { json.push(','); }
            json.push_str(&format!(r#""{}":"{}""#, k, v));
        }
        json.push_str("}}");
    }
    // Fix: the inner braces need to be balanced
    json.push('}');
    json
}

/// Build JSON for a single core's presets.
pub fn preset_to_json(library_name: &str) -> String {
    let preset = match get_preset(library_name) {
        Some(p) => p,
        None => return "{}".to_string(),
    };
    let mut json = String::from("{");
    for (j, (k, v)) in preset.options.iter().enumerate() {
        if j > 0 { json.push(','); }
        json.push_str(&format!(r#""{}":"{}""#, k, v));
    }
    json.push('}');
    json
}

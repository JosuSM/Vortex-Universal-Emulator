package com.vortex.emulator.emulation

/**
 * Available native frontend implementations.
 */
enum class FrontendType(
    val displayName: String,
    val description: String
) {
    CPP(
        displayName = "C++ Frontend",
        description = "Default frontend with C++ runtime. Broad compatibility with most cores."
    ),
    C(
        displayName = "C Frontend",
        description = "Pure C frontend optimized for C-based cores (N64, PSP). Better GlideN64 compatibility."
    ),
    RUST(
        displayName = "Rust Frontend",
        description = "GPU-adaptive frontend with vendor-specific Mali/Adreno workarounds and lock-free audio."
    );

    companion object {
        fun fromName(name: String): FrontendType =
            entries.firstOrNull { it.name == name } ?: CPP
    }
}

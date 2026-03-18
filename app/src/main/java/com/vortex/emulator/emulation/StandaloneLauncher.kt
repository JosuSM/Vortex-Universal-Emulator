package com.vortex.emulator.emulation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.vortex.emulator.core.CoreInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VortexFramework – Standalone Emulator Launcher
 *
 * Launches standalone emulator apps (PPSSPP, Dolphin, Citra, etc.) via Android
 * Intents, bypassing libretro entirely. This avoids the HW rendering issues
 * (deadlocks, Mali GPU incompatibilities) that plague libretro cores for
 * demanding platforms.
 *
 * Each standalone emulator handles its own GPU rendering, audio, input, and
 * save state management — they're full-featured apps that work perfectly on
 * all chipsets.
 */
@Singleton
class StandaloneLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "StandaloneLauncher"

        // ── Known standalone emulator packages ─────────────────
        // PSP
        const val PPSSPP_GOLD = "org.ppsspp.ppssppgold"
        const val PPSSPP_FREE = "org.ppsspp.ppsspp"

        // N64
        const val MUPEN64PLUS_AE = "org.mupen64plusae.v3.fzurita"
        const val MUPEN64PLUS_AE_FREE = "paulscode.android.mupen64plus.free"
        const val M64PLUS_FZ_PRO = "org.mupen64plusae.v3.fzurita.pro"

        // GameCube / Wii
        const val DOLPHIN_EMU = "org.dolphinemu.dolphinemu"
        const val DOLPHIN_MMJR = "org.mm.jr"

        // 3DS
        const val CITRA_EMU = "org.citra.emu"
        const val LIME3DS = "io.github.lime3ds.android"

        // Dreamcast
        const val FLYCAST = "com.flycast.emulator"
        const val REICAST = "com.reicast.emulator"
        const val REDREAM = "io.recompiled.redream"

        // PS2
        const val AETHERSX2 = "xyz.aethersx2.android"
        const val NETHERSX2 = "xyz.nethersx2.android"

        // PSX
        const val DUCKSTATION = "com.github.stenzek.duckstation"

        // Saturn
        const val YABA_SANSHIRO = "org.uoyabause.urern"

        // DS
        const val DRASTIC = "com.dsemu.drastic"
        const val MELONDS_ANDROID = "me.magnum.melonds"

        // PS Vita
        const val VITA3K = "org.nicholasopuni31.vita3k"

        // Multi-system
        const val LEMUROID = "com.nicholasopuni31.lemuroid"
        const val RETROARCH = "com.retroarch"
        const val RETROARCH_PLUS = "com.retroarch.aarch64"

        // SNES standalone
        const val SNES9X_EX = "com.explusalpha.Snes9xPlus"

        // GBA standalone
        const val MGBA_STANDALONE = "io.mgba.mgba"

        /**
         * Mapping of standalone core libraryName → list of known package names
         * (ordered by preference: paid/best first).
         */
        val PACKAGE_MAP: Map<String, List<String>> = mapOf(
            "ppsspp_standalone"      to listOf(PPSSPP_GOLD, PPSSPP_FREE),
            "retroarch_psp_standalone" to listOf(RETROARCH_PLUS, RETROARCH),
            "lemuroid_psp_standalone" to listOf(LEMUROID),
            "mupen64_standalone"     to listOf(MUPEN64PLUS_AE, M64PLUS_FZ_PRO, MUPEN64PLUS_AE_FREE),
            "dolphin_standalone"     to listOf(DOLPHIN_EMU, DOLPHIN_MMJR),
            "citra_standalone"       to listOf(LIME3DS, CITRA_EMU),
            "flycast_standalone"     to listOf(FLYCAST, REDREAM, REICAST),
            "aethersx2_standalone"   to listOf(AETHERSX2, NETHERSX2),
            "duckstation_standalone" to listOf(DUCKSTATION),
            "yabause_standalone"     to listOf(YABA_SANSHIRO),
            "drastic_standalone"     to listOf(DRASTIC, MELONDS_ANDROID),
            "vita3k_standalone"      to listOf(VITA3K),
            "lemuroid_standalone"    to listOf(LEMUROID),
            "retroarch_standalone"   to listOf(RETROARCH, RETROARCH_PLUS),
            "snes9x_standalone"      to listOf(SNES9X_EX),
            "mgba_standalone"        to listOf(MGBA_STANDALONE),
        )

        /** Play Store URL template */
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id="
    }

    /**
     * Check if any supported standalone emulator is installed for a given core.
     */
    fun isAnyEmulatorInstalled(core: CoreInfo): Boolean {
        val packages = PACKAGE_MAP[core.libraryName] ?: return false
        return packages.any { isPackageInstalled(it) }
    }

    /**
     * Get the installed package name for a core, or null if none installed.
     */
    fun getInstalledPackage(core: CoreInfo): String? {
        val packages = PACKAGE_MAP[core.libraryName] ?: return null
        return packages.firstOrNull { isPackageInstalled(it) }
    }

    /**
     * Get all installable package options for a core with install status.
     */
    fun getAvailableEmulators(core: CoreInfo): List<EmulatorOption> {
        val packages = PACKAGE_MAP[core.libraryName] ?: return emptyList()
        return packages.map { pkg ->
            EmulatorOption(
                packageName = pkg,
                displayName = getEmulatorDisplayName(pkg),
                isInstalled = isPackageInstalled(pkg),
                playStoreUrl = "$PLAY_STORE_URL$pkg"
            )
        }
    }

    /**
     * Launch a standalone emulator with the given ROM file.
     * Returns true if launch succeeded.
     */
    fun launch(core: CoreInfo, romPath: String): Boolean {
        val pkg = getInstalledPackage(core)
        if (pkg == null) {
            Log.e(TAG, "No installed emulator for ${core.libraryName}")
            return false
        }

        Log.i(TAG, "Launching $pkg for ROM: $romPath")

        return try {
            val intent = buildLaunchIntent(pkg, romPath)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                Log.e(TAG, "Could not build launch intent for $pkg")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg: ${e.message}", e)
            false
        }
    }

    /**
     * Get the Play Store install Intent for a specific package.
     */
    fun getInstallIntent(packageName: String): Intent {
        return try {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        } catch (_: Exception) {
            Intent(Intent.ACTION_VIEW, Uri.parse("$PLAY_STORE_URL$packageName"))
        }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun buildLaunchIntent(packageName: String, romPath: String): Intent? {
        val romFile = File(romPath)
        if (!romFile.exists()) {
            Log.e(TAG, "ROM file not found: $romPath")
            return null
        }

        val romUri = getFileUri(romFile)

        return when (packageName) {
            // ── PPSSPP: accepts ACTION_VIEW with file URI ──
            PPSSPP_GOLD, PPSSPP_FREE -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Dolphin: accepts ACTION_VIEW or explicit activity ──
            DOLPHIN_EMU, DOLPHIN_MMJR -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Citra / Lime3DS ──
            CITRA_EMU, LIME3DS -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Mupen64Plus AE: accepts ACTION_VIEW ──
            MUPEN64PLUS_AE, M64PLUS_FZ_PRO, MUPEN64PLUS_AE_FREE -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Flycast / Redream / Reicast ──
            FLYCAST, REDREAM, REICAST -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── DuckStation ──
            DUCKSTATION -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── AetherSX2 / NetherSX2 ──
            AETHERSX2, NETHERSX2 -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── DraStic / melonDS ──
            DRASTIC, MELONDS_ANDROID -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Yaba Sanshiro ──
            YABA_SANSHIRO -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── RetroArch: launch with ROM file ──
            RETROARCH, RETROARCH_PLUS -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Lemuroid: launch with ROM file ──
            LEMUROID -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // ── Fallback: generic ACTION_VIEW ──
            else -> {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(romUri, "application/octet-stream")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    /**
     * Get a shareable URI for a file. Uses FileProvider for app-internal files,
     * or direct file:// URI for external storage files.
     */
    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun getEmulatorDisplayName(packageName: String): String = when (packageName) {
        PPSSPP_GOLD -> "PPSSPP Gold (PSP)"
        PPSSPP_FREE -> "PPSSPP Free (PSP)"
        MUPEN64PLUS_AE -> "Mupen64Plus FZ (N64)"
        M64PLUS_FZ_PRO -> "Mupen64Plus FZ Pro (N64)"
        MUPEN64PLUS_AE_FREE -> "Mupen64Plus AE Free (N64)"
        DOLPHIN_EMU -> "Dolphin Emulator (GCN/Wii)"
        DOLPHIN_MMJR -> "Dolphin MMJR (GCN/Wii)"
        CITRA_EMU -> "Citra (3DS)"
        LIME3DS -> "Lime3DS (3DS)"
        FLYCAST -> "Flycast (Dreamcast)"
        REDREAM -> "Redream (Dreamcast)"
        REICAST -> "Reicast (Dreamcast)"
        AETHERSX2 -> "AetherSX2 (PS2)"
        NETHERSX2 -> "NetherSX2 (PS2)"
        DUCKSTATION -> "DuckStation (PSX)"
        YABA_SANSHIRO -> "Yaba Sanshiro (Saturn)"
        DRASTIC -> "DraStic (DS)"
        MELONDS_ANDROID -> "melonDS Android (DS)"
        VITA3K -> "Vita3K (PS Vita)"
        LEMUROID -> "Lemuroid (Multi-System)"
        RETROARCH -> "RetroArch"
        RETROARCH_PLUS -> "RetroArch (64-bit)"
        SNES9X_EX -> "Snes9x EX+ (SNES)"
        MGBA_STANDALONE -> "mGBA (GBA)"
        else -> packageName
    }
}

/**
 * Represents a standalone emulator that can be installed/launched.
 */
data class EmulatorOption(
    val packageName: String,
    val displayName: String,
    val isInstalled: Boolean,
    val playStoreUrl: String
)

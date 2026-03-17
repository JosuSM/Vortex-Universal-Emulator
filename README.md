<p align="center">
  <img src="assets/logo.svg" width="120" alt="Vortex Logo"/>
</p>

<h1 align="center">Vortex Emulator</h1>

<p align="center">
  <strong>Play retro. Feel modern.</strong><br/>
  The open-source Android emulator that brings 15+ classic platforms to your pocket — beautifully.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.2_Nova-00E5FF?style=for-the-badge" alt="Version"/>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/license-MIT-blue?style=for-the-badge" alt="License"/>
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
</p>

---

## Why Vortex?

Most emulators feel like developer tools. Vortex feels like a **gaming app** — designed for real players who care about both performance and aesthetics.

- **One app, 15+ platforms** — NES to PSP, Arcade to Saturn
- **Smart hardware detection** — Automatically tunes settings for your chipset
- **Gorgeous dark UI** — Material 3 with neon cyan & purple accents
- **Save anywhere** — 10 quick-save slots, export/import, rewind time
- **Play together** — Internet & LAN multiplayer for up to 8 players
- **Immersive gaming** — Full-screen emulation with hidden system bars
- **Per-core advanced settings** — Input polling, shader precision, threaded rendering, and more
- **Automatic standalone detection** — Detects installed emulators (PPSSPP, Dolphin, etc.) as usable cores

---

## Supported Platforms

| Platform | Core | Status |
|----------|------|--------|
| NES | FCEUmm / Mesen / Nestopia | Excellent |
| SNES | Snes9x | Excellent |
| Nintendo 64 | Mupen64Plus-Next / ParaLLEl | Great |
| Game Boy Advance | mGBA / VBA-M | Excellent |
| Game Boy Color | mGBA / SameBoy | Excellent |
| Nintendo DS | melonDS / DeSmuME | Great |
| Sega Genesis | Genesis Plus GX / PicoDrive | Excellent |
| PlayStation | SwanStation / Beetle PSX HW | Excellent |
| PSP | PPSSPP | Excellent |
| Dreamcast | Flycast | Great |
| Saturn | Beetle Saturn / Yabause | Good |
| Arcade | FinalBurn Neo / MAME 2003+ | Excellent |

> 21 cores total — each downloaded on-demand and cached locally.

---

## Features

### Emulation
- Native C/C++/Rust libretro frontends with JNI bridge for near-zero overhead
- Vulkan & OpenGL ES rendering with resolution upscaling
- Frame pacing for smooth 60 FPS gameplay
- Real-time FPS counter and performance monitoring
- Core switching per platform without restarting
- PSP software rendering fallback for Mali GPU compatibility
- Crash-safe GL context initialization — GPU failures don't crash the app

### Per-Core Advanced Settings
- Input Polling Mode — Normal or Early Poll for latency-sensitive games
- Threaded Rendering toggle — avoid GPU deadlocks on certain chipsets
- Shader Precision — Low, Medium, or High per core
- Rewind toggle with configurable buffer size (60–600 frames)
- Access via: long-press a game → Settings → Advanced / Compatibility

### Standalone Emulator Integration
- Automatic detection of 25+ standalone emulators (PPSSPP, Dolphin, Citra, etc.)
- Re-detected every time you open the Home or Cores screen
- Direct-launch via `ACTION_VIEW` intent with FileProvider support
- Download standalone APKs directly from GitHub or Play Store
- Libretro cores preferred by default — built-in multiplayer always works
- Switch to standalone manually via the core picker when needed

### Controls
- **8-direction D-Pad** with diagonal support and visual feedback
- **ABXY buttons** with radial gradients and press animations
- Analog stick, shoulder buttons (L/R), Start/Select
- **Physical gamepad support** — auto-detected with full button mapping
- **NDS/3DS touch screen input** — tap directly on the touch display
- Haptic feedback on all controls
- Landscape & portrait optimized layouts
- Hide/show controls on the fly

### Save States
- 10 quick-save slots with one-tap save/load
- Export & import save files via Android SAF
- Rewind time during gameplay
- Fast forward for grinding

### Multiplayer
- **Internet multiplayer** — encrypted WebSocket lobby with AES-256-GCM
- **2–8 players** per room with password-protected lobbies
- Local multiplayer with on-screen player toggle
- LAN netplay — host, join, or browse lobbies
- Real-time input relay bridge during gameplay
- Platform-filtered game search and auto-resolution

### Smart Hardware
- Auto-detects Snapdragon, Dimensity, Exynos, Tensor, Kirin
- Classifies your device: Flagship → Low End
- Recommends optimal settings per game
- GPU driver management (Turnip, Freedreno, PanVK, Panfrost)

### Experience
- **Animated splash screen** — vortex rings, starfield particles, and spring-animated logo
- **Immersive mode** — status and navigation bars hidden during emulation
- **Centered top bar** — Pause and Menu buttons at top center, away from shoulder buttons
- **Exit via Quick Menu** — no accidental exits; Quit Game is in the ⋮ menu
- Material Design 3 dark theme with neon accents
- Scrollable Quick Menu that works in landscape
- Game library with platform badges and cover art
- In-app changelog with release notes
- Identified as a game by Android for Game Dashboard integration
- Clean, focused UI — no clutter

### ROM Patcher
- **6 patch formats** — IPS, UPS, BPS, xdelta3/VCDIFF, PPF (v1/v2/v3), APS (GBA + N64)
- **Auto-detection** — identifies patch format from magic bytes and file extension
- **Patch stacking** — apply multiple patches in sequence with drag-to-reorder
- **CRC32 + MD5 checksums** — verify ROM integrity before and after patching
- **Auto backup & restore** — original ROM saved automatically, one-tap undo
- **Output to new file** — optionally write patched ROM separately
- **Batch progress** — visual tracking for multi-patch operations

---

## Screenshots

> Coming soon — run the app and see for yourself!

---

## Getting Started

### Requirements
- Android 8.0+ (API 26)
- ~50 MB storage + space for cores and ROMs

### Install

Download the latest APK from [Releases](../../releases) or build from source:

```bash
git clone https://github.com/JosuSM/Vortex-Universal-Emulator.git
cd Vortex-Universal-Emulator
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build Requirements
- Android Studio Ladybug (2024.2+)
- JDK 17+
- Android SDK 35, NDK with CMake 3.22.1
- Kotlin 2.1+

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin + C/C++/Rust (native frontends) |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room |
| Navigation | Navigation Compose |
| Image Loading | Coil |
| Settings | DataStore Preferences |
| Networking | OkHttp WebSocket + AES-256-GCM |
| Emulation | libretro API via JNI |
| Lobby Server | Node.js + ws (deployed on Render) |

---

## Contributing

Contributions are welcome! Feel free to open issues, suggest features, or submit pull requests.

## License

This project is licensed under the [MIT License](LICENSE).

---

<p align="center">
  <strong>Vortex v2.2 Nova</strong><br/>
  Built with Kotlin & Jetpack Compose<br/>
  Made by <a href="https://github.com/JosuSM">JosuSM</a>
</p>

# Vortex Emulator

<p align="center">
  <strong>Performance. Compatibility. Style.</strong>
</p>

---

## About

**Vortex** is a next-generation Android emulator built from the ground up for **performance**, **multi-chipset compatibility**, and an exceptional **gaming UX**. It features a stunning Material Design 3 interface with a dark, gamer-focused aesthetic.

## Features

### Multi-Platform Emulation
| Platform | Core | Quality |
|----------|------|---------|
| NES | FCEUmm | Excellent |
| SNES | Snes9x / bsnes | Excellent |
| Nintendo 64 | Mupen64Plus-Next | Great |
| Game Boy Advance | mGBA | Excellent |
| Game Boy Color | mGBA | Excellent |
| Nintendo DS | melonDS | Great |
| Sega Genesis | Genesis Plus GX | Excellent |
| PlayStation | SwanStation | Excellent |
| PSP | PPSSPP | Excellent |
| Dreamcast | Flycast | Great |
| Saturn | Beetle Saturn | Good |
| Arcade | FinalBurn Neo | Excellent |
| GameCube | Dolphin | Good |
| Wii | Dolphin | Good |

### Smart Chipset Detection
- Automatic detection of Qualcomm Snapdragon, MediaTek Dimensity, Samsung Exynos, Google Tensor, HiSilicon Kirin
- Tier classification: **Flagship**, **High End**, **Mid Range**, **Entry Level**, **Low End**
- Auto-recommended settings based on hardware capabilities

### GPU Driver Management
- **Turnip** (Mesa Vulkan) for Adreno GPUs
- **Freedreno** (Mesa OpenGL) for Adreno GPUs
- **PanVK** (Mesa Vulkan) for Mali GPUs
- **Panfrost** (Mesa OpenGL) for Mali GPUs
- Samsung Game Driver for Xclipse (RDNA2) GPUs
- One-tap driver switching

### Performance
- Vulkan & OpenGL ES 3.2 rendering
- Frame pacing for smooth 60fps gameplay
- Real-time FPS counter and performance monitoring
- Resolution upscaling up to 5x native
- Shader support for visual enhancements

### Gamer UX
- Material Design 3 with dark neon aesthetic (Cyan + Purple accents)
- Beautiful game library with platform badges and cover art
- On-screen touch controls: D-Pad, analog stick, action buttons, shoulders
- Quick menu during gameplay (save/load states, rewind, fast-forward, cheats)
- Haptic feedback on controls
- Landscape-optimized layout

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room |
| Navigation | Navigation Compose |
| Image Loading | Coil |
| Settings | DataStore Preferences |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |

## Project Structure

```
app/src/main/java/com/vortex/emulator/
├── VortexApp.kt              # Application class
├── MainActivity.kt            # Single activity
├── core/                      # Emulation engine
│   ├── Platform.kt            # Supported platforms enum
│   ├── CoreInfo.kt            # Core metadata
│   ├── EmulationCore.kt       # Core interface
│   └── CoreManager.kt         # Core lifecycle management
├── gpu/                       # GPU & hardware
│   ├── ChipsetDetector.kt     # Hardware detection & classification
│   └── DriverManager.kt       # GPU driver management
├── game/                      # Game library
│   ├── Game.kt                # Game entity (Room)
│   ├── GameDao.kt             # Database queries
│   ├── GameDatabase.kt        # Room database
│   └── GameScanner.kt         # ROM scanner
├── performance/               # Performance tools
│   ├── PerformanceMonitor.kt  # FPS & stats tracking
│   └── FramePacer.kt          # Frame timing
├── service/                   # Background services
│   └── EmulationService.kt    # Foreground service
├── di/                        # Dependency injection
│   └── AppModule.kt           # Hilt modules
└── ui/                        # User interface
    ├── theme/                 # Material 3 theme
    │   ├── Color.kt
    │   ├── Theme.kt
    │   └── Type.kt
    ├── navigation/            # App navigation
    │   ├── Screen.kt
    │   └── VortexNavigation.kt
    ├── components/            # Reusable components
    │   ├── GameCard.kt
    │   └── VortexHeader.kt
    ├── screens/               # App screens
    │   ├── HomeScreen.kt
    │   ├── LibraryScreen.kt
    │   ├── CoresScreen.kt
    │   ├── PerformanceScreen.kt
    │   ├── SettingsScreen.kt
    │   └── EmulationScreen.kt
    └── viewmodel/             # ViewModels
        ├── HomeViewModel.kt
        ├── LibraryViewModel.kt
        ├── CoresViewModel.kt
        └── PerformanceViewModel.kt
```

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on device
./gradlew installDebug
```

## Release Signing

- Copy `keystore.properties.example` to `keystore.properties` for local release signing.
- Keep the keystore file under `keystore/`.
- Both files are ignored by Git and stay local to your machine.

### CI Or Play Store Uploads

- Gradle now prefers these environment variables when they are present: `VORTEX_SIGNING_STORE_BASE64`, `VORTEX_SIGNING_STORE_FILE`, `VORTEX_SIGNING_STORE_PASSWORD`, `VORTEX_SIGNING_KEY_ALIAS`, `VORTEX_SIGNING_KEY_PASSWORD`.
- Use `VORTEX_SIGNING_STORE_BASE64` in CI when you want to inject the keystore directly from a secret.
- Use `VORTEX_SIGNING_STORE_FILE` when your runner mounts the keystore as a file.
- The included workflow at `.github/workflows/android-release.yml` builds a signed release APK and uploads it as an artifact.
- The included workflow at `.github/workflows/android-play-release.yml` supports both signed uploads and track promotion in Google Play.
- For Play uploads, add `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` and optionally `GOOGLE_PLAY_TRACK` as repository secrets.

### Production-Safe Play Flow

- `android-play-release.yml` is manual only and does not publish automatically on push.
- The workflow has two actions: `upload` builds a signed `.aab` and uploads it to a selected track, and `promote` moves an existing release from one track to another.
- The job environment is `play-<track>`. Configure required reviewers in GitHub environments such as `play-production` to add manual approval before sensitive releases.
- Exact recommended GitHub environment rules are documented in `docs/github-environments.md`.
- Default changelog metadata lives in `distribution/whatsnew/en-US/default.txt`, and the workflow can override it with the `release_notes` input.
- Recommended flow: upload to `internal`, validate there, then run the workflow again with `action=promote` and `source_track=internal` to move the same release to `beta` or `production`.

```bash
export VORTEX_SIGNING_STORE_BASE64="$(base64 -w0 keystore/release-signing.jks)"
export VORTEX_SIGNING_STORE_PASSWORD="your-store-password"
export VORTEX_SIGNING_KEY_ALIAS="vortex-release"
export VORTEX_SIGNING_KEY_PASSWORD="your-key-password"
./gradlew assembleRelease
```

## Requirements

- Android Studio Ladybug (2024.2+)
- JDK 17+
- Android SDK 35
- Kotlin 2.1+

## License

This project is for educational and personal use.

---

<p align="center">
  Built with Kotlin & Jetpack Compose
</p>

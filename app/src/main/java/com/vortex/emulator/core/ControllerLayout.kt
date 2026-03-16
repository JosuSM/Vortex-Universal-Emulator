package com.vortex.emulator.core

/**
 * Defines which on-screen controls to display based on the console being emulated.
 * Different platforms have different controller configurations:
 * - NES/SNES/Genesis: D-Pad + face buttons only (no analog)
 * - N64: D-Pad + analog stick + unique C-button cluster + Z trigger
 * - PSX/PS2: D-Pad + dual analog + L2/R2 shoulder triggers
 * - PSP: D-Pad + single analog + shoulder buttons
 * - NDS/3DS: D-Pad + face buttons + touch screen overlay
 * - GBA/GBC: D-Pad + A/B only (no X/Y)
 * - Dreamcast: D-Pad + analog + face buttons + triggers
 * - Saturn: D-Pad + 6 face buttons + shoulder buttons
 * - Arcade: D-Pad/stick + 6+ action buttons
 * - GameCube: D-Pad + analog + C-stick + triggers
 * - Wii: varies, but analog + D-Pad + motion-mapped buttons
 */

/** Describes which buttons/sticks are visible on the on-screen controller. */
data class ControllerLayout(
    val id: String,
    val displayName: String,
    val showDpad: Boolean = true,
    val showAnalogLeft: Boolean = false,
    val showAnalogRight: Boolean = false,
    val faceButtons: FaceButtonStyle = FaceButtonStyle.ABXY,
    val showShoulderL: Boolean = true,
    val showShoulderR: Boolean = true,
    val showShoulderL2: Boolean = false,
    val showShoulderR2: Boolean = false,
    val showSelect: Boolean = true,
    val showStart: Boolean = true,
    val showZTrigger: Boolean = false,
    val showTouchOverlay: Boolean = false,
)

/** Determines which face button labels and how many are shown. */
enum class FaceButtonStyle {
    /** 4 buttons: A, B, X, Y (SNES/N64/PSX-style) */
    ABXY,

    /** 2 buttons: A, B (NES/GB/GBA) */
    AB_ONLY,

    /** PSX style: Cross, Circle, Square, Triangle */
    PSX_SYMBOLS,

    /** 3 primary + 3 extra (Saturn 6-button, Arcade) */
    SIX_BUTTON,

    /** GameCube: big A, small B, X, Y */
    GAMECUBE,
}

/** Returns the appropriate controller layout for a given platform. */
fun Platform.defaultControllerLayout(): ControllerLayout = when (this) {
    Platform.NES -> ControllerLayout(
        id = "nes",
        displayName = "NES Controller",
        showDpad = true,
        faceButtons = FaceButtonStyle.AB_ONLY,
        showShoulderL = false,
        showShoulderR = false,
    )

    Platform.SNES -> ControllerLayout(
        id = "snes",
        displayName = "SNES Controller",
        showDpad = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
    )

    Platform.N64 -> ControllerLayout(
        id = "n64",
        displayName = "N64 Controller",
        showDpad = true,
        showAnalogLeft = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
        showZTrigger = true,
    )

    Platform.GBA -> ControllerLayout(
        id = "gba",
        displayName = "GBA Controls",
        showDpad = true,
        faceButtons = FaceButtonStyle.AB_ONLY,
        showShoulderL = true,
        showShoulderR = true,
        showSelect = true,
        showStart = true,
    )

    Platform.GBC -> ControllerLayout(
        id = "gbc",
        displayName = "Game Boy Controls",
        showDpad = true,
        faceButtons = FaceButtonStyle.AB_ONLY,
        showShoulderL = false,
        showShoulderR = false,
    )

    Platform.NDS -> ControllerLayout(
        id = "nds",
        displayName = "DS Controls",
        showDpad = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
        showTouchOverlay = true,
    )

    Platform.GENESIS -> ControllerLayout(
        id = "genesis",
        displayName = "Genesis 6-Button",
        showDpad = true,
        faceButtons = FaceButtonStyle.SIX_BUTTON,
        showShoulderL = false,
        showShoulderR = false,
    )

    Platform.PSX -> ControllerLayout(
        id = "psx",
        displayName = "PlayStation DualShock",
        showDpad = true,
        showAnalogLeft = true,
        showAnalogRight = true,
        faceButtons = FaceButtonStyle.PSX_SYMBOLS,
        showShoulderL = true,
        showShoulderR = true,
        showShoulderL2 = true,
        showShoulderR2 = true,
    )

    Platform.PSP -> ControllerLayout(
        id = "psp",
        displayName = "PSP Controls",
        showDpad = true,
        showAnalogLeft = true,
        faceButtons = FaceButtonStyle.PSX_SYMBOLS,
        showShoulderL = true,
        showShoulderR = true,
    )

    Platform.DREAMCAST -> ControllerLayout(
        id = "dreamcast",
        displayName = "Dreamcast Controller",
        showDpad = true,
        showAnalogLeft = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
    )

    Platform.ARCADE -> ControllerLayout(
        id = "arcade",
        displayName = "Arcade Stick",
        showDpad = true,
        faceButtons = FaceButtonStyle.SIX_BUTTON,
        showShoulderL = false,
        showShoulderR = false,
        showSelect = true,
        showStart = true,
    )

    Platform.GAMECUBE -> ControllerLayout(
        id = "gamecube",
        displayName = "GameCube Controller",
        showDpad = true,
        showAnalogLeft = true,
        showAnalogRight = true,
        faceButtons = FaceButtonStyle.GAMECUBE,
        showShoulderL = true,
        showShoulderR = true,
        showShoulderL2 = true,
        showShoulderR2 = true,
        showSelect = false,
        showStart = true,
    )

    Platform.WII -> ControllerLayout(
        id = "wii",
        displayName = "Wii Classic Controller",
        showDpad = true,
        showAnalogLeft = true,
        showAnalogRight = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
        showShoulderL2 = true,
        showShoulderR2 = true,
    )

    Platform.SATURN -> ControllerLayout(
        id = "saturn",
        displayName = "Saturn Controller",
        showDpad = true,
        faceButtons = FaceButtonStyle.SIX_BUTTON,
        showShoulderL = true,
        showShoulderR = true,
    )

    Platform.THREEDS -> ControllerLayout(
        id = "3ds",
        displayName = "3DS Controls",
        showDpad = true,
        showAnalogLeft = true,
        faceButtons = FaceButtonStyle.ABXY,
        showShoulderL = true,
        showShoulderR = true,
        showTouchOverlay = true,
    )

    Platform.PS2 -> ControllerLayout(
        id = "ps2",
        displayName = "PS2 DualShock 2",
        showDpad = true,
        showAnalogLeft = true,
        showAnalogRight = true,
        faceButtons = FaceButtonStyle.PSX_SYMBOLS,
        showShoulderL = true,
        showShoulderR = true,
        showShoulderL2 = true,
        showShoulderR2 = true,
    )

    Platform.VITA -> ControllerLayout(
        id = "vita",
        displayName = "PS Vita Controls",
        showDpad = true,
        showAnalogLeft = true,
        showAnalogRight = true,
        faceButtons = FaceButtonStyle.PSX_SYMBOLS,
        showShoulderL = true,
        showShoulderR = true,
        showTouchOverlay = true,
    )
}

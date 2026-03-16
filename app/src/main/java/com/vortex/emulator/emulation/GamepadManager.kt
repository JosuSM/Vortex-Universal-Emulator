package com.vortex.emulator.emulation

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detects physical gamepads/controllers and maps their input
 * to libretro button/analog IDs for the EmulationEngine.
 */
@Singleton
class GamepadManager @Inject constructor() {

    companion object {
        private const val TAG = "GamepadManager"
        private const val ANALOG_DEAD_ZONE = 0.15f
        private const val ANALOG_SCALE = 32767
    }

    var engine: EmulationEngine? = null
    var activePort: Int = 0

    /** True when at least one gamepad is connected. */
    val isGamepadConnected: Boolean
        get() = getGamepadDeviceIds().isNotEmpty()

    /** Return the name of the first connected gamepad, or null. */
    val connectedGamepadName: String?
        get() {
            val ids = getGamepadDeviceIds()
            if (ids.isEmpty()) return null
            return InputDevice.getDevice(ids.first())?.name
        }

    private fun getGamepadDeviceIds(): List<Int> {
        return InputDevice.getDeviceIds().filter { id ->
            val device = InputDevice.getDevice(id) ?: return@filter false
            val sources = device.sources
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
    }

    /**
     * Handle a key event from a physical controller.
     * Returns true if the event was consumed.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_GAMEPAD) == 0 &&
            (sources and InputDevice.SOURCE_JOYSTICK) == 0) return false

        val eng = engine ?: return false
        val retroButton = mapKeyToRetroButton(event.keyCode) ?: return false
        val pressed = event.action == KeyEvent.ACTION_DOWN

        eng.setButton(activePort, retroButton, pressed)
        return true
    }

    /**
     * Handle a motion event (analog sticks, triggers) from a physical controller.
     * Returns true if the event was consumed.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (sources and InputDevice.SOURCE_GAMEPAD) == 0) return false

        val eng = engine ?: return false

        // Left stick
        val lx = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Y))
        eng.setAnalog(activePort, 0, 0, (lx * ANALOG_SCALE).toInt())
        eng.setAnalog(activePort, 0, 1, (ly * ANALOG_SCALE).toInt())

        // Right stick
        val rx = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_RZ))
        eng.setAnalog(activePort, 1, 0, (rx * ANALOG_SCALE).toInt())
        eng.setAnalog(activePort, 1, 1, (ry * ANALOG_SCALE).toInt())

        // D-pad via axes (some controllers report dpad as hat axes)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_LEFT, hatX < -0.5f)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_RIGHT, hatX > 0.5f)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_UP, hatY < -0.5f)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_DOWN, hatY > 0.5f)

        // Triggers as digital L2/R2
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_L2, lt > 0.5f)
        eng.setButton(activePort, VortexNative.RETRO_DEVICE_ID_JOYPAD_R2, rt > 0.5f)

        return true
    }

    private fun applyDeadZone(value: Float): Float {
        return if (abs(value) < ANALOG_DEAD_ZONE) 0f else value
    }

    private fun mapKeyToRetroButton(keyCode: Int): Int? {
        return when (keyCode) {
            // Face buttons (standard Android → libretro mapping)
            KeyEvent.KEYCODE_BUTTON_A      -> VortexNative.RETRO_DEVICE_ID_JOYPAD_B
            KeyEvent.KEYCODE_BUTTON_B      -> VortexNative.RETRO_DEVICE_ID_JOYPAD_A
            KeyEvent.KEYCODE_BUTTON_X      -> VortexNative.RETRO_DEVICE_ID_JOYPAD_Y
            KeyEvent.KEYCODE_BUTTON_Y      -> VortexNative.RETRO_DEVICE_ID_JOYPAD_X

            // D-pad
            KeyEvent.KEYCODE_DPAD_UP       -> VortexNative.RETRO_DEVICE_ID_JOYPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT    -> VortexNative.RETRO_DEVICE_ID_JOYPAD_RIGHT

            // Menu buttons
            KeyEvent.KEYCODE_BUTTON_SELECT -> VortexNative.RETRO_DEVICE_ID_JOYPAD_SELECT
            KeyEvent.KEYCODE_BUTTON_START  -> VortexNative.RETRO_DEVICE_ID_JOYPAD_START
            KeyEvent.KEYCODE_BACK          -> VortexNative.RETRO_DEVICE_ID_JOYPAD_SELECT

            // Shoulder buttons
            KeyEvent.KEYCODE_BUTTON_L1     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_L
            KeyEvent.KEYCODE_BUTTON_R1     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_R
            KeyEvent.KEYCODE_BUTTON_L2     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_L2
            KeyEvent.KEYCODE_BUTTON_R2     -> VortexNative.RETRO_DEVICE_ID_JOYPAD_R2

            // Stick clicks
            KeyEvent.KEYCODE_BUTTON_THUMBL -> VortexNative.RETRO_DEVICE_ID_JOYPAD_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> VortexNative.RETRO_DEVICE_ID_JOYPAD_R3

            else -> null
        }
    }
}

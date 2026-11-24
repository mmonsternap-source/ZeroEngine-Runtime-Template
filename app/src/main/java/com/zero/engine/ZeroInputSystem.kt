package com.zero.engine

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

class ZeroInputSystem {
    data class TouchZone(
        val action: String,
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float
    )

    private val lock = Any()
    private val actionBindings = mutableMapOf<String, MutableSet<String>>()
    private val keyDownSet = mutableSetOf<String>()
    private val virtualActionDown = mutableSetOf<String>()
    private val activePointers = mutableMapOf<Int, Pair<Float, Float>>()
    private val touchZones = mutableListOf<TouchZone>()

    private var stickX = 0f
    private var stickY = 0f

    private var prevActionStates = mutableMapOf<String, Boolean>()
    private var currActionStates = mutableMapOf<String, Boolean>()
    private var frameStickX = 0f
    private var frameStickY = 0f

    init {
        resetDefaultBindings()
    }

    fun resetDefaultBindings() = synchronized(lock) {
        actionBindings.clear()
        actionBindings["move_left"] = mutableSetOf("A", "LEFT", "DPAD_LEFT", "STICK_LEFT")
        actionBindings["move_right"] = mutableSetOf("D", "RIGHT", "DPAD_RIGHT", "STICK_RIGHT")
        actionBindings["move_up"] = mutableSetOf("W", "UP", "DPAD_UP", "STICK_UP")
        actionBindings["move_down"] = mutableSetOf("S", "DOWN", "DPAD_DOWN", "STICK_DOWN")
        actionBindings["jump"] = mutableSetOf("SPACE", "K", "GAMEPAD_A")
        actionBindings["attack"] = mutableSetOf("J", "Z", "GAMEPAD_X")
        actionBindings["dash"] = mutableSetOf("SHIFT", "L", "X", "GAMEPAD_B", "GAMEPAD_R1")
        actionBindings["pause"] = mutableSetOf("ESC", "P", "GAMEPAD_START")
    }

    fun bindAction(action: String, keys: List<String>) = synchronized(lock) {
        if (action.isEmpty()) return@synchronized
        actionBindings[action] = keys.map { it.uppercase() }.toMutableSet()
    }

    fun setVirtualAction(action: String, pressed: Boolean) = synchronized(lock) {
        if (pressed) virtualActionDown.add(action) else virtualActionDown.remove(action)
    }

    fun bindTouchZone(action: String, cx: Float, cy: Float, w: Float, h: Float) = synchronized(lock) {
        touchZones.removeAll { it.action == action && abs(it.cx - cx) < 1f && abs(it.cy - cy) < 1f }
        touchZones.add(TouchZone(action, cx, cy, max(16f, w), max(16f, h)))
    }

    fun clearTouchZones() = synchronized(lock) {
        touchZones.clear()
    }

    fun getTouchZonesSnapshot(): List<TouchZone> = synchronized(lock) {
        touchZones.toList()
    }

    fun onKeyEvent(keyCode: Int, isDown: Boolean): Boolean {
        val name = mapAndroidKeyCode(keyCode) ?: return false
        synchronized(lock) {
            if (isDown) keyDownSet.add(name) else keyDownSet.remove(name)
        }
        return true
    }

    fun onGamepadMotion(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return false

        val deadzone = 0.22f
        val rawX = event.getAxisValue(MotionEvent.AXIS_X)
        val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val ax = if (abs(rawX) > deadzone) rawX else 0f
        val ay = if (abs(rawY) > deadzone) rawY else 0f

        synchronized(lock) {
            stickX = ax
            stickY = ay
            updateKeyFlag("STICK_LEFT", ax < -deadzone)
            updateKeyFlag("STICK_RIGHT", ax > deadzone)
            updateKeyFlag("STICK_UP", ay < -deadzone)
            updateKeyFlag("STICK_DOWN", ay > deadzone)
            updateKeyFlag("DPAD_LEFT", hatX < -0.5f)
            updateKeyFlag("DPAD_RIGHT", hatX > 0.5f)
            updateKeyFlag("DPAD_UP", hatY < -0.5f)
            updateKeyFlag("DPAD_DOWN", hatY > 0.5f)
        }
        return true
    }

    fun onMultiTouch(event: MotionEvent, viewW: Int, viewH: Int) {
        synchronized(lock) {
            val w = max(1, viewW).toFloat()
            val h = max(1, viewH).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = event.actionIndex
                    val pid = event.getPointerId(idx)
                    activePointers[pid] = ((event.getX(idx) / w) * 1080f) to ((event.getY(idx) / h) * 1920f)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        activePointers[pid] = ((event.getX(i) / w) * 1080f) to ((event.getY(i) / h) * 1920f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val idx = event.actionIndex
                    activePointers.remove(event.getPointerId(idx))
                }
                MotionEvent.ACTION_CANCEL -> {
                    activePointers.clear()
                }
                else -> Unit
            }
        }
    }

    fun clearAllPointers() = synchronized(lock) {
        activePointers.clear()
        virtualActionDown.clear()
    }

    fun beginFrame() = synchronized(lock) {
        prevActionStates.clear()
        prevActionStates.putAll(currActionStates)
        currActionStates.clear()

        frameStickX = stickX
        frameStickY = stickY

        val allActions = HashSet<String>(actionBindings.keys)
        allActions.addAll(virtualActionDown)
        for (z in touchZones) allActions.add(z.action)

        for (act in allActions) {
            var active = virtualActionDown.contains(act)
            if (!active) {
                val keys = actionBindings[act]
                if (keys != null) {
                    for (k in keys) {
                        if (keyDownSet.contains(k)) {
                            active = true
                            break
                        }
                    }
                }
            }
            if (!active && activePointers.isNotEmpty()) {
                for (z in touchZones) {
                    if (z.action != act) continue
                    val hw = z.w * 0.5f
                    val hh = z.h * 0.5f
                    for ((px, py) in activePointers.values) {
                        if (abs(px - z.cx) <= hw && abs(py - z.cy) <= hh) {
                            active = true
                            break
                        }
                    }
                    if (active) break
                }
            }
            currActionStates[act] = active
        }
    }

    fun isActionPressed(action: String): Boolean = currActionStates[action] == true

    fun isActionJustPressed(action: String): Boolean =
        currActionStates[action] == true && prevActionStates[action] != true

    fun isActionJustReleased(action: String): Boolean =
        currActionStates[action] != true && prevActionStates[action] == true

    fun getAxis(negAction: String, posAction: String): Float {
        if (negAction == "move_left" && posAction == "move_right" && abs(frameStickX) > 0.22f) {
            return frameStickX.coerceIn(-1f, 1f)
        }
        if (negAction == "move_up" && posAction == "move_down" && abs(frameStickY) > 0.22f) {
            return frameStickY.coerceIn(-1f, 1f)
        }
        var v = 0f
        if (isActionPressed(negAction)) v -= 1f
        if (isActionPressed(posAction)) v += 1f
        return v
    }

    fun getTouchCount(): Int = synchronized(lock) { activePointers.size }

    private fun updateKeyFlag(key: String, down: Boolean) {
        if (down) keyDownSet.add(key) else keyDownSet.remove(key)
    }

    private fun mapAndroidKeyCode(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        KeyEvent.KEYCODE_SPACE -> "SPACE"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ENTER"
        KeyEvent.KEYCODE_ESCAPE -> "ESC"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHIFT"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "CTRL"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_BUTTON_A -> "GAMEPAD_A"
        KeyEvent.KEYCODE_BUTTON_B -> "GAMEPAD_B"
        KeyEvent.KEYCODE_BUTTON_X -> "GAMEPAD_X"
        KeyEvent.KEYCODE_BUTTON_Y -> "GAMEPAD_Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "GAMEPAD_L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "GAMEPAD_R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "GAMEPAD_L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "GAMEPAD_R2"
        KeyEvent.KEYCODE_BUTTON_START -> "GAMEPAD_START"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "GAMEPAD_SELECT"
        else -> null
    }
}

package com.cortinadev.dogmatix.ui.common

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.focus.FocusRequester
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.util.PadDirection
import com.cortinadev.dogmatix.util.SelectHold
import kotlinx.coroutines.channels.BufferOverflow
import com.cortinadev.dogmatix.util.RemoteKey
import com.cortinadev.dogmatix.util.TvMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Buttons the app handles itself. A / B / D-pad go through the normal focus system.
 * ZL / ZR switch sections, LB / RB switch panels inside a screen, Select stars a game,
 * R3 collapses / expands the filter panel, Start (6.0) is a screen's own extra action (Library: surprise me).
 * 8.0: a short SELECT press fires on release; holding it opens the quick menu (see [Gamepad.quickMenuOpen]).
 */
enum class GamepadButton { PREV_TAB, NEXT_TAB, PREV_PANEL, NEXT_PANEL, X, Y, FAVOURITE, TOGGLE_FILTERS, FOCUS_TAB, START }

/**
 * Process-wide gamepad state: whether one is connected (drives the button legend)
 * and a bus for the shoulder / X / Y presses that screens react to.
 */
object Gamepad {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val presses = MutableSharedFlow<GamepadButton>(extraBufferCapacity = 8)

    /** One requester per section tab (keyed by route) so focus can be handed to the active tab. */
    val tabFocus: Map<String, FocusRequester> = mapOf(
        "home" to FocusRequester(), "downloads" to FocusRequester(),
        "sources" to FocusRequester(), "settings" to FocusRequester()
    )
    var currentRoute: String = "home"

    /** Requester of the active section tab (screens hand focus back to the header with B). */
    val sectionFocus: FocusRequester get() = tabFocus[currentRoute] ?: tabFocus.getValue("home")

    /**
     * A screen can publish a context-specific button legend here (null = the section default).
     * Wrapped in [Legend] (identity equality) on purpose: StateFlow conflates equal values, and two
     * screens with the same entries (Settings and RomM) would otherwise share one stored reference
     * and the outgoing screen's onDispose would wipe the incoming one's legend.
     */
    val legendOverride = MutableStateFlow<Legend?>(null)

    /** How the face and shoulder buttons are named in the legend (Settings); actions never change. */
    val layout = MutableStateFlow(GamepadLayout.XBOX)

    /** Settings: the pad reports its face buttons the other way round, so A/B and X/Y are swapped. */
    val swapFaceButtons = MutableStateFlow(false)

    /** Guards [remap] against re-entering itself when a swapped event is dispatched again. */
    private var remapping = false

    /**
     * The event the app should act on, with the swap setting applied (see [swapFaceKeyCode]).
     * Returns [event] itself when nothing changes, so callers can compare by identity.
     */
    fun remap(event: KeyEvent): KeyEvent {
        if (!swapFaceButtons.value || remapping) return event
        val keyCode = swapFaceKeyCode(event.keyCode)
        if (keyCode == event.keyCode) return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, keyCode, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source
        )
    }

    /** Runs [block] with [remap] disabled: the event it dispatches is already swapped. */
    fun withoutRemapping(block: () -> Unit) {
        remapping = true
        try { block() } finally { remapping = false }
    }

    private var listener: InputManager.InputDeviceListener? = null

    fun startWatching(context: Context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        refresh()
        if (listener == null) {
            listener = object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refresh()
                override fun onInputDeviceRemoved(deviceId: Int) = refresh()
                override fun onInputDeviceChanged(deviceId: Int) = refresh()
            }.also { manager.registerInputDeviceListener(it, null) }
        }
    }

    fun refresh() {
        _connected.value = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { isGamepad(it) } == true
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        val pad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val stick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return (pad || stick) && !device.isVirtual
    }

    /**
     * After a section switch the focus ring is hidden; the next D-pad press only brings it
     * back (on the active tab) instead of moving anything. Set by the shell.
     */
    val pointerHidden = MutableStateFlow(false)
    private var swallowNextUp = false

    /** Call from Activity.dispatchKeyEvent, before the views see the key. */
    fun interceptKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) return interceptSelect(event)
        if (event.action == KeyEvent.ACTION_UP && menuSwallowUps.remove(event.keyCode)) return true
        if (quickMenuOpen.value) return interceptMenuKey(event)
        // Shortcut buttons are consumed entirely (down and up): if their key-up reached Compose
        // with nothing focused, it would re-initialise focus on the first tab.
        val shortcut = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.PREV_TAB
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.NEXT_TAB
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.PREV_PANEL
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.NEXT_PANEL
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButton.FAVOURITE
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButton.TOGGLE_FILTERS
            KeyEvent.KEYCODE_BUTTON_START -> GamepadButton.START
            else -> remoteShortcut(event.keyCode)
        }
        if (shortcut != null) {
            if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) _connected.value = true
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) presses.tryEmit(shortcut)
            return true
        }
        val dpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> true
            else -> false
        }
        if (!dpad) return false
        if (event.action == KeyEvent.ACTION_DOWN && pointerHidden.value) {
            pointerHidden.value = false
            swallowNextUp = true
            presses.tryEmit(GamepadButton.FOCUS_TAB)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && swallowNextUp) {
            swallowNextUp = false
            return true
        }
        return false
    }

    // ---- 8.0: quick menu (hold SELECT) ----

    /** Whether the quick menu is showing; the overlay sets it back to false when it closes. */
    val quickMenuOpen = MutableStateFlow(false)

    /** D-pad / A / release input while the menu is open. */
    val quickMenuEvents = MutableSharedFlow<QuickMenuEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Latest left stick position while the menu is open (null right after it opens). */
    val quickMenuStick = MutableStateFlow<StickVector?>(null)

    private val selectHold = SelectHold()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val holdCheck = Runnable { if (selectHold.poll(SystemClock.uptimeMillis()) == SelectHold.Outcome.OPEN_MENU) openQuickMenu() }

    /** Keys whose down the menu ate: their up is swallowed too, even after the menu has closed. */
    private val menuSwallowUps = mutableSetOf<Int>()

    fun openQuickMenu() {
        quickMenuStick.value = null
        quickMenuOpen.value = true
    }

    fun closeQuickMenu() { quickMenuOpen.value = false }

    /** SELECT: short press = favourite (on release), hold = quick menu. Always consumed. */
    private fun interceptSelect(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) _connected.value = true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (selectHold.down(event.eventTime, quickMenuOpen.value) == SelectHold.Outcome.CLOSE_MENU) closeQuickMenu()
                    else mainHandler.postDelayed(holdCheck, SelectHold.HOLD_MS)
                } else if (selectHold.poll(event.eventTime) == SelectHold.Outcome.OPEN_MENU) {
                    mainHandler.removeCallbacks(holdCheck)
                    openQuickMenu()
                }
            }
            KeyEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(holdCheck)
                if (event.isCanceled) { selectHold.cancel(); return true }
                when (selectHold.up(event.eventTime)) {
                    SelectHold.Outcome.SHORT_PRESS -> presses.tryEmit(GamepadButton.FAVOURITE)
                    SelectHold.Outcome.OPEN_MENU -> openQuickMenu()
                    SelectHold.Outcome.RELEASE -> quickMenuEvents.tryEmit(QuickMenuEvent.Release)
                    else -> Unit
                }
            }
        }
        return true
    }

    /** While the menu is open it takes the D-pad, A and B; other pad buttons do nothing underneath it. */
    private fun interceptMenuKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> PadDirection.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadDirection.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadDirection.RIGHT
            else -> null
        }
        when {
            direction != null -> if (down) quickMenuEvents.tryEmit(QuickMenuEvent.Move(direction))
            event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) quickMenuEvents.tryEmit(QuickMenuEvent.Activate)
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> if (down) closeQuickMenu()
            KeyEvent.isGamepadButton(event.keyCode) -> Unit
            else -> return false
        }
        if (down) menuSwallowUps += event.keyCode
        return true
    }

    private var lastHat: PadDirection? = null

    /**
     * 8.0: a TV remote's extra keys as gamepad shortcuts (see [TvMode.remoteKey]). Menu and Search
     * act only in the library (details, search): elsewhere X and Y do things a stray remote key
     * should not (X deletes in Downloads).
     */
    private fun remoteShortcut(keyCode: Int): GamepadButton? = when (TvMode.remoteKey(keyCode)) {
        RemoteKey.PREV_SECTION -> GamepadButton.PREV_TAB
        RemoteKey.NEXT_SECTION -> GamepadButton.NEXT_TAB
        RemoteKey.DETAILS -> GamepadButton.X.takeIf { currentRoute == "home" }
        RemoteKey.SEARCH -> GamepadButton.Y.takeIf { currentRoute == "home" }
        null -> null
    }

    private var leftTriggerDown = false
    private var rightTriggerDown = false

    /** Some pads report ZL / ZR only as analog axes; treat crossing half travel as a press. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        if (quickMenuOpen.value) {
            // The left stick points at a slice. Consumed, so the system does not also turn it into D-pad keys.
            quickMenuStick.value = StickVector(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
            // Pads whose D-pad is a hat: consuming the event stops the system making D-pad keys of it, so step here.
            val hat = when {
                event.getAxisValue(MotionEvent.AXIS_HAT_Y) < -0.5f -> PadDirection.UP
                event.getAxisValue(MotionEvent.AXIS_HAT_Y) > 0.5f -> PadDirection.DOWN
                event.getAxisValue(MotionEvent.AXIS_HAT_X) < -0.5f -> PadDirection.LEFT
                event.getAxisValue(MotionEvent.AXIS_HAT_X) > 0.5f -> PadDirection.RIGHT
                else -> null
            }
            if (hat != null && hat != lastHat) quickMenuEvents.tryEmit(QuickMenuEvent.Move(hat))
            lastHat = hat
            return true
        }
        lastHat = null
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (left && !leftTriggerDown) presses.tryEmit(GamepadButton.PREV_TAB)
        if (right && !rightTriggerDown) presses.tryEmit(GamepadButton.NEXT_TAB)
        leftTriggerDown = left
        rightTriggerDown = right
        return false
    }
}

/** 8.0: input for the open quick menu (see [Gamepad.quickMenuEvents]). */
sealed interface QuickMenuEvent {
    data class Move(val direction: PadDirection) : QuickMenuEvent
    /** A (or Enter): activate the selected slice. */
    data object Activate : QuickMenuEvent
    /** The SELECT press that opened the menu was let go: activate the selected slice, if there is one. */
    data object Release : QuickMenuEvent
}

/** Left stick position (x right, y down, each -1..1). */
data class StickVector(val x: Float, val y: Float)

/** One published legend; plain class so two lists with equal entries are still distinct. */
class Legend(val entries: List<LegendEntry>)

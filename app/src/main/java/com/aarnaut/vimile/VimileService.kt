package com.aarnaut.vimile

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The core of Vimile. Android calls [onKeyEvent] for every hardware key press
 * before the foreground app sees it. Returning true swallows the key; returning
 * false lets it through to the app.
 *
 * Normal mode commands (see README for the full table):
 *   f / Shift+F    hints: tap / long-press
 *   j k            scroll down / up        d u   page down / up
 *   h l            scroll left / right     x     dismiss (tap outside)
 *   Shift+H        back
 *   gh gr gn gq    home, recent apps, notifications, quick settings
 *   i              insert mode (all keys go to the app) until Esc
 */
class VimileService : AccessibilityService() {

    private enum class Mode { NORMAL, INSERT, HINTS }

    private var mode = Mode.NORMAL

    /** Key codes whose key-down we swallowed and whose key-up we still owe a swallow. */
    private val consumedKeys = mutableSetOf<Int>()

    /** True after `g`, while waiting for the second key of a `g` command. */
    private var pendingG = false

    private lateinit var overlay: HintOverlay
    private lateinit var nav: Navigator
    private var hints: List<Hint> = emptyList()
    private var typed = ""
    private var longPress = false

    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        Log.i(TAG, "Vimile connected")
        overlay = HintOverlay(this)
        nav = Navigator(this)
        registerDebugReceiver()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hide()
        debugReceiver?.let(::unregisterReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A new screen or dialog makes the hints point at the wrong things.
        if (mode == Mode.HINTS &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName != packageName
        ) {
            exitHints()
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        Log.d(
            TAG,
            "key ${KeyEvent.keyCodeToString(event.keyCode)} down=$isDown " +
                "meta=0x${Integer.toHexString(event.metaState)} mode=$mode"
        )

        // Commands run on key-down. A key whose down we swallowed must have its up swallowed
        // too, whatever the modifiers say by then: the emulator releases Shift *before* the
        // letter, so Shift+H arrives as SHIFT↓ H↓ SHIFT↑ H↑.
        if (!isDown) return consumedKeys.remove(event.keyCode)

        val consumed = handleKeyDown(
            keyCode = event.keyCode,
            shift = event.isShiftPressed,
            otherModifiers = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
            firstPress = event.repeatCount == 0,
            char = event.getUnicodeChar(0).toChar().lowercaseChar(),
        )
        if (consumed) consumedKeys.add(event.keyCode)
        return consumed
    }

    /** Returns true if Vimile handled (and so swallows) this key press. */
    private fun handleKeyDown(
        keyCode: Int,
        shift: Boolean,
        otherModifiers: Boolean,
        firstPress: Boolean,
        char: Char,
    ): Boolean {
        if (mode == Mode.HINTS) {
            // Modifier shortcuts cancel hints and go through; every other key belongs to us.
            if (otherModifiers) {
                exitHints()
                return false
            }
            if (firstPress) handleHintKey(keyCode, char)
            return true
        }

        // Let anything with Ctrl/Alt/Meta through so app and system shortcuts keep working.
        if (otherModifiers) return false

        if (mode == Mode.INSERT) {
            // The emulator turns the Mac's Esc key into BACK, so accept either to leave
            // insert mode. ESCAPE is swallowed; BACK still goes through and does its usual job.
            when (keyCode) {
                KeyEvent.KEYCODE_ESCAPE -> { setMode(Mode.NORMAL); return true }
                KeyEvent.KEYCODE_BACK -> setMode(Mode.NORMAL)
            }
            return false
        }

        if (isEditableFocused()) {
            pendingG = false
            return false
        }

        // Shift on its own must not cancel a pending `g` (Shift+…) or do anything else.
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            return false
        }

        if (pendingG) {
            pendingG = false
            val command: (() -> Unit)? = when (keyCode) {
                KeyEvent.KEYCODE_H -> { { performGlobalAction(GLOBAL_ACTION_HOME) } }
                KeyEvent.KEYCODE_R -> { { performGlobalAction(GLOBAL_ACTION_RECENTS) } }
                KeyEvent.KEYCODE_N -> { { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) } }
                KeyEvent.KEYCODE_Q -> { { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) } }
                else -> null
            }
            Log.i(TAG, "g + ${KeyEvent.keyCodeToString(keyCode)} -> ${command != null}")
            if (firstPress) command?.invoke()
            return true // Swallow the key after `g` even if it isn't a command.
        }

        val command: (() -> Unit)? = when (keyCode) {
            KeyEvent.KEYCODE_I -> { { setMode(Mode.INSERT) } }
            KeyEvent.KEYCODE_F -> { { enterHints(longPress = shift) } }
            KeyEvent.KEYCODE_J -> { { nav.scroll(Direction.DOWN, SCROLL_STEP) } }
            KeyEvent.KEYCODE_K -> { { nav.scroll(Direction.UP, SCROLL_STEP) } }
            KeyEvent.KEYCODE_L -> { { nav.scroll(Direction.RIGHT, SCROLL_STEP) } }
            KeyEvent.KEYCODE_H -> if (shift) {
                { performGlobalAction(GLOBAL_ACTION_BACK) }
            } else {
                { nav.scroll(Direction.LEFT, SCROLL_STEP) }
            }
            KeyEvent.KEYCODE_D -> { { nav.page(forward = true) } }
            KeyEvent.KEYCODE_U -> { { nav.page(forward = false) } }
            KeyEvent.KEYCODE_X -> { { Log.i(TAG, "dismiss: ${nav.dismiss()}") } }
            KeyEvent.KEYCODE_G -> if (shift) null else { { pendingG = true } }
            else -> null
        }
        if (command == null) return false
        // Scrolling repeats while the key is held; everything else fires once.
        val repeats = keyCode in REPEATING_KEYS && !shift
        if (firstPress || repeats) command()
        return true
    }

    // ---- Hints ---------------------------------------------------------------------------

    private fun enterHints(longPress: Boolean) {
        val minSizePx = (MIN_TARGET_DP * resources.displayMetrics.density).toInt()
        val targets = if (windows.isNotEmpty()) {
            HintTargets.collect(windows, minSizePx)
        } else {
            rootInActiveWindow?.let { HintTargets.collect(it, minSizePx) }.orEmpty()
        }
        if (targets.isEmpty()) {
            toast("Vimile: nothing to tap here")
            return
        }
        val labels = HintLabels.generate(targets.size)
        hints = targets.mapIndexed { i, (node, bounds) -> Hint(labels[i], node, bounds) }
        typed = ""
        this.longPress = longPress
        setMode(Mode.HINTS)
        overlay.show(hints, typed)
        Log.i(TAG, "showing ${hints.size} hints (longPress=$longPress)")
        hints.forEach { h ->
            Log.d(
                TAG,
                "hint ${h.label} ${h.node.packageName} ${h.node.className} " +
                    "id=${h.node.viewIdResourceName} bounds=${h.bounds}"
            )
        }
    }

    private fun exitHints() {
        overlay.hide()
        hints = emptyList()
        typed = ""
        setMode(Mode.NORMAL)
    }

    private fun handleHintKey(keyCode: Int, char: Char) {
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { exitHints(); return }
            KeyEvent.KEYCODE_DEL -> {
                if (typed.isEmpty()) exitHints() else {
                    typed = typed.dropLast(1)
                    overlay.show(hints, typed)
                }
                return
            }
        }
        if (char in HintLabels.CHARS) typeHintChar(char)
    }

    private fun typeHintChar(ch: Char) {
        val candidate = typed + ch
        val matches = hints.filter { it.label.startsWith(candidate) }
        when {
            matches.isEmpty() -> return // Not a valid next letter; ignore it.
            matches.size == 1 && matches[0].label == candidate -> {
                val hint = matches[0]
                exitHints()
                activate(hint)
            }
            else -> {
                typed = candidate
                overlay.show(hints, typed)
            }
        }
    }

    /** Clicks (or long-clicks) the element, falling back to a real tap at its centre. */
    private fun activate(hint: Hint) {
        val node = hint.node
        node.refresh()
        val action = if (longPress) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }
        if (node.isEditable) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clicked = node.performAction(action)
        Log.i(TAG, "activate ${hint.label} action=$action ok=$clicked bounds=${hint.bounds}")
        if (!clicked) nav.tap(hint.bounds.exactCenterX(), hint.bounds.exactCenterY(), longPress)
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        val previous = mode
        mode = newMode
        pendingG = false
        Log.i(TAG, "mode -> $newMode")
        // Hints are their own feedback; only announce insert/normal switches.
        when {
            newMode == Mode.INSERT -> toast("Vimile: insert mode")
            newMode == Mode.NORMAL && previous == Mode.INSERT -> toast("Vimile: normal mode")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun isEditableFocused(): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.isEditable == true
    }

    /**
     * Debug builds only: feeds key presses from the terminal through the same handler real
     * keys use, because adb-injected key presses never reach accessibility services.
     * Lowercase letters are plain keys, uppercase letters are Shift+key, `.` is Esc (shell-safe).
     *   adb shell am broadcast -a com.aarnaut.vimile.DEBUG --es keys f      (show hints)
     *   adb shell am broadcast -a com.aarnaut.vimile.DEBUG --es keys gn     (open notifications)
     */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("keys").orEmpty().forEach { c ->
                    val keyCode = when {
                        c == '.' -> KeyEvent.KEYCODE_ESCAPE
                        c.isLetter() -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
                        else -> return@forEach
                    }
                    Log.d(TAG, "debug key '$c'")
                    handleKeyDown(keyCode, c.isUpperCase(), false, true, c.lowercaseChar())
                }
            }
        }
        // Exported so `adb shell am broadcast` can reach it. The three-argument overload exists
        // since API 26 (our minSdk); before API 33 the flag is simply ignored.
        @Suppress("InlinedApi")
        registerReceiver(receiver, IntentFilter(DEBUG_ACTION), RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    companion object {
        private const val TAG = "Vimile"
        private const val DEBUG_ACTION = "com.aarnaut.vimile.DEBUG"
        private const val MIN_TARGET_DP = 10
        /** j/k/h/l swipe this share of the scrollable area. */
        private const val SCROLL_STEP = 0.4f
        private val REPEATING_KEYS = setOf(
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_L,
        )
    }
}

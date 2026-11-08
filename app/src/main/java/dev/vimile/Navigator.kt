package dev.vimile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Everything that isn't a click: scrolling, swiping and dismissing. */
class Navigator(private val service: AccessibilityService) {

    private val density = service.resources.displayMetrics.density

    // ---- Taps ----------------------------------------------------------------------------

    fun tap(x: Float, y: Float, long: Boolean = false) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, if (long) 600L else 50L)
    }

    // ---- Scrolling -----------------------------------------------------------------------

    /**
     * Scrolls the content by swiping a finger across the main scrollable area (or the
     * middle of the screen if there is none). [direction] is the way the *content view*
     * moves through the content: DOWN shows what is below, so the finger moves up.
     * [fraction] is how far to swipe, as a share of the area's height or width.
     */
    fun scroll(direction: Direction, fraction: Float) {
        val area = scrollArea(direction)
        val margin = (EDGE_MARGIN_DP * density).toInt()
        val safe = Rect(area).apply { inset(margin, margin) }
        if (safe.isEmpty) return

        val cx = safe.exactCenterX()
        val cy = safe.exactCenterY()
        val dy = (safe.height() * fraction / 2).coerceAtMost(safe.height() / 2f)
        val dx = (safe.width() * fraction / 2).coerceAtMost(safe.width() / 2f)
        val path = Path()
        when (direction) {
            Direction.DOWN -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            Direction.UP -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            Direction.RIGHT -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            Direction.LEFT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        Log.d(TAG, "scroll $direction in $area")
        dispatch(path, SCROLL_DURATION_MS)
    }

    /**
     * Scrolls a whole page using the list's own accessibility action, which moves exactly
     * one page with no fling. Falls back to a long swipe.
     */
    fun page(forward: Boolean) {
        val node = mainScrollable(vertical = true)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (node?.performAction(action) == true) return
        scroll(if (forward) Direction.DOWN else Direction.UP, 0.8f)
    }

    private fun scrollArea(direction: Direction): Rect {
        val vertical = direction == Direction.UP || direction == Direction.DOWN
        mainScrollable(vertical)?.let { node ->
            return Rect().also { node.getBoundsInScreen(it) }
        }
        val size = screenSize()
        return Rect(0, 0, size.x, size.y)
    }

    /**
     * The largest visible scrollable element in the focused window. For horizontal
     * scrolling it prefers elements that say they scroll left/right (pagers, carousels);
     * for vertical scrolling it skips those that only scroll sideways.
     */
    private fun mainScrollable(vertical: Boolean): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (node.isScrollable) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                candidates += node to bounds.width() * bounds.height()
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)

        val sideways = { n: AccessibilityNodeInfo ->
            n.actionList.any {
                it.id == AccessibilityAction.ACTION_SCROLL_LEFT.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_RIGHT.id
            }
        }
        val upDown = { n: AccessibilityNodeInfo ->
            n.actionList.any {
                it.id == AccessibilityAction.ACTION_SCROLL_UP.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_DOWN.id
            }
        }
        val preferred = if (vertical) {
            candidates.filter { (n, _) -> !sideways(n) || upDown(n) }
        } else {
            candidates.filter { (n, _) -> sideways(n) }
        }
        return (preferred.ifEmpty { candidates }).maxByOrNull { it.second }?.first
    }

    // ---- Dismissing ----------------------------------------------------------------------

    /**
     * Closes whatever is on top (bottom sheet, dialog, menu) the way a tap outside it would.
     * Tries, in order:
     *  1. a full-window "scrim" behind a bottom sheet (Material's touch_outside view, or
     *     Compose's scrim), which closes the sheet when clicked;
     *  2. an element that offers the accessibility "dismiss" action;
     *  3. if the focused window is smaller than the screen (a dialog or popup), a real tap
     *     just outside it;
     *  4. Back.
     * Returns a short description of what it did, for the log.
     */
    fun dismiss(): String {
        val root = service.rootInActiveWindow
            ?: return "back (no window)".also { back() }
        // The notification shade and quick settings belong to System UI. Clicking its
        // background claims success but leaves the shade open, so close it directly.
        if (root.packageName == SYSTEM_UI) {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else {
                AccessibilityService.GLOBAL_ACTION_BACK
            }
            service.performGlobalAction(action)
            return "closed system UI (shade)"
        }

        val windowBounds = Rect()
        root.window?.getBoundsInScreen(windowBounds) ?: root.getBoundsInScreen(windowBounds)

        findNode(root) { n ->
            n.isVisibleToUser && n.isClickable && (
                n.viewIdResourceName?.endsWith(":id/touch_outside") == true ||
                    Rect().also { r -> n.getBoundsInScreen(r) } == windowBounds
                )
        }?.let { scrim ->
            if (scrim.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "clicked scrim ${scrim.viewIdResourceName ?: scrim.className}"
            }
        }

        findNode(root) { n ->
            n.isVisibleToUser && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
        }?.let { node ->
            if (node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                return "dismiss action on ${node.className}"
            }
        }

        val screen = screenSize()
        val gap = (OUTSIDE_TAP_GAP_DP * density)
        val statusBar = statusBarHeight()
        val isPopup = windowBounds.height() < screen.y - statusBar - gap ||
            windowBounds.width() < screen.x - gap
        if (isPopup) {
            val x = windowBounds.exactCenterX()
            val above = windowBounds.top - gap
            val below = windowBounds.bottom + gap
            when {
                above > statusBar + gap -> { tap(x, above); return "tapped above popup $windowBounds" }
                below < screen.y - gap -> { tap(x, below); return "tapped below popup $windowBounds" }
            }
        }

        back()
        return "back"
    }

    private fun back() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    @Suppress("DEPRECATION")
    fun screenSize(): Point {
        val wm = service.getSystemService(WindowManager::class.java)
        return Point().also { wm.defaultDisplay.getRealSize(it) }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * density).toInt()
    }

    companion object {
        private const val TAG = "Vimile"
        private const val SYSTEM_UI = "com.android.systemui"
        /** Keep swipes away from screen edges, where they'd trigger system gestures. */
        private const val EDGE_MARGIN_DP = 48
        private const val SCROLL_DURATION_MS = 250L
        private const val OUTSIDE_TAP_GAP_DP = 24
    }
}

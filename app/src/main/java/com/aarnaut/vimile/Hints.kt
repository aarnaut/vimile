package com.aarnaut.vimile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One on-screen hint: the letters to type, the element it targets, and where it is. */
data class Hint(
    val label: String,
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
)

object HintLabels {
    /** Home-row-first alphabet, the same idea as Vimium's default. */
    const val CHARS = "sadfjklewcmpgh"

    /**
     * Returns [count] labels, all the same length so none is a prefix of another.
     * The first letter varies fastest, so two-letter labels start with a spread of
     * different letters and you can usually narrow down after one keystroke.
     */
    fun generate(count: Int): List<String> {
        if (count <= 0) return emptyList()
        var length = 1
        var capacity = CHARS.length
        while (capacity < count) {
            length++
            capacity *= CHARS.length
        }
        return (0 until count).map { index ->
            var n = index
            buildString {
                repeat(length) {
                    append(CHARS[n % CHARS.length])
                    n /= CHARS.length
                }
            }
        }
    }
}

object HintTargets {
    /**
     * Collects targets from every window on screen (app, dialogs, status bar, notification
     * shade, keyboard), topmost first. A target whose centre is covered by a window above
     * it is skipped, so the app behind a dialog or the open shade gets no hints.
     */
    fun collect(
        windows: List<AccessibilityWindowInfo>,
        minSizePx: Int,
    ): List<Pair<AccessibilityNodeInfo, Rect>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val covering = mutableListOf<Rect>()
        val ordered = windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
        val screen = Rect()
        ordered.forEach { w -> screen.union(Rect().also { w.getBoundsInScreen(it) }) }
        // Full-screen clickables are backgrounds and scrims (`x` dismisses those);
        // a hint on them just sits in the top-left corner and confuses.
        val maxArea = screen.width().toLong() * screen.height() * 9 / 10

        for (window in ordered) {
            val root = window.root ?: continue
            val windowBounds = Rect().also { window.getBoundsInScreen(it) }
            result += collect(root, minSizePx).filter { (_, bounds) ->
                bounds.width().toLong() * bounds.height() < maxArea &&
                    covering.none { it.contains(bounds.centerX(), bounds.centerY()) }
            }
            covering += windowBounds
        }
        return result
    }

    /**
     * Walks the window's UI tree and returns elements worth a hint: anything visible
     * (at least [minSizePx] on each side) that can be clicked, long-clicked or typed
     * into. Elements with exactly
     * the same bounds as one already found (a clickable row wrapping a clickable
     * child, say) are skipped so they don't get stacked labels.
     */
    fun collect(root: AccessibilityNodeInfo, minSizePx: Int): List<Pair<AccessibilityNodeInfo, Rect>> {
        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val result = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val seen = mutableSetOf<Rect>()

        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            // Checkable-but-not-clickable nodes (a radio button inside a clickable row) are
            // left out: the row already has a hint and clicking it toggles the button.
            val actionable = node.isClickable || node.isLongClickable || node.isEditable
            if (actionable) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                // Skip slivers, e.g. a list row scrolled almost entirely off screen.
                if (bounds.intersect(windowBounds) && bounds.width() >= minSizePx &&
                    bounds.height() >= minSizePx && seen.add(Rect(bounds))
                ) {
                    result += node to bounds
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::visit)
            }
        }

        visit(root)
        return result
    }
}

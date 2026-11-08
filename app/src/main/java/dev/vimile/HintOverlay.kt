package dev.vimile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A full-screen, see-through, untouchable window drawn above every app. Only an
 * accessibility service may add a TYPE_ACCESSIBILITY_OVERLAY window, and it needs
 * no extra "draw over other apps" permission.
 */
class HintOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = HintView(context)
    private var attached = false

    fun show(hints: List<Hint>, typed: String) {
        view.update(hints, typed)
        if (!attached) {
            windowManager.addView(view, layoutParams())
            attached = true
        }
    }

    fun hide() {
        if (attached) {
            windowManager.removeView(view)
            attached = false
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}

/** Draws a small yellow badge at the top-left corner of each hinted element. */
private class HintView(context: Context) : View(context) {

    private var hints: List<Hint> = emptyList()
    private var typed = ""

    private val density = resources.displayMetrics.density
    private val padH = 4 * density
    private val padV = 1.5f * density
    private val corner = 3 * density
    private val gap = 2 * density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = 0xFF1A1A1A.toInt()
    }
    private val typedPaint = Paint(textPaint).apply { color = 0xFFA07800.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = 0xFF8D6E00.toInt()
    }

    private val location = IntArray(2)
    private val badge = RectF()

    fun update(hints: List<Hint>, typed: String) {
        this.hints = hints
        this.typed = typed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Bounds are in screen coordinates; the window may not start at (0, 0).
        getLocationOnScreen(location)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val placed = mutableListOf<RectF>()

        for (hint in hints) {
            if (!hint.label.startsWith(typed)) continue
            val label = hint.label.uppercase()
            val done = label.substring(0, typed.length)
            val rest = label.substring(typed.length)

            val left = (hint.bounds.left - location[0]).toFloat()
            val top = (hint.bounds.top - location[1]).toFloat()
            badge.set(left, top, left + textPaint.measureText(label) + 2 * padH, top + textHeight + 2 * padV)
            // Nested elements often share a top-left corner; slide right past earlier badges.
            while (placed.any { RectF.intersects(it, badge) }) {
                badge.offset(badge.width() + gap, 0f)
            }
            if (badge.right > width) badge.offset(width - badge.right, 0f)
            if (badge.bottom > height) badge.offset(0f, height - badge.bottom)
            placed += RectF(badge)

            canvas.drawRoundRect(badge, corner, corner, fillPaint)
            canvas.drawRoundRect(badge, corner, corner, strokePaint)

            val baseline = badge.top + padV - metrics.ascent
            val x = badge.left + padH
            canvas.drawText(done, x, baseline, typedPaint)
            canvas.drawText(rest, x + typedPaint.measureText(done), baseline, textPaint)
        }
    }
}

package me.timschneeberger.rootlessjamesdsp.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Two octaves of piano keys (C to C to C, 25 keys). Each key maps to a pitch-shift interval via
 * [intervalForKey] (key index 0 = left C ... 24 = right C). Touching or sliding over keys calls
 * [onKey] once per newly touched key; keys whose interval equals [activeInterval] are highlighted.
 */
@SuppressLint("ViewConstructor")
class PianoKeyboardView(
    context: Context,
    private val intervalForKey: (Int) -> Int,
    private val onKey: (Int) -> Unit,
    private val colors: Colors,
) : View(context) {

    data class Colors(
        val white: Int,
        val black: Int,
        val outline: Int,
        val active: Int,
        val activeText: Int,
        val label: Int,
    )

    var activeInterval: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = context.resources.displayMetrics.density
    private val whiteKeys = (0 until KEY_COUNT).filter { !isBlack(it) }
    private val blackKeys = (0 until KEY_COUNT).filter { isBlack(it) }
    private val keyRects = Array(KEY_COUNT) { RectF() }
    private var touchedKey = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val whiteWidth = w.toFloat() / whiteKeys.size
        val blackWidth = whiteWidth * 0.6f
        val blackHeight = h * 0.6f
        whiteKeys.forEachIndexed { i, key ->
            keyRects[key].set(i * whiteWidth, 0f, (i + 1) * whiteWidth, h.toFloat())
        }
        blackKeys.forEach { key ->
            // centered on the boundary between the white keys left and right of it
            val boundary = keyRects[key - 1].right
            keyRects[key].set(boundary - blackWidth / 2, 0f, boundary + blackWidth / 2, blackHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 3f * density
        stroke.color = colors.outline
        for (key in whiteKeys) {
            val r = keyRects[key]
            val active = intervalForKey(key) == activeInterval
            fill.color = if (active) colors.active else colors.white
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
            // Interval labels on the C keys
            if (key % 12 == 0) {
                text.color = if (active) colors.activeText else colors.label
                canvas.drawText(formatInterval(intervalForKey(key)), r.centerX(), r.bottom - 6f * density, text)
            }
        }
        for (key in blackKeys) {
            val r = keyRects[key]
            fill.color = if (intervalForKey(key) == activeInterval) colors.active else colors.black
            canvas.drawRoundRect(r, radius, radius, fill)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val key = keyAt(event.x, event.y)
                if (key >= 0 && key != touchedKey) {
                    touchedKey = key
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKey(intervalForKey(key))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchedKey = -1
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Int {
        // Black keys lie on top of the white keys
        blackKeys.firstOrNull { keyRects[it].contains(x, y) }?.let { return it }
        return whiteKeys.firstOrNull { x >= keyRects[it].left && x < keyRects[it].right } ?: -1
    }

    companion object {
        const val KEY_COUNT = 25

        private fun isBlack(key: Int) = when (key % 12) {
            1, 3, 6, 8, 10 -> true
            else -> false
        }

        fun formatInterval(semitones: Int) = when {
            semitones > 0 -> "+$semitones"
            semitones < 0 -> "−${-semitones}"
            else -> "0"
        }
    }
}

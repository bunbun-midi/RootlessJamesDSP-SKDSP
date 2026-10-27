package me.timschneeberger.rootlessjamesdsp.bpm

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingPanel
import me.timschneeberger.rootlessjamesdsp.floating.LcdStyle
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftController
import java.util.Locale

/**
 * Floating retro-LCD tempo display: detected BPM and, while the speed shifter slows playback
 * down, the resulting BPM. Drag to move; tap to forget the estimate and listen again.
 * Its size is set in the BPM display settings (30..100 %; the original size is 90 %).
 */
class BpmDisplay(context: Context) : FloatingPanel(context, BpmController.prefs(context), "bpm_display") {
    private lateinit var label: TextView
    private lateinit var value: TextView
    private lateinit var adjusted: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelBackground: GradientDrawable

    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == context.getString(R.string.key_bpm_size))
            applyScale()
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun createView(): View {
        val font = try {
            ResourcesCompat.getFont(context, R.font.jetbrainsmono)
        } catch (ex: Exception) {
            null
        } ?: Typeface.MONOSPACE

        fun lcdText(color: Int, bold: Boolean) = TextView(context).apply {
            typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            includeFontPadding = false
        }

        label = lcdText(LcdStyle.DIM, false).apply { letterSpacing = 0.25f }
        value = lcdText(LcdStyle.TEXT, true).apply {
            letterSpacing = 0.05f
            gravity = Gravity.END
        }
        adjusted = lcdText(LcdStyle.TEXT, false).apply { gravity = Gravity.END }

        panelBackground = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(LcdStyle.BACKGROUND_TOP, LcdStyle.BACKGROUND_BOTTOM)
        )
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground
            addView(label)
            addView(value, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(adjusted, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            contentDescription = context.getString(R.string.bpm_display_title)

            makeDraggable(this) {
                BpmController.requestReset()
                render()
            }
        }
        applyScale()
        return panel
    }

    /** Sizes everything by the size setting; 90 % is the original size */
    private fun applyScale() {
        if (!::panel.isInitialized)
            return
        val percent = BpmController.prefs(context)
            .getFloat(context.getString(R.string.key_bpm_size), DEFAULT_SIZE_PERCENT)
            .coerceIn(MIN_SIZE_PERCENT, 100f)
        val s = percent / DEFAULT_SIZE_PERCENT
        fun px(dpValue: Float) = (dpValue * s * density).toInt()

        for ((view, sp) in listOf(label to 11f, value to 34f, adjusted to 13f)) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * s)
            view.setShadowLayer(6 * s * density, 0f, 0f, LcdStyle.GLOW) // faint backlight glow
        }
        (value.layoutParams as LinearLayout.LayoutParams).topMargin = px(2f)
        (adjusted.layoutParams as LinearLayout.LayoutParams).topMargin = px(4f)
        panel.minimumWidth = px(150f)
        panel.setPadding(px(14f), px(8f), px(14f), px(10f))
        panelBackground.cornerRadius = 10 * s * density
        panelBackground.setStroke(px(2f).coerceAtLeast(1), LcdStyle.BEZEL)
        panel.requestLayout()
    }

    override fun defaultPosition(screen: Point) = Point(dp(16f), (screen.y * 0.12f).toInt())

    override fun onShown() {
        BpmController.prefs(context).registerOnSharedPreferenceChangeListener(sizeListener)
        handler.post(tick)
    }

    override fun onHidden() {
        BpmController.prefs(context).unregisterOnSharedPreferenceChangeListener(sizeListener)
    }

    private fun render() {
        val bpm = BpmController.fold(BpmController.bpm, BpmController.range(context))
        val known = bpm > 0f

        label.text = context.getString(if (known) R.string.bpm_display_label else R.string.bpm_display_listening)
        value.text = if (known) String.format(Locale.US, "%.1f", bpm) else "---.-"
        value.alpha = if (known && BpmController.confidence < LOW_CONFIDENCE) 0.55f else 1f

        // While slowed down: the tempo that is actually playing
        val speed = SpeedShiftController.speed
        val slowed = SpeedShiftController.isActive && speed < 1f
        adjusted.isVisible = slowed
        if (slowed) {
            adjusted.text = if (known)
                String.format(Locale.US, "×%.2f → %.1f", speed, bpm * speed)
            else
                String.format(Locale.US, "×%.2f", speed)
        }
    }

    companion object {
        private const val REFRESH_MS = 250L
        private const val LOW_CONFIDENCE = 0.2f
        private const val DEFAULT_SIZE_PERCENT = 90f
        private const val MIN_SIZE_PERCENT = 30f
    }
}

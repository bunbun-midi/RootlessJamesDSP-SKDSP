package me.timschneeberger.rootlessjamesdsp.bpm

import android.content.Context
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
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftController
import java.util.Locale

/**
 * Floating retro-LCD tempo display: detected BPM and, while the speed shifter slows playback
 * down, the resulting BPM. Drag to move; tap to forget the estimate and listen again.
 */
class BpmDisplay(context: Context) : FloatingPanel(context, BpmController.prefs(context), "bpm_display") {
    private lateinit var label: TextView
    private lateinit var value: TextView
    private lateinit var adjusted: TextView

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

        fun lcdText(sizeSp: Float, color: Int, bold: Boolean) = TextView(context).apply {
            typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            includeFontPadding = false
            setShadowLayer(6 * density, 0f, 0f, LCD_GLOW) // faint backlight glow
        }

        label = lcdText(11f, LCD_DIM, false).apply { letterSpacing = 0.25f }
        value = lcdText(34f, LCD_TEXT, true).apply {
            letterSpacing = 0.05f
            gravity = Gravity.END
        }
        adjusted = lcdText(13f, LCD_TEXT, false).apply { gravity = Gravity.END }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(150f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(10f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(LCD_BACKGROUND_TOP, LCD_BACKGROUND_BOTTOM)
            ).apply {
                cornerRadius = 10 * density
                setStroke(dp(2f), LCD_BEZEL)
            }
            addView(label)
            addView(value, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) })
            addView(adjusted, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) })
            contentDescription = context.getString(R.string.bpm_display_title)

            makeDraggable(this) {
                BpmController.requestReset()
                render()
            }
        }
    }

    override fun defaultPosition(screen: Point) = Point(dp(16f), (screen.y * 0.12f).toInt())

    override fun onShown() {
        handler.post(tick)
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

        // Retro LCD palette: light greyish-blue segments on a dark blue-green-black glass
        private const val LCD_BACKGROUND_TOP = 0xFF0F2A2E.toInt()
        private const val LCD_BACKGROUND_BOTTOM = 0xFF061518.toInt()
        private const val LCD_BEZEL = 0xFF29474E.toInt()
        private const val LCD_TEXT = 0xFFB4C8D8.toInt()
        private const val LCD_DIM = 0xFF6E8A99.toInt()
        private const val LCD_GLOW = 0x6690B4CC
    }
}

package me.timschneeberger.rootlessjamesdsp.bpm

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingPanel
import me.timschneeberger.rootlessjamesdsp.floating.LcdStyle
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftController
import me.timschneeberger.rootlessjamesdsp.speedshift.TempoMatcher
import java.util.Locale

/**
 * Floating retro-LCD tempo display: detected BPM and, while the speed shifter slows playback
 * down, the resulting BPM. Drag to move; tap to forget the estimate and listen again;
 * double-tap to enter a target tempo (see BpmTargetInput / TempoMatcher).
 * Its size is set in the BPM display settings (30..100 %, default 55 %; the original size is 90 %).
 */
class BpmDisplay(context: Context) : FloatingPanel(context, BpmController.prefs(context), "bpm_display") {
    private lateinit var label: TextView
    private lateinit var value: TextView
    private lateinit var adjusted: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelBackground: GradientDrawable
    private var targetInput: BpmTargetInput? = null

    // A single tap (listen again) waits for a possible second tap (target tempo input)
    private var tapPending = false
    private val singleTap = Runnable {
        tapPending = false
        BpmController.requestReset()
        render()
    }

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
                if (tapPending) {
                    handler.removeCallbacks(singleTap)
                    tapPending = false
                    openTargetInput()
                } else {
                    tapPending = true
                    handler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
                }
            }
        }
        applyScale()
        return panel
    }

    /** Sizes everything by the size setting; 90 % is the original size, 55 % the default */
    private fun applyScale() {
        if (!::panel.isInitialized)
            return
        val percent = BpmController.prefs(context)
            .getFloat(context.getString(R.string.key_bpm_size), DEFAULT_SIZE_PERCENT)
            .coerceIn(MIN_SIZE_PERCENT, 100f)
        val s = percent / REFERENCE_SIZE_PERCENT
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
        tapPending = false
        targetInput?.close()
        targetInput = null
    }

    private fun openTargetInput() {
        if (targetInput != null)
            return
        targetInput = BpmTargetInput(context) { targetInput = null }.also { it.show() }
        if (targetInput?.isShown != true)
            targetInput = null
    }

    private fun render() {
        val bpm = BpmController.fold(BpmController.bpm, BpmController.range(context))
        val known = bpm > 0f

        val target = SpeedShiftController.targetBpm
        label.text = when {
            target > 0f -> context.getString(R.string.bpm_display_target_label, BpmTargetInput.formatBpm(target))
            known -> context.getString(R.string.bpm_display_label)
            else -> context.getString(R.string.bpm_display_listening)
        }
        value.text = if (known) String.format(Locale.US, "%.1f", bpm) else "---.-"
        value.alpha = if (known && BpmController.confidence < LOW_CONFIDENCE) 0.55f else 1f

        // While slowed down: the speed and the tempo that is actually playing. In target mode the
        // playing tempo comes from the audio being played (behind the detected one by the backlog).
        val active = SpeedShiftController.isActive
        val speed = if (active) SpeedShiftController.appliedSpeed else SpeedShiftController.speed
        val playing = if (target > 0f)
            TempoMatcher.foldToTarget(SpeedShiftController.playingBpm, target) // same octave as the target
        else bpm
        adjusted.isVisible = active && (speed < 1f || target > 0f)
        if (adjusted.isVisible) {
            adjusted.text = when {
                target > 0f && SpeedShiftController.targetLimited ->
                    context.getString(R.string.bpm_display_target_limited)
                playing > 0f -> String.format(Locale.US, "×%.3f → %.2f", speed, playing * speed)
                else -> String.format(Locale.US, "×%.3f", speed)
            }
        }
    }

    companion object {
        private const val REFRESH_MS = 250L
        private const val LOW_CONFIDENCE = 0.2f
        private const val DEFAULT_SIZE_PERCENT = 55f
        /** Size setting at which the display has its original size */
        private const val REFERENCE_SIZE_PERCENT = 90f
        private const val MIN_SIZE_PERCENT = 30f
    }
}

package me.timschneeberger.rootlessjamesdsp.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Point
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingPanel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Floating four-octave piano keyboard that sets the pitch shifter's interval:
 *  - top row:    C = -12 st ... middle C = 0 ... C = +12 st
 *  - bottom row: C = -24 st ... B = -13 st, middle C = +12 st ... C = +24 st
 * The header holds a drag handle, the current interval, the portamento slider and a close button.
 */
class KeyboardPanel(context: Context) :
    FloatingPanel(context, KeyboardController.prefs(context), "keyboard_panel"), KeyboardController.Listener {

    private lateinit var topRow: PianoKeyboardView
    private lateinit var bottomRow: PianoKeyboardView
    private lateinit var intervalText: TextView
    private lateinit var portamentoText: TextView

    override fun windowWidth(screen: Point) = (screen.x * WIDTH_FRACTION).roundToInt()

    override fun defaultPosition(screen: Point) =
        Point(((1f - WIDTH_FRACTION) / 2f * screen.x).roundToInt(), (screen.y * 0.55f).roundToInt())

    override fun createView(): View {
        val ctx = themedContext
        fun color(attr: Int) = MaterialColors.getColor(ctx, attr, 0)

        val keyColors = PianoKeyboardView.Colors(
            white = 0xFFF7F7F5.toInt(),
            black = 0xFF1E1E1E.toInt(),
            outline = 0xFF9A9A9A.toInt(),
            active = color(com.google.android.material.R.attr.colorPrimary),
            activeText = color(com.google.android.material.R.attr.colorOnPrimary),
            label = 0xFF6A6A6A.toInt(),
        )
        topRow = PianoKeyboardView(ctx, { key -> key - 12 }, { onKey(it) }, keyColors)
        bottomRow = PianoKeyboardView(ctx, { key -> if (key < 12) key - 24 else key }, { onKey(it) }, keyColors)

        // Header: drag handle | interval | portamento slider + value | close
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
        val handle = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_drag_handle_24dp)
            imageTintList = ColorStateList.valueOf(onSurface)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            contentDescription = ctx.getString(R.string.keyboard_move)
        }
        makeDraggable(handle)

        intervalText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(onSurface)
            minWidth = dp(64f)
            gravity = Gravity.CENTER_VERTICAL
        }
        val portamentoLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.keyboard_portamento_short)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurface)
        }
        val slider = Slider(ctx).apply {
            valueFrom = 0f
            valueTo = KeyboardController.MAX_PORTAMENTO_MS
            stepSize = 10f
            value = (KeyboardController.portamentoMs(context) / 10f).roundToInt() * 10f
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, fromUser ->
                updatePortamentoText(v)
                if (fromUser)
                    KeyboardController.setPortamentoMs(context, v)
            }
        }
        portamentoText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurface)
            minWidth = dp(56f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        updatePortamentoText(slider.value)
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close_24dp)
            imageTintList = ColorStateList.valueOf(onSurface)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            contentDescription = ctx.getString(R.string.keyboard_close)
            setOnClickListener {
                // Turning the setting off makes the service remove the keyboard
                KeyboardController.prefs(context).edit()
                    .putBoolean(context.getString(R.string.key_keyboard_enable), false).apply()
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(intervalText)
            addView(portamentoLabel)
            addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(portamentoText)
            addView(close)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(2f), dp(6f), dp(8f))
            addView(header)
            addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)))
            addView(bottomRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
                topMargin = dp(6f)
            })
        }

        val card = MaterialCardView(ctx).apply {
            radius = 14 * density
            cardElevation = 6 * density
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            strokeWidth = 0
            addView(content)
        }
        // Room for the card's shadow inside the (transparent) window
        return FrameLayout(ctx).apply {
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            clipToPadding = false
            addView(card)
        }
    }

    override fun onShown() {
        KeyboardController.addListener(this)
        onIntervalChanged()
    }

    override fun onHidden() {
        KeyboardController.removeListener(this)
    }

    override fun onIntervalChanged() {
        val interval = KeyboardController.currentInterval
        topRow.activeInterval = interval
        bottomRow.activeInterval = interval
        val target = context.getString(
            if (KeyboardController.isHqTarget(context)) R.string.keyboard_target_hq_short
            else R.string.keyboard_target_standard_short
        )
        intervalText.text = if (interval != null)
            "${PianoKeyboardView.formatInterval(interval)} st · $target"
        else
            target
    }

    private fun onKey(semitones: Int) = KeyboardController.play(semitones)

    private fun updatePortamentoText(ms: Float) {
        portamentoText.text = String.format(Locale.getDefault(), "%d ms", ms.roundToInt())
    }

    companion object {
        private const val WIDTH_FRACTION = 0.9f
        private const val ROW_HEIGHT_DP = 84f
    }
}

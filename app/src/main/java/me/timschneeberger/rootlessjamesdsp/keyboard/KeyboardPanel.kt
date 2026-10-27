package me.timschneeberger.rootlessjamesdsp.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Point
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingPanel
import kotlin.math.roundToInt

/**
 * Floating four-octave piano keyboard that sets the pitch shifter's interval:
 *  - top row:    C = -12 st ... middle C = 0 ... C = +12 st
 *  - bottom row: C = -24 st ... B = -13 st, middle C = +12 st ... C = +24 st
 * The header holds drag handles at both ends, the current interval, the portamento slider and a
 * minimize button. Minimized, the keyboard is a small square where the minimize button was; tapping it
 * brings the keyboard back (dragging moves it).
 */
class KeyboardPanel(context: Context) :
    FloatingPanel(context, KeyboardController.prefs(context), "keyboard_panel"), KeyboardController.Listener {

    private lateinit var topRow: PianoKeyboardView
    private lateinit var bottomRow: PianoKeyboardView
    private lateinit var intervalText: TextView
    private lateinit var portamentoText: TextView
    private lateinit var fullView: View
    private lateinit var miniView: View
    private var minimized = false

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
        // Drag handles at both ends of the title bar (left, and right next to the minimize button)
        fun dragHandle() = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_drag_handle_24dp)
            imageTintList = ColorStateList.valueOf(onSurface)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            contentDescription = ctx.getString(R.string.keyboard_move)
            makeDraggable(this)
        }
        val handle = dragHandle()
        val handleRight = dragHandle()

        intervalText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurface)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(10f), 0)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(onSurface)
            minWidth = dp(26f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        updatePortamentoText(slider.value)
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close_24dp)
            imageTintList = ColorStateList.valueOf(onSurface)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            contentDescription = ctx.getString(R.string.keyboard_minimize)
            setOnClickListener { minimize() }
        }

        // Thin title bar: icons 28 dp, slider centered in a 32 dp row (the slider's own 48 dp touch
        // height is clipped). Negative slider margins remove the slider's built-in side padding.
        val iconSize = dp(ICON_DP)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(intervalText)
            addView(portamentoLabel)
            addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = -dp(10f)
                marginEnd = -dp(8f)
            })
            addView(portamentoText)
            addView(handleRight, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginStart = dp(4f) })
            addView(close, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginStart = dp(2f) })
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(CONTENT_PAD_DP), dp(CONTENT_PAD_TOP_DP), dp(CONTENT_PAD_DP), dp(8f))
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)))
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
        val shadow = dp(SHADOW_PAD_DP)
        fullView = FrameLayout(ctx).apply {
            setPadding(shadow, shadow, shadow, shadow)
            clipToPadding = false
            addView(card)
        }

        // Minimized: a square the size of the minimize button, showing a note
        val miniCard = MaterialCardView(ctx).apply {
            radius = 8 * density
            cardElevation = 6 * density
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            strokeWidth = 0
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_music_note_24dp)
                imageTintList = ColorStateList.valueOf(onSurface)
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        miniView = FrameLayout(ctx).apply {
            setPadding(shadow, shadow, shadow, shadow)
            clipToPadding = false
            contentDescription = ctx.getString(R.string.keyboard_expand)
            addView(miniCard, FrameLayout.LayoutParams(iconSize, iconSize))
            // Clicks from accessibility services; touches go through makeDraggable
            setOnClickListener { expand() }
        }
        makeDraggable(miniView, onTap = ::expand, saveKey = MINI_POSITION_KEY)

        minimized = KeyboardController.isMinimized(context)
        fullView.isVisible = !minimized
        miniView.isVisible = minimized

        return FrameLayout(ctx).apply {
            addView(fullView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(miniView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onShown() {
        KeyboardController.addListener(this)
        onIntervalChanged()
        if (minimized) {
            // Saved square position, or where the minimize button of the full keyboard is
            val full = fullToMini(windowX, windowY, windowWidthPx)
            setWindow(
                positionPrefs.getInt("${MINI_POSITION_KEY}_x", full.x),
                positionPrefs.getInt("${MINI_POSITION_KEY}_y", full.y),
                WindowManager.LayoutParams.WRAP_CONTENT,
                miniSize(),
            )
        }
    }

    /* The minimize button's top-left corner inside the full window (see the layout above) */
    private fun closeOffsetX(fullWidth: Int) =
        fullWidth - dp(SHADOW_PAD_DP) - dp(CONTENT_PAD_DP) - dp(ICON_DP)
    private fun closeOffsetY() =
        dp(SHADOW_PAD_DP) + dp(CONTENT_PAD_TOP_DP) + (dp(HEADER_HEIGHT_DP) - dp(ICON_DP)) / 2

    /** Square window position that puts the square exactly over the minimize button */
    private fun fullToMini(x: Int, y: Int, fullWidth: Int) = Point(
        x + closeOffsetX(fullWidth) - dp(SHADOW_PAD_DP),
        y + closeOffsetY() - dp(SHADOW_PAD_DP),
    )

    private fun miniSize() = Point(dp(ICON_DP) + 2 * dp(SHADOW_PAD_DP), dp(ICON_DP) + 2 * dp(SHADOW_PAD_DP))

    private fun minimize() {
        if (minimized || !isShown)
            return
        minimized = true
        KeyboardController.setMinimized(context, true)
        val mini = fullToMini(windowX, windowY, windowWidthPx)
        fullView.isVisible = false
        miniView.isVisible = true
        setWindow(mini.x, mini.y, WindowManager.LayoutParams.WRAP_CONTENT, miniSize())
        savePosition(MINI_POSITION_KEY)
    }

    private fun expand() {
        if (!minimized || !isShown)
            return
        minimized = false
        KeyboardController.setMinimized(context, false)
        // Full keyboard with its minimize button under the square (kept on screen)
        val width = windowWidth(screenSize())
        val height = fullView.height.takeIf { it > 0 } ?: dp(FULL_HEIGHT_DP)
        miniView.isVisible = false
        fullView.isVisible = true
        setWindow(
            windowX + dp(SHADOW_PAD_DP) - closeOffsetX(width),
            windowY + dp(SHADOW_PAD_DP) - closeOffsetY(),
            width,
            Point(width, height),
        )
        savePosition()
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
        portamentoText.text = ms.roundToInt().toString()
    }

    companion object {
        private const val WIDTH_FRACTION = 0.9f
        private const val ROW_HEIGHT_DP = 84f
        private const val HEADER_HEIGHT_DP = 32f
        private const val ICON_DP = 28f
        private const val SHADOW_PAD_DP = 6f
        private const val CONTENT_PAD_DP = 6f
        private const val CONTENT_PAD_TOP_DP = 2f
        /** Shadow + padding + header + two rows with gap (used before the keyboard was measured) */
        private const val FULL_HEIGHT_DP = 6f + 2f + 32f + 84f + 6f + 84f + 8f + 6f
        private const val MINI_POSITION_KEY = "keyboard_panel_mini"
    }
}

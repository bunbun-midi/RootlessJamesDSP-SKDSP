package me.timschneeberger.rootlessjamesdsp.bpm

import android.content.Context
import android.graphics.Point
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.floating.FloatingPanel
import me.timschneeberger.rootlessjamesdsp.speedshift.SpeedShiftController
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Small floating form (opened by double-tapping the BPM display) to enter a target tempo.
 * "Set" starts target tempo mode (the speed shifter then plays the music at that tempo),
 * "Off" ends it. Back, "Cancel" or a touch outside closes it. [onClosed] is called once it is gone.
 */
class BpmTargetInput(context: Context, private val onClosed: () -> Unit) :
    FloatingPanel(context, BpmController.prefs(context), "bpm_target_input", focusable = true) {

    private lateinit var field: EditText
    private lateinit var error: TextView

    override fun windowWidth(screen: Point) = (minOf(screen.x, screen.y) * 0.85f).roundToInt()

    override fun defaultPosition(screen: Point) =
        Point(((screen.x - windowWidth(screen)) / 2f).roundToInt(), (screen.y * 0.12f).roundToInt())

    override fun createView(): View {
        val ctx = themedContext
        fun color(attr: Int) = MaterialColors.getColor(ctx, attr, 0)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.bpm_target_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(onSurface)
        }
        val detected = BpmController.bpm
        val info = TextView(ctx).apply {
            text = if (detected > 0f)
                ctx.getString(R.string.bpm_target_detected,
                    String.format(Locale.getDefault(), "%.2f", BpmController.fold(detected, BpmController.range(context))))
            else
                ctx.getString(R.string.bpm_target_not_detected)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurfaceVariant)
        }
        val hintText = TextView(ctx).apply {
            text = ctx.getString(R.string.bpm_target_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurfaceVariant)
        }

        val initial = SpeedShiftController.targetBpm.takeIf { it > 0f }
            ?: SpeedShiftController.lastTargetBpm(context).takeIf { it > 0f }
        field = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = ctx.getString(R.string.bpm_target_field_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setSingleLine()
            if (initial != null)
                setText(formatBpm(initial))
            selectAll()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    apply()
                    true
                } else false
            }
        }
        error = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(com.google.android.material.R.attr.colorError))
            isVisible = false
        }

        fun textButton(textRes: Int, onClick: () -> Unit) =
            MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = ctx.getString(textRes)
                setOnClickListener { onClick() }
            }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (SpeedShiftController.targetBpm > 0f) {
                addView(textButton(R.string.bpm_target_off) {
                    SpeedShiftController.setTargetBpm(context, 0f)
                    close()
                })
                addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            }
            addView(textButton(android.R.string.cancel) { close() })
            addView(textButton(R.string.bpm_target_set) { apply() })
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(12f), dp(8f))
            addView(title)
            addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f); marginEnd = dp(8f) })
            addView(error)
            addView(hintText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f); marginEnd = dp(8f) })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        val card = MaterialCardView(ctx).apply {
            radius = 20 * density
            cardElevation = 8 * density
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            strokeWidth = 0
            addView(content)
        }
        // Back closes the form (the window has key focus while it is shown)
        return object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP)
                        close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            clipToPadding = false
            addView(card)
        }
    }

    override fun onShown() {
        field.requestFocus()
        // The keyboard can only be shown once the window has focus
        field.postDelayed({
            if (isShown)
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    override fun onOutsideTouch() = close()

    private fun apply() {
        val value = field.text.toString().trim().replace(',', '.').toFloatOrNull()
        if (value == null || value < SpeedShiftController.MIN_TARGET_BPM || value > SpeedShiftController.MAX_TARGET_BPM) {
            error.text = context.getString(R.string.bpm_target_invalid,
                SpeedShiftController.MIN_TARGET_BPM.roundToInt(), SpeedShiftController.MAX_TARGET_BPM.roundToInt())
            error.isVisible = true
            return
        }
        SpeedShiftController.setTargetBpm(context, value)
        close()
    }

    private var closed = false

    fun close() {
        if (closed)
            return
        closed = true
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        hide()
        onClosed()
    }

    companion object {
        /** Up to 3 decimals, without trailing zeros (e.g. "120", "127.35") */
        fun formatBpm(value: Float): String =
            String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}

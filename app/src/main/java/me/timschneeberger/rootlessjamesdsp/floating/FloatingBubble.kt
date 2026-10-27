package me.timschneeberger.rootlessjamesdsp.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.bpm.BpmController
import me.timschneeberger.rootlessjamesdsp.databinding.ViewFloatingBubbleBinding
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.abs

/**
 * Round floating button drawn over other apps (needs "display over other apps", see
 * Settings.canDrawOverlays). Handles the window, theming and these gestures:
 *  - tap: [onTap]
 *  - vertical swipe (only if [supportsVerticalSwipe]): [onSwipe] with the number of steps
 *  - long-press and drag: move the button; it docks to the left or right edge and the position
 *    is saved in [positionPrefs] under "<positionKey>_x/_y/_right"
 *
 * Subclasses draw their state in [render] using [setAppearance] and [setLabel]. With
 * "LCD-style floating buttons" (BPM display settings) the theme colors are replaced by the BPM
 * display's palette ([LcdStyle]); the size stays the same.
 */
abstract class FloatingBubble(
    protected val context: Context,
    private val positionPrefs: SharedPreferences,
    private val positionKey: String,
    private val defaultYFraction: Float,
) : KoinComponent {
    private val preferences: Preferences.App by inject()

    private val windowManager = context.getSystemService<WindowManager>()!!
    protected val handler = Handler(Looper.getMainLooper())
    protected val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    protected val binding: ViewFloatingBubbleBinding
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    var isShown = false
        private set
    private var onRightSide = true

    // Gesture state
    private enum class Mode { NONE, SWIPE, DRAG, IGNORE }
    private var mode = Mode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var swipeOriginY = 0f
    private var lastSwipeSteps = 0
    private var dragStartX = 0
    private var dragStartY = 0

    /** True while the user is swiping vertically on the button */
    protected val isSwiping get() = mode == Mode.SWIPE
    /** True while the user is moving the button */
    protected val isDragging get() = mode == Mode.DRAG

    // Hooks
    protected abstract fun onTap()
    protected abstract fun render()
    protected open val supportsVerticalSwipe = false
    protected open val swipeStepPx = 16 * density
    protected open fun onSwipeStart() {}
    /** [steps] since the swipe started (up = positive). Return true if the value changed (haptic tick). */
    protected open fun onSwipe(steps: Int): Boolean = false
    protected open fun onSwipeEnd() {}

    // LCD style (BPM display palette), followed live
    private var lcd = false
    private val lcdPrefs = BpmController.prefs(context)
    private val lcdKey = context.getString(R.string.key_bpm_lcd_buttons)
    private val lcdListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == lcdKey) {
            lcd = LcdStyle.buttonsEnabled(context)
            styleLabel()
            if (isShown)
                render()
        }
    }
    private val lcdOff by lazy { lcdButtonBackground(active = false) }
    private val lcdOn by lazy { lcdButtonBackground(active = true) }

    private val longPressRunnable = Runnable {
        if (mode == Mode.NONE) {
            mode = Mode.DRAG
            dragStartX = params.x
            dragStartY = params.y
            binding.bubbleButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            binding.bubbleButton.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
            render()
        }
    }

    init {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        val appTheme = try {
            AppTheme.valueOf(preferences.get<String>(R.string.key_appearance_app_theme))
        } catch (ex: Exception) {
            AppTheme.DEFAULT
        }
        val themedContext = ThemingDelegate.getThemeResIds(appTheme, isAmoled)
            .fold(context) { ctx, resId -> ContextThemeWrapper(ctx, resId) }

        binding = ViewFloatingBubbleBinding.inflate(LayoutInflater.from(themedContext))
        lcd = LcdStyle.buttonsEnabled(context)
        styleLabel()

        setupGestures()
    }

    open fun show() {
        if (isShown)
            return

        val screen = screenSize()
        onRightSide = positionPrefs.getBoolean("${positionKey}_right", true)
        params.x = positionPrefs.getInt("${positionKey}_x", 0)
        params.y = positionPrefs.getInt("${positionKey}_y", (screen.y * defaultYFraction).toInt())
        applySide()
        clampPosition()

        try {
            windowManager.addView(binding.root, params)
            isShown = true
        } catch (ex: Exception) {
            Timber.e("Failed to show floating button")
            Timber.e(ex)
            return
        }
        lcdPrefs.registerOnSharedPreferenceChangeListener(lcdListener)
        lcd = LcdStyle.buttonsEnabled(context)
        styleLabel()
        render()
    }

    open fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!isShown)
            return
        isShown = false
        lcdPrefs.unregisterOnSharedPreferenceChangeListener(lcdListener)
        try {
            windowManager.removeViewImmediate(binding.root)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
    }

    // ---- Drawing helpers ----

    protected fun color(@AttrRes attr: Int) = MaterialColors.getColor(binding.root, attr)

    /**
     * [active]: the button's function is on (slowed down, recording, processing); in LCD style
     * this lights the segments up instead of using [backgroundAttr]/[foregroundAttr].
     */
    protected fun setAppearance(
        @DrawableRes icon: Int,
        value: String,
        @AttrRes backgroundAttr: Int,
        @AttrRes foregroundAttr: Int,
        active: Boolean,
        alpha: Float = 1f,
        contentDescription: String,
    ) {
        val foreground: Int
        if (lcd) {
            foreground = if (active) LcdStyle.TEXT else LcdStyle.DIM
            binding.bubbleButton.setCardBackgroundColor(LcdStyle.BACKGROUND_BOTTOM)
            binding.bubbleContent.background = if (active) lcdOn else lcdOff
            if (active)
                binding.bubbleValue.setShadowLayer(4 * density, 0f, 0f, LcdStyle.GLOW)
            else
                binding.bubbleValue.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            foreground = color(foregroundAttr)
            binding.bubbleButton.setCardBackgroundColor(color(backgroundAttr))
            binding.bubbleContent.background = null
            binding.bubbleValue.setShadowLayer(0f, 0f, 0f, 0)
        }
        binding.bubbleIcon.setImageResource(icon)
        binding.bubbleIcon.imageTintList = ColorStateList.valueOf(foreground)
        binding.bubbleValue.setTextColor(foreground)
        binding.bubbleValue.text = value
        binding.bubbleButton.alpha = alpha
        binding.bubbleButton.contentDescription = contentDescription
    }

    private fun lcdButtonBackground(active: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(LcdStyle.BACKGROUND_TOP, LcdStyle.BACKGROUND_BOTTOM)
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke((2 * density).toInt(), if (active) LcdStyle.BEZEL_ACTIVE else LcdStyle.BEZEL)
    }

    private fun styleLabel() {
        binding.bubbleLabel.background = if (lcd) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(LcdStyle.BACKGROUND_TOP, LcdStyle.BACKGROUND_BOTTOM)
            ).apply {
                cornerRadius = 100 * density
                setStroke((1.5f * density).toInt().coerceAtLeast(1), LcdStyle.BEZEL)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(color(com.google.android.material.R.attr.colorSurfaceInverse))
            }
        }
        binding.bubbleLabel.setTextColor(
            if (lcd) LcdStyle.TEXT else color(com.google.android.material.R.attr.colorOnSurfaceInverse)
        )
    }

    /** Text pill next to the button (towards the screen center); null hides it. Hidden while dragging. */
    protected fun setLabel(text: String?) {
        val visible = text != null && mode != Mode.DRAG
        binding.bubbleLabel.isVisible = visible
        if (visible)
            binding.bubbleLabel.text = text
    }

    // ---- Gestures ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.bubbleButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onTap()
        }

        binding.bubbleButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = Mode.NONE
                    downRawX = event.rawX
                    downRawY = event.rawY
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    when (mode) {
                        Mode.NONE -> {
                            if (supportsVerticalSwipe && abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                                handler.removeCallbacks(longPressRunnable)
                                mode = Mode.SWIPE
                                swipeOriginY = event.rawY
                                lastSwipeSteps = 0
                                onSwipeStart()
                                render()
                            } else if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                handler.removeCallbacks(longPressRunnable)
                                mode = Mode.IGNORE
                            }
                        }
                        Mode.SWIPE -> {
                            // Up = positive steps
                            val steps = ((swipeOriginY - event.rawY) / swipeStepPx).toInt()
                            if (steps != lastSwipeSteps) {
                                lastSwipeSteps = steps
                                if (onSwipe(steps))
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        Mode.DRAG -> {
                            params.x = dragStartX + (if (onRightSide) -dx else dx).toInt()
                            params.y = dragStartY + dy.toInt()
                            clampPosition()
                            updateLayout()
                        }
                        Mode.IGNORE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()

                    val previous = mode
                    mode = Mode.NONE
                    when (previous) {
                        Mode.NONE -> if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        Mode.SWIPE -> onSwipeEnd()
                        Mode.DRAG -> finishDrag(event.rawX)
                        Mode.IGNORE -> {}
                    }
                    render()
                }
            }
            true
        }
    }

    private fun finishDrag(rawX: Float) {
        // Dock to the side the finger was released on; the label then opens towards the screen center
        val screen = screenSize()
        val rightSide = rawX > screen.x / 2f
        if (rightSide != onRightSide) {
            // Convert the offset from one screen edge to the other (label is hidden while dragging)
            params.x = screen.x - params.x - binding.root.width
            onRightSide = rightSide
            applySide()
        }
        clampPosition()
        updateLayout()

        positionPrefs.edit()
            .putBoolean("${positionKey}_right", onRightSide)
            .putInt("${positionKey}_x", params.x)
            .putInt("${positionKey}_y", params.y)
            .apply()
    }

    private fun applySide() {
        params.gravity = Gravity.TOP or (if (onRightSide) Gravity.END else Gravity.START)
        binding.root.layoutDirection = if (onRightSide) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun clampPosition() {
        val screen = screenSize()
        val width = binding.root.width.takeIf { it > 0 } ?: (80 * density).toInt()
        val height = binding.root.height.takeIf { it > 0 } ?: (80 * density).toInt()
        params.x = params.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.y - height).coerceAtLeast(0))
    }

    private fun updateLayout() {
        if (!isShown)
            return
        try {
            windowManager.updateViewLayout(binding.root, params)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
    }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = context.resources.displayMetrics
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }
}

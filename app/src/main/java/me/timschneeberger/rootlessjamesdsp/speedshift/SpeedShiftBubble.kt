package me.timschneeberger.rootlessjamesdsp.speedshift

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ViewSpeedShiftBubbleBinding
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.abs

/**
 * Floating button drawn over other apps that controls the speed shifter:
 *  - tap: toggle slow-down on/off
 *  - swipe up/down: change the speed in 0.05x steps (the multiple is shown next to the button)
 *  - long-press and drag: move the button (position is remembered)
 *
 * Requires the "display over other apps" permission (Settings.canDrawOverlays).
 */
class SpeedShiftBubble(private val context: Context) : SpeedShiftController.Listener, KoinComponent {
    private val preferences: Preferences.App by inject()

    private val windowManager = context.getSystemService<WindowManager>()!!
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeStepPx = 16 * density

    private val binding: ViewSpeedShiftBubbleBinding
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private var isShown = false
    private var onRightSide = true

    // Gesture state
    private enum class Mode { NONE, SWIPE, DRAG, IGNORE }
    private var mode = Mode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var swipeOriginY = 0f
    private var swipeStartSpeed = SpeedShiftController.DEFAULT_SPEED
    private var dragStartX = 0
    private var dragStartY = 0
    private var hideLabelPending = false

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

    private val hideLabelRunnable = Runnable {
        hideLabelPending = false
        render()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            if (isShown && SpeedShiftController.isActive)
                handler.postDelayed(this, TICK_INTERVAL_MS)
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

        binding = ViewSpeedShiftBubbleBinding.inflate(LayoutInflater.from(themedContext))
        binding.bubbleLabel.background = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceInverse))
        }
        binding.bubbleLabel.setTextColor(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceInverse)
        )

        setupGestures()
    }

    fun show() {
        if (isShown)
            return

        val prefs = SpeedShiftController.prefs(context)
        val screen = screenSize()
        onRightSide = prefs.getBoolean(context.getString(R.string.key_speed_shift_bubble_right), true)
        params.x = prefs.getInt(context.getString(R.string.key_speed_shift_bubble_x), 0)
        params.y = prefs.getInt(context.getString(R.string.key_speed_shift_bubble_y), (screen.y * 0.35f).toInt())
        applySide()
        clampPosition()

        try {
            windowManager.addView(binding.root, params)
            isShown = true
        } catch (ex: Exception) {
            Timber.e("Failed to show speed shift bubble")
            Timber.e(ex)
            return
        }

        SpeedShiftController.addListener(this)
        render()
        if (SpeedShiftController.isActive)
            handler.post(tickRunnable)
    }

    fun hide() {
        SpeedShiftController.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        if (!isShown)
            return
        isShown = false
        try {
            windowManager.removeViewImmediate(binding.root)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
    }

    override fun onSpeedShiftStateChanged() {
        render()
        handler.removeCallbacks(tickRunnable)
        if (isShown && SpeedShiftController.isActive)
            handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private fun render() {
        val active = SpeedShiftController.isActive
        val speed = SpeedShiftController.speed
        val root = binding.root
        val speedText = SpeedShiftController.formatSpeed(speed)

        val background = MaterialColors.getColor(root,
            if (active) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorSecondaryContainer)
        val foreground = MaterialColors.getColor(root,
            if (active) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSecondaryContainer)

        binding.bubbleButton.setCardBackgroundColor(background)
        binding.bubbleIcon.imageTintList = ColorStateList.valueOf(foreground)
        binding.bubbleSpeed.setTextColor(foreground)
        binding.bubbleSpeed.text = speedText
        binding.bubbleButton.alpha = if (active) 1f else 0.85f

        // Side label: the multiple while swiping, otherwise how far playback lags behind
        val backlog = SpeedShiftController.backlogSeconds
        val label = when {
            mode == Mode.DRAG -> null
            mode == Mode.SWIPE || hideLabelPending -> speedText
            active && backlog >= 1f -> context.getString(R.string.speed_shift_behind,
                SpeedShiftController.formatDuration(backlog))
            else -> null
        }
        binding.bubbleLabel.isVisible = label != null
        if (label != null)
            binding.bubbleLabel.text = label

        binding.bubbleButton.contentDescription = if (active)
            context.getString(R.string.speed_shift_bubble_on_desc, speedText)
        else
            context.getString(R.string.speed_shift_bubble_off_desc, speedText)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.bubbleButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            SpeedShiftController.toggle()
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
                            if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                                handler.removeCallbacks(longPressRunnable)
                                handler.removeCallbacks(hideLabelRunnable)
                                mode = Mode.SWIPE
                                swipeOriginY = event.rawY
                                swipeStartSpeed = SpeedShiftController.speed
                                render()
                            } else if (abs(dx) > touchSlop) {
                                handler.removeCallbacks(longPressRunnable)
                                mode = Mode.IGNORE
                            }
                        }
                        Mode.SWIPE -> {
                            // Up = faster (towards 1x), down = slower
                            val steps = ((swipeOriginY - event.rawY) / swipeStepPx).toInt()
                            val target = swipeStartSpeed + steps * SpeedShiftController.SPEED_STEP
                            if (SpeedShiftController.setSpeed(context, target))
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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

                    when (mode) {
                        Mode.NONE -> if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        Mode.SWIPE -> {
                            // Keep showing the new multiple for a moment
                            hideLabelPending = true
                            handler.postDelayed(hideLabelRunnable, LABEL_LINGER_MS)
                        }
                        Mode.DRAG -> finishDrag(event.rawX)
                        Mode.IGNORE -> {}
                    }
                    mode = Mode.NONE
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

        SpeedShiftController.prefs(context).edit()
            .putBoolean(context.getString(R.string.key_speed_shift_bubble_right), onRightSide)
            .putInt(context.getString(R.string.key_speed_shift_bubble_x), params.x)
            .putInt(context.getString(R.string.key_speed_shift_bubble_y), params.y)
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

    companion object {
        private const val TICK_INTERVAL_MS = 500L
        private const val LABEL_LINGER_MS = 1200L
    }
}

package me.timschneeberger.rootlessjamesdsp.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.abs

/**
 * A panel drawn over other apps (needs "display over other apps", see Settings.canDrawOverlays),
 * e.g. the BPM display or the piano keyboard. Subclasses build their view in [createView] and
 * call [makeDraggable] on the part that moves the panel. The position is saved in
 * [positionPrefs] under "<positionKey>_x/_y".
 */
abstract class FloatingPanel(
    protected val context: Context,
    protected val positionPrefs: SharedPreferences,
    protected val positionKey: String,
) : KoinComponent {
    private val preferences: Preferences.App by inject()

    private val windowManager = context.getSystemService<WindowManager>()!!
    protected val handler = Handler(Looper.getMainLooper())
    protected val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Context with the app's theme (Material colors/attributes) */
    protected val themedContext: Context by lazy {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        val appTheme = try {
            AppTheme.valueOf(preferences.get<String>(R.string.key_appearance_app_theme))
        } catch (ex: Exception) {
            AppTheme.DEFAULT
        }
        ThemingDelegate.getThemeResIds(appTheme, isAmoled)
            .fold(context) { ctx, resId -> ContextThemeWrapper(ctx, resId) }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var root: View? = null

    var isShown = false
        private set

    protected abstract fun createView(): View

    /** Window width in pixels, or WRAP_CONTENT */
    protected open fun windowWidth(screen: Point): Int = WindowManager.LayoutParams.WRAP_CONTENT

    /** Initial top-left position when nothing has been saved yet */
    protected abstract fun defaultPosition(screen: Point): Point

    protected open fun onShown() {}
    protected open fun onHidden() {}

    fun show() {
        if (isShown)
            return
        val view = try {
            createView()
        } catch (ex: Exception) {
            Timber.e("Failed to create floating panel")
            Timber.e(ex)
            return
        }

        val screen = screenSize()
        val default = defaultPosition(screen)
        params.width = windowWidth(screen)
        params.x = positionPrefs.getInt("${positionKey}_x", default.x)
        params.y = positionPrefs.getInt("${positionKey}_y", default.y)
        clampPosition(view)

        try {
            windowManager.addView(view, params)
        } catch (ex: Exception) {
            Timber.e("Failed to show floating panel")
            Timber.e(ex)
            return
        }
        root = view
        isShown = true
        onShown()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val view = root ?: return
        root = null
        isShown = false
        onHidden()
        try {
            windowManager.removeViewImmediate(view)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
    }

    /**
     * Dragging [handle] moves the panel and saves its position under [saveKey]. A touch without
     * movement calls [onTap] (if given).
     */
    @SuppressLint("ClickableViewAccessibility")
    protected fun makeDraggable(handle: View, onTap: (() -> Unit)? = null, saveKey: String = positionKey) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop))
                        dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        root?.let { clampPosition(it) }
                        updateLayout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        savePosition(saveKey)
                    } else if (onTap != null) {
                        view.performClick()
                        onTap()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }

    private fun clampPosition(view: View) {
        val width = if (params.width > 0) params.width else view.width.takeIf { it > 0 } ?: (120 * density).toInt()
        val height = view.height.takeIf { it > 0 } ?: (60 * density).toInt()
        clampPosition(width, height)
    }

    private fun clampPosition(width: Int, height: Int) {
        val screen = screenSize()
        params.x = params.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.y - height).coerceAtLeast(0))
    }

    /** Current window position (top-left, pixels) and width (pixels or WRAP_CONTENT) */
    protected val windowX get() = params.x
    protected val windowY get() = params.y
    protected val windowWidthPx get() = params.width

    /**
     * Moves and resizes the window. [width] in pixels or WRAP_CONTENT; [size] is the size the
     * content will have (the view may not be measured yet) and keeps the window on screen.
     */
    protected fun setWindow(x: Int, y: Int, width: Int, size: Point) {
        params.width = width
        params.x = x
        params.y = y
        clampPosition(size.x, size.y)
        updateLayout()
    }

    /** Saves the current window position under "<key>_x/_y" */
    protected fun savePosition(key: String = positionKey) {
        positionPrefs.edit()
            .putInt("${key}_x", params.x)
            .putInt("${key}_y", params.y)
            .apply()
    }

    private fun updateLayout() {
        val view = root ?: return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (ex: Exception) {
            Timber.w(ex)
        }
    }

    protected fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = context.resources.displayMetrics
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }

    protected fun dp(value: Float) = (value * density).toInt()
}

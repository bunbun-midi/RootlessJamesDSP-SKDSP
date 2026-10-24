package me.timschneeberger.rootlessjamesdsp.speedshift

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Shared state of the speed shifter ("slow down what is playing right now").
 *
 * - [isActive] is toggled by tapping the floating button; it is intentionally not persisted
 *   (the service always starts with real-time playback).
 * - [speed] is persisted in the "speedshift" preferences, which are not part of presets.
 * - The audio thread of RootlessAudioProcessorService reads [isActive]/[speed] once per block
 *   and reports [backlogSeconds].
 */
object SpeedShiftController {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 1.0f
    const val DEFAULT_SPEED = 0.75f
    /** Speed values are kept on a 0.01 grid (the slider's resolution) */
    const val SPEED_RESOLUTION = 0.01f

    const val MIN_STEP = 0.01f
    const val MAX_STEP = 0.25f
    const val DEFAULT_STEP = 0.05f

    fun interface Listener {
        /** Always called on the main thread */
        fun onSpeedShiftStateChanged()
    }

    @Volatile
    var isActive = false
        private set

    @Volatile
    var speed = DEFAULT_SPEED
        private set

    /** Speed change per swipe step on the floating button (user setting) */
    @Volatile
    var speedStep = DEFAULT_STEP
        private set

    /** Audio that has been captured but not played yet, in seconds (written by the audio thread) */
    @Volatile
    var backlogSeconds = 0f

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun setActive(active: Boolean) {
        if (isActive == active)
            return
        isActive = active
        if (!active)
            backlogSeconds = 0f
        notifyListeners()
    }

    fun toggle() = setActive(!isActive)

    /** Sets the speed (clamped, rounded to [SPEED_RESOLUTION]); returns true if it changed */
    fun setSpeed(context: Context, value: Float, persist: Boolean = true): Boolean {
        val quantized = quantize(value)
        if (quantized == speed)
            return false
        speed = quantized
        if (persist) {
            prefs(context).edit()
                .putFloat(context.getString(R.string.key_speed_shift_speed), quantized)
                .apply()
        }
        notifyListeners()
        return true
    }

    fun loadFromPreferences(context: Context) {
        val prefs = prefs(context)
        speedStep = roundToResolution(
            prefs.getFloat(context.getString(R.string.key_speed_shift_step), DEFAULT_STEP).coerceIn(MIN_STEP, MAX_STEP)
        )
        val stored = prefs.getFloat(context.getString(R.string.key_speed_shift_speed), DEFAULT_SPEED)
        setSpeed(context, stored, persist = false)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREF_SPEED_SHIFT, Context.MODE_PRIVATE)

    fun quantize(value: Float): Float = roundToResolution(value.coerceIn(MIN_SPEED, MAX_SPEED))

    // Round to 2 decimals so that the stored value is exactly e.g. 0.35f
    private fun roundToResolution(value: Float): Float = (value / SPEED_RESOLUTION).roundToInt() / 100f

    fun formatSpeed(value: Float): String = String.format(Locale.getDefault(), "%.2f×", value)

    fun formatDuration(seconds: Float): String {
        val total = seconds.roundToInt()
        return if (total < 60) "$total s"
        else String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    private fun notifyListeners() {
        if (Looper.myLooper() == Looper.getMainLooper())
            listeners.forEach { it.onSpeedShiftStateChanged() }
        else
            mainHandler.post { listeners.forEach { it.onSpeedShiftStateChanged() } }
    }
}
